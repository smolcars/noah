import AppIntents
import Foundation
import Observation

/// Pay someone (docs/native-rewrite/design/voice-assistants.md): opens Send with the recipient, and the amount when
/// one was given, filled in. Nothing is sent: the user still holds to send. The request takes the payment-link route,
/// so onboarding, launch gates, the app lock and a payment already under way treat it as a tapped payment link.
@available(iOS 18.4, *)
struct PayNoahRecipientIntent: AppIntent {
    static let title: LocalizedStringResource = "Pay Someone"
    static let description = IntentDescription("Opens Send in Noah with the recipient and amount filled in. Noah never sends until you hold to send.")
    static let authenticationPolicy: IntentAuthenticationPolicy = .requiresAuthentication
    static let openAppWhenRun = true

    @Parameter(title: "Recipient", requestValueDialog: "Who do you want to pay?")
    var recipient: NoahRecipientEntity

    @Parameter(title: "Amount in Sats", description: "Optional. Send asks for it when it's missing.")
    var amountSats: Int?

    static var parameterSummary: some ParameterSummary {
        Summary("Pay \(\.$recipient)") { \.$amountSats }
    }

    init() { /* AppIntent requires an empty init. */ }

    init(recipient: NoahRecipientEntity, amountSats: Int? = nil) {
        self.recipient = recipient
        self.amountSats = amountSats
    }

    /// What Send is handed: the recipient's address, and a positive amount or none.
    var request: PaymentLinkRoute.Pay {
        .init(recipient: recipient.id, sats: amountSats.flatMap { $0 > 0 ? UInt64($0) : nil })
    }

    @MainActor
    func perform() async -> some IntentResult & ProvidesDialog { // swiftlint:disable:this async_without_await - AppIntent.perform
        SiriPayRoute.shared.pending = request
        return .result(dialog: "\(AssistantReply.pay(recipient: recipient.spoken))")
    }
}

/// Siri's Pay, held in memory until `RootView` hands it to the payment-link route with the app's gate, as
/// `onOpenURL` does for a link.
@MainActor
@Observable
final class SiriPayRoute {
    static let shared = SiriPayRoute()
    var pending: PaymentLinkRoute.Pay?
}

extension AssistantReply {
    static func pay(recipient: String, locale: String = LegacyLocale.resolved()) -> String {
        L10n.text("Opening Send to {recipient}.", recipient, locale: locale)
    }
}
