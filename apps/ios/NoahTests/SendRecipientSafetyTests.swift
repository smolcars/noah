import Foundation
import Testing

@testable import Noah

@MainActor struct SendRecipientSafetyTests {
    private func freshController() -> SendPaymentController {
        SendPaymentTests().make(SendPaymentTests.Backend(), SendPaymentTests.Storage())
    }
    let quote = SendQuote(amount: 1000, fee: 208, total: 1208, feeRate: 1, approximate: true)
    @Test func scannedArkAfterLightningRouteNeverRedirects() async throws {
        // RN Ark decoder fixture re-encoded with the testnet HRP; never submitted to the SDK.
        let ark = "tark1pwh9vsmezqqpjy9akejayl2vvcse6he97rn40g84xrlvrlnhayuuyefrp9nse2yspqqkm2rpd"
        var reviewed: SendPayment?
        let model = SendModel(
            controller: freshController(),
            resolveRecipient: { recipient in
                .init(
                    destination: recipient, minMsat: 1000, maxMsat: 1_000_000_000,
                    commentAllowed: 100,
                    arkAddress: "A-ark-route")
            },
            quotePayment: { payment in
                reviewed = payment
                return .init(amount: payment.amount, fee: 0, total: payment.amount)
            })
        model.amount = "1000"
        model.arkBalance = 10000
        model.destination = "alice@example.com"
        await model.acceptRecipient()
        #expect(model.matchingRoute?.arkAddress == "A-ark-route")
        model.comment = "old recipient's note"
        await model.importRecipient(ark)
        #expect(model.route == nil)
        #expect(model.comment.isEmpty)
        #expect(reviewed?.destination == ark)
        #expect(reviewed?.method == "ark")
        // Even an accidentally reintroduced route must not substitute for a non-LNURL recipient.
        model.route = .init(
            destination: "alice@example.com", minMsat: 0, maxMsat: .max, commentAllowed: 100,
            arkAddress: "A-ark-route")
        await model.review()
        #expect(reviewed?.destination == ark)
    }

    @Test func everyDestinationAssignmentInvalidatesSynchronously() {
        let model = SendModel(controller: freshController())
        model.destination = "a@example.com"
        model.parsed = .init(destinationType: "lnurl", amount: 500)
        model.amount = "500"
        model.route = .init(
            destination: model.destination, minMsat: 0, maxMsat: .max, commentAllowed: 1,
            arkAddress: "old")
        model.payment = .init(destination: "old", method: "ark", amount: 500)
        model.quote = quote
        model.showReview = true
        model.progress.recipientConfirmed = true
        model.progress.railConfirmed = true
        model.source = .offchain
        model.destination = "b@example.com"  // Same setter for typing, paste, scan and repeat prefill.
        #expect(model.route == nil && model.payment == nil && model.quote == nil)
        #expect(model.parsed.destinationType == nil && model.source == nil)
        #expect(model.amount.isEmpty && !model.showReview)
        #expect(!model.progress.recipientConfirmed && !model.progress.railConfirmed)
    }

    @Test func lateRecipientResolutionCannotRestoreStaleRoute() async {
        let model = SendModel(
            controller: freshController(),
            resolveRecipient: { recipient in
                try await Task.sleep(for: .milliseconds(25))
                return .init(
                    destination: recipient, minMsat: 0, maxMsat: .max, commentAllowed: 0,
                    arkAddress: "old")
            })
        model.destination = "a@example.com"
        let pending = Task { await model.previewRecipient() }
        await Task.yield()
        model.destination = "b@example.com"
        await pending.value
        #expect(model.route == nil)
    }

    @Test func onchainMaxExplainsWhyItIsUnavailable() async {
        let model = SendModel(controller: freshController())
        model.onchainBalance = 0
        model.onchainTotal = 24900
        model.maxSend()
        model.source = .onchain
        #expect(!model.sources.contains(.onchain))
        await model.continueStage()
        #expect(model.error == SendFailure.onchainMaxUnavailable.errorDescription)
        #expect(!model.progress.sourceConfirmed && !model.showReview)
    }
}
