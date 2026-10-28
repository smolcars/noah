import Foundation
import Observation

@MainActor
@Observable
final class HistoryModel {
    struct Dependencies: Sendable {
        var load: @Sendable () async throws -> [HistoryTransaction]
        var sync: @Sendable () async throws -> Void
        var changes: @Sendable () async throws -> AsyncThrowingStream<String, Error>
        var blockTime: @Sendable (String) async throws -> Date?
        var price: @Sendable (Int64, String) async throws -> Double?
        var currentPrice: @Sendable (String) async throws -> Double? = { _ in nil }
        /// Settled recurring occurrences to join by movement ID or payment hash.
        var recurring: @Sendable () async -> [HistoryMapping.RecurringLink] = { [] }
        /// The wallet's chain tip and one transaction's status: read when a pending
        /// on-chain detail opens, never polled.
        var chainTip: @Sendable () async throws -> UInt32? = { nil }
        var chainStatus: @Sendable (String) async throws -> ChainTransactionStatus? = { _ in nil }
        /// One coin by ID, spent ones included.
        var coin: @Sendable (String) async throws -> VtxoModel? = { _ in nil }

        static func live(session: WalletSession, client: Client, variant: AppVariant) -> Self {
            Self(
                load: { try await session.transactionHistory() }, sync: { try await session.sync() },
                changes: {
                    // Stream adaptation has no wallet handle; the session owns and stops the native wait.
                    let changes = try await session.stateChanges()
                    return AsyncThrowingStream { continuation in
                        let task = Task {
                            do {
                                for try await event in changes { continuation.yield(event.reason) }
                                continuation.finish()
                            } catch { continuation.finish(throwing: error) }
                        }
                        continuation.onTermination = { _ in task.cancel() }
                    }
                },
                blockTime: { txid in
                    guard let base = WalletConfiguration.effective(for: variant, endpoint: EsploraSettings().selection(for: variant).endpoint).esplora,
                          let url = URL(string: base)?.appendingPathComponent("tx").appendingPathComponent(txid) else { return nil }
                    var request = URLRequest(url: url)
                    request.timeoutInterval = 10
                    let (data, response) = try await URLSession.shared.data(for: request)
                    guard (response as? HTTPURLResponse)?.statusCode == 200 else { return nil }
                    let status = try JSONDecoder().decode(EsploraTransaction.self, from: data).status
                    return status?.confirmed == true ? status?.block_time.map(Date.init(timeIntervalSince1970:)) : nil
                },
                price: { timestamp, currency in
                    let response = try await client.historicalFiatPrice(body: .json(.init(currency: currency, timestamp: timestamp)))
                        .ok.body.json
                    return response.rate.isFinite && response.rate > 0 ? response.rate : nil
                },
                currentPrice: { currency in
                    let response = try await client.fiatPrices(body: .json(.init())).ok.body.json
                    guard let rate = response.rates.additionalProperties[currency], rate.isFinite, rate > 0 else { return nil }
                    return rate
                },
                recurring: { await RecurringExecutor.shared.historyLinks() },
                chainTip: { try await session.chainTip() },
                chainStatus: { try await session.chainTransactionStatus($0) },
                coin: { try await session.vtxo($0) })
        }
    }

    private(set) var transactions: [HistoryTransaction] = []
    private(set) var isLoading = true
    private(set) var isRefreshing = false
    private(set) var error: String?
    /// Counts completed history reads, so an open detail re-reads its chain status with them (no polling).
    private(set) var revision = 0
    private let dependencies: Dependencies
    private var blockTimes: [String: Date] = [:]
    private var prices: [String: Double] = [:]
    private var priceKeys: [String] = []
    private var generation = UUID()
    private var enrichment: Task<Void, Never>?
    /// The last recurring join, applied at once so rows don't flicker on each refresh.
    private var recurringLinks: [HistoryMapping.RecurringLink] = []
    private struct Quote { let currency: String; let price: Double; let at: Date }
    private var quote: Quote?

    func awaitEnrichment() async { await enrichment?.value }

    /// The current price for today's and pending rows, cached for five minutes as on Home.
    func currentPrice(currency: String) async -> Double? {
        if let quote, quote.currency == currency, Date().timeIntervalSince(quote.at) < 300 { return quote.price }
        guard let price = try? await dependencies.currentPrice(currency), price.isFinite, price > 0 else {
            return quote?.currency == currency ? quote?.price : nil
        }
        quote = Quote(currency: currency, price: price, at: Date())
        return price
    }

    enum CoinLookup: Equatable { case found(VtxoModel), missing, failed }

    /// A coin by ID, told apart from one the wallet doesn't have and from a read that failed.
    func coin(_ id: String) async -> CoinLookup {
        do { return try await dependencies.coin(id).map(CoinLookup.found) ?? .missing } catch { return .failed }
    }

    /// One read of the chain tip and, when there's a txid, its status (Send's block row data).
    func chainState(txid: String?) async -> (tip: UInt32?, status: ChainTransactionStatus?) {
        let tip = try? await dependencies.chainTip()
        guard let txid else { return (tip, nil) }
        return (tip, try? await dependencies.chainStatus(txid))
    }
    private let log = AppLog("history")

