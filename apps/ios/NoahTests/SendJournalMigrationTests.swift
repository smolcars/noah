import Foundation
import Testing

@testable import Noah

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
}
