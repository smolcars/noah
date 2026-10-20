import Foundation
import Testing
@testable import Noah

@MainActor struct SendRepeatParityTests {
    private func model() -> SendModel {
        SendModel(controller: SendPaymentTests().make(SendPaymentTests.Backend(), SendPaymentTests.Storage()),
            resolveRecipient: { destination in
                .init(destination: destination, minMsat: 1000, maxMsat: 10_000_000, commentAllowed: 100, arkAddress: "fresh-ark")
            }, quotePayment: { payment in .init(amount: payment.amount, fee: 0, total: payment.amount) })
    }
    @Test func repeatConsumesOnceAndRevalidatesWithNormalRailChoice() async {
        for comment in ["", "note"] {
            let send = model()
            send.arkBalance = 10_000
            send.destination = "old@example.com"
            send.route = .init(destination: send.destination, minMsat: 0, maxMsat: .max, commentAllowed: 100, arkAddress: "stale")
            var pending: RepeatPayment? = .init(destination: "alice@example.com", comment: comment,
                amountMode: "SATS", amountInput: "1000", amountSat: 1000)
            send.consumeRepeatPayment(&pending, currency: "USD")
            #expect(pending == nil && send.stage == .recipient)
            #expect(send.route == nil && send.quote == nil && !send.showReview)
            #expect(!send.progress.recipientConfirmed && send.progress.selectedRail == nil)
            send.amount = "2000"
            send.consumeRepeatPayment(&pending, currency: "USD")
            #expect(send.amount == "2000")
            await send.acceptRecipient()
            #expect(send.matchingRoute?.arkAddress == "fresh-ark")
            #expect(send.progress.selectedRail == (comment.isEmpty ? .ark : .lightning))
            #expect(!send.controller.locked)
        }
    }
    @Test func repeatRetainsFiatOnlyForMatchingCurrencyAndWaitsForEditableDraft() {
        for currency in ["USD", "EUR"] {
            let send = model()
            var pending: RepeatPayment? = .init(destination: "alice@example.com", comment: "note",
                amountMode: "FIAT", amountInput: "2.50", amountSat: 2500, fiatCurrency: "USD")
            send.busy = true
            send.consumeRepeatPayment(&pending, currency: currency)
            #expect(pending != nil && send.destination.isEmpty)
            send.busy = false
            send.consumeRepeatPayment(&pending, currency: currency)
            #expect(send.amount == (currency == "USD" ? "2.50" : "2500"))
            #expect(send.useFiat == (currency == "USD"))
            #expect(send.comment == "note" && pending == nil)
        }
    }
    @Test func repeatInvalidRecipientUsesNormalValidation() async {
        let send = model()
        var pending: RepeatPayment? = .init(destination: "invalid", comment: "", amountMode: "SATS", amountInput: "1", amountSat: 1)
        send.consumeRepeatPayment(&pending, currency: "USD")
        await send.acceptRecipient()
        #expect(send.error != nil && !send.progress.recipientConfirmed)
        #expect(send.quote == nil && !send.controller.locked)
    }
}
