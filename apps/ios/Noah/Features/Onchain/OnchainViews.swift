import SwiftUI

struct OnchainAmountRow: View {
    @Environment(AppModel.self) private var app
    let title: String
    let sats: UInt64
    var body: some View {
        LabeledContent(
            title, value: BitcoinAmount.format(sats: Int64(clamping: sats), unit: app.amountUnit)
        )
        .monospacedDigit()
    }
}

struct OnchainStatusSection: View {
    let model: OnchainModel
    var body: some View {
        if model.loading || model.busy {
            ProgressView().frame(maxWidth: .infinity).accessibilityLabel("Updating wallet")
        }
        if let attempt = model.attempt {
            Section(attempt.receipt == nil ? "Check operation status" : "Submitted") {
                Text(attempt.quote.action.title).font(NoahFont.headline)
                OnchainAmountRow(title: "Reviewed amount", sats: attempt.quote.amount)
                Text(attempt.quote.createdAt, style: .date).font(NoahFont.footnote)
                Text(attempt.receipt?.message ?? OnchainFailure.uncertain.errorDescription!).textSelection(.enabled)
                if let txid = attempt.receipt?.txid {
                    ExitTransactionRow(title: "Transaction ID", txid: txid)
                    Button("Copy transaction ID") { UIPasteboard.general.string = txid }
                }
                if attempt.receipt != nil {
                    Button("Done") { Task { await model.acknowledge() } }.disabled(model.busy)
                }
            }
        }
        if let error = model.error { Section { Text(error).foregroundStyle(NoahColor.destructive) } }
        if let message = model.message, model.attempt == nil { Section { Text(message) } }
    }
}

struct OnchainReviewSheet: View {
    let quote: OnchainQuote
    let confirm: () -> Void
    var cancelTitle = "Cancel"
    var onCancel: (() -> Void)? = nil
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        NavigationStack {
            List {
                Section {
                    OnchainAmountRow(title: "Amount selected", sats: quote.amount)
                    if !quote.ids.isEmpty { LabeledContent("VTXOs selected", value: "\(quote.ids.count)") }
                    if quote.action != .cancel {
                        OnchainAmountRow(
                            title: quote.action == .board
                                ? "Ark boarding fee"
                                : quote.action == .refresh ? "Refresh fee" : "Estimated claim fee", sats: quote.fee)
                        if quote.action == .board || quote.action == .startExit || quote.action == .progress {
                            OnchainAmountRow(
                                title: quote.action == .board
                                    ? "Estimated onchain fee" : "Estimated broadcast fees", sats: quote.chainFee)
                        }
                        OnchainAmountRow(
                            title: quote.action == .board ? "Ark balance receives" : "Amount after fee",
                            sats: quote.net)
                    }
                    if let destination = quote.destination {
                        LabeledContent("Destination") { Text(destination).textSelection(.enabled) }
                    }
                    if quote.automatic {
                        OnchainAmountRow(
                            title: "Estimated onchain reserve",
                            sats: quote.confirmed - quote.amount - quote.chainFee)
                    }
                }
                Section {
                    Text(explanation)
                    if quote.action == .startExit || quote.action == .progress || quote.action == .claim {
                        if let warning = ExitRules.warning(
                            amount: quote.amount, claimFee: quote.fee, totalFee: quote.totalFee,
                            claimOnly: quote.action == .claim)
                        {
                            Text(warning).foregroundStyle(NoahColor.destructive)
                        }
                        if !quote.fundable {
                            Text(
                                "Additional confirmed onchain funds would be required to progress this exit."
                            )
                        }
                    }
                    if quote.action == .startExit || quote.action == .progress {
                        Text(OnchainFailure.exitProgressUnavailable.errorDescription!).foregroundStyle(
                            NoahColor.destructive)
                    }
                    Button(
                        role: quote.action == .startExit || quote.action == .progress || quote.action == .claim
                            ? .destructive : nil
                    ) {
                        confirm()
                    } label: {
                        Text(quote.action.title)
                    }
                    .disabled(quote.action == .startExit || quote.action == .progress)
                    .accessibilityIdentifier("onchain-confirm")
                }
            }
            .settingsStyle(LocalizedStringKey(quote.action.title))
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(cancelTitle) {
                        onCancel?()
                        dismiss()
                    }
                }
            }
        }
        .presentationDetents([.large])
    }
    private var explanation: String {
        switch quote.action {
        case .board:
            "Move onchain bitcoin into Ark. The onchain fee is an estimate and the final fee may differ. Funds become spendable after the board completes."
        case .refresh: "This refreshes the selected VTXOs in a delegated Ark round."
        case .startExit:
            "Broadcast fees are paid separately from confirmed onchain funds; only the claim fee is deducted from the recovered value. This is a read-only estimate."
        case .progress:
            "This is a read-only estimate for all pending exits. Cancellation is possible only before the final exit transaction is broadcast."
        case .claim:
            "This broadcasts the claim transaction. Earlier broadcast fees are not deducted again."
        case .cancel:
            "Cancel this exit before its final transaction is broadcast. The VTXO remains spendable. The wallet will recheck whether cancellation is still possible."
        }
    }
}

/// Shared review presentation also invalidates quotes on backgrounding or navigation.
struct OnchainScreen: ViewModifier {
    @Environment(\.scenePhase) private var phase
    let model: OnchainModel
    func body(content: Content) -> some View {
        content
            .task {
                await model.load()
                while !Task.isCancelled {
                    do { try await Task.sleep(for: .seconds(60)) } catch { return }
                    if phase == .active && !model.busy && model.quote == nil { await model.load() }
                }
            }
            .refreshable {
                model.invalidate()
                await model.load()
            }
            .onChange(of: phase) { _, _ in model.invalidate() }
            .onDisappear { model.invalidate() }
            .sheet(item: Binding(get: { model.quote }, set: { if $0 == nil { model.invalidate() } })) {
                quote in
                OnchainReviewSheet(quote: quote) { Task { await model.confirm() } }
            }
    }
}
