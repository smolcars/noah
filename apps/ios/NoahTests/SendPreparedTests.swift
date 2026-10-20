import BarkBoltFFI
import Foundation
import Testing

@testable import Noah

@MainActor struct SendPreparedTests {
    @MainActor final class Journal {
        var attempt: SendPaymentController.Attempt?
        func write(_ value: SendPaymentController.Attempt?) { attempt = value }
    }
    static let payment = SendPayment(destination: "destination", method: "onchain", amount: 1000, source: .onchain)
    static var quote: SendQuote {
        .init(amount: 1000, fee: 143, total: 1143, feeRate: 1,
              prepared: .init(.init(id: "prepared", txid: "exact", amountSats: 1000,
                                    feeSats: 143, vsize: 143, inputs: [.init(txid: "input", vout: 0)])))
    }
    @Test func exactReviewAndExpiry() {
        var quote = Self.quote
        #expect(!quote.approximate && !quote.recipientIsEstimated)
        #expect(quote.prepared?.vsize == 143 && quote.prepared?.inputCount == 1)
        #expect(quote.prepared?.isCurrent == true)
        quote.prepared?.createdAt = Date().addingTimeInterval(-121)
        #expect(quote.prepared?.isCurrent == false)
    }
    @Test func broadcastOutcomes() throws {
        for broadcast in [BroadcastOutcome.accepted, .failed(reason: "contains private diagnostic")] {
            let receipt = try SendOnchainSubmission.receipt(.init(txid: "exact", broadcast: broadcast), quote: Self.quote)
            #expect(receipt.txid == "exact" && receipt.amount == 1000 && receipt.fee == 143)
            #expect(receipt.proof == .preparedTxid && receipt.isProven(for: Self.payment))
            var offboard = Self.payment
            offboard.source = .offchain
            #expect(!receipt.isProven(for: offboard))
            #expect(receipt.broadcastRetryPending == (broadcast == .accepted ? nil : true))
        }
        #expect(throws: SendNotSubmitted.self) {
            try SendOnchainSubmission.receipt(.init(txid: "exact", broadcast: .notAttempted(reason: "disk")), quote: Self.quote)
        }
        #expect(throws: SendFailure.unresolved) {
            try SendOnchainSubmission.receipt(.init(txid: "other", broadcast: .accepted), quote: Self.quote)
        }
    }
    @Test func journalBeforeBroadcastAndFailureNeverUnlocks() async throws {
        let quote = Self.quote
        let storage = Journal()
        let receipt = try SendOnchainSubmission.receipt(.init(txid: "exact", broadcast: .failed(reason: "rpc")), quote: Self.quote)
        let controller = SendPaymentController(dependencies: .init(
            prepare: { _, _ in .init(movementIDs: []) },
            pay: { _, _, _ in
                await MainActor.run {
                    #expect(storage.attempt?.broadcastingTxid == "exact")
                    #expect(storage.attempt?.quote == quote)
                }
                return receipt
            }, reconcile: { _ in .paid(receipt) }, read: { storage.attempt }, write: { storage.write($0) }))
        controller.submit(Self.payment, quote: quote)
        try await SendPaymentTests.waitUntil { controller.state != .submitting }
        #expect(controller.state == .succeeded(receipt))
        controller.acknowledge()
        #expect(controller.locked && controller.canOverride)
        let restarted = SendPaymentController(dependencies: .init(
            prepare: { _, _ in Issue.record("must not prepare again"); throw SendFailure.unresolved },
            pay: { _, _, _ in Issue.record("must not broadcast again"); throw SendFailure.unresolved },
            reconcile: { _ in .paid(receipt) }, read: { storage.attempt }, write: { storage.write($0) }))
        restarted.acknowledge()
        #expect(restarted.locked)
    }
    @Test func crashRecoveryBindsOnlyExactTxidAndAbsenceStaysUncertain() throws {
        var attempt = SendPaymentController.Attempt(broadcastingTxid: "exact", payment: Self.payment,
                                                   snapshot: .init(movementIDs: []), quote: Self.quote)
        let tx = try SendSafetyTests().transaction(id: "exact")
        guard case .paid(let receipt) = try SendReconciliation.onchain(attempt, transactions: [tx]) else {
            Issue.record("Exact prepared transaction must establish receipt"); return
        }
        #expect(receipt.amount == 1000 && receipt.fee == 143 && receipt.txid == "exact")
        #expect(receipt.broadcastRetryPending == true)
        #expect(try SendReconciliation.onchain(attempt, transactions: []) == .uncertain)
        #expect(try SendReconciliation.onchain(attempt, transactions: [SendSafetyTests().transaction(id: "lookalike")]) == .uncertain)
        attempt.payment.max = true
        #expect(try SendReconciliation.onchain(attempt, transactions: [tx]) == .paid(receipt))
        attempt.receipt = receipt
        guard case .paid(let missing) = try SendReconciliation.onchain(attempt, transactions: []) else { return }
        #expect(missing.status == .unlocated)
    }

    @Test(arguments: [false, true])
    func recoveredBroadcastStaysLockedUntilConfirmation(failedReceiptWrite: Bool) async throws {
        let storage = SendPaymentTests.Storage()
        let quote = Self.quote
        if failedReceiptWrite {
            storage.failReceiptWrite = true
            let controller = SendPaymentController(dependencies: .init(
                prepare: { _, _ in .init(movementIDs: []) },
                pay: { _, _, _ in
                    try SendOnchainSubmission.receipt(.init(txid: "exact", broadcast: .failed(reason: "rpc")), quote: quote)
                }, reconcile: { _ in .uncertain },
                read: { storage.attempt }, write: { try storage.write($0) }))
            controller.submit(Self.payment, quote: quote)
            try await SendPaymentTests.waitUntil { controller.state != .submitting }
            #expect(controller.attempt?.receipt?.broadcastRetryPending == true)
            #expect(controller.message?.contains("could not be saved") == true)
        } else {
            // Process died after the SDK recorded the transaction, before any receipt was saved.
            storage.attempt = .init(broadcastingTxid: "exact", payment: Self.payment,
                                    snapshot: .init(movementIDs: []), quote: quote)
        }
        #expect(storage.attempt?.broadcastingTxid == "exact" && storage.attempt?.receipt == nil)
        storage.failReceiptWrite = false
        let backend = SendPaymentTests.Backend()
        let recovered = SendSafetyTests().controller(storage) { _ in
            // A failed sync must propagate, even though cached history contains the exact txid.
            if await backend.fail { throw URLError(.notConnectedToInternet) }
            return await backend.reconcile()
        }
        await recovered.checkStatus()
        #expect(recovered.state == .uncertain && recovered.locked)
        #expect(recovered.message?.contains("Couldn't check the latest status — you're offline") == true)
        recovered.acknowledge()
        #expect(storage.attempt != nil)

        await backend.succeed()
        let pending = try SendReconciliation.onchain(try #require(storage.attempt),
                                                     transactions: [SendSafetyTests().transaction(id: "exact")])
        await backend.setResolution(pending)
        await recovered.checkStatus()
        #expect(recovered.attempt?.receipt?.broadcastRetryPending == true)
        recovered.acknowledge()
        recovered.startNewPaymentAfterHistoryCheck(confirmed: false)
        #expect(recovered.locked && recovered.canOverride && storage.attempt != nil)

        let restarted = SendSafetyTests().controller(storage) { _ in await backend.reconcile() }
        restarted.acknowledge()
        #expect(restarted.locked)
        let confirmed = try SendReconciliation.onchain(try #require(storage.attempt),
                                                       transactions: [SendSafetyTests().transaction(id: "exact", confirmed: true)])
        await backend.setResolution(confirmed)
        await restarted.checkStatus()
        #expect(restarted.attempt?.receipt?.status == .confirmed)
        #expect(restarted.attempt?.receipt?.broadcastRetryPending == nil)
        restarted.acknowledge()
        #expect(!restarted.locked && storage.attempt == nil)
        #expect(await backend.calls == 0)
    }
}
