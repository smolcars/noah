import SwiftUI

/// Advanced → Esplora: where Noah reads the Bitcoin chain. Test and save, or go back to the
/// default, exactly as before; regtest uses Bitcoin Core instead.
struct EsploraSettingsScreen: View {
    @Environment(AppModel.self) private var model
    @Environment(\.settings) private var settings
    @Environment(\.locale) private var locale
    @State private var selection = EsploraSettings().selection(for: .current)
    @State private var input = ""
    @State private var busy = false
    @State private var error: String?
    @State private var haptics = OnboardingHaptics()

    private var language: String { locale.identifier }
    private var regtest: Bool { AppVariant.current == .regtest }
    private var effectiveEndpoint: String { WalletConfiguration.effective(for: .current, endpoint: selection.endpoint).esplora ?? "" }

    var body: some View {
        SettingsScroll(line: L10n.text("Where Noah reads the Bitcoin chain.", locale: language)) {
            VStack(alignment: .leading, spacing: 4) {
                Text(verbatim: regtest ? "Bitcoin Core" : effectiveEndpoint).noahText(SettingsText.current).lineLimit(2).truncationMode(.middle)
                    .textSelection(.enabled)
                Text(L10n.text(regtest ? "Regtest wallets use Bitcoin Core." : selection.endpoint == nil ? "Noah default" : "Custom server",
                               locale: language))
                    .noahText(NoahTokens.Typography.sub).foregroundStyle(NoahTokens.Colors.muted)
            }
            .padding(.top, 20)
            if !regtest {
                SettingsField(label: L10n.text("Custom server", locale: language), placeholder: "https://mempool.space/api",
                              text: Binding(get: { input }, set: { input = $0; error = nil }), failure: error != nil, keyboard: .URL,
                              id: "esplora-endpoint-input")
                    .disabled(busy)
                if let error {
                    SettingsFine(text: L10n.text(error, locale: language), failure: true)
                } else {
                    SettingsFine(text: L10n.text("Noah checks it’s on the right network before switching.", locale: language))
                }
                if let attention = selection.attention { SettingsFine(text: attention, failure: true) }
                SettingsWarning(text: L10n.text("A custom server can see your addresses. Use one you trust.", locale: language), systemImage: "eye")
            }
        }
        .settingsChrome("Esplora")
        .settingsFooter {
            if !regtest {
                Button(L10n.text("Use default", locale: language)) { apply(nil) }
                    .buttonStyle(OnboardingSecondaryButtonStyle(height: NoahTokens.Size.button))
                    .disabled(busy || selection.endpoint == nil && selection.input == nil)
                    .accessibilityIdentifier("reset-esplora-endpoint")
                Button { apply(input) } label: {
                    HStack(spacing: 8) {
                        if busy { ProgressView().tint(NoahTokens.Colors.background) }
                        Text(L10n.text(busy ? "Testing" : "Test and save", locale: language))
                    }
                }
                .buttonStyle(SettingsActionStyle())
                .disabled(busy || input.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                .accessibilityIdentifier("apply-esplora-endpoint")
            }
        }
        .onboardingHaptics(haptics)
        .onAppear { input = selection.endpoint == nil ? "" : (selection.input ?? effectiveEndpoint) }
        .onReceive(NotificationCenter.default.publisher(for: AppServices.esploraReopened)) { _ in
            selection = EsploraSettings().selection(for: .current)
            Task {
                error = await model.session.isOpen
                    ? L10n.text("Endpoint not changed. The wallet is using the previous endpoint.", locale: language)
                    : L10n.text("Endpoint not changed. Restart Noah to reopen the previous endpoint.", locale: language)
            }
        }
    }

    private func apply(_ value: String?) {
        guard !busy else { return }
        busy = true
        error = nil
        Task {
            defer { busy = false; selection = EsploraSettings().selection(for: .current) }
            do {
                try await AppServices.shared.switchEsploraEndpoint(value)
                selection = EsploraSettings().selection(for: .current)
                input = selection.endpoint == nil ? "" : effectiveEndpoint
                haptics.play(.success)
                settings.toast(selection.endpoint == nil ? "Using the default" : "Switched")
            } catch is EsploraOpenWait.Timeout {
                haptics.play(.error)
                error = L10n.text("The server isn't responding; switching back…", locale: language)
            } catch {
                haptics.play(.error)
                self.error = LogFile.redact(error.localizedDescription)
            }
            await model.walletConfigurationChanged()
        }
    }
}
