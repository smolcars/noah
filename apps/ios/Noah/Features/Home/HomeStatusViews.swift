import SwiftUI

struct HomeStatusBanners: View {
    let home: HomeModel
    @Environment(\.locale) private var locale
    @State private var showRounds = false

    var body: some View {
        if let banner = HomeBanner.backup(home.backup, now: home.bannerNow.timeIntervalSince1970 * 1000, locale: locale) {
            NoahCard {
                VStack(alignment: .leading, spacing: 8) {
                    Text(LocalizedStringKey(banner.title)).font(NoahFont.headline)
                    Text(LocalizedStringKey(banner.message)).font(NoahFont.footnote)
                    if banner.retry {
                        Button("Retry") { Task { await home.retryBackup() } }
                            .disabled(home.isRetryingBackup)
                    }
                }
            }
        }
        if let round = home.activeRounds.first {
            Button { showRounds = true } label: {
                NoahCard {
                    VStack(alignment: .leading, spacing: 8) {
                        Text(LocalizedStringKey(home.activeRounds.count == 1 ? "Round in progress" : "Rounds in progress")).font(NoahFont.headline)
                        Text(home.activeRounds.count == 1 ? L10n.text("Round #{id} {status}", round.roundID, round.statusLabel, locale: locale.identifier) : L10n.text("{count} rounds pending", home.activeRounds.count, locale: locale.identifier))
                        Text("View").foregroundStyle(NoahColor.brand)
                    }
                }
            }
            .buttonStyle(.plain)
            .sheet(isPresented: $showRounds) {
                NavigationStack {
                    List {
                        ForEach(home.activeRounds) { round in
                            Section(L10n.text("Round #{_gt_value_2}", round.roundID, locale: locale.identifier)) {
                                LabeledContent("Status") { Text(LocalizedStringKey(round.statusLabel)) }
                                LabeledContent("Final") { Text(LocalizedStringKey(round.isFinal ? "Yes" : "No")) }
                                LabeledContent("Successful") { Text(LocalizedStringKey(round.isSuccess ? "Yes" : "No")) }
                                if let txid = round.fundingTxid { RoundTransactionRow(label: "Funding tx", txid: txid, copyable: true) }
                                ForEach(Array(round.unsignedFundingTxids.enumerated()), id: \.offset) { index, txid in
                                    RoundTransactionRow(label: L10n.text("Unsigned tx {value1}", index + 1, locale: locale.identifier), txid: txid, copyable: false)
                                }
                                if let error = round.error { LabeledContent("Error", value: error) }
                            }
                        }
                    }
                    .navigationTitle("Pending Rounds")
                    .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Close") { showRounds = false } } }
                }
            }
        }
        if let completion = home.autoBoard {
            NoahCard {
                VStack(alignment: .leading, spacing: 8) {
                    Text("Boarding completed").font(NoahFont.headline)
                    Text(L10n.text("{amount} available in Ark", BitcoinAmount.format(sats: Int64(clamping: completion.netSats), unit: .sats, locale: locale)))
                        .font(NoahFont.footnote)
                }
            }
        }
        if let message = home.backgroundMessage {
            NoahCard { ProgressView(LocalizedStringKey(message)).font(NoahFont.footnote).frame(maxWidth: .infinity) }
        }
    }
}

private struct RoundTransactionRow: View {
    @Environment(\.locale) private var locale

    @Environment(AppModel.self) private var app
    let label: String
    let txid: String
    let copyable: Bool
    @State private var copied = false

    var body: some View {
        LabeledContent(label) {
            HStack {
                Text(txid).lineLimit(1).truncationMode(.middle)
                if copyable {
                    Button(copied ? "Copied" : "Copy", systemImage: copied ? "checkmark" : "doc.on.doc") {
                        UIPasteboard.general.string = txid
                        copied = true
                    }
                    .labelStyle(.iconOnly).accessibilityLabel("Copy \(label)")
                    .task(id: copied) {
                        guard copied else { return }
                        try? await Task.sleep(for: .seconds(1))
                        copied = false
                    }
                }
                if let url = URL(string: app.variant == .mainnet ? "https://mempool.space/tx/" : "https://mempool.space/signet/tx/")?.appendingPathComponent(txid) {
                    Link(destination: url) { Image(systemName: "arrow.up.right.square") }
                        .accessibilityLabel(L10n.text("Open {value1} in browser", label, locale: locale.identifier))
                }
            }
        }
    }
}
