import BarkBoltFFI
import Foundation
import Testing

@testable import Noah

/// SDK 0.6.0 returns movement ids for Ark sends and offboards (FFI item 16).
@MainActor struct SendMovementIdentityTests {
    let fixtures = SendSafetyTests()
    let offboards = SendOffboardReceiptTests()

    @Test(arguments: ["ark", "offboard", "sendOnchain"])
    func returnedMovementIDSurvivesRestartWithoutHistoryMatching(rail: String) async throws {
        let ark = rail == "ark"
        let payment = SendPayment(destination: "reviewed-recipient", method: ark ? "ark" : "onchain", amount: 1000,
                                  source: ark ? nil : .offchain, max: rail == "offboard")
        let receipt = ark
            ? SendReceipt(amount: 1000, movementID: 7, proof: .submitReturnedArkSuccess)
            : SendReceipt(amount: 1000, txid: "returned", movementID: 7, amountIsPending: false, status: .submitted, proof: .submitReturnedTxid)
        let storage = SendPaymentTests.Storage()
        let subject = SendPaymentController(dependencies: .init(
            prepare: { _, _ in .init(movementIDs: [1]) }, pay: { _, _, _ in receipt },
            reconcile: { _ in Issue.record("A returned result needs no reconciliation"); return .uncertain },
            read: { storage.attempt }, write: { try storage.write($0) }))
        subject.submit(payment, quote: .init(amount: 1000, fee: 0, total: 1000))
        try await SendPaymentTests.waitUntil { subject.state != .submitting }
        #expect(subject.state == .succeeded(receipt))
        storage.attempt = try fixtures.roundTrip(#require(storage.attempt))
        let restarted = fixtures.controller(storage) { _ in .uncertain }
        #expect(restarted.attempt?.receipt?.movementID == 7)
        #expect(restarted.attempt?.receipt?.isProven(for: payment) == true)
    }

    /// Once Send's shared journal is gone, the recurring journal is the only copy of the returned id.
    @Test func recurringArkMovementIDSurvivesRestartWithoutTheSendJournal() async throws {
        let h = try RecurringExecutorTests.Harness()
        defer { try? FileManager.default.removeItem(at: h.directory) }
        h.arkMovementID = 42
        let schedule = try h.add()
        // Die after the payment, before the schedule settles.
        h.afterPay = { h.storeWriteFails.withLock { $0 = true } }
        await h.executor.execute()
        #expect(h.calls.withLock { $0 } == 1)
        let paid = try #require(h.journal.load()[schedule.id])
        #expect(paid.terminal?.outcome == .succeeded && paid.terminal?.attempt.receipt?.movementID == 42)
        #expect(paid.historyApplied != true)

        // Settlement releases Send's shared journal before applying the schedule; removing it here keeps
        // the recurring journal as the only copy whatever that order. Restart with storage healthy again.
        h.saved = nil
        h.afterPay = nil
        h.storeWriteFails.withLock { $0 = false }
        h.store = RecurringStore(settings: h.settings)
        h.rebuild()
        h.controller.reloadForWalletChange()
        #expect(h.controller.attempt == nil)
        for _ in 0..<2 { await h.executor.execute(); await h.executor.checkStatus(schedule.id) }
        #expect(h.calls.withLock { $0 } == 1)
        let settled = try #require(h.journal.load()[schedule.id])
        #expect(settled.terminal?.settlementID == paid.terminal?.settlementID)
        #expect(settled.terminal?.attempt.receipt?.movementID == 42)
        #expect(settled.consumed == true && settled.historyApplied == true && settled.released == true)
        #expect(try h.store.load().schedules[schedule.id]?.occurrencesPaid == 1)
    }

    @Test func metadataAttachesOnlyToTheExactNewMovement() {
        let snapshot = SendSnapshot(movementIDs: [1, 2])
        #expect(SendReconciliation.metadataMovementID(.init(amount: 1000, movementID: 7, proof: .submitReturnedArkSuccess),
                                                      snapshot: snapshot) == 7)
        // An inherited checkpoint's movement predates the attempt and keeps its own metadata.
        #expect(SendReconciliation.metadataMovementID(.init(amount: 1000, movementID: 2, proof: .actualMovement),
                                                      snapshot: snapshot) == nil)
        // Without an exact id nothing is attached; history is never matched by destination or amount.
        #expect(SendReconciliation.metadataMovementID(.init(amount: 1000, proof: .submitReturnedArkSuccess),
                                                      snapshot: snapshot) == nil)
    }

    @Test func offboardReceiptNeedsTheReturnedMovementAndTxidToAgree() throws {
        let saved = offboards.attempt()
        var receipt = try #require(saved.receipt)
        receipt.movementID = 2
        var other = try offboards.movement()
        other.id = 3
        // Two movements claim the txid: the returned id picks the payment's own.
        let resolved = SendReconciliation.offboardReceipt(receipt, payment: saved.payment, movements: [try offboards.movement(), other])
        #expect(resolved.amount == 850 && resolved.fee == 150 && resolved.movementID == 2 && resolved.amountIsPending == false)
        // Neither a movement with the txid but another id, nor the id with another txid, fills the receipt.
        for history in [[other], [try offboards.movement(txid: "other")]] {
            let unresolved = SendReconciliation.offboardReceipt(receipt, payment: saved.payment, movements: history)
            #expect(unresolved == receipt && unresolved.amountDescription == "Recipient amount pending")
        }
    }

    @Test func journalsFromBeforeMovementIDsKeepTodaysMatching() async throws {
        // Older receipts have no movementID key at all.
        var saved = offboards.attempt()
        var object = try #require(JSONSerialization.jsonObject(with: JSONEncoder().encode(saved)) as? [String: Any])
        var receipt = try #require(object["receipt"] as? [String: Any])
        #expect(receipt["movementID"] == nil)
        receipt.removeValue(forKey: "movementID")
        object["receipt"] = receipt
        saved = try JSONDecoder().decode(SendPaymentController.Attempt.self, from: JSONSerialization.data(withJSONObject: object))
        let storage = SendPaymentTests.Storage()
        storage.attempt = saved
        let actual = try offboards.movement()
        let restarted = fixtures.controller(storage) { attempt in
            .paid(SendReconciliation.offboardReceipt(attempt.receipt!, payment: attempt.payment, movements: [actual]))
        }
        await restarted.checkStatus()
        #expect(restarted.attempt?.receipt?.amount == 850 && restarted.attempt?.receipt?.movementID == 2)

        // An Ark attempt that never saved a receipt has no id: it stays uncertain with candidates, never "not sent".
        storage.attempt = try fixtures.roundTrip(fixtures.attempt("ark"))
        let pending = fixtures.movement("ark", status: "pending")
        let ark = fixtures.controller(storage) { SendReconciliation.ark($0, movements: [pending]) }
        await ark.checkStatus()
        #expect(ark.state == .uncertain && ark.attempt?.candidates?.map(\.id) == [2])
        #expect(ark.locked)
    }
}
