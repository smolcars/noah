import Foundation
@testable import Noah
import Testing

@MainActor struct SendJournalMigrationTests {
    /// Raw pre-proof journal shapes, rather than encoding today's model as a legacy journal.
    func legacy(version: Int, method: String, receiptKey: String = "receipt") throws -> SendPaymentController.Attempt {
        var payment: [String: Any] = ["destination": "1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa",
                                      "method": method, "amount": 1000, "max": false, "comment": ""]
        var receipt: [String: Any] = ["amount": 2000]
        if method == "onchain" {
            payment["source"] = "onchain"
            receipt["txid"] = "historical-wrong-amount"
            receipt["status"] = "confirmed"
        } else {
            receipt["paymentHash"] = "invoice-hash"
            receipt["movementID"] = 1
            receipt["fee"] = 83
        }
        let json: [String: Any] = [
            "safetyVersion": version, "payment": payment,
            "snapshot": ["movementIDs": [1], "bdkTxids": ["old"]],
            receiptKey: receipt, "failed": false, "paymentHash": "invoice-hash",
            "quote": ["amount": 1000, "fee": 208, "total": 1208, "approximate": true],
        ]
        return try JSONDecoder().decode(SendPaymentController.Attempt.self, from: JSONSerialization.data(withJSONObject: json))
    }
    @Test(arguments: [1, 2], ["onchain", "lightning"])
    func unsafeV1AndEarlyV2ReceiptsReopenLocked(version: Int, method: String) async throws {
        let storage = SendPaymentTests.Storage()
        storage.attempt = try legacy(version: version, method: method)
        let subject = SendSafetyTests().controller(storage) { _ in .uncertain }
        #expect(subject.state == .uncertain && subject.canOverride)
        #expect(subject.attempt?.receipt == nil && subject.attempt?.alreadyPaid == nil)
        #expect(storage.attempt?.safetyVersion == 3)
        #expect(storage.attempt?.quote?.amount == 1000)
        if method == "onchain" { #expect(storage.attempt?.possibleTxids == ["historical-wrong-amount"]) }
        subject.acknowledge()
        subject.submit(.init(destination: "other", method: "ark", amount: 1), quote: .init(amount: 1, fee: 0, total: 1))
        #expect(subject.locked)
        storage.attempt = try SendSafetyTests().roundTrip(#require(storage.attempt))
        let restarted = SendSafetyTests().controller(storage) { _ in .uncertain }
        await restarted.checkStatus()
        #expect(restarted.state == .uncertain && restarted.canOverride)
        restarted.startNewPaymentAfterHistoryCheck(confirmed: false)
        #expect(restarted.locked && storage.attempt != nil)
        restarted.startNewPaymentAfterHistoryCheck(confirmed: true)
        #expect(!restarted.locked && storage.attempt == nil)
    }
    @Test(arguments: [1, 2])
    func fabricatedAlreadyPaidAlsoReopens(version: Int) throws {
        let storage = SendPaymentTests.Storage()
        storage.attempt = try legacy(version: version, method: "lightning", receiptKey: "alreadyPaid")
        let subject = SendSafetyTests().controller(storage) { _ in .uncertain }
        #expect(subject.state == .uncertain && subject.attempt?.alreadyPaid == nil)
        #expect(storage.attempt?.safetyVersion == 3)
    }
    @Test func failedMigrationWriteNeverClearsJournalOrUnlocks() throws {
        let storage = SendPaymentTests.Storage()
        storage.attempt = try legacy(version: 2, method: "onchain")
        storage.failWrite = true
        let subject = SendSafetyTests().controller(storage) { _ in .uncertain }
        #expect(subject.state == .storageBlocked && subject.locked)
        #expect(storage.attempt?.receipt?.txid == "historical-wrong-amount")
        #expect(storage.attempt?.safetyVersion == 2)
        subject.acknowledge()
        subject.startNewPaymentAfterHistoryCheck(confirmed: true)
        #expect(subject.locked && storage.attempt != nil)
    }
    @Test func currentVersionWithoutProofAlsoFailsClosed() throws {
        let storage = SendPaymentTests.Storage()
        storage.attempt = try legacy(version: 3, method: "onchain")
        let subject = SendSafetyTests().controller(storage) { _ in .uncertain }
        #expect(subject.state == .uncertain && subject.attempt?.receipt == nil)
        #expect(subject.attempt?.possibleTxids == ["historical-wrong-amount"])
    }
    @Test func failedMigrationRetainsOriginalEvidenceAndRetryPersistsWithoutPaying() async throws {
        let storage = SendPaymentTests.Storage()
        storage.attempt = try legacy(version: 2, method: "onchain")
        storage.failWrite = true
        var reads = 0
        let subject = SendPaymentController(dependencies: .init(
            prepare: { _, _ in Issue.record("Must not prepare"); throw SendFailure.unresolved },
            pay: { _, _, _ in Issue.record("Must not pay"); throw SendFailure.unresolved },
            reconcile: { _ in Issue.record("Blocked migration must not reconcile"); return .uncertain },
            read: {
                reads += 1
                if reads > 1 { throw CocoaError(.fileReadNoPermission) }
                return storage.attempt
            }, write: { try storage.write($0) }))
        #expect(subject.state == .storageBlocked && subject.canOverride)
        #expect(subject.attempt?.receipt?.txid == "historical-wrong-amount")
        #expect(subject.attempt?.safetyVersion == 2)
        subject.acknowledge()
        subject.submit(.init(destination: "other", method: "ark", amount: 1), quote: .init(amount: 1, fee: 0, total: 1))
        #expect(subject.locked)
        storage.failWrite = false
        await subject.checkStatus()
        #expect(subject.state == .uncertain && subject.locked)
        #expect(storage.attempt?.safetyVersion == 3 && subject.attempt?.safetyVersion == 3)
        #expect(subject.attempt?.receipt == nil)
        #expect(subject.attempt?.possibleTxids == ["historical-wrong-amount"])
        #expect(reads == 1)
    }
    @Test(arguments: ["audit", "remove", "none"])
    func blockedMigrationOverrideRequiresConfirmationAuditAndSuccessfulRemoval(failure: String) throws {
        let storage = SendPaymentTests.Storage()
        storage.attempt = try legacy(version: 2, method: "onchain")
        var audits: [SendOverrideAudit] = []
        let subject = SendPaymentController(dependencies: .init(
            prepare: { _, _ in Issue.record("Must not prepare"); throw SendFailure.unresolved },
            pay: { _, _, _ in Issue.record("Must not pay"); throw SendFailure.unresolved },
            reconcile: { _ in Issue.record("Must not reconcile"); return .uncertain },
            read: { storage.attempt }, write: {
                if $0 != nil || failure == "remove" { throw CocoaError(.fileWriteNoPermission) }
                #expect(audits.last?.txid == "historical-wrong-amount")
                try storage.write($0)
            }, auditOverride: {
                if failure == "audit" { throw CocoaError(.fileWriteNoPermission) }
                audits.append($0)
            }))
        #expect(subject.state == .storageBlocked && subject.canOverride)
        subject.acknowledge()
        subject.startNewPaymentAfterHistoryCheck(confirmed: false)
        #expect(subject.locked && audits.isEmpty)
        #expect(subject.overrideWarning.contains("spend twice") && subject.overrideWarning.contains("future broadcast retries"))
        subject.startNewPaymentAfterHistoryCheck(confirmed: true)
        #expect(subject.locked == (failure != "none"))
        if failure != "none" {
            #expect(subject.attempt?.receipt?.txid == "historical-wrong-amount")
            #expect(subject.attempt?.safetyVersion == 2 && storage.attempt?.safetyVersion == 2)
        } else {
            #expect(storage.attempt == nil && subject.attempt == nil)
            let audit = try #require(audits.last)
            #expect(audit.txid == "historical-wrong-amount")
            #expect(audit.title == SendOverrideAudit.titleText)
            #expect(audit.warning == SendOverrideAudit.warningText)
            #expect(audit.confirmation == SendOverrideAudit.confirmationText)
        }
    }
}
