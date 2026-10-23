import SwiftUI

/// Review (send-receive.md): "Send ₿X", who it goes to, then Via, Arrives, Fee and Total,
/// at most one line of today's fine print, and Hold to send. Once the controller holds
/// the payment it becomes the sending stage and result.
struct SendConfirmation: View {
    var confirm: (() -> Void)? = nil
    var btcPrice: Double? = nil
    var fiatCurrency = "USD"
    let payment: SendPayment
    let quote: SendQuote
    @Bindable var controller: SendPaymentController
    let cancel: () -> Void
    let done: () -> Void
    /// Leaving an unresolved result (see `SendOutcomeView.onLeave`).
    let onLeave: () -> Void
    var unit: BitcoinAmountUnit = .bip177
    var recipient: SendRecipientLabel? = nil
    /// "The invoice sets the amount to ₿X", when you had typed a different amount.
    var invoiceNote: String? = nil
    /// Nil measures its own window (a host without Send around it).
    var wide: Bool? = nil
    var sideInsets: (leading: CGFloat, trailing: CGFloat) = (0, 0)
    var clock: SendMotionClock? = nil
    var gate: SendHoldGate? = nil
    var chainTip: UInt32? = nil
    @State private var ownGate = SendHoldGate()
    @State private var window = OnboardingLayout(size: .zero, insets: EdgeInsets())
    @Environment(\.locale) private var locale

    private var isWide: Bool { wide ?? window.wide }
    private var label: SendRecipientLabel {
        recipient ?? .of(destination: payment.destination, payment: payment, ownDomain: LightningIdentity.domain(for: .current))
    }
    private func format(_ sats: UInt64) -> String { BitcoinAmount.format(sats: Int64(clamping: sats), unit: unit, locale: locale) }

    var body: some View {
        Group {
            if controller.locked {
                SendOutcomeView(controller: controller, btcPrice: btcPrice, fiatCurrency: fiatCurrency, done: done, clock: clock,
                                unit: unit, recipient: label, wide: wide, sideInsets: sideInsets, onLeave: onLeave, reviewed: payment, chainTip: chainTip)
            } else {
                review
            }
        }
        .background { if wide == nil { Color.clear.onboardingWindow($window) } }
        .task(id: quote.prepared?.id) {
            // A signed transaction expires; review again for a fresh fee rate.
            guard let prepared = quote.prepared else { return }
            let remaining = max(0, SendPreparedTransaction.reviewLifetime - Date().timeIntervalSince(prepared.createdAt))
            do { try await Task.sleep(for: .seconds(remaining)) } catch { return }
            if !controller.locked { cancel() }
        }
    }

