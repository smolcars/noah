import BarkBoltFFI
import SwiftUI

struct ArkInfoView: View {
    @Environment(\.locale) private var locale

    @State private var info: ArkInfo?
    @State private var loading = false
    @State private var error: String?
    @State private var selection = EsploraSettings().selection(for: .current)
    private var configuration: WalletConfiguration { .effective(for: .current, endpoint: selection.endpoint) }
    var body: some View {
        List {
            if loading { ProgressView() }
            if let error { Text(LocalizedStringKey(error)).foregroundStyle(NoahColor.destructive) }
            if let info {
                LabeledContent("Network", value: String(describing: info.network))
                Section("Keys") {
                    CopySettingRow(title: "Server pubkey", value: info.serverPubkey)
                    CopySettingRow(title: "Mailbox pubkey", value: info.mailboxPubkey)
                }
                Section("Boarding limits") {
                    LabeledContent("Minimum board amount", value: "\(info.minBoardAmountSats) sats")
                    LabeledContent("Maximum VTXO amount", value: info.maxVtxoAmountSats.map { "\($0) sats" } ?? "Unlimited")
                    LabeledContent("Required board confirmations", value: L10n.text("{count} blocks", info.requiredBoardConfirmations, locale: locale.identifier))
                }
                Section("Rounds & Expiry") {
                    LabeledContent("Round interval", value: "\(info.roundIntervalSecs) seconds")
                    LabeledContent("Round nonces", value: String(info.nbRoundNonces))
                    LabeledContent("VTXO exit delta", value: L10n.text("{count} blocks", info.vtxoExitDelta, locale: locale.identifier))
                    LabeledContent("VTXO lifetime", value: L10n.text("{count} blocks", info.vtxoLifetime, locale: locale.identifier))
                    LabeledContent("HTLC send expiry delta", value: L10n.text("{count} blocks", info.htlcSendExpiryDelta, locale: locale.identifier))
                }
                Section("Lightning") { LabeledContent("Receive anti-DoS") { Text(LocalizedStringKey(info.lnReceiveAntiDosRequired ? "Yes" : "No")) } }
            }
            Section("Wallet endpoints") {
                CopySettingRow(title: "Ark server", value: configuration.arkServer)
                if let esplora = configuration.esplora { CopySettingRow(title: selection.endpoint == nil ? "Esplora API · Noah default" : "Esplora API · Custom endpoint", value: esplora) }
                if let bitcoind = configuration.bitcoind { CopySettingRow(title: "Bitcoind RPC", value: bitcoind) }
                NavigationLink("Esplora Settings") { EsploraSettingsScreen() }
            }
            Section("Explorer APIs") {
                CopySettingRow(title: "Block height API", value: ArkInfoEndpoints.blockHeight(variant: .current, esploraOverride: selection.endpoint))
                let prices = ServerEndpoint.priceURLs(base: ServerEndpoint.baseURL(for: .current))
                CopySettingRow(title: "Mempool explorer", value: "https://mempool.space" + (AppVariant.current == .mainnet ? "" : "/signet"))
                CopySettingRow(title: "Price API", value: prices.current.absoluteString)
                CopySettingRow(title: "Historical price API", value: prices.historical.absoluteString)
            }
        }.settingsStyle("Ark Info").onAppear { selection = EsploraSettings().selection(for: .current) }.task { await load() }.refreshable { await load() }
    }
    private func load() async {
        loading = true; error = nil
        defer { loading = false }
        do {
            info = try await WalletSession.shared.withOpenWallet { try await $0.wallet.arkInfo() }
            if info == nil { error = "Ark server info is unavailable. Pull to refresh." }
        } catch { self.error = "Couldn't load Ark info. Pull to refresh." }
    }
}
