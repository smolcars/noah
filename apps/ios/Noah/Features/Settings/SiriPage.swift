import AppIntents
import SwiftUI

/// App → Siri: Siri's tip, the "Siri & Search" switch, a few more things to say, and the way to Noah's shortcuts
/// (docs/native-rewrite/design/voice-assistants.md).
struct SiriPage: View {
    @Environment(\.locale) private var locale
    @State private var indexed = SiriIndexer.shared.enabled
    @State private var changing = false

    private var language: String { locale.identifier }

    /// The phrase as Siri hears it: in the system's language, as Siri's tip shows it, with the app's name.
    nonisolated static func spoken(_ phrase: String, bundle: Bundle = .main) -> String {
        let name = bundle.object(forInfoDictionaryKey: "CFBundleDisplayName") as? String ?? "Noah"
        return bundle.localizedString(forKey: phrase, value: phrase, table: "AppShortcuts").replacingOccurrences(of: "${applicationName}", with: name)
    }

    var body: some View {
        SettingsScroll(line: L10n.text("Ask Siri about your wallet while your device is unlocked. Siri never sends money.", locale: language)) {
            SiriTipView(intent: GetNoahBalanceIntent())
                .padding(.top, 18)
            SettingsGroup {
                SettingsToggleRow(icon: "magnifyingglass", title: L10n.text("Siri & Search", locale: language),
                                  sub: L10n.text("Lets Siri and Spotlight find your balance, payments and who you pay", locale: language),
                                  isOn: Binding(get: { indexed }, set: setIndexed), hairline: false, id: "settings-siri-index")
            }
            .disabled(changing)
            SettingsGroup(label: "Try saying") {
                // Request is a shortcut from iOS 26 (its snippet).
                if #available(iOS 26.0, *) { example(SiriExamplePhrase.invoice, L10n.text("Lightning invoice", locale: language)) }
                example(SiriExamplePhrase.lightningAddress, L10n.text("Lightning address", locale: language))
                example(SiriExamplePhrase.lastPayment, L10n.text("Last payment", locale: language))
                example(SiriExamplePhrase.scan, L10n.text("Scan QR code", locale: language), last: true)
            }
            ShortcutsLink()
                .frame(maxWidth: .infinity)
                .padding(.top, 28)
        }
        .settingsChrome("Siri")
    }

    /// Off removes everything Noah indexed and stops indexing; on indexes again.
    private func setIndexed(_ on: Bool) {
        indexed = on
        changing = true
        Task {
            do { try await SiriIndexer.shared.setEnabled(on) } catch { indexed = !on }
            changing = false
        }
    }

    private func example(_ phrase: String, _ label: String, last: Bool = false) -> some View {
        SettingsRowLabel(title: "“\(Self.spoken(phrase))”", sub: label, hairline: !last) { EmptyView() }
            .accessibilityElement(children: .combine)
    }
}
