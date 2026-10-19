import Foundation
import Testing
@testable import Noah

@Suite(.timeLimit(.minutes(1)))
@MainActor
struct HistoryModelTests {
    private func transaction(_ id: String = "movement-1") -> HistoryTransaction {
        .init(id: id, type: "Arkoor", amount: 2100, date: "2026-07-15T12:00:00.000Z", direction: "incoming", source: "ark")
    }
    private func dependencies(_ rows: [HistoryTransaction]) -> HistoryModel.Dependencies {
        .init(load: { rows }, sync: {}, changes: { AsyncThrowingStream { $0.finish() } },
              blockTime: { _ in nil }, price: { _, _ in 100000 })
    }

    @Test func closedWalletIsRecoverable() async {
        let session = WalletSession()
        var dependencies = dependencies([])
        dependencies.load = { try await session.transactionHistory() }
        let model = HistoryModel(dependencies: dependencies)
        await model.refresh(currency: "USD")
        #expect(model.transactions.isEmpty)
        #expect(model.error != nil)
        #expect(!model.isLoading && !model.isRefreshing)
    }

    @Test func pricesUseHistoricalTimestampAndCurrency() async {
        let transaction = transaction()
        var dependencies = dependencies([transaction])
        dependencies.price = { timestamp, currency in
            #expect(timestamp == 1784116800)
            #expect(currency == "EUR")
            return 85000
        }
        let model = HistoryModel(dependencies: dependencies)
        await model.refresh(currency: "EUR")
        #expect(model.transactions.first?.btcPrice == 85000)
    }

    @Test func unavailableMarketDataDoesNotHidePayments() async {
        struct Offline: Error {}
        var dependencies = dependencies([transaction()])
        dependencies.price = { _, _ in throw Offline() }
        let model = HistoryModel(dependencies: dependencies)
        await model.refresh(currency: "USD")
        #expect(model.transactions.count == 1)
        #expect(model.transactions.first?.btcPrice == nil)
        #expect(model.error == nil)
    }

    @Test func missingHistoricalPriceUsesUncachedCurrentRate() async {
        actor Quotes {
            var count = 0
            func next() -> Double { count += 1; return Double(count) * 100000 }
        }
        let quotes = Quotes()
        var dependencies = dependencies([transaction()])
        dependencies.price = { _, _ in nil }
        dependencies.currentPrice = { _ in await quotes.next() }
        let model = HistoryModel(dependencies: dependencies)
        await model.refresh(currency: "USD")
        #expect(model.transactions.first?.btcPrice == 100000)
        await model.refresh(currency: "USD")
        #expect(model.transactions.first?.btcPrice == 200000)
    }

    @Test func historicalQuotesShareTheRNDayAndCurrencyCache() async {
        actor Quotes {
            var count = 0
            func next() -> Double { count += 1; return 100000 }
        }
        let quotes = Quotes()
        var later = transaction("movement-2")
        later.date = "2026-07-15T13:00:00.000Z"
        var dependencies = dependencies([transaction(), later])
        dependencies.price = { _, _ in await quotes.next() }
        let model = HistoryModel(dependencies: dependencies)
        await model.refresh(currency: "USD")
        #expect(await quotes.count == 1)
        await model.refresh(currency: "EUR")
        #expect(await quotes.count == 2)
    }

    private actor Gate {
        var pending: CheckedContinuation<Double?, Never>?
        var started: Bool { pending != nil }
        func wait() async -> Double? { await withCheckedContinuation { pending = $0 } }
        func finish() { pending?.resume(returning: 100000); pending = nil }
    }

    @Test func olderEnrichmentCannotOverwriteNewRefresh() async {
        let gate = Gate()
        var dependencies = dependencies([transaction()])
        dependencies.price = { _, currency in currency == "USD" ? await gate.wait() : 85000 }
        let model = HistoryModel(dependencies: dependencies)
        let old = Task { await model.refresh(currency: "USD") }
        while !(await gate.started) { await Task.yield() }
        #expect(model.transactions.count == 1) // wallet data is visible before price fetch
        await model.refresh(currency: "EUR")
        await gate.finish()
        await old.value
        #expect(model.transactions.first?.btcPrice == 85000)
    }

    @Test func committedChangesRefreshHistoryAndCancellationEndsObservation() async {
        actor Reads {
            var count = 0
            func next(_ transaction: HistoryTransaction) -> [HistoryTransaction] { count += 1; return [transaction] }
        }
        let reads = Reads()
        let row = transaction()
        let (stream, continuation) = AsyncThrowingStream<String, Error>.makeStream()
        var dependencies = dependencies([])
        dependencies.changes = { stream }
        dependencies.load = { await reads.next(row) }
        let model = HistoryModel(dependencies: dependencies)
        let observation = Task { await model.observe(currency: "USD") }
        while await reads.count < 1 { await Task.yield() }
        continuation.yield("databaseChanged")
        while await reads.count < 2 { await Task.yield() }
        observation.cancel()
        await observation.value
        #expect(model.transactions.count == 1)
    }

    @Test func csvUsesFilteredSignedAccountingAndEscapesFields() {
        var outgoing = transaction()
        outgoing.direction = "outgoing"
        outgoing.destination = "a,\"b\"\nc"
        var canceled = transaction("movement-2")
        canceled.type = "Onchain"
        canceled.movementKind = "exit"
        canceled.movementStatus = "canceled"
        let rows = [outgoing, canceled].filter(HistoryFilter.ark.includes)
        let csv = HistoryCSV(transactions: rows, currency: "USD").content
        #expect(csv.contains("Amount (sats)"))
        #expect(csv.contains("Outgoing,-2100"))
        #expect(csv.contains("\"a,\"\"b\"\"\nc\""))
        #expect(!csv.contains("movement-2"))
        #expect(HistoryCSV(transactions: [canceled], currency: "USD").content.contains("None,0"))
    }

    @Test func explorerMatchesRNVariantAndStripsOutpoint() {
        var row = transaction()
        row.type = "Onchain"
        row.txid = "funding:1"
        #expect(row.explorerURL(variant: .mainnet)?.absoluteString == "https://mempool.space/tx/funding")
        #expect(row.explorerURL(variant: .signet)?.absoluteString == "https://mempool.space/signet/tx/funding")
        #expect(row.explorerURL(variant: .regtest) == row.explorerURL(variant: .signet))
        row.type = "Arkoor"
        #expect(row.explorerURL(variant: .mainnet) == nil)
    }
}