    private var review: some View {
        OnboardingPanes(wide: isWide, column: .leadingFits, sideInsets: wide == nil ? (window.insets.leading, window.insets.trailing) : sideInsets) {
            VStack(alignment: .leading, spacing: 0) {
                HStack {
                    SendIconButton(systemName: "chevron.left", label: "Back", id: "send-review-back", action: cancel)
                    Spacer()
                }
                .frame(height: NoahTokens.Size.hitTarget)
                .padding(.horizontal, -2)
                .padding(.top, isWide ? 14 : 4)
                Text(verbatim: L10n.text("Send {amount}", format(quote.amount), locale: locale.identifier))
                    .noahText(SendText.big).foregroundStyle(NoahTokens.Colors.foreground)
                    .lineLimit(1).minimumScaleFactor(0.5)
                    .padding(.top, 18)
                    .accessibilityIdentifier("send-review-title")
                HStack(spacing: 12) {
                    SendAvatar(label: label, size: 36)
                    Group { if label.nameIsVerbatim { Text(verbatim: label.name) } else { Text(LocalizedStringKey(label.name)) } }
                        .noahText(NoahTokens.Typography.body).foregroundStyle(NoahTokens.Colors.foreground)
                        .lineLimit(1).truncationMode(.middle)
                }
                .padding(.top, 16)
            }
            .padding(.horizontal, isWide ? 34 : 20)
            .padding(.bottom, isWide ? 96 : 0)
            .frame(maxWidth: isWide ? OnboardingLayout.paneContentWidth + 68 : nil)
            .frame(maxWidth: .infinity, maxHeight: isWide ? .infinity : nil, alignment: .top)
            VStack(alignment: .leading, spacing: 0) {
                // Wide: rows and Hold to send sit at the bottom of the trailing pane (prototype `#vReview .R`),
                // and the rows scroll when large text makes them taller than the pane.
                ScrollView {
                    VStack(alignment: .leading, spacing: 0) {
                        rows.padding(.top, isWide ? 0 : 24)
                        if let fine { Text(verbatim: fine).noahText(HomeText.footnote).foregroundStyle(NoahTokens.Colors.muted).padding(.top, 12) }
                        if let message = controller.message {
                            Text(LocalizedStringKey(message)).noahText(HomeText.footnote).foregroundStyle(NoahTokens.Colors.failure).padding(.top, 12)
                        }
                    }
                }
                .scrollBounceBehavior(.basedOnSize)
                .defaultScrollAnchor(isWide ? .bottom : nil, for: .alignment)
                .frame(maxHeight: .infinity)
                .padding(.top, isWide ? 66 : 0)
                SendHoldButton(amount: format(quote.amount), enabled: !controller.locked) {
                    (gate ?? ownGate).confirm(generation: controller.generation) {
                        if let confirm { confirm() } else { controller.submit(payment, quote: quote) }
                    }
                }
                .padding(.top, isWide ? 24 : 12).padding(.bottom, 6)
            }
            .padding(.leading, isWide ? 40 : 20).padding(.trailing, isWide ? 34 : 20)
            .padding(.vertical, isWide ? 24 : 0).padding(.bottom, 12)
            .frame(maxWidth: isWide ? OnboardingLayout.paneContentWidth + 74 : nil)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: isWide ? .bottom : .top)
        }
        .background(NoahTokens.Colors.background.ignoresSafeArea())
    }

    private var rail: SendMotion.Rail { SendMotion.Rail(payment) }

    @ViewBuilder private var rows: some View {
        let estimated = quote.approximate || quote.recipientIsEstimated
        VStack(spacing: 0) {
            SendDetailRow("Via", text: SendOutcomeView.railName(rail), glyph: SendOutcomeView.railGlyph(rail))
            if let source = payment.source {
                SendDetailRow("From", text: source.label)
            }
            SendDetailRow("Arrives", text: Self.arrives(rail, required: WalletConfiguration.base(for: .current).roundTxRequiredConfirmations,
                                                        locale: locale.identifier), verbatim: true, wraps: true)
            if quote.fee == 0 && rail == .ark {
                SendDetailRow(estimated ? "Estimated fee" : "Fee", text: "Free")
            } else {
                SendDetailRow(estimated ? "Estimated fee" : "Fee", number: format(quote.fee))
            }
            SendDetailRow(quote.recipientIsEstimated ? "Total (reviewed coins)" : quote.approximate ? "Estimated total" : "Total",
                          number: format(quote.total), last: payment.comment.isEmpty)
            if !payment.comment.isEmpty {
                SendDetailRow("Note", text: payment.comment, verbatim: true, last: true)
            }
        }
        .accessibilityIdentifier("send-review-rows")
    }

    private var fine: String? { Self.fine(invoiceNote: invoiceNote, quote: quote, locale: locale.identifier) }

    /// One line at most: the invoice amount, the signed transaction's expiry, or today's estimate notes.
    static func fine(invoiceNote: String?, quote: SendQuote, locale: String) -> String? {
        if let invoiceNote { return invoiceNote }
        if quote.prepared != nil {
            return L10n.text("This signed transaction expires after two minutes. Review again for a fresh fee rate.", locale: locale)
        }
        if quote.recipientIsEstimated { return L10n.text(SendQuote.recipientEstimateExplanation, locale: locale) }
        if quote.approximate { return L10n.text(SendQuote.feeEstimateExplanation, locale: locale) }
        return nil
    }

    /// The Arrives row. An Ark-funded on-chain send waits for the round's confirmations
    /// (today's setting), shown here so no fine print can crowd them out.
    static func arrives(_ rail: SendMotion.Rail, required: UInt32, locale: String) -> String {
        switch rail {
        case .ark: L10n.text("Instantly", locale: locale)
        case .lightning: L10n.text("In seconds", locale: locale)
        case .onchainWallet: L10n.text("In about 20 minutes", locale: locale)
        case .onchainSwap: required <= 1 ? L10n.text("After one confirmation", locale: locale)
            : L10n.text("After {count} confirmations", Int(required), locale: locale)
        }
    }
}
