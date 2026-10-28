import SwiftUI

/// Today's Pending Rounds sheet, opened from the "Round in progress" status line.
struct PendingRoundsView: View {
    let rounds: [HomeRound]
    let onClose: () -> Void
    @Environment(\.locale) private var locale

    var body: some View {
        NavigationStack {
            List {
                ForEach(rounds) { round in
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
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Close", action: onClose) } }
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
                if let url = URL(string: app.variant == .mainnet ? "https://mempool.space/tx/" : "https://mempool.space/signet/tx/")?
                    .appendingPathComponent(txid) {
                    Link(destination: url) { Image(systemName: "arrow.up.right.square") }
                        .accessibilityLabel(L10n.text("Open {value1} in browser", label, locale: locale.identifier))
                }
            }
        }
    }
}
