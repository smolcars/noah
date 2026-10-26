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
        await model.refresh(currency: "USD"); await model.awaitEnrichment()
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
        await model.refresh(currency: "EUR"); await model.awaitEnrichment()
        #expect(model.transactions.first?.btcPrice == 85000)
    }

    @Test func unavailableMarketDataDoesNotHidePayments() async {
        struct Offline: Error {}
        var dependencies = dependencies([transaction()])
        dependencies.price = { _, _ in throw Offline() }
        let model = HistoryModel(dependencies: dependencies)
        await model.refresh(currency: "USD"); await model.awaitEnrichment()
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
        await model.refresh(currency: "USD"); await model.awaitEnrichment()
        #expect(model.transactions.first?.btcPrice == 100000)
        await model.refresh(currency: "USD"); await model.awaitEnrichment()
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
        await model.refresh(currency: "USD"); await model.awaitEnrichment()
        #expect(await quotes.count == 1)
        await model.refresh(currency: "EUR"); await model.awaitEnrichment()
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
        await model.awaitEnrichment()
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

    @Test func stalledQuotesDoNotBlockSettledWalletEventsAndPublishIncrementally() async {
        actor Probe {
            var reads = 0
            var active = 0
            var canceled = 0
            func load(_ rows: [HistoryTransaction]) -> [HistoryTransaction] {
                reads += 1
                var rows = rows
                rows[0].movementStatus = reads == 1 ? "pending" : "successful"
                return rows
            }
            func quote(_ timestamp: Int64) async throws -> Double? {
                if timestamp == 1784116800 { return 100000 }
                active += 1
                #expect(active == 1)
                defer { active -= 1; canceled += 1 }
                try await Task.sleep(for: .seconds(60))
                return nil
            }
        }
        let probe = Probe()
        var older = transaction("older")
        older.date = "2026-07-14T12:00:00.000Z"
        let rows = [transaction(), older]
        let (stream, continuation) = AsyncThrowingStream<String, Error>.makeStream()
        var dependencies = dependencies(rows)
        dependencies.changes = { stream }
        dependencies.load = { await probe.load(rows) }
        dependencies.price = { timestamp, _ in try await probe.quote(timestamp) }
        let model = HistoryModel(dependencies: dependencies)
        let observation = Task { await model.observe(currency: "USD") }
        while await probe.active == 0 { await Task.yield() }
        #expect(!model.isRefreshing && model.transactions.first?.btcPrice == 100000)
        continuation.yield("databaseChanged")
        while model.transactions.first?.movementStatus != "successful" { await Task.yield() }
        #expect(!model.isRefreshing)
        observation.cancel()
        await observation.value
        await model.awaitEnrichment()
        #expect(await probe.active == 0)
        #expect(await probe.canceled >= 1)
    }

    @Test func warmQuoteCacheYieldsToWalletEventsDuringLargeEnrichment() async {
        @MainActor final class Probe {
            var rows: [HistoryTransaction] = []
            var quotes = 0
            var settled = false
            var enrichedAtEvent: Int?
            weak var model: HistoryModel?
            func load() -> [HistoryTransaction] {
                if settled { enrichedAtEvent = model?.transactions.filter { $0.btcPrice != nil }.count }
                return rows
            }
            func quote() -> Double { quotes += 1; return 100000 }
        }
        let probe = Probe()
        probe.rows = [transaction()]
        let (stream, continuation) = AsyncThrowingStream<String, Error>.makeStream()
        var dependencies = dependencies([])
        dependencies.changes = { stream }
        dependencies.load = { await probe.load() }
        dependencies.price = { _, _ in await probe.quote() }
        let model = HistoryModel(dependencies: dependencies)
        probe.model = model
        await model.refresh(currency: "USD"); await model.awaitEnrichment()
        // Enrichment yields every 32 cached rows, so a thousand leave room to see it yield.
        let count = 1000
        probe.rows = (1...count).map {
            var row = transaction("movement-\($0)")
            row.sortTimestamp = 1784116800000
            return row
        }
        let observation = Task { await model.observe(currency: "USD") }
        let deadline = ContinuousClock.now + .seconds(10)
        while (model.transactions.count != count || model.transactions.allSatisfy { $0.btcPrice == nil }),
              ContinuousClock.now < deadline { await Task.yield() }
        #expect(model.transactions.contains { $0.btcPrice != nil })
        #expect(model.transactions.filter { $0.btcPrice != nil }.count < count,
                "cached enrichment must yield before completing all rows")
        probe.settled = true; continuation.yield("databaseChanged")
        while (probe.enrichedAtEvent == nil || model.isRefreshing), ContinuousClock.now < deadline { await Task.yield() }
        #expect((probe.enrichedAtEvent ?? count) < count, "wallet event must be consumed during enrichment")
        await model.awaitEnrichment()
        #expect(model.transactions.allSatisfy { $0.btcPrice == 100000 })
        #expect(probe.quotes == 1)
        observation.cancel(); await observation.value
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
}
