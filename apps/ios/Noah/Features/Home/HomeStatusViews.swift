import SwiftUI

struct HomeStatusBanners: View {
    let home: HomeModel
    @Environment(\.locale) private var locale
    @State private var showRounds = false

    var body: some View {
        TimelineView(.periodic(from: .now, by: 1)) { context in
            if let banner = HomeBanner.backup(home.backup, now: context.date.timeIntervalSince1970 * 1000, locale: locale) {
                NoahCard {
                    VStack(alignment: .leading, spacing: 8) {
                        Text(banner.title).font(NoahFont.headline)
                        Text(banner.message).font(NoahFont.footnote)
                        if banner.retry {
                            Button("Retry") { Task { await home.retryBackup() } }
                                .disabled(home.isRetryingBackup)
                        }
                    }
                }
            }
        }
        if let round = home.activeRounds.first {
            Button { showRounds = true } label: {
                NoahCard {
                    VStack(alignment: .leading, spacing: 8) {
                        Text(home.activeRounds.count == 1 ? "Round in progress" : "Rounds in progress").font(NoahFont.headline)
                        Text(home.activeRounds.count == 1 ? "Round #\(round.roundID) \(round.statusLabel)" : "\(home.activeRounds.count) rounds pending")
                        Text("View").foregroundStyle(NoahColor.brand)
                    }
                }
            }
            .buttonStyle(.plain)
            .sheet(isPresented: $showRounds) {
                NavigationStack {
                    List {
                        ForEach(home.activeRounds) { round in
                            Section("Round #\(round.roundID)") {
                                LabeledContent("Status", value: round.statusLabel)
                                LabeledContent("Final", value: round.isFinal ? "Yes" : "No")
                                LabeledContent("Successful", value: round.isSuccess ? "Yes" : "No")
                                if let txid = round.fundingTxid { RoundTransactionRow(label: "Funding tx", txid: txid, copyable: true) }
                                ForEach(Array(round.unsignedFundingTxids.enumerated()), id: \.offset) { index, txid in
                                    RoundTransactionRow(label: "Unsigned tx \(index + 1)", txid: txid, copyable: false)
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
                    Text("\(BitcoinAmount.format(sats: Int64(clamping: completion.netSats), unit: .sats, locale: locale)) available in Ark")
                        .font(NoahFont.footnote)
                }
            }
        }
        if home.backgroundRunning { ProgressView("Background task in progress...") }
    }
}

private struct RoundTransactionRow: View {
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
                        .accessibilityLabel("Open \(label) in browser")
                }
            }
        }
    }
}
