import SwiftUI

/// Review (send-receive.md): "Send ₿X", who it goes to, then Via, Arrives, Fee and Total,
/// at most one line of today's fine print, and Hold to send. Once the controller holds
/// the payment it becomes the sending stage and result.
struct SendConfirmation: View {
    var confirm: (() -> Void)?
    var btcPrice: Double?
    var fiatCurrency = "USD"
    let payment: SendPayment
    let quote: SendQuote
    @Bindable var controller: SendPaymentController
    let cancel: () -> Void
    let done: () -> Void
    /// Leaving an unresolved result (see `SendOutcomeView.onLeave`).
    let onLeave: () -> Void
    var unit: BitcoinAmountUnit = .bip177
    var recipient: SendRecipientLabel?
    /// "The invoice sets the amount to ₿X", when you had typed a different amount.
    var invoiceNote: String?
    /// Nil measures its own window (a host without Send around it).
    var wide: Bool? // swiftlint:disable:this discouraged_optional_boolean - nil measures its own window
    var sideInsets: (leading: CGFloat, trailing: CGFloat) = (0, 0)
    var clock: SendMotionClock?
    var gate: SendHoldGate?
    var chainTip: UInt32?
    var backToSend = false
    @State private var ownGate = SendHoldGate()
    @State private var window = OnboardingLayout(size: .zero, insets: EdgeInsets())
    @Environment(\.locale) private var locale
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    private var isWide: Bool { wide ?? window.wide }
    private var label: SendRecipientLabel {
        recipient ?? .of(destination: payment.destination, ownDomain: LightningIdentity.domain(for: .current), payment: payment)
    }
    private func format(_ sats: UInt64) -> String { BitcoinAmount.format(sats: Int64(clamping: sats), unit: unit, locale: locale) }

    var body: some View {
        Group {
            if controller.locked {
                SendOutcomeView(controller: controller, btcPrice: btcPrice, fiatCurrency: fiatCurrency, done: done, clock: clock,
                                unit: unit, recipient: label, wide: wide, sideInsets: sideInsets, onLeave: onLeave, reviewed: payment, chainTip: chainTip,
                                backToSend: backToSend)
            } else {
                review
            }
        }
        .background { if wide == nil { Color.clear.onboardingWindow($window) } }
        .task(id: quote.prepared?.id) {
            // A signed transaction expires; review again for a fresh fee rate.
            guard let prepared = quote.prepared else { return }
            let remaining = Duration.seconds(SendPreparedTransaction.reviewLifetime)
                - ReviewClock.elapsed(since: prepared.reviewedAt, createdAt: prepared.createdAt)
            do { try await Task.sleep(for: max(.zero, remaining)) } catch { return }
            if !controller.locked { cancel() }
        }
    }

    private var review: some View {
        OnboardingPanes(wide: isWide, column: .leadingFits, sideInsets: wide == nil ? (window.insets.leading, window.insets.trailing) : sideInsets) {
            leadingPane
            trailingPane
        }
        .background(NoahTokens.Colors.background.ignoresSafeArea())
    }

