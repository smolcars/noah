import SwiftUI

struct TransactionDetailView: View {
    @Environment(\.locale) private var locale
    @Environment(AppModel.self) private var app
    let transaction: HistoryTransaction
    let onRepeatPayment: (RepeatPayment) -> Void
    let onClose: () -> Void
    @State private var showTechnicalDetails = false

    var body: some View {
        ScrollView {
            VStack(spacing: 24) {
                HStack {
                    Text("Payment details").font(NoahFont.headline)
                    Spacer()
                    Button("Close payment details", systemImage: "xmark", action: onClose)
                        .labelStyle(.iconOnly).frame(minWidth: 44, minHeight: 44)
                        .glassButtonStyle()
                }
                VStack(spacing: 12) {
                    Image(systemName: transaction.symbol).font(.largeTitle).foregroundStyle(transaction.amountColor)
                    Text(receiptAction).font(NoahFont.callout).foregroundStyle(NoahColor.mutedForeground)
                    Text(BitcoinAmount.format(sats: transaction.amount, unit: app.amountUnit, locale: locale))
                        .font(NoahFont.inter(36, .bold, relativeTo: .largeTitle))
                        .monospacedDigit().minimumScaleFactor(0.5).lineLimit(1)
                    if let price = transaction.btcPrice {
                        Text(FiatCurrency.display(sats: transaction.amount, btcPrice: price, currency: app.preferredCurrency, locale: locale))
                            .foregroundStyle(NoahColor.mutedForeground)
                    } else {
                        Text("Historical value unavailable").font(NoahFont.footnote).foregroundStyle(NoahColor.mutedForeground)
                    }
                    Text(LocalizedStringKey(transaction.receiptStatus)).font(NoahFont.callout)
                    if let destination = transaction.destination, !destination.isEmpty {
                        Text(destination).font(NoahFont.callout).multilineTextAlignment(.center)
                            .lineLimit(2).truncationMode(.middle).textSelection(.enabled)
                        Button("Copy destination", systemImage: "doc.on.doc") { copy(destination) }
                            .glassButtonStyle()
                    }
                    if transaction.canRepeat, let details = transaction.repeatPayment {
                        Button("Repeat payment", systemImage: "arrow.clockwise") { onRepeatPayment(details) }
                            .glassButtonStyle(prominent: true)
                            .accessibilityIdentifier("repeat-payment-button")
                    }
                }
                VStack(spacing: 0) {
                    detail("Date", transaction.formattedDate)
                    detail("Route", transaction.type == "Lnurl" ? "Lightning address" : transaction.displayLabel)
                    if let repeatPayment = transaction.repeatPayment {
                        if repeatPayment.amountMode == "FIAT", let currency = repeatPayment.fiatCurrency,
                           let amount = Double(repeatPayment.amountInput) {
                            detail("Entered as", FiatCurrency.format(amount, currency: currency))
                        }
                        if !repeatPayment.comment.isEmpty { detail("Message", repeatPayment.comment) }
                    }
                    amountDetail("Ark fee", transaction.offchainFeeSat)
                    if transaction.hasOnchainFee == true { amountDetail("Onchain fee", transaction.onchainFeeSat) }
                }
                DisclosureGroup("Technical details", isExpanded: $showTechnicalDetails) {
                    VStack(spacing: 0) {
                        detail("Payment ID", transaction.id, copyable: true)
                        if let txid = transaction.txid { detail("Transaction ID", txid, copyable: true) }
                        if let url = transaction.explorerURL(variant: app.variant) {
                            Link(destination: url) { Label("View in block explorer", systemImage: "arrow.up.right.square") }
                                .frame(maxWidth: .infinity, alignment: .leading).padding(.vertical, 12)
                        }
                        if let received = transaction.receivedOn, received.count == 1, let first = received.first, !first.destination.isEmpty {
                            detail("Invoice", first.destination, copyable: true)
                        }
                        if transaction.source == "onchain-wallet" || transaction.balanceChangeSat != nil {
                            detail("Chain status", transaction.hasConfirmation == true ? "Confirmed" : "Unconfirmed")
                            amountDetail("Balance change", transaction.balanceChangeSat)
                            if let height = transaction.confirmationHeight { detail("Block height", String(height)) }
                            if let hash = transaction.confirmationHash { detail("Block hash", hash, copyable: true) }
                            if let hex = transaction.txHex { detail("Raw transaction", hex, copyable: true) }
                        }
                        if let kind = transaction.movementKindLabel { detail("Movement type", kind) }
                        if let id = transaction.movementId { detail("Movement ID", String(id), copyable: true) }
                        if let name = transaction.subsystemName { detail("Subsystem", "\(name) (\(transaction.subsystemKind ?? ""))") }
                        amountDetail("Intended balance change", transaction.intendedBalanceSat)
                        amountDetail("Effective balance change", transaction.effectiveBalanceSat)
                        if let anchor = transaction.chainAnchor { detail("Chain anchor", anchor, copyable: true) }
                        if let metadata = transaction.metadataJson, !metadata.isEmpty { detail("Metadata", metadata, copyable: true) }
                        destinations("Sent to", transaction.sentTo)
                        destinations("Received on", transaction.receivedOn)
                        vtxos("Input VTXOs", transaction.inputVtxos)
                        vtxos("Output VTXOs", transaction.outputVtxos)
                        vtxos("Exited VTXOs", transaction.exitedVtxos)
                    }
                }
                .font(NoahFont.callout)
            }
            .padding(24)
            .frame(maxWidth: 680)
            .frame(maxWidth: .infinity)
        }
        .background(NoahColor.background.ignoresSafeArea())
        .foregroundStyle(NoahColor.foreground)
    }

    private var receiptAction: LocalizedStringKey {
        switch transaction.accounting.direction {
        case "Transfer": "Transferred"
        case "None": "Canceled"
        case "Outgoing": "Sent"
        default: "Received"
        }
    }

    private func detail(_ label: String, _ value: String, copyable: Bool = false) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(LocalizedStringKey(label)).font(NoahFont.footnote).foregroundStyle(NoahColor.mutedForeground)
                Spacer()
                if copyable {
                    Button { copy(value) } label: { Image(systemName: "doc.on.doc").frame(minWidth: 44, minHeight: 44) }
                        .accessibilityLabel(Text(L10n.text("Copy {label}", L10n.text(label))))
                }
            }
            Text(value).font(NoahFont.callout).textSelection(.enabled).fixedSize(horizontal: false, vertical: true)
            Divider().padding(.top, 8)
        }
        .padding(.vertical, 8)
    }

    @ViewBuilder private func amountDetail(_ label: String, _ amount: Int64?) -> some View {
        if let amount { detail(label, BitcoinAmount.format(sats: amount, unit: app.amountUnit, locale: locale)) }
    }
    @ViewBuilder private func destinations(_ label: String, _ values: [HistoryTransaction.Destination]?) -> some View {
        if let values, !values.isEmpty {
            detail(label, values.map { "\($0.destination) · \(BitcoinAmount.format(sats: $0.amount_sat, unit: app.amountUnit, locale: locale))" }.joined(separator: "\n"), copyable: true)
        }
    }
    @ViewBuilder private func vtxos(_ label: String, _ values: [String]?) -> some View {
        if let values, !values.isEmpty { detail(label, values.joined(separator: "\n"), copyable: true) }
    }
    private func copy(_ value: String) {
        UIPasteboard.general.setItems([[UIPasteboard.typeAutomatic: value]], options: [.localOnly: true, .expirationDate: Date().addingTimeInterval(120)])
    }
}
