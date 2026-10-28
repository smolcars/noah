import SwiftUI

/// Sending and its result (send-receive.md): "Sending ₿X" to them above the motion stage
/// and its phase line; when the real outcome arrives the title cross-fades to the result,
/// with its rows and Done. Today's edge states (already paid, may have been sent, status
/// unavailable, awaiting broadcast) keep their meaning and wording.
struct SendOutcomeView: View {
    @Environment(\.locale) private var locale
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.sendReceiveFixture) private var fixture
    @Bindable var controller: SendPaymentController
    var btcPrice: Double?
    var fiatCurrency = "USD"
    let done: () -> Void
    var clock: SendMotionClock?
    var unit: BitcoinAmountUnit = .bip177
    var recipient: SendRecipientLabel?
    /// Nil measures its own window (a host without Send around it).
    var wide: Bool? // swiftlint:disable:this discouraged_optional_boolean - nil measures its own window
    var sideInsets: (leading: CGFloat, trailing: CGFloat) = (0, 0)
    /// Done while Lightning is still paying, or Close on an unresolved result: leave, keeping
    /// the attempt and the send lock. Required, so no host can leave it doing nothing.
    let onLeave: () -> Void
    /// The payment just confirmed, until the controller has saved its attempt.
    var reviewed: SendPayment?
    /// The chain tip, read by Send with each receipt check.
    var chainTip: UInt32?
    @State private var ownClock = SendMotionClock()
    @State private var history: [HistoryTransaction] = []
    @State private var refund: SendRefund?
    @State private var completedAt = Date()
    @State private var confirmOverride = false
    @State private var resultShown = false
    @State private var slow = false
    @State private var window = OnboardingLayout(size: .zero, insets: EdgeInsets())
    @State private var haptics = OnboardingHaptics()

    private var motion: SendMotionClock { clock ?? ownClock }
    private var tip: UInt32? { fixture?.tip ?? chainTip }
    private var isWide: Bool { wide ?? window.wide }
    private var attempt: SendPaymentController.Attempt? { controller.attempt }
    private var payment: SendPayment? { attempt?.payment ?? (controller.state == .submitting ? reviewed : nil) }
    private var rail: SendMotion.Rail { payment.map(SendMotion.Rail.init) ?? .ark }
    private var outcome: SendMotion.Outcome {
        SendMotion.outcome(state: controller.state, attempt: attempt, checking: controller.checking, refund: refund.map { $0 == .recorded })
    }
    /// A status check never takes a shown result back to "Sending".
    private var shownOutcome: SendMotion.Outcome { outcome == .inFlight && motion.outcome != .inFlight ? motion.outcome : outcome }
    private var kind: SendResultKind { SendResultKind.of(state: controller.state, outcome: shownOutcome, slow: slow) }
    private var receipt: SendReceipt? {
        switch controller.state {
        case .succeeded(let receipt), .alreadyPaid(let receipt): receipt
        default: attempt?.receipt
        }
    }
    private var confirmedHeight: UInt32? {
        if case .confirmed(let height) = receipt?.chainStatus { return height }
        return nil
    }
    private var requiredConfirmations: Int {
        rail == .onchainSwap ? Int(WalletConfiguration.base(for: .current).roundTxRequiredConfirmations) : 1
    }
    private var label: SendRecipientLabel {
        recipient ?? payment.map { .of(destination: $0.destination, ownDomain: LightningIdentity.domain(for: .current), payment: $0) }
            ?? SendRecipientLabel(name: "", nameIsVerbatim: true, avatar: .lightning)
    }
    private var recipientName: String { label.nameIsVerbatim ? label.name : L10n.text(label.name, locale: locale.identifier) }
    private func format(_ sats: UInt64) -> String { BitcoinAmount.format(sats: Int64(clamping: sats), unit: unit, locale: locale) }
    private var sendingAmount: UInt64 { attempt?.quote?.amount ?? payment?.amount ?? 0 }

    var body: some View {
        Group {
            if isWide {
                // The stage leads; the result and Done trail. Nothing crosses the fold.
                panes
            } else {
                // One column; long results scroll above Done.
                column
            }
        }
        .background(NoahTokens.Colors.background.ignoresSafeArea())
        // Measured even inside Send, which decides wide: short windows size the stage and header.
        .background { Color.clear.onboardingWindow($window) }
        .onboardingHaptics(haptics)
        .onChange(of: outcome, initial: true) { _, next in motion.observe(next) }
        .onChange(of: confirmedHeight, initial: true) { _, height in motion.confirm(height: height) }
        .task(id: controller.state) {
            guard case .succeeded = controller.state else { return }
            let rows: [HistoryTransaction]
            if let fixture { rows = fixture.history } else { rows = (try? await WalletSession.shared.transactionHistory()) ?? [] }
            if !Task.isCancelled { history = rows }
        }
        .task(id: refundKey) { await readRefund() }
        .task(id: resultKey) { await revealResult() }
        .task(id: hapticKey) { await playHaptics() }
        .alert(SendOverrideAudit.titleText, isPresented: $confirmOverride) {
            Button("Keep checking", role: .cancel) {}
            Button(LocalizedStringKey(SendOverrideAudit.confirmationText), role: .destructive) {
                controller.startNewPaymentAfterHistoryCheck(confirmed: true)
                if !controller.locked { done() }
            }
        } message: {
            Text(LocalizedStringKey(controller.overrideWarning))
        }
    }

    private var panes: some View {
        OnboardingPanes(wide: true, sideInsets: wide == nil ? (window.insets.leading, window.insets.trailing) : sideInsets) {
            leading
                .padding(.horizontal, 34)
                .padding(.bottom, 96)
                .frame(maxWidth: OnboardingLayout.paneContentWidth + 68)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
            VStack(alignment: .leading, spacing: 0) {
                Color.clear.frame(height: 66)
                ScrollView { resultSection }.scrollBounceBehavior(.basedOnSize)
                Spacer(minLength: 24)
                actions
            }
            .padding(.leading, 40).padding(.trailing, 34).padding(.vertical, 24)
            .frame(maxWidth: OnboardingLayout.paneContentWidth + 74)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        }
    }

    private var column: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                leading
                resultSection.padding(.top, 18)
            }
            .padding(.horizontal, 20)
            .padding(.bottom, 12)
        }
        .scrollBounceBehavior(.basedOnSize)
        .safeAreaInset(edge: .bottom, spacing: 0) {
            actions.padding(.horizontal, 20).padding(.top, 8).padding(.bottom, 12)
                .background(NoahTokens.Colors.background)
        }
    }

    // MARK: Leading: title, recipient, stage, phase

    private var title: String {
        let language = locale.identifier
        guard resultShown || ![.sent, .refunded, .stillSending].contains(kind) else {
            return L10n.text("Sending {amount}", format(sendingAmount), locale: language)
        }
        switch kind {
        case .sending: return L10n.text("Sending {amount}", format(sendingAmount), locale: language)
        case .stillSending: return L10n.text("Still sending {amount}", format(sendingAmount), locale: language)
        case .sent:
            guard let receipt else { return L10n.text("Payment sent", locale: language) }
            if receipt.amountIsPending == true { return L10n.text("Payment submitted", locale: language) }
            return L10n.text("Sent {amount}", (receipt.amountIsEstimated == true ? "≈ " : "") + format(receipt.amount), locale: language)
        case .refunded: return L10n.text("Payment failed", locale: language)
        case .failed: return L10n.text(refund == .untied ? "Payment failed" : "Payment not sent", locale: language)
        case .alreadyPaid: return L10n.text("Invoice already paid", locale: language)
        case .uncertain: return L10n.text("Payment may have been sent", locale: language)
        case .unavailable: return L10n.text("Payment status unavailable", locale: language)
        case .awaitingBroadcast: return L10n.text("Payment submitted", locale: language)
        }
    }

    private var leading: some View {
        VStack(spacing: 0) {
            // Short windows (the Duo closed) give the result the room.
            Color.clear.frame(height: isWide ? 22 : isShort ? 8 : NoahTokens.Size.hitTarget + 4)
            VStack(alignment: .leading, spacing: 6) {
                Text(verbatim: title)
                    .noahText(SendText.big).foregroundStyle(NoahTokens.Colors.foreground)
                    .lineLimit(isShort ? 1 : 2).minimumScaleFactor(0.5)
                    .contentTransition(.opacity)
                    .animation(NoahTokens.Motion.travel(reduceMotion, NoahTokens.Motion.bouncy), value: title)
                    .accessibilityIdentifier(kind == .sent ? "send-success" : "send-outcome-title")
                if !recipientName.isEmpty {
                    Text(verbatim: L10n.text("to {recipient}", recipientName, locale: locale.identifier))
                        .noahText(NoahTokens.Typography.body).foregroundStyle(NoahTokens.Colors.muted)
                        .lineLimit(1).truncationMode(.middle)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            // Without a saved payment (status unavailable) there's nothing to draw.
            if payment != nil {
                GeometryReader { proxy in
                    SendStageView(rail: rail, clock: motion, recipient: label, tip: tip, nextLabel: L10n.text("next", locale: locale.identifier))
                        .frame(width: proxy.size.width, height: proxy.size.height)
                }
                .frame(height: stageHeight)
                .animation(NoahTokens.Motion.travel(reduceMotion, NoahTokens.Motion.soft), value: stageHeight)
                .padding(.top, isWide ? 40 : 8)
                phaseLine.padding(.top, 4)
            }
        }
    }

    private var isShort: Bool { !isWide && window.size.height > 0 && window.size.height < 760 }

    private var stageHeight: CGFloat {
        if isWide { return 330 }
        let height = window.size.height > 0 ? window.size.height : 852
        // Short windows (the Duo closed) make room for the result once it shows.
        return min(270, max(150, height * (resultShown && height < 760 ? 0.22 : 0.32)))
    }

    /// Redraws at the phase's boundaries only, never on a running clock.
    private var phaseLine: some View {
        let outcomeAt = motion.since(motion.outcomeAt) ?? .infinity, confirmedAt = motion.since(motion.confirmedAt)
        let now = Date()
        let boundaries = motion.submittedAt.map { start in
            SendMotion.phaseBoundaries(rail: rail, outcomeAt: outcomeAt, confirmedAt: confirmedAt)
                .map { start.addingTimeInterval($0 + 0.01) }.filter { $0 > now }.sorted()
        } ?? []
        return TimelineView(.explicit([now] + boundaries)) { timeline in
            let t = motion.elapsed(timeline.date)
            let phase = SendMotion.phase(
                rail: rail,
                outcome: motion.outcome,
                outcomeAt: motion.since(motion.outcomeAt) ?? .infinity,
                at: t,
                confirmedAt: motion.since(motion.confirmedAt))
            let text = phaseText(phase)
            Text(verbatim: text)
                .noahText(NoahTokens.Typography.control)
                .foregroundStyle([.delivered, .paid, .refunded, .confirmed].contains(phase) ? NoahTokens.Colors.foreground : NoahTokens.Colors.muted)
                .multilineTextAlignment(.center)
                .lineLimit(2)
                .frame(maxWidth: .infinity, minHeight: 20)
                .id(text)
                // The new line rises in; the old one gives way at once (prototype `setPhase`).
                .transition(.asymmetric(insertion: reduceMotion ? .opacity : .opacity.combined(with: .offset(y: 6)), removal: .identity))
                .animation(NoahTokens.Motion.travel(reduceMotion), value: text)
                .accessibilityHidden(text.isEmpty)
                .accessibilityIdentifier("send-phase")
        }
    }

    private func phaseText(_ phase: SendMotion.Phase) -> String {
        let language = locale.identifier
        switch phase {
        case .start, .stopped, .drop: return ""
        case .split: return L10n.text("Splitting your coin", locale: language)
        case .sign: return L10n.text("Signing with Ark", locale: language)
        case .deliver: return L10n.text("Delivering", locale: language)
        case .delivered: return L10n.text("Delivered", locale: language)
        case .swap: return L10n.text("Swapping with Ark", locale: language)
        case .waiting:
            return tip.map { L10n.text("Waiting for block {height}", ($0 + 1).formatted(.number.locale(locale)), locale: language) } ?? ""
        case .confirmed: return confirmedText(language)
        default: return lightningPhaseText(phase, language: language)
        }
    }

    /// Lightning's lock, pay and refund steps.
    private func lightningPhaseText(_ phase: SendMotion.Phase, language: String) -> String {
        switch phase {
        case .lock: return L10n.text("Locking {amount}", format(sendingAmount), locale: language)
        case .hold: return L10n.text("Ark holds it while Lightning pays", locale: language)
        case .paying: return L10n.text("Paying over Lightning", locale: language)
        case .stillPaying: return L10n.text("Still paying. This can take a few minutes.", locale: language)
        case .unlock: return L10n.text("Their receipt unlocks it", locale: language)
        case .paid: return L10n.text("Paid", locale: language)
        case .fizzle: return L10n.text("That payment didn’t go through", locale: language)
        case .signBack: return L10n.text("Ark signs it back to you", locale: language)
        case .refunded: return L10n.text("Back in your wallet", locale: language)
        default: return ""
        }
    }

    private func confirmedText(_ language: String) -> String {
        guard let height = motion.confirmedHeight else { return "" }
        let line = L10n.text("Confirmed in block {height}", height.formatted(.number.locale(locale)), locale: language)
        guard requiredConfirmations > 1 else { return line }
        let count = min(SendMotion.confirmations(height: height, tip: tip), requiredConfirmations)
        return line + " · " + L10n.text("{n} of {total}", count, requiredConfirmations, locale: language)
    }

    // MARK: Trailing: the result

    private var resultSection: some View {
        VStack(alignment: .leading, spacing: 0) {
            if resultShown || ![.sent, .refunded, .stillSending, .sending].contains(kind) { result }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .transition(reduceMotion ? .opacity : .opacity.combined(with: .offset(y: 16)))
        .animation(NoahTokens.Motion.travel(reduceMotion), value: resultShown)
    }

    @ViewBuilder private var result: some View {
        if let line { Text(verbatim: line).noahText(NoahTokens.Typography.sub).foregroundStyle(NoahTokens.Colors.muted)
            .fixedSize(horizontal: false, vertical: true).accessibilityIdentifier("send-result-line")
        }
        switch kind {
        case .sending: EmptyView()
        case .stillSending:
            rows {
                SendDetailRow("Via", text: Self.railName(rail), glyph: Self.railGlyph(rail))
                SendDetailRow(
                    "Fee",
                    number: L10n.text("up to {amount}", format(attempt?.existingLightning?.fee ?? attempt?.quote?.fee ?? 0), locale: locale.identifier),
                    last: true)
            }
            existingLightning
        case .sent:
            if let receipt {
                rows {
                    SendDetailRow("Via", text: Self.railName(rail), glyph: Self.railGlyph(rail))
                    feeRow(receipt)
                    if let proof = proof(receipt) {
                        SendDetailRow(source: proof.label, copy: proof.value, shown: Self.short(proof.value),
                                      last: receipt.txid == nil || AppVariant.current == .regtest, id: "send-success-copy-proof", onCopy: copied)
                    }
                }
                explorerLink(receipt)
                if let status = receipt.chainStatus, status == .notFound {
                    fine(L10n.text(status.message, locale: locale.identifier))
                }
            }
        case .refunded:
            rows {
                SendDetailRow("Via", text: Self.railName(rail), glyph: Self.railGlyph(rail))
                SendDetailRow("Fee", text: "None", last: true)
            }
        case .failed: EmptyView()
        case .alreadyPaid:
            if case .alreadyPaid(let receipt) = controller.state {
                Text("No new payment was made for the amount you reviewed.").noahText(NoahTokens.Typography.sub)
                    .foregroundStyle(NoahTokens.Colors.muted).padding(.top, 10)
                if let fee = receipt.fee { rows { SendDetailRow("Fee", number: format(fee), last: true) } }
            }
        case .uncertain: uncertain
        case .unavailable: EmptyView()
        case .awaitingBroadcast:
            if let receipt {
                rows {
                    SendDetailRow("Via", text: Self.railName(rail), glyph: Self.railGlyph(rail))
                    feeRow(receipt)
                    if let txid = receipt.txid {
                        SendDetailRow(source: "Transaction ID", copy: txid, shown: Self.short(txid), last: true,
                                      id: "send-success-copy-proof", onCopy: copied)
                    }
                }
            }
        }
        if let message = controller.message {
            fine(L10n.text(message, locale: locale.identifier))
        }
    }
}

/// The result's rows, actions and the haptics and refund reads behind them.
extension SendOutcomeView {
    private var line: String? {
        let language = locale.identifier
        switch kind {
        case .sending: return nil
        case .stillSending:
            return L10n.text("Lightning is still finding a route. You can leave this screen; Noah finishes it in the background.", locale: language)
        case .sent:
            guard let receipt else { return nil }
            if receipt.amountIsPending == true { return L10n.text("Recipient amount pending", locale: language) }
            switch rail {
            case .ark:
                return (receipt.fee ?? attempt?.quote?.fee ?? 0) == 0
                    ? L10n.text("It arrived instantly. Ark sends are free.", locale: language)
                    : L10n.text("It arrived instantly.", locale: language)
            case .lightning: return L10n.text("Paid over Lightning. Their secret is your proof of payment.", locale: language)
            case .onchainSwap, .onchainWallet:
                return Self.onchainLine(confirmed: motion.confirmedHeight != nil).map { L10n.text($0, locale: language) }
            }
        case .refunded:
            // Only a person or address can be "reached"; an invoice or offer just gets the refund line.
            guard case .initial = label.avatar else { return L10n.text("{amount} is back in your wallet.", format(sendingAmount), locale: language) }
            return L10n.text("{amount} is back in your wallet. Lightning couldn’t reach {recipient}.", format(sendingAmount), recipientName, locale: language)
        case .failed:
            // A retried invoice: bark's records can't say this attempt's coins came back, so no "back in your wallet".
            if refund == .untied { return L10n.text("That payment didn’t go through.", locale: language) }
            return attempt?.failureMessage.map { L10n.text($0, locale: language) } ?? L10n.text(payment?.source == .onchain
                ? "Submission stopped before a transaction could be recorded. Review the amount and fee before trying again."
                : "Wallet history confirms this payment failed or was canceled.", locale: language)
        case .alreadyPaid:
            // An amountless invoice's receipt has no amount it can prove (SendReconciliation.alreadyPaid).
            guard case .alreadyPaid(let receipt) = controller.state, receipt.amount > 0 else { return nil }
            return L10n.text("This invoice was already paid: {amount}", format(receipt.amount), locale: language)
        case .uncertain: return L10n.text(SendReceipt.uncertainMessage, locale: language)
        case .unavailable:
            return L10n.text("Noah could not read or save the payment attempt. Sending is disabled until its status can be recovered.", locale: language)
        case .awaitingBroadcast: return L10n.text(SendReceipt.broadcastPendingMessage, locale: language)
        }
    }

    private func rows(@ViewBuilder _ content: () -> some View) -> some View {
        VStack(spacing: 0) { content() }.padding(.top, 18)
    }

    private func fine(_ text: String) -> some View {
        Text(verbatim: text).noahText(HomeText.footnote).foregroundStyle(NoahTokens.Colors.muted)
            .fixedSize(horizontal: false, vertical: true).padding(.top, 12)
    }

    @ViewBuilder private func feeRow(_ receipt: SendReceipt) -> some View {
        if let fee = receipt.fee {
            if fee == 0 && rail == .ark { SendDetailRow("Fee", text: "Free") } else { SendDetailRow("Fee", number: format(fee)) }
        } else if receipt.amountIsPending == true {
            SendDetailRow("Fee", text: "Actual fee pending")
        } else if let quote = attempt?.quote {
            if quote.fee == 0 && rail == .ark {
                SendDetailRow("Fee", text: "Free")
            } else {
                SendDetailRow(quote.approximate ? "Estimated fee" : "Fee", number: format(quote.fee))
            }
        }
    }

    /// The preimage for Lightning (from history) or the transaction ID on-chain.
    private func proof(_ receipt: SendReceipt) -> (label: String, value: String)? {
        guard let attempt else { return nil }
        let details = SendSuccessDetails(receipt: receipt, payment: attempt.payment, history: history,
                                         completedAt: attempt.startedAt ?? completedAt)
        guard let proof = details.proof else { return nil }
        return (proof.label == "Payment preimage" ? "Proof of payment" : proof.label, proof.value)
    }

    @ViewBuilder private func explorerLink(_ receipt: SendReceipt) -> some View {
        // Regtest has no public explorer. Use the same network explorers as History.
        if let txid = receipt.txid, AppVariant.current != .regtest,
           let base = URL(string: AppVariant.current == .mainnet ? "https://mempool.space/tx/" : "https://mempool.space/signet/tx/") {
            Link(destination: base.appendingPathComponent(txid)) {
                Label("View in explorer", systemImage: "arrow.up.right.square").noahText(HomeText.strong)
            }
            .foregroundStyle(NoahTokens.Colors.foreground)
            .padding(.top, 12)
        }
    }

    @ViewBuilder private var existingLightning: some View {
        if let progress = attempt?.existingLightning {
            fine(L10n.text("A payment of {amount} sats for this invoice is already in progress. Do not pay again.",
                           progress.amount.formatted(.number.locale(locale)), locale: locale.identifier))
            if let reviewed = attempt?.payment.amount, reviewed != progress.amount {
                Text("This differs from the \(reviewed.formatted(.number.locale(locale))) sats you reviewed.")
                    .noahText(HomeText.footnote).foregroundStyle(NoahTokens.Colors.muted).padding(.top, 6)
            }
        }
    }

    /// Today's "may have been sent" evidence, unchanged.
    @ViewBuilder private var uncertain: some View {
        existingLightning
        if let progress = attempt?.existingLightning {
            Text("Fee for the existing payment: \(progress.fee.formatted(.number.locale(locale))) sats")
                .noahText(HomeText.footnote).foregroundStyle(NoahTokens.Colors.muted).padding(.top, 6)
        }
        if let receipt = attempt?.receipt {
            fine(L10n.text(
                receipt
                    .conflictDescription ??
                    "Noah can’t find this transaction right now. Its confirmation may no longer be trackable in-app. Check a block explorer.",
                locale: locale.identifier))
            if let status = receipt.chainStatus { fine(L10n.text(status.message, locale: locale.identifier)) }
            if let txid = receipt.txid {
                Text(verbatim: txid).noahText(SendText.mono).foregroundStyle(NoahTokens.Colors.foreground).textSelection(.enabled).padding(.top, 8)
            }
        }
        if let candidates = attempt?.candidates {
            if candidates.isEmpty { fine(L10n.text("No matching payment is visible in History yet.", locale: locale.identifier)) }
            ForEach(candidates) { candidate in
                VStack(alignment: .leading, spacing: 2) {
                    Text(candidate.status == "successful" ? "Appears to have gone through — check History" : "Possible related payment in History")
                        .noahText(HomeText.strong)
                    Text(verbatim: "#\(candidate.id) · \(candidate.status) · \(candidate.amount) sats").noahText(HomeText.footnote)
                    Text(verbatim: candidate.createdAt).noahText(SendText.small).foregroundStyle(NoahTokens.Colors.muted)
                }
                .padding(.top, 10)
            }
        }
        ForEach(attempt?.possibleTxids ?? [], id: \.self) { txid in
            Text("This transaction may be your payment: \(txid)").noahText(HomeText.footnote).textSelection(.enabled).padding(.top, 8)
        }
    }

    // MARK: Actions

    private var actions: some View {
        VStack(spacing: 10) {
            ForEach(Self.actions(kind: kind, uncertain: controller.state == .uncertain, resultShown: resultShown), id: \.self) { action in
                switch action {
                case .check: checkButton
                case .acknowledge: pill("Done", id: "send-success-done", action: acknowledge)
                case .newPayment: pill("Done", id: "send-new-payment", action: acknowledge)
                case .retry: pill("Retry", id: "send-retry") { Task { await controller.checkStatus() } }
                // Leaving keeps the attempt and the send lock exactly as they are.
                case .leave: pill("Done", id: "send-success-done") { onLeave() }
                case .close: pill("Close", id: "send-leave", prominent: false) { onLeave() }
                }
            }
            if controller.canOverride {
                Button("Start a new payment after checking history", role: .destructive) { confirmOverride = true }
                    .noahText(HomeText.strong)
                    .foregroundStyle(NoahTokens.Colors.failure)
                    .frame(minHeight: NoahTokens.Size.hitTarget)
                    .accessibilityIdentifier("send-override")
            }
        }
        .animation(NoahTokens.Motion.travel(reduceMotion), value: resultShown)
    }

    private var checkButton: some View {
        Button {
            Task { await controller.checkStatus() }
        } label: {
            ZStack {
                Text("Check status").opacity(controller.checking ? 0 : 1)
                if controller.checking { ProgressView().tint(NoahTokens.Colors.foreground) }
            }
            .frame(maxWidth: .infinity)
        }
        .buttonStyle(HomePillStyle(prominent: false, height: 52, font: NoahTokens.Typography.button))
        .disabled(controller.checking)
        .accessibilityIdentifier("send-check-status")
    }

    /// What a result offers, in order.
    enum Action: Hashable {
        case check, acknowledge, newPayment, retry
        /// Leave Send through `onLeave`; nothing is resolved, cleared or resubmitted.
        case leave, close
    }

    /// Unresolved results (may have been sent, status unavailable, awaiting broadcast) have no
    /// Done, and the tab bar is hidden there, so they offer Close.
    static func actions(kind: SendResultKind, uncertain: Bool, resultShown: Bool) -> [Action] {
        switch kind {
        case .sending: return []
        case .stillSending: return (uncertain ? [.check] : []) + (resultShown || uncertain ? [.leave] : [])
        case .sent, .refunded, .alreadyPaid: return resultShown || kind == .alreadyPaid ? [.acknowledge] : []
        case .failed: return [.newPayment]
        case .uncertain, .awaitingBroadcast: return [.check, .close]
        case .unavailable: return [.retry, .close]
        }
    }

    private func pill(_ title: LocalizedStringKey, id: String, prominent: Bool = true, action: @escaping () -> Void) -> some View {
        Button(action: action) { Text(title).frame(maxWidth: .infinity) }
            .buttonStyle(HomePillStyle(prominent: prominent, height: 52, font: NoahTokens.Typography.button))
            .accessibilityIdentifier(id)
            .transition(.opacity)
    }

    private func acknowledge() {
        haptics.play(.light)
        controller.acknowledge()
        if !controller.locked { done() }
    }

    private func copied() {
        haptics.play(.success)
        UIAccessibility.post(notification: .announcement, argument: L10n.text("Copied", locale: locale.identifier))
    }

    /// An on-chain send's line: none once confirmed, when the block row's phase line already says
    /// "Confirmed in block N" (send-receive.md, the block row).
    static func onchainLine(confirmed: Bool) -> String? {
        confirmed ? nil : "It confirms in about 20 minutes. Activity shows when."
    }

    /// A proof shortened to its ends, as the prototype shows it ("9f2c…a41e").
    static func short(_ value: String) -> String {
        value.count > 12 ? String(value.prefix(4)) + "…" + String(value.suffix(4)) : value
    }

    static func railName(_ rail: SendMotion.Rail) -> String {
        switch rail { case .ark: "Ark"; case .lightning: "Lightning"; case .onchainSwap, .onchainWallet: "On-chain" }
    }

    static func railGlyph(_ rail: SendMotion.Rail) -> String {
        switch rail { case .ark: "sailboat"; case .lightning: "bolt"; case .onchainSwap, .onchainWallet: "link" }
    }

    // MARK: Observation

    private var refundKey: String? {
        guard controller.state == .failed, rail == .lightning, attempt?.failureMessage == nil else { return nil }
        return attempt?.paymentHash ?? ""
    }

    /// Reads what history records coming back; without a payment hash there's no evidence.
    private func readRefund() async {
        guard let hash = refundKey else { refund = nil; return }
        if let fixture { refund = fixture.refunded == true ? .recorded : .notRecorded; return }
        guard !hash.isEmpty else { refund = .notRecorded; return }
        let read = (try? await WalletSession.shared.lightningSendRefund(paymentHash: hash, before: attempt?.snapshot.movementIDs ?? [])) ?? .notRecorded
        if !Task.isCancelled { refund = read }
    }

    private var resultKey: String { "\(rail)-\(shownOutcome)-\(motion.submittedAt?.timeIntervalSince1970 ?? 0)-\(motion.outcomeAt?.timeIntervalSince1970 ?? 0)"
    }

    /// The result appears when the motion reaches it; Lightning that's still paying after about 10 s shows its pending result.
    private func revealResult() async {
        let outcome = shownOutcome
        let outcomeAt = motion.since(motion.outcomeAt) ?? .infinity
        let at: Double? = switch outcome {
        case .inFlight: rail == .lightning ? SendMotion.payingStart + SendMotion.stillPayingAfter : nil
        default: SendMotion.resultAt(rail: rail, outcome: outcome, outcomeAt: outcomeAt)
        }
        guard let at else { resultShown = false; slow = false; return }
        let wait = at - motion.elapsed(Date())
        if wait > 0 {
            // The ending plays before its result appears.
            if outcome != .inFlight { resultShown = false }
            do { try await Task.sleep(for: .seconds(wait)) } catch { return }
        }
        withAnimation(NoahTokens.Motion.travel(reduceMotion)) {
            if outcome == .inFlight { slow = true }
            resultShown = true
        }
    }

    private var hapticKey: String {
        "\(rail)-\(outcome)-\(motion.outcomeAt?.timeIntervalSince1970 ?? 0)-\(motion.confirmedHeight ?? 0)-\(tip ?? 0)"
    }

    private func playHaptics() async {
        let confirmations = SendMotion.confirmations(height: motion.confirmedHeight, tip: tip)
        let schedule = SendMotion.haptics(rail: rail, outcome: outcome, outcomeAt: motion.since(motion.outcomeAt) ?? .infinity,
                                          confirmations: confirmations, required: requiredConfirmations,
                                          confirmedAt: motion.since(motion.confirmedAt))
        for item in schedule.sorted(by: { $0.at < $1.at }) where item.at.isFinite {
            let wait = item.at - motion.elapsed(Date())
            if wait > 0 { do { try await Task.sleep(for: .seconds(wait)) } catch { return } }
            guard motion.shouldPlay(item.key, at: item.at) else { continue }
            switch item.kind {
            case .light: haptics.play(.light)
            case .success: haptics.play(.success)
            case .error: haptics.play(.error)
            }
        }
    }
}
