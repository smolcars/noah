import BarkBoltFFI
import Foundation
@testable import Noah
import Testing

@MainActor struct SendRoundSevenTests {
    @Test(arguments: [SendReceipt.Status.submitted, .unlocated, .conflicted], [false, true])
    func unresolvedReceiptOverrideSurvivesRestart(status: SendReceipt.Status, restart: Bool) throws {
        let storage = SendPaymentTests.Storage()
        storage.attempt = .init(payment: SendPreparedTests.payment, snapshot: .init(movementIDs: []),
                                receipt: .init(amount: 1000, txid: "exact", status: status, proof: .preparedTxid,
                                               broadcastRetryPending: true), quote: SendPreparedTests.quote)
        if restart { storage.attempt = try SendSafetyTests().roundTrip(#require(storage.attempt)) }
        var audits: [SendOverrideAudit] = []
        let controller = SendPaymentController(dependencies: .init(
            prepare: { _, _ in Issue.record("Must not prepare"); throw SendFailure.unresolved },
            pay: { _, _, _ in Issue.record("Must not pay"); throw SendFailure.unresolved },
            reconcile: { _ in .uncertain }, read: { storage.attempt }, write: { try storage.write($0) },
            auditOverride: { audits.append($0) }))
        controller.acknowledge()
        controller.submit(SendPreparedTests.payment, quote: SendPreparedTests.quote)
        #expect(controller.locked && controller.canOverride)
        controller.startNewPaymentAfterHistoryCheck(confirmed: false)
        #expect(controller.locked && audits.isEmpty)
        let warning = controller.overrideWarning
        let before = Date()
        controller.startNewPaymentAfterHistoryCheck(confirmed: true)
        #expect(!controller.locked && storage.attempt == nil)
        let audit = try #require(audits.first)
        #expect(audit.txid == "exact" && audit.overriddenAt >= before)
        #expect(audit.warning == warning)
        #expect(audit.warning.contains("does not cancel Bark’s stored transaction or its future broadcast retries"))
        #expect(audit.warning.contains("spend twice"))
    }

    @Test(arguments: [false, true])
    func overrideAuditFailureKeepsLock(auditFailure: Bool) {
        let storage = SendPaymentTests.Storage()
        storage.attempt = .init(payment: SendPreparedTests.payment, snapshot: .init(movementIDs: []),
                                receipt: .init(amount: 1000, txid: "exact", status: .submitted,
                                               proof: .preparedTxid, broadcastRetryPending: true))
        storage.failWrite = !auditFailure
        let controller = SendPaymentController(dependencies: .init(
            prepare: { _, _ in throw SendFailure.unresolved }, pay: { _, _, _ in throw SendFailure.unresolved },
            reconcile: { _ in .uncertain }, read: { storage.attempt }, write: { try storage.write($0) },
            auditOverride: { _ in if auditFailure { throw CocoaError(.fileWriteNoPermission) } }))
        controller.startNewPaymentAfterHistoryCheck(confirmed: true)
        #expect(controller.locked && storage.attempt?.receipt?.txid == "exact")
    }

    @Test(arguments: [SendReceipt.Status.confirmed, .submitted], [SendSource.onchain, .offchain])
    func offlineRefreshPreservesProvenOutcome(status: SendReceipt.Status, source: SendSource) async throws {
        let storage = SendPaymentTests.Storage()
        var payment = SendPreparedTests.payment
        payment.source = source
        let receipt = SendReceipt(amount: 1000, txid: "exact", status: status, fee: 143,
                                  proof: source == .onchain ? .preparedTxid : .submitReturnedTxid)
        storage.attempt = .init(payment: payment, snapshot: .init(movementIDs: []), receipt: receipt)
        let controller = SendSafetyTests().controller(storage) { _ in throw URLError(.notConnectedToInternet) }
        await controller.checkStatus()
        #expect(controller.state == .succeeded(receipt))
        #expect(controller.attempt?.receipt == receipt && storage.attempt?.receipt == receipt)
        #expect(controller.message?.contains("Couldn't check the latest status — you're offline") == true)
        storage.attempt = try SendSafetyTests().roundTrip(#require(storage.attempt))
        let restarted = SendSafetyTests().controller(storage) { _ in throw URLError(.notConnectedToInternet) }
        await restarted.checkStatus()
        #expect(restarted.state == .succeeded(receipt))
        restarted.acknowledge()
        #expect(!restarted.locked)
    }

    @Test(arguments: [false, true])
    func failedRefreshNeverUpgradesCrashOrPendingReceipt(hasReceipt: Bool) async throws {
        let storage = SendPaymentTests.Storage()
        storage.attempt = .init(broadcastingTxid: "exact", payment: SendPreparedTests.payment,
                                snapshot: .init(movementIDs: []), quote: SendPreparedTests.quote)
        if hasReceipt {
            storage.attempt?.receipt = .init(amount: 1000, txid: "exact", status: .submitted,
                                             proof: .preparedTxid, broadcastRetryPending: true)
        }
        for _ in 0..<2 {
            let controller = SendSafetyTests().controller(storage) { _ in throw URLError(.timedOut) }
            let before = controller.state
            await controller.checkStatus()
            #expect(controller.state == before)
            #expect(controller.attempt?.receipt?.broadcastRetryPending == (hasReceipt ? true : nil))
            controller.acknowledge()
            #expect(controller.locked)
            storage.attempt = try SendSafetyTests().roundTrip(#require(storage.attempt))
        }
    }

    @Test(arguments: [false, true], [false, true])
    func confirmedCompetingSpendIsReportedEvenAfterEvictionAndRestart(evicted: Bool, hasReceipt: Bool) async throws {
        let original = try SendSafetyTests().transaction(id: "exact")
        let competing = try SendSafetyTests().transaction(id: "replacement", confirmed: true)
        var quote = SendPreparedTests.quote
        quote.prepared?.inputs = try SendTransaction.parse(hex: original.txHex).inputs
        var saved = SendPaymentController.Attempt(broadcastingTxid: "exact", payment: SendPreparedTests.payment,
                                                  snapshot: .init(movementIDs: []), quote: quote)
        if hasReceipt {
            saved.receipt = .init(amount: 1000, txid: "exact", status: .submitted,
                                  proof: .preparedTxid, broadcastRetryPending: true)
        }
        let transactions = evicted ? [competing] : [original, competing]
        let storage = SendPaymentTests.Storage()
        storage.attempt = try SendSafetyTests().roundTrip(saved)
        let controller = SendSafetyTests().controller(storage) { try SendReconciliation.onchain($0, transactions: transactions) }
        await controller.checkStatus()
        #expect(controller.state == .uncertain && controller.canOverride)
        #expect(controller.attempt?.receipt?.status == .conflicted)
        #expect(controller.attempt?.receipt?.confirmedConflictTxid == "replacement")
        #expect(controller.overrideWarning.contains("confirmed different transaction (replacement)"))
        controller.acknowledge()
        #expect(controller.locked)
        storage.attempt = try SendSafetyTests().roundTrip(#require(storage.attempt))
        let restarted = SendSafetyTests().controller(storage) { _ in throw URLError(.notConnectedToInternet) }
        await restarted.checkStatus()
        #expect(restarted.state == .uncertain && restarted.canOverride)
        #expect(restarted.overrideWarning == controller.overrideWarning)
        restarted.startNewPaymentAfterHistoryCheck(confirmed: true)
        #expect(!restarted.locked)
    }

    @Test(arguments: [false, true])
    func unconfirmedOrMalformedCompetitionDoesNotProveConflict(malformed: Bool) throws {
        let original = try SendSafetyTests().transaction(id: "exact")
        var competing = try SendSafetyTests().transaction(id: "replacement", confirmed: malformed)
        if malformed { competing.txHex = "0200000001" }
        var saved = SendPaymentController.Attempt(broadcastingTxid: "exact", payment: SendPreparedTests.payment,
                                                  snapshot: .init(movementIDs: []), quote: SendPreparedTests.quote)
        saved.quote?.prepared?.inputs = try SendTransaction.parse(hex: original.txHex).inputs
        guard case .paid(let receipt) = try SendReconciliation.onchain(saved, transactions: [original, competing]) else {
            Issue.record("Expected original receipt"); return
        }
        #expect(receipt.status == .submitted && receipt.confirmedConflictTxid == nil)
        #expect(receipt.broadcastRetryPending == true)
    }

    @Test(arguments: [false, true])
    func inputOutpointsUseConsensusByteOrderAndIndex(witness: Bool) throws {
        var tx = try SendSafetyTests().transaction(witness: witness)
        let wireTxid = (0..<32).map { String(format: "%02x", $0) }.joined()
        let offset = witness ? 14 : 10
        let start = tx.txHex.index(tx.txHex.startIndex, offsetBy: offset)
        let end = tx.txHex.index(start, offsetBy: 72)
        tx.txHex.replaceSubrange(start..<end, with: wireTxid + "78563412")
        #expect(try SendTransaction.parse(hex: tx.txHex).inputs == [
            .init(txid: (0..<32).reversed().map { String(format: "%02x", $0) }.joined(), vout: 0x1234_5678),
        ])
    }

    @Test func oldQuoteUsesCachedOriginalInputsWithoutMistakingOtherOutpointsForConflict() throws {
        var saved = SendPaymentController.Attempt(broadcastingTxid: "exact", payment: SendPreparedTests.payment,
                                                  snapshot: .init(movementIDs: []), quote: SendPreparedTests.quote)
        saved.quote?.prepared?.inputs = nil
        let original = try SendSafetyTests().transaction(id: "exact")
        var competing = try SendSafetyTests().transaction(id: "replacement", confirmed: true)
        guard case .paid(let receipt) = try SendReconciliation.onchain(saved, transactions: [original, competing]) else {
            Issue.record("Expected conflicted receipt"); return
        }
        #expect(receipt.status == .conflicted && receipt.confirmedConflictTxid == "replacement")
        let index = competing.txHex.index(competing.txHex.startIndex, offsetBy: 74)
        competing.txHex.replaceSubrange(index..<competing.txHex.index(index, offsetBy: 2), with: "01")
        guard case .paid(let unrelated) = try SendReconciliation.onchain(saved, transactions: [original, competing]) else {
            Issue.record("Expected pending receipt"); return
        }
        #expect(unrelated.status == .submitted && unrelated.confirmedConflictTxid == nil)
    }

    @Test func liveOverrideAuditPersistsBeforeUnlockAndAcrossNextPayment() throws {
        let documents = FileManager.default.temporaryDirectory.appending(path: "send-r7-audit-\(UUID())")
        defer { try? FileManager.default.removeItem(at: documents) }
        let location = WalletDataLocation(variant: .regtest, documentsDirectory: documents)
        try FileManager.default.createDirectory(at: location.directory, withIntermediateDirectories: true)
        var dependencies = SendPaymentController.Dependencies.live(location: location)
        dependencies.identity = nil
        try dependencies.write(.init(broadcastingTxid: "exact", payment: SendPreparedTests.payment,
                                     snapshot: .init(movementIDs: []), quote: SendPreparedTests.quote))
        let controller = SendPaymentController(dependencies: dependencies)
        controller.startNewPaymentAfterHistoryCheck(confirmed: true)
        #expect(!controller.locked)
        #expect(!SendPaymentController(dependencies: dependencies).locked)
        try dependencies.write(.init(payment: SendPreparedTests.payment, snapshot: .init(movementIDs: [])))
        let file = location.directory.appending(path: "noah-send-overrides.json")
        let audit = try #require(JSONDecoder().decode([SendOverrideAudit].self, from: Data(contentsOf: file)).first)
        #expect(audit.txid == "exact" && audit.warning == SendOverrideAudit.warningText)
        #expect(audit.title == SendOverrideAudit.titleText && audit.confirmation == SendOverrideAudit.confirmationText)
    }
}
