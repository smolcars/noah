import Foundation
import Testing

@testable import Noah

struct OnchainOverrideTests {
    private func quote() -> OnchainQuote {
        var value = OnchainJournalTests.quote
        value.id = UUID()
        return value
    }

    @Test func overrideRetainsConsentAndLateOriginalReceiptAcrossNewSubmissionAndRestart() async throws {
        let file = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: file) }
        let journal = OnchainOperationJournal(file: file)
        let original = quote()
        await #expect(throws: OnchainFailure.uncertain) {
            try await journal.perform(original) { throw OnchainFailure.uncertain }
        }
        try await journal.recordTransaction("original-txid", for: original.id)
        let now = Date(timeIntervalSince1970: 123456)
        try await journal.discardUncertain(original.id, now: now)
        let restarted = OnchainOperationJournal(file: file)
        let retained = try #require(try await restarted.load())
        #expect(retained.quote == original && retained.txid == "original-txid")
        let audit = try #require(retained.overrideAudit)
        #expect(audit.overriddenAt == now)
        #expect(audit.warning == OnchainOperationJournal.OverrideAudit.warningText)
        #expect(audit.confirmation == OnchainOperationJournal.OverrideAudit.confirmationText)
        #expect(audit.statusMessage == OnchainFailure.uncertain.errorDescription)
        #expect(!retained.blocks(.board, ids: []))
        await #expect(throws: OnchainFailure.busy) { try await restarted.discardUncertain(original.id) }

        let next = quote()
        _ = try await restarted.perform(next) { .init(message: "new submission", txid: "new-txid") }
        #expect(try await !restarted.reconcile(original.id, evidence: .init(
            walletID: "wallet", transactionIDs: ["new-txid"])))
        #expect(try await restarted.reconcile(original.id, evidence: .init(
            walletID: "wallet", transactionIDs: ["original-txid"])))
        try await restarted.refreshBoardFees(walletID: "wallet", fees: ["original-txid": 1543])
        let attempts = try await OnchainOperationJournal(file: file).loadAll()
        #expect(attempts.count == 2)
        #expect(attempts[0].receipt?.txid == "original-txid" && attempts[0].receipt?.onchainFee == 1543)
        #expect(attempts[0].overrideAudit?.overriddenAt == now)
        #expect(attempts[1].receipt?.txid == "new-txid" && attempts[1].overrideAudit == nil)
        try await restarted.acknowledge(original.id)
        #expect(try await restarted.load()?.overrideAudit?.acknowledgedAt != nil)
        #expect(try await restarted.loadAll().count == 2)
    }

    @Test(arguments: [false, true])
    func migratesCurrentSingleAndArrayJournalsWithoutLosingAttempts(single: Bool) async throws {
        let file = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: file) }
        let original = OnchainOperationJournal.Attempt(quote: quote())
        var other = OnchainOperationJournal.Attempt(quote: quote())
        other.quote.action = .claim
        let data = try single ? JSONEncoder().encode(original) : JSONEncoder().encode([original, other])
        // Both existing schemas lack the optional override field.
        #expect(!String(decoding: data, as: UTF8.self).contains("override"))
        try data.write(to: file)
        let journal = OnchainOperationJournal(file: file)
        #expect(try await journal.loadAll().allSatisfy { $0.overrideAudit == nil })
        try await journal.discardUncertain(original.id)
        let attempts = try await OnchainOperationJournal(file: file).loadAll()
        #expect(attempts.count == (single ? 1 : 2))
        #expect(attempts[0].id == original.id && attempts[0].overrideAudit != nil)
        if !single { #expect(attempts[1].id == other.id && attempts[1].overrideAudit == nil) }
    }

    @Test func prunesOnlyOldestOverridesAndNeverActiveAttempts() async throws {
        let file = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: file) }
        let normal = OnchainOperationJournal.Attempt(quote: quote())
        let overrides = (0..<OnchainOperationJournal.maxOverriddenAttempts).map { index in
            OnchainOperationJournal.Attempt(quote: quote(), overrideAudit: .init(
                overriddenAt: Date(timeIntervalSince1970: Double(index))))
        }
        let latest = OnchainOperationJournal.Attempt(quote: quote())
        try JSONEncoder().encode([normal] + overrides.reversed() + [latest]).write(to: file)
        let journal = OnchainOperationJournal(file: file)
        try await journal.discardUncertain(latest.id, now: Date(timeIntervalSince1970: 999))
        let saved = try await OnchainOperationJournal(file: file).loadAll()
        #expect(saved.count == OnchainOperationJournal.maxOverriddenAttempts + 1)
        #expect(saved.contains { $0.id == normal.id && $0.overrideAudit == nil })
        #expect(!saved.contains { $0.id == overrides[0].id })
        #expect(saved.contains { $0.id == latest.id && $0.overrideAudit != nil })
    }

    private actor Cache {
        var ids: Set<String> = []
        func confirmOriginal() { ids = ["original-txid"] }
    }

    @MainActor
    @Test func modelRefreshRecognisesAndShowsLateOriginalWithoutRepeatingEitherSubmission() async throws {
        let file = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: file) }
        let journal = OnchainOperationJournal(file: file)
        let original = quote()
        await #expect(throws: OnchainFailure.uncertain) {
            try await journal.perform(original) { throw OnchainFailure.uncertain }
        }
        try await journal.recordTransaction("original-txid", for: original.id)
        let calls = OnchainModelTests.Calls()
        let cache = Cache()
        let reads = WalletSession.OnchainAttemptReads(
            walletID: "wallet", transactionIDs: { await cache.ids },
            vtxoState: { _ in Issue.record("No VTXO reads for board"); return "Unknown" },
            exitState: { _ in Issue.record("No exit reads for board"); return nil })
        var dependencies = OnchainModelTests().dependencies(calls)
        let next = quote()
        dependencies.quote = { _, _, _, _ in next }
        dependencies.attempt = { try await journal.loadAll() }
        dependencies.check = { try await WalletSession.checkOnchainAttempt($0, journal: journal, reads: reads) }
        dependencies.discard = { try await journal.discardUncertain($0) }
        dependencies.acknowledge = { try await journal.acknowledge($0) }
        dependencies.execute = { quote in
            try await journal.perform(quote) { .init(message: await calls.mutate(), txid: "new-txid") }
        }
        let model = OnchainModel(dependencies: dependencies)
        await model.load()
        await model.discardUncertain(original.id)
        #expect(model.attempts.first?.overrideAudit != nil)
        await model.review(.board, amount: next.amount)
        #expect(model.quote?.id == next.id)
        await model.confirm()
        #expect(await calls.mutations == 1)
        #expect(model.attempts.count == 2 && model.attempts[0].receipt == nil)
        await cache.confirmOriginal()
        await model.load() // Same path as screen polling/pull-to-refresh.
        #expect(model.attempts[0].receipt?.txid == "original-txid")
        #expect(model.attempts[1].receipt?.txid == "new-txid")
        #expect(model.attempts[0].overrideAudit != nil)
        await model.acknowledge(original.id)
        let relaunched = OnchainModel(dependencies: dependencies)
        await relaunched.load()
        #expect(relaunched.attempts.map(\.id) == [next.id])
        await relaunched.review(.startExit, ids: ["a"])
        #expect(relaunched.attempts.map(\.id) == [next.id])
        #expect(try await journal.loadAll().first?.overrideAudit?.acknowledgedAt != nil)
        #expect(await calls.mutations == 1)
    }
}
