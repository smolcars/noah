import BarkBoltFFI
import Foundation
@testable import Noah
import Testing

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
    @Test func invalidationWinsOverAStatusCheckWaitingForTheSession() async {
        let storage = SendPaymentTests.Storage()
        let identity = Identity()
        let gate = WalletRestorerTests.Gate()
        storage.attempt = .init(
            walletIdentity: identity.value,
            payment: .init(destination: "ark", method: "ark", amount: 1000),
            snapshot: .init(movementIDs: []))
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
    func foreignAttemptDiscardedBeforeReceiptOrReconciliation(field: String) async {
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
        storage.attempt = .init(
            walletIdentity: identity.value,
            payment: .init(destination: "ark", method: "ark", amount: 1000),
            snapshot: .init(movementIDs: []))
        storage.attempt = try SendSafetyTests().roundTrip(#require(storage.attempt))
        let restarted = controller(storage, identity)
        #expect(restarted.state == .uncertain && restarted.attempt?.walletIdentity == identity.value)
        restarted.acknowledge()
        #expect(restarted.locked)
    }
    @Test func legacyWithoutIdentityNeverClaimsOwnershipOrSuccess() async {
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

@MainActor struct SendCheckpointAdoptionTests {
    actor Backend {
        var checks = 0
        var pays = 0
        var status = LightningSendStatus.unknown
        var fail = false
        func check(_: String) throws -> LightningSendStatus {
            checks += 1
            if fail { throw URLError(.timedOut) }
            return status
        }
        func pay() -> LightningSendStatus { pays += 1; return .unknown }
        func configure(_ status: LightningSendStatus, fail: Bool = false) { self.status = status; self.fail = fail }
    }
    let identity = SendWalletIdentity(fingerprint: "reviewed", network: "regtest")
    var quote: SendQuote { .init(amount: 1000, fee: 100, total: 1100) }
    func payment() throws -> SendPayment { try SendLightningSafetyTests().payment() }
    func send(_ payment: SendPayment) throws -> LightningSend {
        .init(paymentHash: try #require(PaymentEncoding.invoice(payment.destination)?.hash), htlcVtxoIds: ["held"], movementId: 1,
              invoice: payment.destination, amountSats: 1000, feeSats: 83, htlcVtxoCount: 1, hasFailedRevocation: false)
    }
    @Test(arguments: ["hash", "invoice", "amount", "fee", "total", "overflow", "reviewedOverflow", "revocation", "movement"])
    func mismatchesNeverAdvanceOrPay(field: String) async throws {
        let payment = try payment()
        let original = try send(payment)
        var pending = original
        var bound = quote
        switch field {
        case "hash": pending.paymentHash = "wrong"
        case "invoice": pending.invoice += "q" // same declared hash/amount, different signed parameters
        case "amount": pending.amountSats = 2000
        case "fee": pending.feeSats = 101; bound.total = 2000
        case "total": bound.total = 1082
        case "overflow": pending.feeSats = UInt64.max; bound.fee = UInt64.max; bound.total = UInt64.max
        case "reviewedOverflow": bound.fee = UInt64.max; bound.total = UInt64.max
        case "revocation": pending.hasFailedRevocation = true
        case "movement": pending.movementId = 2
        default: break
        }
        let backend = Backend()
        await #expect(throws: SendExistingLightning.self, "Mismatched checkpoint advanced") {
            _ = try await SendLightningCheckpoint(original).advance(invoice: payment.destination, payment: payment, quote: bound,
                                                                    state: .inProgress(send: pending), check: { try await backend.check($0) })
        }
        #expect(await backend.checks == 0)
        #expect(await backend.pays == 0)
    }
    @Test func checkpointDisappearsBetweenInspectionAndAdvanceNeverPays() async throws {
        let payment = try payment()
        let checkpoint = SendLightningCheckpoint(try send(payment))
        let backend = Backend()
        // Inspection was inProgress; the checkpoint vanishes inside checkLightningPayment.
        var reviewed = quote
        reviewed.checkpoint = checkpoint
        #expect(SendLightningCheckpoint.prepared(reviewed: reviewed, inspected: .unknown) == checkpoint)
        let status = try await SendLightningSubmission.submit(payment: payment, state: .inProgress(send: try send(payment)), movements: [],
                                                              checkpoint: checkpoint, quote: quote, check: { try await backend.check($0) },
                                                              pay: { await backend.pay() })
        #expect(throws: SendFailure.unresolved) { try checkpoint.receipt(status: status, movements: [], payment: payment, quote: quote) }
        #expect(await backend.checks == 1)
        #expect(await backend.pays == 0)
        // On the next refresh it is unknown already; neither API may create a new payment.
        await #expect(throws: SendFailure.unresolved) {
            try await SendLightningSubmission.submit(payment: payment, state: .unknown, movements: [],
                                                     checkpoint: checkpoint, quote: quote, check: { try await backend.check($0) }, pay: { await backend.pay() })
        }
        #expect(await backend.checks == 1)
        #expect(await backend.pays == 0)
    }
    @Test func persistencePrecedesAdvanceAndRestartTracksActualReceipt() async throws {
        let payment = try payment()
        let send = try send(payment)
        let checkpoint = SendLightningCheckpoint(send)
        let backend = Backend()
        let storage = SendPaymentTests.Storage()
        let snapshot = SendSnapshot(movementIDs: [1], walletIdentity: identity, checkpoint: checkpoint)
        var actual = SendSafetyTests().movement("lightning", amount: 1000, hash: checkpoint.paymentHash)
        actual.offchainFeeSats = 83
        actual.effectiveBalanceSats = -1083
        let movement = actual
        func make() -> SendPaymentController {
            SendPaymentController(dependencies: .init(
                prepare: { _, _ in snapshot },
                pay: { payment, quote, _ in
                    _ = try await checkpoint.advance(invoice: payment.destination, payment: payment, quote: quote,
                                                     state: .inProgress(send: send), check: { try await backend.check($0) })
                    throw SendFailure.unresolved
                },
                reconcile: { attempt in
                    let saved = try #require(attempt.snapshot.checkpoint)
                    let quote = try #require(attempt.quote)
                    let status = try await saved.advance(invoice: payment.destination, payment: payment, quote: quote,
                                                         state: .inProgress(send: send), check: { try await backend.check($0) })
                    return .paid(try saved.receipt(status: status, movements: [movement], payment: payment, quote: quote))
                }, read: { storage.attempt }, write: { try storage.write($0) }, identity: { identity }))
        }
        let subject = make()
        let caller = Task { subject.submit(payment, quote: quote) }
        await caller.value
        caller.cancel()
        try await SendPaymentTests.waitUntil { subject.state != .submitting }
        #expect(subject.state == .uncertain)
        #expect(storage.attempt?.snapshot.checkpoint == checkpoint && storage.attempt?.walletIdentity == identity)
        subject.acknowledge()
        subject.submit(payment, quote: quote)
        #expect(subject.locked)
        #expect(await backend.pays == 0)
        storage.attempt = try SendSafetyTests().roundTrip(#require(storage.attempt))
        let restarted = make()
        #expect(restarted.state == .uncertain)
        await backend.configure(.paid(paymentHash: checkpoint.paymentHash, preimage: "proof"))
        await restarted.checkStatus()
        guard case .succeeded(let receipt) = restarted.state else { Issue.record("No proven receipt"); return }
        #expect(receipt.amount == 1000 && receipt.fee == 83 && receipt.proof == .actualMovement)
        #expect(await backend.pays == 0)
    }
    @Test func failedAdoptionWriteNeverAdvances() async throws {
        let payment = try payment()
        let checkpoint = SendLightningCheckpoint(try send(payment))
        let backend = Backend()
        let storage = SendPaymentTests.Storage()
        let subject = SendPaymentController(dependencies: .init(
            prepare: { _, _ in .init(movementIDs: [], checkpoint: checkpoint) },
            pay: { _, _, _ in _ = try await backend.check(checkpoint.paymentHash); throw SendFailure.unresolved },
            reconcile: { _ in .uncertain }, read: { storage.attempt }, write: {
                if $0?.snapshot.checkpoint != nil { throw CocoaError(.fileWriteNoPermission) }
                try storage.write($0)
            }, identity: { identity }))
        subject.submit(payment, quote: quote)
        try await SendPaymentTests.waitUntil { subject.state != .submitting }
        #expect(subject.state == .uncertain && storage.attempt != nil)
        #expect(await backend.checks == 0)
        #expect(await backend.pays == 0)
    }
    @Test(arguments: ["unknown", "emptyPreimage", "wrongHash", "missingMovement", "wrongAmount", "wrongFee", "failed", "ambiguous"])
    func missingTerminalEvidenceStaysUncertain(kind: String) throws {
        let payment = try payment()
        let checkpoint = SendLightningCheckpoint(try send(payment))
        var status = LightningSendStatus.paid(paymentHash: checkpoint.paymentHash, preimage: "proof")
        var movement = SendSafetyTests().movement("lightning", amount: 1000, hash: checkpoint.paymentHash)
        movement.offchainFeeSats = 83
        movement.effectiveBalanceSats = -1083
        var movements = [movement]
        switch kind {
        case "unknown": status = .unknown
        case "emptyPreimage": status = .paid(paymentHash: checkpoint.paymentHash, preimage: "")
        case "wrongHash": status = .paid(paymentHash: "wrong", preimage: "proof")
        case "missingMovement": movements = []
        case "wrongAmount": movements[0] = SendSafetyTests().movement("lightning", amount: 2000, hash: checkpoint.paymentHash)
        case "wrongFee": movements[0].offchainFeeSats = 101; movements[0].effectiveBalanceSats = -1101
        case "failed": movements[0].status = "failed"
        case "ambiguous": movements.append(movement)
        default: break
        }
        #expect(throws: SendFailure.unresolved) { try checkpoint.receipt(status: status, movements: movements, payment: payment, quote: quote) }
    }
    @Test func heldFundsDoNotDisableLightningReview() throws {
        let payment = try payment()
        let storage = SendPaymentTests.Storage()
        let controller = SendWalletIdentityTests().controller(storage, SendWalletIdentityTests.Identity())
        let model = SendModel(controller: controller)
        model.destination = payment.destination
        model.amount = "1000"
        model.arkBalance = 0
        #expect(model.availability["lightning"] == false)
        model.pendingLightningHashes = [try #require(PaymentEncoding.invoice(payment.destination)?.hash)]
        #expect(model.availability["lightning"] == true)
        #expect(model.availability["ark"] == false)
    }
    @Test func lookupErrorOnAdoptedCheckpointNeverPays() async throws {
        let payment = try payment()
        let send = try send(payment)
        let backend = Backend()
        await backend.configure(.unknown, fail: true)
        await #expect(throws: URLError.self) {
            try await SendLightningSubmission.submit(payment: payment, state: .inProgress(send: send), movements: [],
                                                     checkpoint: .init(send), quote: quote, check: { try await backend.check($0) },
                                                     pay: { await backend.pay() })
        }
        #expect(await backend.checks == 1)
        #expect(await backend.pays == 0)
    }
    @Test func inspectionErrorPersistsLockWithoutPayment() async throws {
        let storage = SendPaymentTests.Storage()
        let subject = SendPaymentController(dependencies: .init(
            prepare: { _, _ in throw URLError(.networkConnectionLost) },
            pay: { _, _, _ in Issue.record("Must not pay"); throw SendFailure.unresolved },
            reconcile: { _ in .uncertain }, read: { storage.attempt }, write: { try storage.write($0) }, identity: { identity }))
        subject.submit(try payment(), quote: quote)
        try await SendPaymentTests.waitUntil { subject.state != .submitting }
        #expect(subject.state == .uncertain && storage.attempt?.walletIdentity == identity)
    }
}
