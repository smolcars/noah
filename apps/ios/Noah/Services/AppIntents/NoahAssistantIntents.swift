import AppIntents // swiftlint:disable:this file_name - the assistant intents beyond the RN copy, side by side
import Foundation
import UIKit

/// The Lightning address the server gave this wallet (docs/native-rewrite/design/voice-assistants.md).
struct GetNoahLightningAddressIntent: AppIntent {
    static let title: LocalizedStringResource = "Get Lightning Address"
    static let description = IntentDescription("Returns your Noah Lightning address.")
    static let authenticationPolicy: IntentAuthenticationPolicy = .requiresAuthentication

    func perform() async throws -> some IntentResult & ReturnsValue<String?> & ProvidesDialog {
        let address: String?
        do { address = try await NoahIntentWallet.current.lightningAddress() } catch { throw AssistantFailure(error) }
        return .result(value: address, dialog: "\(AssistantReply.lightningAddress(address))")
    }
}

/// The newest settled payment, after a sync.
struct GetNoahLastPaymentIntent: AppIntent {
    static let title: LocalizedStringResource = "Get Last Payment"
    static let description = IntentDescription("Synchronizes Noah and tells you about your newest payment.")
    static let authenticationPolicy: IntentAuthenticationPolicy = .requiresAuthentication

    func perform() async throws -> some IntentResult & ProvidesDialog {
        let history: [HistoryTransaction]
        do { history = try await NoahIntentWallet.current.history() } catch { throw AssistantFailure(error) }
        let locale = LegacyLocale.resolved()
        return .result(dialog: "\(AssistantReply.lastPayment(AssistantReply.lastPayment(in: history, locale: locale), locale: locale))")
    }
}

/// Opens Receive the way the widget's Receive does: Noah opens and hands itself the widget's URL, so `WidgetRoute`
/// holds it through the app lock, drops it behind onboarding or a launch gate, and never leaves a payment under way.
/// (`OpenURLIntent` only opens universal links, not the app's own scheme.)
struct OpenNoahReceiveIntent: AppIntent {
    static let title: LocalizedStringResource = "Open Receive"
    static let description = IntentDescription("Opens Noah on Receive.")
    static let authenticationPolicy: IntentAuthenticationPolicy = .requiresAuthentication
    static let openAppWhenRun = true

    @MainActor
    func perform() async -> some IntentResult {
        await UIApplication.shared.open(WidgetRoute.url(.receive, variant: .current))
        return .result()
    }
}

/// Opens Send's camera the way the Lock Screen widget's Scan does.
struct OpenNoahScanIntent: AppIntent {
    static let title: LocalizedStringResource = "Scan QR Code"
    static let description = IntentDescription("Opens Noah's camera to scan a payment QR code.")
    static let authenticationPolicy: IntentAuthenticationPolicy = .requiresAuthentication
    static let openAppWhenRun = true

    @MainActor
    func perform() async -> some IntentResult {
        await UIApplication.shared.open(WidgetRoute.url(.scan, variant: .current))
        return .result()
    }
}
