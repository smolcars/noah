import SwiftUI

/// App → Beta: the USDC send switch. Turning it on opens the Satora client, which creates its phrase, then shows the
/// phrase. Off hides USDC from Send and keeps everything; open swaps still finish on each foreground pass.
struct BetaPage: View {
    @Environment(\.locale) private var locale
    @Environment(\.settings) private var settings
    @State private var on = SatoraStore.enabled()
    @State private var error: String?

    private var language: String { locale.identifier }

    var body: some View {
        SettingsScroll(line: L10n.text("Early features that may change or go away.", locale: language)) {
            SettingsGroup {
                SettingsToggleRow(icon: "dollarsign.circle", title: L10n.text("Send USDC", locale: language),
                                  sub: L10n.text("Pay USDC addresses on Arbitrum through Satora's swap service", locale: language),
                                  isOn: Binding(get: { on }, set: setOn), hairline: on, id: "settings-beta-usdc")
                if on {
                    SettingsRow(icon: "key", title: L10n.text("Swap phrase", locale: language), hairline: false, id: "settings-swap-phrase") {
                        settings.open(.swapPhrase)
                    }
                }
            }
            if AppVariant.current != .mainnet {
                SettingsFine(text: L10n.text("USDC sends work on mainnet only.", locale: language))
            }
            if let error { SettingsFine(text: error, failure: true) }
        }
        .settingsChrome("Beta")
    }

    private func setOn(_ value: Bool) {
        error = nil
        guard value else {
            SettingsStorage.shared.remove(forKey: SatoraStore.enabledKey)
            on = false
            return
        }
        do {
            _ = try SatoraStore.shared.open()
            try SettingsStorage.shared.setDurably("true", forKey: SatoraStore.enabledKey)
            on = true
            settings.open(.swapPhrase)
        } catch { self.error = L10n.text("Couldn't turn on USDC sends.", locale: language) }
    }
}
