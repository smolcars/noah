import BarkBoltFFI
import Foundation
@testable import Noah
import Testing

@MainActor struct SendLightningSafetyTests {
    actor Payer {
        var calls = 0
        func pay() -> LightningSendStatus { calls += 1; return .unknown }
    }
    struct Expected: Decodable {}
    func payment() throws -> SendPayment {
        let fixtures = try Fixtures.load("send-utils", input: SendFixtureTests.ParseInput.self, expected: Expected.self)
        let invoice = try #require(fixtures.first { PaymentEncoding.invoice($0.input.destination) != nil })
        return .init(destination: invoice.input.destination, method: "lightning", amount: 1000)
    }
    @Test func sendsWaitWithAnExplicitSixtySecondServerRetry() {
        #expect(SendLightningSubmission.options == LightningSendOptions(wait: true, retryForSecs: 60))
    }
    @Test(arguments: [UInt64(1000), 2000])
    func existingCheckpointNeverCallsPay(amount: UInt64) async throws {
        let payment = try payment()
        let pending = LightningSend(paymentHash: try #require(PaymentEncoding.invoice(payment.destination)?.hash),
                                    htlcVtxoIds: [], movementId: nil, invoice: payment.destination, amountSats: amount, feeSats: 83,
                                    htlcVtxoCount: 1, hasFailedRevocation: false)
        let payer = Payer()
        do {
            _ = try await SendLightningSubmission.submit(payment: payment, state: .inProgress(send: pending), movements: []) {
                await payer.pay()
            }
            Issue.record("Existing checkpoints must never reach payment")
        } catch let error as SendExistingLightning {
            #expect(error.progress == .init(amount: amount, fee: 83))
        }
        #expect(await payer.calls == 0)
    }
    @Test func unknownPendingMovementNeverCallsPay() async throws {
        let payment = try payment()
        let hash = try #require(PaymentEncoding.invoice(payment.destination)?.hash)
        let pending = SendSafetyTests().movement("lightning", status: "pending", amount: 2000, hash: hash)
        let payer = Payer()
        await #expect(throws: SendFailure.unresolved) {
            _ = try await SendLightningSubmission.submit(payment: payment, state: .unknown, movements: [pending]) {
                await payer.pay()
            }
        }
        #expect(await payer.calls == 0)
    }
    @Test func paidInvoiceShowsOnlyProvableNumbersWithoutCallingPay() async throws {
        let payment = try payment()
        let hash = try #require(PaymentEncoding.invoice(payment.destination)?.hash)
        var movement = SendSafetyTests().movement("lightning", amount: 2000, hash: hash)
        movement.offchainFeeSats = 83
        movement.effectiveBalanceSats = -2083
        let payer = Payer()
        // An amountless invoice: the movement can't say which attempt's amount and fee it holds.
        do {
            _ = try await SendLightningSubmission.submit(payment: payment, state: .paid(paymentHash: hash, preimage: "proof"), movements: [movement]) {
                await payer.pay()
            }
            Issue.record("Paid invoices must not reach payment")
        } catch let error as SendAlreadyPaid {
            #expect(error.receipt.amount == 0 && error.receipt.fee == nil)
        }
        // An invoice with its own amount: the movement's amount and the fee its effective balance took.
        let fixed = SendPayment(destination: try SendLnurlSafetyTests().invoice(), method: "lightning", amount: 1000)
        movement.sentToAddresses[0].amountSats = 1000
        movement.effectiveBalanceSats = -1083
        do {
            _ = try await SendLightningSubmission.submit(payment: fixed, state: .paid(paymentHash: hash, preimage: "proof"), movements: [movement]) {
                await payer.pay()
            }
            Issue.record("Paid invoices must not reach payment")
        } catch let error as SendAlreadyPaid {
            #expect(error.receipt.amount == 1000 && error.receipt.fee == 83)
        }
        #expect(await payer.calls == 0)
    }
    @Test func mismatchedCheckpointSurvivesRestartLockedWithRealAmounts() async throws {
        let storage = SendPaymentTests.Storage()
        let payment = try payment()
        let subject = SendPaymentController(dependencies: .init(
            prepare: { _, _ in .init(movementIDs: []) },
            pay: { _, _, _ in throw SendExistingLightning(progress: .init(amount: 2000, fee: 83)) },
            reconcile: { _ in .uncertain }, read: { storage.attempt }, write: { try storage.write($0) }))
        subject.submit(payment, quote: .init(amount: 1000, fee: 50, total: 1050))
        try await SendPaymentTests.waitUntil { subject.state != .submitting }
        #expect(subject.state == .uncertain)
        #expect(subject.attempt?.existingLightning == .init(amount: 2000, fee: 83))
        subject.acknowledge()
        #expect(subject.locked)
        storage.attempt = try SendSafetyTests().roundTrip(#require(storage.attempt))
        let restarted = SendSafetyTests().controller(storage) { _ in .uncertain }
        #expect(restarted.state == .uncertain)
        #expect(restarted.attempt?.existingLightning?.amount == 2000)
        #expect(restarted.attempt?.payment.amount == 1000)
    }
}
