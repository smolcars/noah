import BarkBoltFFI
import Foundation
import Testing

@testable import Noah

@MainActor struct SendWalletIdentityTests {
    final class Identity {
        var value = SendWalletIdentity(fingerprint: "first", network: "regtest")
    }
    func controller(_ storage: SendPaymentTests.Storage, _ identity: Identity) -> SendPaymentController {
        SendPaymentController(dependencies: .init(
            prepare: { _, _ in .init(movementIDs: []) },
            pay: { _, _, _ in .init(amount: 1000, proof: .submitReturnedArkSuccess) },
            reconcile: { _ in Issue.record("Must not reconcile another or unbound wallet"); return .uncertain },
            read: { storage.attempt }, write: { try storage.write($0) }, identity: { identity.value }))
    }
    @Test func invalidationWinsOverAStatusCheckWaitingForTheSession() async throws {
        let storage = SendPaymentTests.Storage()
        let identity = Identity()
        let gate = WalletRestorerTests.Gate()
        storage.attempt = .init(walletIdentity: identity.value, payment: .init(destination: "ark", method: "ark", amount: 1000), snapshot: .init(movementIDs: []))
        let subject = SendPaymentController(dependencies: .init(
            prepare: { _, _ in .init(movementIDs: []) }, pay: { _, _, _ in throw SendFailure.unresolved },
            reconcile: { _ in Issue.record("Stale status check"); return .uncertain },
            read: { storage.attempt }, write: { try storage.write($0) }, identity: { identity.value },
            withReservation: { body in await gate.enter(); await body() }))
        let checking = Task { await subject.checkStatus() }
        await gate.waitUntilEntered()
        #expect(subject.checking)
        storage.attempt = nil
        identity.value.fingerprint = "replacement"
        subject.reloadForWalletChange()
        await gate.release()
        await checking.value
        #expect(subject.state == .ready && subject.attempt == nil && !subject.checking)
    }
    @Test(arguments: ["fingerprint", "network"])
    func foreignAttemptDiscardedBeforeReceiptOrReconciliation(field: String) async throws {
        let storage = SendPaymentTests.Storage()
        let identity = Identity()
        storage.attempt = .init(walletIdentity: identity.value, payment: .init(destination: "ark", method: "ark", amount: 1000),
                                snapshot: .init(movementIDs: []), receipt: .init(amount: 1000, proof: .submitReturnedArkSuccess))
        if field == "fingerprint" { identity.value.fingerprint = "second" } else { identity.value.network = "signet" }
        let subject = controller(storage, identity)
        await subject.checkStatus()
        #expect(subject.state == .ready && subject.attempt == nil && storage.attempt == nil)
    }
    @Test func foreignJournalRemovalFailureBlocks() {
        let storage = SendPaymentTests.Storage()
        let identity = Identity()
        storage.attempt = .init(walletIdentity: .init(fingerprint: "other", network: "regtest"),
                                payment: .init(destination: "ark", method: "ark", amount: 1000), snapshot: .init(movementIDs: []))
        storage.failWrite = true
        let subject = controller(storage, identity)
        #expect(subject.state == .storageBlocked && subject.attempt == nil)
    }
    @Test func sameWalletRestartRetainsLock() throws {
        let storage = SendPaymentTests.Storage()
        let identity = Identity()
        storage.attempt = .init(walletIdentity: identity.value, payment: .init(destination: "ark", method: "ark", amount: 1000), snapshot: .init(movementIDs: []))
        storage.attempt = try SendSafetyTests().roundTrip(#require(storage.attempt))
        let restarted = controller(storage, identity)
        #expect(restarted.state == .uncertain && restarted.attempt?.walletIdentity == identity.value)
        restarted.acknowledge()
        #expect(restarted.locked)
    }
    @Test func legacyWithoutIdentityNeverClaimsOwnershipOrSuccess() async throws {
        let storage = SendPaymentTests.Storage()
        let identity = Identity()
        storage.attempt = .init(payment: .init(destination: "old", method: "ark", amount: 1000), snapshot: .init(movementIDs: []),
                                receipt: .init(amount: 1000, proof: .submitReturnedArkSuccess))
        let subject = controller(storage, identity)
        #expect(subject.state == .uncertain && subject.attempt == nil && subject.canOverride)
        await subject.checkStatus()
        subject.acknowledge()
        subject.startNewPaymentAfterHistoryCheck(confirmed: false)
        #expect(subject.locked && storage.attempt != nil)
        storage.failWrite = true
        subject.startNewPaymentAfterHistoryCheck(confirmed: true)
        #expect(subject.locked)
        storage.failWrite = false
        subject.startNewPaymentAfterHistoryCheck(confirmed: true)
        #expect(subject.state == .ready && storage.attempt == nil)
    }
}
