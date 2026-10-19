import BarkBoltFFI
import SwiftUI

struct ArkInfoView: View {
    @State private var info: ArkInfo?
    @State private var loading = false
    @State private var error: String?
    private let configuration = WalletConfiguration.base(for: .current)
    var body: some View {
        List {
            if loading { ProgressView() }
            if let error { Text(error).foregroundStyle(NoahColor.destructive) }
            if let info {
                Section("Keys") {
                    CopySettingRow(title: "Server pubkey", value: info.serverPubkey)
                    CopySettingRow(title: "Mailbox pubkey", value: info.mailboxPubkey)
                }
                Section("Boarding limits") {
                    LabeledContent("Minimum board amount", value: "\(info.minBoardAmountSats) sats")
                    LabeledContent("Maximum VTXO amount", value: info.maxVtxoAmountSats.map { "\($0) sats" } ?? "Unlimited")
                    LabeledContent("Required board confirmations", value: "\(info.requiredBoardConfirmations) blocks")
                }
                Section("Rounds & Expiry") {
                    LabeledContent("Round interval", value: "\(info.roundIntervalSecs) seconds")
                    LabeledContent("Round nonces", value: String(info.nbRoundNonces))
                    LabeledContent("VTXO exit delta", value: "\(info.vtxoExitDelta) blocks")
                    LabeledContent("VTXO lifetime", value: "\(info.vtxoLifetime) blocks")
                    LabeledContent("HTLC send expiry delta", value: "\(info.htlcSendExpiryDelta) blocks")
                }
                Section("Lightning") { LabeledContent("Receive anti-DoS", value: info.lnReceiveAntiDosRequired ? "Yes" : "No") }
            }
            Section("Wallet endpoints") {
                CopySettingRow(title: "Ark server", value: configuration.arkServer)
                if let esplora = configuration.esplora { CopySettingRow(title: "Esplora API · Noah default", value: esplora) }
                if let bitcoind = configuration.bitcoind { CopySettingRow(title: "Bitcoind RPC", value: bitcoind) }
                SettingsLabel(title: "Esplora Settings", detail: "Coming soon")
            }
            Section("Explorer APIs") {
                CopySettingRow(title: "Mempool explorer", value: "https://mempool.space" + (AppVariant.current == .mainnet ? "" : "/signet"))
                CopySettingRow(title: "Price API", value: "https://mempool.space/api/v1/prices")
                CopySettingRow(title: "Historical price API", value: "https://mempool.space/api/v1/historical-price")
            }
        }.settingsStyle("Ark Info").task { await load() }.refreshable { await load() }
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