    private var leadingPane: some View {
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
                // Accessibility sizes wrap "Send" and the amount rather than shrinking what was asked to grow.
                .lineLimit(dynamicTypeSize.isAccessibilitySize ? 3 : 1)
                .minimumScaleFactor(dynamicTypeSize.isAccessibilitySize ? 0.85 : 0.5)
                .fixedSize(horizontal: false, vertical: dynamicTypeSize.isAccessibilitySize)
                .padding(.top, 18)
                .accessibilityIdentifier("send-review-title")
            if let btcPrice {
                Text(verbatim: FiatCurrency.display(sats: Int64(clamping: quote.amount), btcPrice: btcPrice, currency: fiatCurrency, locale: locale))
                    .noahText(NoahTokens.Typography.body).foregroundStyle(NoahTokens.Colors.muted)
                    .padding(.top, 4)
                    .accessibilityIdentifier("send-review-fiat")
            } else if unit == .bip177 {
                Text(verbatim: BitcoinAmount.satsLine(Int64(clamping: quote.amount), locale: locale))
                    .noahText(NoahTokens.Typography.body).foregroundStyle(NoahTokens.Colors.muted)
                    .padding(.top, 4)
                    .accessibilityIdentifier("send-review-sats")
            }
        }
        .padding(.horizontal, isWide ? 34 : 20)
        .padding(.bottom, isWide ? 96 : 0)
        .frame(maxWidth: isWide ? OnboardingLayout.paneContentWidth + 68 : nil)
        .frame(maxWidth: .infinity, maxHeight: isWide ? .infinity : nil, alignment: .top)
    }

    private var trailingPane: some View {
        VStack(alignment: .leading, spacing: 0) {
            // Wide: rows and Hold to send sit at the bottom of the trailing pane (prototype `#vReview .R`),
            // and the rows scroll when large text makes them taller than the pane.
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    recipientBlock.padding(.top, isWide ? 0 : 20)
                    rows.padding(.top, 16)
                    if let fine {
                        Text(verbatim: fine).noahText(NoahTokens.Typography.sub).foregroundStyle(NoahTokens.Colors.muted)
                            .fixedSize(horizontal: false, vertical: true).padding(.top, 12)
                    }
                    if let message = controller.message {
                        Text(LocalizedStringKey(message)).noahText(NoahTokens.Typography.sub).foregroundStyle(NoahTokens.Colors.failure)
                            .fixedSize(horizontal: false, vertical: true).padding(.top, 12)
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

    private var rail: SendMotion.Rail { SendMotion.Rail(payment) }

    private var recipientBlock: some View {
        SendToBlock(label: label, payment: payment)
            .padding(.bottom, 16)
            .overlay(alignment: .bottom) { Rectangle().fill(NoahTokens.Colors.hairline).frame(height: 1) }
            .accessibilityIdentifier("send-review-recipient")
    }

    /// The reviewed destination in full, unless the name above already is it.
    static func fullDestination(_ destination: String, shown: String) -> String? {
        let value = SendDestination.cleaned(destination)
        return value.isEmpty || value.caseInsensitiveCompare(shown) == .orderedSame ? nil : value
    }

    /// A BOLT11 invoice's or BOLT12 offer's own description, as the payee wrote it.
    static func invoiceDescription(_ payment: SendPayment) -> String? {
        let value = SendDestination.cleaned(payment.destination)
        let text = (PaymentEncoding.invoice(value)?.description ?? PaymentEncoding.offerDescription(value))?
            .trimmingCharacters(in: .whitespacesAndNewlines)
        return text?.isEmpty == false ? text : nil
    }

    @ViewBuilder private var rows: some View {
        let estimated = quote.approximate || quote.recipientIsEstimated
        // Every row stacks its label above its value when large text won't fit them on one line.
        VStack(spacing: 0) {
            SendDetailRow("Via", text: SendOutcomeView.railName(rail), glyph: SendOutcomeView.railGlyph(rail), wraps: true)
            if let source = payment.source {
                SendDetailRow("From", text: source.label, wraps: true)
            }
            SendDetailRow("Arrives", text: Self.arrives(rail, required: WalletConfiguration.base(for: .current).roundTxRequiredConfirmations,
                                                        locale: locale.identifier), verbatim: true, wraps: true)
            if quote.fee == 0 && rail == .ark {
                SendDetailRow(estimated ? "Estimated fee" : "Fee", text: "Free", wraps: true)
            } else {
                SendDetailRow(estimated ? "Estimated fee" : "Fee", number: format(quote.fee), wraps: true)
            }
            SendDetailRow(quote.approximate && !quote.recipientIsEstimated ? "Estimated total" : "Total",
                          number: format(quote.total), last: payment.comment.isEmpty, wraps: true)
            if !payment.comment.isEmpty {
                SendDetailRow("Note", text: payment.comment, verbatim: true, last: true, wraps: true)
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
        case .ark, .lightning: L10n.text("Usually in seconds", locale: locale)
        case .onchainWallet: L10n.text("In about 20 minutes", locale: locale)
        case .onchainSwap:
            // The method sheet's "About 20 min" for on-chain, with the blocks it waits for; one block isn't 20 minutes.
            if required <= 1 { L10n.text("After one Bitcoin block", locale: locale) }
            else { L10n.text("About 20 min · {count} Bitcoin blocks", Int(required), locale: locale) }
        }
    }
}

/// "To" on review and on the result: who it goes to, the invoice's own description, then the full address
/// or invoice in a soft card, selectable, so it can be compared before holding. An invoice never names its
/// payee; its card shows the start and expands in place ("Show full invoice" / "Show less").
struct SendToBlock: View {
    let label: SendRecipientLabel
    let payment: SendPayment
    @Environment(\.sendReceiveFixture) private var fixture
    @State private var expanded = false
    @State private var copied = false

    var body: some View {
        let full = SendConfirmation.fullDestination(payment.destination, shown: label.name)
        VStack(alignment: .leading, spacing: 8) {
            Text("To").noahText(NoahTokens.Typography.sub).foregroundStyle(NoahTokens.Colors.muted)
                .accessibilityAddTraits(.isHeader)
            HStack(spacing: 12) {
                SendAvatar(label: label, size: 36)
                Group { if label.nameIsVerbatim { Text(verbatim: label.name) } else { Text(LocalizedStringKey(label.identity)) } }
                    .noahText(NoahTokens.Typography.label).foregroundStyle(NoahTokens.Colors.foreground)
                    .lineLimit(2).truncationMode(.middle)
            }
            if let note = SendConfirmation.invoiceDescription(payment) {
                Text(verbatim: "“\(note)”").noahText(NoahTokens.Typography.body).foregroundStyle(NoahTokens.Colors.foreground)
                    .fixedSize(horizontal: false, vertical: true)
            }
            if let full { card(full) }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .contain)
        .onAppear { if fixture?.invoiceExpanded == true { expanded = true } }
    }

    /// Addresses are short enough to show whole; an invoice starts collapsed to its first two lines and last
    /// characters and toggles between them and the whole. The copy button copies the whole string either way.
    private func card(_ full: String) -> some View {
        let collapsible = SendMotion.Rail(payment) == .lightning
        return VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .top, spacing: 4) {
                Group {
                    if collapsible && !expanded {
                        // Its start, then its end, so both can be compared without expanding.
                        VStack(alignment: .leading, spacing: 0) {
                            identifier(full, lines: 2)
                            identifier("…", selectable: false)
                            identifier(String(full.suffix(12)), selectable: false)
                        }
                        .accessibilityElement(children: .ignore)
                        .accessibilityLabel(Text(verbatim: full))
                    } else {
                        identifier(full)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .accessibilityIdentifier("send-review-destination")
                copyButton(full)
            }
            if collapsible { disclosure }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(NoahTokens.Colors.soft, in: RoundedRectangle(cornerRadius: NoahTokens.Radius.m, style: .continuous))
        .padding(.top, 4)
    }

    /// Quieter than the name above, still 4.5:1 on the soft card (the muted token isn't, in light).
    private func identifier(_ value: String, lines: Int = 0, selectable: Bool = true) -> some View {
        IdentifierText(value: value, size: 13, style: .footnote, opacity: 0.62, lines: lines, clipped: true, selectable: selectable)
    }

    private func copyButton(_ full: String) -> some View {
        Button {
            UIPasteboard.general.setItems([[UIPasteboard.typeAutomatic: full]],
                                          options: [.localOnly: true, .expirationDate: Date().addingTimeInterval(120)])
            withAnimation(NoahTokens.Motion.soft) { copied = true }
            Task {
                try? await Task.sleep(for: .seconds(1.2))
                withAnimation(NoahTokens.Motion.soft) { copied = false }
            }
        } label: {
            Image(systemName: copied ? "checkmark" : "doc.on.doc")
                .font(.system(size: 15, weight: copied ? .semibold : .regular))
                .contentTransition(.symbolEffect(.replace))
                .frame(width: NoahTokens.Size.hitTarget, height: NoahTokens.Size.hitTarget)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .foregroundStyle(NoahTokens.Colors.muted)
        .padding(.vertical, -12).padding(.trailing, -12)
        .sensoryFeedback(.success, trigger: copied) { _, now in now }
        .accessibilityLabel(copied ? Text("Copied") : Text("Copy"))
        .accessibilityIdentifier("send-destination-copy")
    }

    private var disclosure: some View {
        Button {
            withAnimation(NoahTokens.Motion.soft) { expanded.toggle() }
        } label: {
            HStack(spacing: 4) {
                if expanded { Text("Show less") } else { Text("Show full invoice") }
                Image(systemName: expanded ? "chevron.up" : "chevron.down").accessibilityHidden(true)
            }
            .noahText(NoahTokens.Typography.sub).foregroundStyle(NoahTokens.Colors.foreground)
            .frame(minHeight: NoahTokens.Size.hitTarget, alignment: .leading)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .padding(.vertical, -10)
        .accessibilityIdentifier("send-destination-disclosure")
    }
}
