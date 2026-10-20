import SwiftUI

struct EsploraSettingsScreen: View {
    @Environment(AppModel.self) private var model
    @State private var selection = EsploraSettings().selection(for: .current)
    @State private var input = ""
    @State private var busy = false
    @State private var message: String?
    @State private var error: String?
    private var defaultEndpoint: String { WalletConfiguration.base(for: .current).esplora ?? "" }
    private var effectiveEndpoint: String { WalletConfiguration.effective(for: .current, endpoint: selection.endpoint).esplora ?? "" }

    var body: some View {
        List {
            Section {
                Text("Used for Bitcoin network data throughout the wallet.")
                if AppVariant.current == .regtest { Text("Regtest wallets use Bitcoin Core instead of Esplora.") }
            }
            Section("Active endpoint") {
                Text(effectiveEndpoint)
                Text(selection.endpoint == nil ? "Noah default" : "Custom endpoint").foregroundStyle(NoahColor.mutedForeground)
            }
            Section("Esplora API base URL") {
                TextField("https://mempool.space/api", text: Binding(get: { input }, set: { input = $0; error = nil; message = nil }))
                    .textInputAutocapitalization(.never).autocorrectionDisabled().keyboardType(.URL)
                    .disabled(busy || AppVariant.current == .regtest)
                    .accessibilityIdentifier("esplora-endpoint-input")
                Text("Noah will test /block-height/0 and verify the Bitcoin network before changing the wallet configuration. You may also paste the full test or tip URL.")
                    .font(NoahFont.footnote).foregroundStyle(NoahColor.mutedForeground)
                if let attention = selection.attention { Text(attention).foregroundStyle(NoahColor.destructive) }
            }
            Section {
                if busy { ProgressView("Testing Endpoint...") }
                Button("Test & Save Endpoint") { apply(input) }
                    .disabled(busy || input.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || AppVariant.current == .regtest)
                    .accessibilityIdentifier("apply-esplora-endpoint")
                Button("Reset to Noah Default") { apply(nil) }
                    .disabled(busy || selection.endpoint == nil && selection.input == nil || AppVariant.current == .regtest)
                    .accessibilityIdentifier("reset-esplora-endpoint")
                if let message { Text("Endpoint updated").bold(); Text(message) }
                if let error { Text("Endpoint not changed").bold(); Text(error).foregroundStyle(NoahColor.destructive) }
            }
            Section("Default endpoint") {
                Text(defaultEndpoint)
                Text("A custom Esplora server can observe the Bitcoin addresses and transactions requested by this wallet. Only use a server you trust.")
                    .font(NoahFont.footnote).foregroundStyle(NoahColor.mutedForeground)
            }
        }
        .settingsStyle("Edit Esplora API")
        .onAppear { input = selection.input ?? effectiveEndpoint }
        .onReceive(NotificationCenter.default.publisher(for: AppServices.esploraReopened)) { _ in
            selection = EsploraSettings().selection(for: .current)
            Task {
                error = await model.session.isOpen
                    ? L10n.text("Endpoint not changed. The wallet is using the previous endpoint.")
                    : L10n.text("Endpoint not changed. Restart Noah to reopen the previous endpoint.")
            }
        }
    }
    private func apply(_ value: String?) {
        guard !busy else { return }
        busy = true; message = nil; error = nil
        Task {
            defer { busy = false; selection = EsploraSettings().selection(for: .current) }
            do {
                try await AppServices.shared.switchEsploraEndpoint(value)
                selection = EsploraSettings().selection(for: .current)
                input = effectiveEndpoint
                message = selection.endpoint == nil ? "The wallet is now using Noah's default Esplora endpoint." : "The wallet is now using the new Esplora endpoint."
            } catch is EsploraOpenWait.Timeout {
                error = L10n.text("The server isn't responding; switching back…")
            } catch { self.error = LogFile.redact(error.localizedDescription) }
            await model.walletConfigurationChanged()
        }
    }
}
