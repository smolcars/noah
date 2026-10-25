import SwiftUI

struct OnchainAmountRow: View {
    @Environment(\.locale) private var locale
    @Environment(AppModel.self) private var app
    let title: String
    let sats: UInt64
    var body: some View {
        LabeledContent(
            title, value: BitcoinAmount.format(sats: Int64(clamping: sats), unit: app.amountUnit, locale: locale)
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
                Text(LocalizedStringKey(attempt.quote.action.title)).font(NoahFont.headline)
                OnchainAmountRow(title: "Reviewed amount", sats: attempt.quote.amount)
                Text(attempt.quote.createdAt, style: .date).font(NoahFont.footnote)
                Text(LocalizedStringKey(attempt.receipt?.message ?? OnchainFailure.uncertain.errorDescription!)).textSelection(.enabled)
                if attempt.receipt?.broadcastRetryPending == true {
                    Text("Boarding submitted, but delivery is unconfirmed. Noah will keep retrying the broadcast. Do not board again.")
                }
                if let audit = attempt.overrideAudit {
                    LabeledContent("Repeat protection overridden") {
                        Text(audit.overriddenAt, format: .dateTime)
                    }
                    DisclosureGroup("Override confirmation") {
                        Text(LocalizedStringKey(audit.statusMessage))
                        Text(LocalizedStringKey(audit.title))
                        Text(LocalizedStringKey(audit.warning))
                        Text(LocalizedStringKey(audit.confirmation))
                    }
                }
                if let fee = attempt.receipt?.arkFee { OnchainAmountRow(title: "Ark fee", sats: fee) }
                if let fee = attempt.receipt?.onchainFee {
                    OnchainAmountRow(title: "Actual onchain fee", sats: fee)
                }
                if attempt.quote.action == .board, let receipt = attempt.receipt {
                    let amounts = receipt.boardAmounts(quote: attempt.quote)
                    OnchainAmountRow(title: "Ark receives", sats: amounts.arkReceives)
                    OnchainAmountRow(title: "Total leaving onchain wallet", sats: amounts.totalLeaving)
                }
                if let status = attempt.receipt?.chainStatus { Text(LocalizedStringKey(status.message)) }
                if let error = attempt.chainStatusError { Text(LocalizedStringKey(error)).foregroundStyle(NoahColor.destructive) }
                // An uncertain board still has its exact journaled funding txid (Android parity).
                if let txid = attempt.receipt?.txid ?? attempt.txid {
                    ExitTransactionRow(title: "Transaction ID", txid: txid)
                    Button("Copy transaction ID") { UIPasteboard.general.string = txid }
                    if attempt.receipt != nil {
                        Button("Refresh network status") { Task { await model.checkStatus(attempt.id) } }.disabled(model.busy)
                    }
                }
                if attempt.receipt?.broadcastRetryPending == true {
                    Button("Check status") { Task { await model.checkStatus(attempt.id) } }.disabled(model.busy)
                } else if attempt.receipt != nil {
                    Button("Done") { Task { await model.acknowledge(attempt.id) } }.disabled(model.busy)
                } else {
                    Text("Noah checks again automatically as your wallet syncs.")
                    Button("Check status") { Task { await model.checkStatus(attempt.id) } }.disabled(model.busy)
                    // An uncertain board may be partly recorded: only Check status can release it.
                    if attempt.overrideAudit == nil, attempt.uncertainBoard != true {
                        Button("Override repeat protection…", role: .destructive) { discardID = attempt.id }
                            .disabled(model.busy)
                    }
                }
            }
            .alert(OnchainOperationJournal.OverrideAudit.titleText, isPresented: Binding(
                get: { discardID == attempt.id }, set: { if !$0 { discardID = nil } })) {
                Button("Keep attempt", role: .cancel) { discardID = nil }
                Button(LocalizedStringKey(OnchainOperationJournal.OverrideAudit.confirmationText), role: .destructive) {
                    discardID = nil
                    Task { await model.discardUncertain(attempt.id) }
                }
            } message: {
                Text(LocalizedStringKey(OnchainOperationJournal.OverrideAudit.warningText))
            }
        }
        if model.estimateFallback != nil {
            Section {
                Text("Fee estimate unavailable")
                Button("Continue without estimate") { Task { await model.continueWithoutEstimate() } }
                    .disabled(model.busy)
            }
        }
        if let error = model.error { Section { Text(LocalizedStringKey(error)).foregroundStyle(NoahColor.destructive) } }
        if let message = model.message { Section { Text(LocalizedStringKey(message)) } }
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
                        title: quote.action == .board ? "Amount boarded" : "Amount selected",
                        sats: quote.amount)
                    if !quote.ids.isEmpty { LabeledContent("VTXOs selected", value: "\(quote.ids.count)") }
                    if quote.action != .cancel, let fee = quote.fee, let chainFee = quote.chainFee {
                        OnchainAmountRow(
                            title: quote.action == .board
                                ? "Ark fee"
                                : quote.action == .refresh ? "Refresh fee" : "Estimated claim fee", sats: fee)
                        if quote.action == .board || quote.action == .startExit || quote.action == .progress {
                            OnchainAmountRow(
                                title: quote.action == .board
                                    ? "Onchain fee" : "Estimated broadcast fees", sats: chainFee)
                        }
                        OnchainAmountRow(
                            title: quote.action == .board ? "Ark receives" : "Amount after fee",
                            sats: quote.net)
                    }
                    if let prepared = quote.prepared {
                        OnchainAmountRow(title: "Total leaving onchain wallet", sats: quote.amount + quote.chainFee!)
                        LabeledContent("Signed transaction size", value: "\(prepared.vsize) vB")
                        LabeledContent("Funding inputs", value: "\(prepared.inputCount)")
                    }
                    if let destination = quote.destination {
                        LabeledContent("Destination") { Text(destination).textSelection(.enabled) }
                    }
                    if let warning = quote.externalDestinationWarning {
                        Text(LocalizedStringKey(warning)).foregroundStyle(NoahColor.destructive)
                    }
                    if quote.automatic {
                        OnchainAmountRow(
                            title: "Onchain reserve left",
                            sats: quote.confirmed - quote.amount - quote.chainFee!)
                    }
                }
                Section {
                    if quote.feesUnknown {
                        Text("Fee estimate unavailable. Fees and amount to receive are unknown. Continue without an estimate only if you accept this uncertainty.")
                            .foregroundStyle(NoahColor.destructive)
                        if quote.action == .startExit {
                            Text("Broadcast funding status is unknown; confirmed onchain funds are required.")
                        }
                    }
                    Text(LocalizedStringKey(explanation))
                    if quote.action == .startExit || quote.action == .progress || quote.action == .claim {
                        if let fee = quote.fee, quote.chainFee != nil, let warning = ExitRules.warning(
                            amount: quote.amount, claimFee: fee, totalFee: quote.totalFee,
                            claimOnly: quote.action == .claim)
                        {
                            Text(LocalizedStringKey(warning)).foregroundStyle(NoahColor.destructive)
                        }
                        if !quote.feesUnknown && !quote.fundable {
                            Text(
                                "Additional confirmed onchain funds required. You are starting tracking anyway; fund the wallet before progressing."
                            )
                        }
                    }
                    Button(
                        role: quote.action == .startExit || quote.action == .progress || quote.action == .claim
                            ? .destructive : nil
                    ) {
                        confirm()
                    } label: {
                        Text(LocalizedStringKey(quote.feesUnknown ? (quote.action == .claim ? "Broadcast without estimate" : "Start Selected Exit") : quote.action.title))
                    }
                    .accessibilityIdentifier("onchain-confirm")
                }
            }
            .settingsStyle(LocalizedStringKey(quote.feesUnknown ? "Continue without estimate?" : quote.action.title))
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(LocalizedStringKey(cancelTitle)) {
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
            "Move onchain bitcoin into Ark using this exact signed funding transaction. The boarding amount includes the Ark fee; the onchain fee is paid in addition. Funds become spendable after the board completes. This review expires after two minutes."
                + (quote.boardAll == true ? " MAX uses confirmed funds only and deducts the onchain fee from that balance." : "")
        case .refresh: "This refreshes the selected VTXOs in a delegated Ark round."
        case .startExit:
            quote.feesUnknown ? "Once started, Noah progresses the exit on its own, including in the background when the system lets it, broadcasting and fee-bumping exit transactions and paying fees from your onchain balance. Only the claim fee is deducted from the recovered value. Cancellation is possible only until the final exit transaction is broadcast." : "Once started, Noah progresses the exit on its own, including in the background when the system lets it, broadcasting and fee-bumping exit transactions and paying fees from your onchain balance. The fees shown are estimates and may change. Only the claim fee is deducted from the recovered value. Cancellation is possible only until the final exit transaction is broadcast."
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
    /// Settings' pages present their own review (Renew, Move to Ark) in a pane-aware sheet.
    var presentsReview = true
    /// More to reload when pulled (a VTXO read by ID).
    var refresh: (() async -> Void)?
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
            // A renew, an exit step or arriving funds show at once, not at the next minute (keyed as History's observer).
            .task(id: app.summary == nil ? nil : app.walletSessionRevision) {
                guard app.summary != nil else { return }
                await model.observeChanges { phase == .active && !model.busy && model.quote == nil }
            }
            .refreshable {
                model.invalidate()
                await model.load()
                await refresh?()
            }
            .onChange(of: phase) { _, new in
                model.invalidate()
                model.sceneChanged()
                // Back in the app: read again once the foreground sync lets go of the wallet (the read waits for it).
                if new == .active, !model.busy { Task { await model.load() } }
            }
            .onChange(of: app.isWalletSuspended) { _, suspended in
                if suspended { model.invalidate() }
            }
            .onChange(of: app.summary?.fingerprint) { _, _ in model.invalidate() }
            .onDisappear { model.invalidate() }
            .sheet(item: Binding(get: { presentsReview ? model.quote : nil }, set: { if $0 == nil { model.invalidate() } })) {
                quote in
                OnchainReviewSheet(quote: quote) { Task { await model.confirm() } }
            }
    }
}