    init(dependencies: Dependencies) { self.dependencies = dependencies }

    /// The view owns this task's lifetime. Subscribe before the initial read;
    /// the session's initial event covers commits that race with mounting.
    func observe(currency: String) async {
        defer { enrichment?.cancel() }
        do {
            var resubscribe = true
            while resubscribe && !Task.isCancelled {
                resubscribe = false
                let changes = try await dependencies.changes()
                await refresh(currency: currency)
                for try await reason in changes {
                    guard !Task.isCancelled else { return }
                    await refresh(currency: currency)
                    if reason == "resyncRequired" { resubscribe = true; break }
                }
            }
        } catch {
            guard !Task.isCancelled else { return }
            await refresh(currency: currency)
        }
    }

    private static func unified(_ entries: [HistoryTransaction]) -> [HistoryTransaction] {
        HistoryRules.merge(entries.filter { $0.source != "onchain-wallet" },
                           onchain: entries.filter { $0.source == "onchain-wallet" })
            .sorted(by: HistoryRules.precedes)
    }

    func refresh(currency: String, sync: Bool = false) async {
        let generation = UUID()
        self.generation = generation
        let previous = enrichment
        previous?.cancel()
        isRefreshing = true
        defer {
            if self.generation == generation { isRefreshing = false; isLoading = false }
        }
        do {
            if sync { try await dependencies.sync() }
            let latest = HistoryMapping.joinRecurring(try await dependencies.load(), links: recurringLinks)
            guard self.generation == generation, !Task.isCancelled else { return }
            // Publish wallet data before optional network enrichment finishes.
            transactions = Self.unified(latest)
            isLoading = false
            error = nil
            revision += 1
            // One worker bounds optional requests without holding up wallet observation.
            enrichment = Task {
                await previous?.value
                await self.enrich(latest, currency: currency, generation: generation)
            }
        } catch {
            guard self.generation == generation, !Task.isCancelled else { return }
            if error is WalletSession.SessionError {
                transactions = []
                self.error = L10n.text("Your wallet isn't ready. Please try again.")
            } else {
                self.error = L10n.text("Couldn't load transactions. Please try again.")
                // Native errors can contain destinations or full transaction IDs.
                log.w("History read failed")
            }
        }
    }

    /// The cached block time, or a fetch after publishing what's ready (a miss can stall the single worker).
    private func blockTime(_ txid: String, publishFirst publish: () async -> Void) async -> Date? {
        if let cached = blockTimes[txid] { return cached }
        await publish()
        return try? await dependencies.blockTime(txid)
    }

    private func enrich(_ rows: [HistoryTransaction], currency: String, generation: UUID) async {
        var latest = rows
        var dirty = false
        @MainActor func publish() async {
            guard !Task.isCancelled, self.generation == generation, dirty else { return }
            transactions = Self.unified(latest)
            dirty = false
            await Task.yield()
        }
        let links = await dependencies.recurring()
        guard !Task.isCancelled, self.generation == generation else { return }
        if links != recurringLinks {
            recurringLinks = links
            latest = HistoryMapping.joinRecurring(latest.map { var row = $0; row.recurring = nil; return row }, links: links)
            dirty = true
            await publish()
        }
        for index in latest.indices {
            if Task.isCancelled || self.generation != generation { return }
            if latest[index].source == "onchain-wallet", latest[index].hasConfirmation == true,
               let txid = latest[index].txid {
                let time = await blockTime(txid, publishFirst: publish)
                guard !Task.isCancelled, self.generation == generation else { return }
                if let time {
                    blockTimes[txid] = time
                    latest[index].date = HistoryRules.isoDate(time)
                    latest[index].dateLabel = nil
                    latest[index].sortTimestamp = time.timeIntervalSince1970 * 1000
                    dirty = true
                }
            }
            if let date = latest[index].timestamp, date.timeIntervalSince1970 > 0 {
                let seconds = Int64(date.timeIntervalSince1970)
                let key = "\(currency):\(latest[index].date.prefix(10))"
                let price: Double?
                if let cached = prices[key] { price = cached }
                else { await publish(); price = try? await dependencies.price(seconds, currency) }
                guard !Task.isCancelled, self.generation == generation else { return }
                if let price, price.isFinite, price > 0 {
                    if prices[key] == nil { priceKeys.append(key) }
                    prices[key] = price
                    if priceKeys.count > 200 { prices.removeValue(forKey: priceKeys.removeFirst()) }
                    latest[index].btcPrice = price
                } else {
                    // Publish completed rows before a cache miss can stall the single worker.
                    await publish()
                    latest[index].btcPrice = try? await dependencies.currentPrice(currency)
                }
            }
            guard !Task.isCancelled, self.generation == generation else { return }
            dirty = true
            // Cached quotes never suspend: bound merge/sort work and let wallet events run.
            if (index + 1).isMultiple(of: 32) || index == latest.indices.last { await publish() }
        }
    }
}

/// The fields of Esplora's `/tx/:txid` that date a confirmation.
private struct EsploraTransaction: Decodable {
    struct Status: Decodable {
        var confirmed: Bool? // swiftlint:disable:this discouraged_optional_boolean - Esplora's JSON omits it
        var block_time: Double?
    }
    var status: Status?
}
