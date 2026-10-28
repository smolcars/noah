import BarkBoltFFI
import SwiftUI

/// Advanced → Ark server: every row today's Ark info shows, with plainer labels. Bitcoind RPC only on regtest.
struct ArkInfoView: View {
    @Environment(AppModel.self) private var app
    @Environment(\.settings) private var settings
    @Environment(\.locale) private var locale
    @State private var info: ArkInfo?
    @State private var loading = false
    @State private var error: String?
    @State private var selection = EsploraSettings().selection(for: .current)
    @State private var haptics = OnboardingHaptics()

    private var language: String { locale.identifier }
    private var configuration: WalletConfiguration { .effective(for: .current, endpoint: selection.endpoint) }

    var body: some View {
        SettingsScroll {
            Text(verbatim: AppVariant.current.rawValue.capitalized).noahText(HomeText.section)
                .padding(.horizontal, 9).padding(.vertical, 3)
                .background(NoahTokens.Colors.soft, in: Capsule())
                .padding(.top, 6)
            if loading && info == nil { ProgressView().frame(maxWidth: .infinity).padding(.top, 24) }
            if let error { SettingsFine(text: L10n.text(error, locale: language), failure: true) }
            VStack(spacing: 0) {
                row("Address", URL(string: configuration.arkServer)?.host() ?? configuration.arkServer, copy: configuration.arkServer)
                if let info {
                    row("Network", String(describing: info.network))
                    row("Server key", info.serverPubkey, mono: true, copy: info.serverPubkey)
                    row("Mailbox key", info.mailboxPubkey, mono: true, copy: info.mailboxPubkey, last: true)
                }
            }
            .padding(.top, 12)
            infoSections
            endpoints
        }
        .settingsChrome("Ark server")
        .onboardingHaptics(haptics)
        .onAppear { selection = EsploraSettings().selection(for: .current) }
        .task { await load() }
        .refreshable { await load() }
    }

    @ViewBuilder private var infoSections: some View {
        if let info {
            SettingsSection(title: "Moving to Ark")
            row("Smallest move", money(info.minBoardAmountSats), numeric: true)
            row("Largest VTXO", info.maxVtxoAmountSats.map(money) ?? L10n.text("No limit", locale: language))
            row("Confirmations", L10n.plural("{n} blocks", count: Int(info.requiredBoardConfirmations), locale: language), last: true)
            SettingsSection(title: "Rounds and expiry")
            row("Round every", Duration.seconds(Int64(info.roundIntervalSecs)).formatted(.units(allowed: [.hours, .minutes, .seconds], width: .wide)))
            row("Round nonces", String(info.nbRoundNonces), numeric: true)
            row("VTXO lifetime", L10n.plural("{n} blocks", count: Int(info.vtxoLifetime), locale: language))
            row("Exit delta", L10n.plural("{n} blocks", count: Int(info.vtxoExitDelta), locale: language))
            row("Lightning send delta", L10n.plural("{n} blocks", count: Int(info.htlcSendExpiryDelta), locale: language), last: true)
            SettingsSection(title: "Lightning")
            row("Receive anti-DoS", L10n.text(info.lnReceiveAntiDosRequired ? "Required" : "Not required", locale: language), last: true)
        }
    }

    @ViewBuilder private var endpoints: some View {
        SettingsSection(title: "Endpoints")
        SettingsDetailRow(label: L10n.text("Esplora", locale: language),
                          value: selection.endpoint == nil ? L10n.text("Noah default", locale: language) : L10n.text("Custom", locale: language)) {
            haptics.play(.light)
            settings.open(.esplora)
        }
        if let esplora = configuration.esplora { row("Esplora API", host(esplora), copy: esplora) }
        if let bitcoind = configuration.bitcoind { row("Bitcoind RPC", host(bitcoind), copy: bitcoind) }
        let height = ArkInfoEndpoints.blockHeight(variant: .current, esploraOverride: selection.endpoint)
        row("Block height API", host(height), copy: height)
        let explorer = "https://mempool.space" + (AppVariant.current == .mainnet ? "" : "/signet")
        row("Explorer", host(explorer), copy: explorer)
        let prices = ServerEndpoint.priceURLs(base: ServerEndpoint.baseURL(for: .current))
        row("Price API", host(prices.current.absoluteString), copy: prices.current.absoluteString)
        row("Historical price API", host(prices.historical.absoluteString), copy: prices.historical.absoluteString, last: true)
    }

    private func row(_ label: String, _ value: String, mono: Bool = false, numeric: Bool = false, copy: String? = nil, last: Bool = false) -> some View {
        SettingsDetailRow(label: L10n.text(label, locale: language), value: value, mono: mono, numeric: numeric, copy: copy, hairline: !last)
    }

    private func host(_ url: String) -> String { URL(string: url)?.host() ?? url }

    private func money(_ sats: UInt64) -> String { BitcoinAmount.format(sats: Int64(clamping: sats), unit: app.amountUnit, locale: locale) }

    private func load() async {
        loading = true
        error = nil
        defer { loading = false }
        do {
            info = try await WalletSession.shared.withOpenWallet { try await $0.wallet.arkInfo() }
            if info == nil { error = "Ark server info is unavailable. Try refreshing." }
        } catch { self.error = "Ark server info is unavailable. Try refreshing." }
    }
}
