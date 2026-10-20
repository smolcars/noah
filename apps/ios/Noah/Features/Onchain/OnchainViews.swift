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
    @State private var discardID: UUID?
    var body: some View {
        if model.loading || model.busy {
            ProgressView().frame(maxWidth: .infinity).accessibilityLabel("Updating wallet")
        }
        ForEach(model.attempts) { attempt in
            Section(
                attempt.overrideAudit != nil ? "Overridden attempt"
                    : attempt.receipt == nil ? "Check operation status" : "Submitted"
            ) {
                Text(attempt.quote.action.title).font(NoahFont.headline)
                OnchainAmountRow(title: "Reviewed amount", sats: attempt.quote.amount)
                Text(attempt.quote.createdAt, style: .date).font(NoahFont.footnote)
                Text(attempt.receipt?.message ?? OnchainFailure.uncertain.errorDescription!).textSelection(.enabled)
                if let audit = attempt.overrideAudit {
                    LabeledContent("Repeat protection overridden") {
                        Text(audit.overriddenAt, format: .dateTime)
                    }
                    DisclosureGroup("Override confirmation") {
                        Text(audit.statusMessage)
                        Text(audit.title)
                        Text(audit.warning)
                        Text(audit.confirmation)
                    }
                }
                if let fee = attempt.receipt?.onchainFee {
                    OnchainAmountRow(title: "Actual onchain fee", sats: fee)
                }
                if let txid = attempt.receipt?.txid {
                    ExitTransactionRow(title: "Transaction ID", txid: txid)
                    Button("Copy transaction ID") { UIPasteboard.general.string = txid }
                }
                if attempt.receipt != nil {
                    Button("Done") { Task { await model.acknowledge(attempt.id) } }.disabled(model.busy)
                } else {
                    Text("Noah checks again automatically as your wallet syncs.")
                    Button("Check status") { Task { await model.checkStatus(attempt.id) } }.disabled(model.busy)
                    if attempt.overrideAudit == nil {
                        Button("Override repeat protection…", role: .destructive) { discardID = attempt.id }
                            .disabled(model.busy)
                    }
                }
            }
            .alert(OnchainOperationJournal.OverrideAudit.titleText, isPresented: Binding(
                get: { discardID == attempt.id }, set: { if !$0 { discardID = nil } })) {
                Button("Keep attempt", role: .cancel) { discardID = nil }
                Button(OnchainOperationJournal.OverrideAudit.confirmationText, role: .destructive) {
                    discardID = nil
                    Task { await model.discardUncertain(attempt.id) }
                }
            } message: {
                Text(OnchainOperationJournal.OverrideAudit.warningText)
            }
        }
        if let error = model.error { Section { Text(error).foregroundStyle(NoahColor.destructive) } }
        if let message = model.message { Section { Text(message) } }
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
                    OnchainAmountRow(
                        title: quote.action == .board ? "Estimated boarding amount" : "Amount selected",
                        sats: quote.amount)
                    if !quote.ids.isEmpty { LabeledContent("VTXOs selected", value: "\(quote.ids.count)") }
                    if quote.action != .cancel {
                        OnchainAmountRow(
                            title: quote.action == .board
                                ? "Estimated Ark boarding fee"
                                : quote.action == .refresh ? "Refresh fee" : "Estimated claim fee", sats: quote.fee)
                        if quote.action == .board || quote.action == .startExit || quote.action == .progress {
                            OnchainAmountRow(
                                title: quote.action == .board
                                    ? "Estimated onchain fee" : "Estimated broadcast fees", sats: quote.chainFee)
                        }
                        OnchainAmountRow(
                            title: quote.action == .board ? "Estimated Ark balance receives" : "Amount after fee",
                            sats: quote.net)
                    }
                    if let destination = quote.destination {
                        LabeledContent("Destination") { Text(destination).textSelection(.enabled) }
                    }
                    if let warning = quote.externalDestinationWarning {
                        Text(warning).foregroundStyle(NoahColor.destructive)
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
                    Button(
                        role: quote.action == .startExit || quote.action == .progress || quote.action == .claim
                            ? .destructive : nil
                    ) {
                        confirm()
                    } label: {
                        Text(quote.action.title)
                    }
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
            "Move onchain bitcoin into Ark. Fees and received amounts are estimates. The final fee may be higher if more coins are needed. Funds become spendable after the board completes."
                + (quote.boardAll == true ? " MAX boards all available onchain funds, including spendable pending funds. The final boarding amount may differ from this estimate." : "")
        case .refresh: "This refreshes the selected VTXOs in a delegated Ark round."
        case .startExit:
            "Once started, Noah progresses the exit automatically in the background, broadcasting and fee-bumping exit transactions and paying fees from your onchain balance. The fees shown are estimates and may change. Only the claim fee is deducted from the recovered value. Cancellation is possible only until the final exit transaction is broadcast."
        case .progress:
            "This may broadcast or fee-bump Bitcoin transactions required by the emergency exit process. Fees are paid from your onchain balance. Cancellation is possible only before the final exit transaction is broadcast."
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
    @Environment(AppModel.self) private var app
    let model: OnchainModel
    func body(content: Content) -> some View {
        content
            .disabled(app.isWalletSuspended)
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
            .onChange(of: app.isWalletSuspended) { _, suspended in
                if suspended { model.invalidate() }
            }
            .onDisappear { model.invalidate() }
            .sheet(item: Binding(get: { model.quote }, set: { if $0 == nil { model.invalidate() } })) {
                quote in
                OnchainReviewSheet(quote: quote) { Task { await model.confirm() } }
            }
    }
}
