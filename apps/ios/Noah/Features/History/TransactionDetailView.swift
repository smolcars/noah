import SwiftUI

/// A payment's detail (activity.md, "Detail"): the header, the rows that apply, Details with
/// everything today's technical section shows, and Pay again. Activity pushes it on phones and
/// shows it in the trailing pane on wide windows; Home opens it as a sheet.
struct TransactionDetailView: View {
    @Environment(\.locale) private var locale
    @Environment(AppModel.self) private var app
    @Environment(HistoryModel.self) private var history
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    let transaction: HistoryTransaction
    var wide = false
    /// × in the bar (wide panes and Home's sheets); a phone push has Back instead.
    var closable = true
    let onRepeatPayment: (RepeatPayment) -> Void
    let onClose: () -> Void
    /// Activity keeps Details open across pose changes; Home's sheet keeps its own.
    private let externalDetails: Binding<Bool>?
    @State private var localDetails = false
    /// The current price and the block row's chain reads, for the record shown.
    @State private var reads = ActivityDetailReads()
    @State private var copied: String?
    @State private var toast: HomeToast?
    @State private var shareAnchor: CGRect?
    @State private var haptics = OnboardingHaptics()

    init(transaction: HistoryTransaction, wide: Bool = false, closable: Bool = true, detailsOpen: Binding<Bool>? = nil,
         onRepeatPayment: @escaping (RepeatPayment) -> Void, onClose: @escaping () -> Void) {
        self.transaction = transaction
        self.wide = wide
        self.closable = closable
        self.onRepeatPayment = onRepeatPayment
        self.onClose = onClose
        externalDetails = detailsOpen
    }

    private var detailsOpen: Bool {
        get { externalDetails?.wrappedValue ?? localDetails }
        nonmutating set {
            if let externalDetails { externalDetails.wrappedValue = newValue } else { localDetails = newValue }
        }
    }

    private var ownDomain: String {
        app.server.state.lightningAddress?.split(separator: "@").last.map(String.init) ?? LightningIdentity.domain(for: app.variant)
    }
    private var row: HomeActivityRow { HomeActivityRow(transaction, ownDomain: ownDomain) }
    private var detail: ActivityDetail { ActivityDetail(transaction, ownDomain: ownDomain) }
    private var language: String { locale.identifier }
    private var hidden: Bool { app.isBalanceHidden }
    private var payAgain: RepeatPayment? { transaction.canRepeat ? transaction.repeatPayment : nil }

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    header
                    VStack(spacing: 0) { mainRows }.padding(.top, 24)
                    detailsToggle(proxy)
                    if detailsOpen {
                        VStack(alignment: .leading, spacing: 0) { detailRows }
                            .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
                    }
                }
                .foregroundStyle(NoahTokens.Colors.foreground)
                .padding(.leading, wide ? 36 : NoahTokens.Space.screen).padding(.trailing, wide ? 32 : NoahTokens.Space.screen)
                .padding(.top, wide ? 4 : 8)
                .padding(.bottom, payAgain == nil ? 60 : 130)
                .frame(maxWidth: 640, alignment: .leading)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .background(NoahTokens.Colors.background.ignoresSafeArea())
        .safeAreaInset(edge: .bottom) { if let payAgain { payAgainBar(payAgain) } }
        .overlay(alignment: .top) { toastLayer }
        .navigationTitle("")
        .navigationBarTitleDisplayMode(.inline)
        .modifier(DetailChrome(wide: wide, bar: AnyView(paneBar)) { toolbar })
        .tint(NoahTokens.Colors.foreground)
        // Each completed history read re-reads the block row's chain status, as wallet events arrive.
        .task(id: "\(transaction.id):\(history.revision)") { await load() }
        // Wide panes reuse this view for the next selection: nothing of the last payment carries over.
        .onChange(of: transaction.id) { _, id in
            copied = nil
            toast = nil
            reads.select(id)
        }
        .onboardingHaptics(haptics)
        .accessibilityIdentifier("transaction-detail")
    }

    // MARK: Header

    private var header: some View {
        VStack(alignment: .leading, spacing: 0) {
            HomeAvatar(avatar: row.avatar, pending: row.status.isPending, failed: row.status.isFailure, isNew: false, size: 60)
                // Switching rows on wide pops the avatar (bouncy) while the amount rolls.
                .id(transaction.id)
                .transition(reduceMotion ? .opacity : .scale(scale: 0.6).combined(with: .opacity))
                .animation(NoahTokens.Motion.travel(reduceMotion, NoahTokens.Motion.bouncy), value: transaction.id)
                .padding(.top, 6)
            // A paid amountless invoice with no proven debit has no amount to show.
            if row.sats != nil {
                Text(verbatim: row.amount(unit: app.amountUnit, hidden: hidden, locale: locale))
                    .noahText(ActivityDetailText.amount)
                    .monospacedDigit()
                    .strikethrough(detail.unsuccessful, pattern: .solid)
                    .foregroundStyle(detail.unsuccessful ? NoahTokens.Colors.muted : NoahTokens.Colors.foreground)
                    .lineLimit(1).minimumScaleFactor(0.5)
                    .contentTransition(reduceMotion ? .opacity : .numericText(value: Double(transaction.shownAmount ?? 0)))
                    .padding(.top, 18)
                    .accessibilityIdentifier("transaction-detail-amount")
            }
            HStack(spacing: 8) {
                if detail.tone == .pending { ActivityOrangePulse() }
                Text(verbatim: statusText).noahText(NoahTokens.Typography.label)
                    .foregroundStyle(detail.tone == .failure ? NoahTokens.Colors.failure
                        : detail.tone == .muted ? NoahTokens.Colors.muted : NoahTokens.Colors.foreground)
            }
            .padding(.top, 12)
            .accessibilityIdentifier("transaction-detail-status")
            if let fiat {
                Text(verbatim: fiat).noahText(NoahTokens.Typography.sub).foregroundStyle(NoahTokens.Colors.muted).padding(.top, 3)
            }
            if let txid = detail.blockTxid { blockRow(txid) }
        }
    }

    private var statusText: String {
        if detail.status == "Renewed {n} coins", let coins = detail.renewedCoins {
            return L10n.plural("Renewed {n} coins", count: coins, locale: language)
        }
        return L10n.text(detail.status, locale: language)
    }

    private var fiat: String? {
        if hidden && !detail.unsuccessful && transaction.shownAmount != nil { return HomeModel.hiddenFiat(currency: app.preferredCurrency) }
        return ActivityDetail.fiat(transaction, current: reads.price(for: transaction.id), currency: app.preferredCurrency, locale: locale)
    }

    @ViewBuilder private func blockRow(_: String) -> some View {
        let confirmed: UInt32? = if case .confirmed(let height) = reads.status(for: transaction.id) { height } else { transaction.confirmationHeight }
        let chainTip = reads.tip(for: transaction.id)
        let required = ActivityDetail.requiredConfirmations(transaction)
        ActivityBlockRow(tip: chainTip, confirmedHeight: confirmed)
            .frame(width: 204, height: 62)
            .padding(.leading, -8)
            .padding(.top, 20)
        if let phase = phaseLine(confirmed: confirmed, required: required) {
            Text(verbatim: phase).noahText(NoahTokens.Typography.control).foregroundStyle(NoahTokens.Colors.muted)
                .padding(.top, 4)
                .accessibilityIdentifier("transaction-detail-phase")
        }
    }

    private func phaseLine(confirmed: UInt32?, required: Int) -> String? {
        let chainTip = reads.tip(for: transaction.id)
        if let confirmed {
            let line = L10n.text("Confirmed in block {height}", confirmed.formatted(.number.locale(locale)), locale: language)
            guard required > 1 else { return line }
            let count = min(SendMotion.confirmations(height: confirmed, tip: chainTip), required)
            return line + " · " + L10n.text("{n} of {total}", count, required, locale: language)
        }
        return chainTip.map { L10n.text("Waiting for block {height}", ($0 + 1).formatted(.number.locale(locale)), locale: language) }
    }

    // MARK: Rows

    @ViewBuilder private var mainRows: some View {
        if let to = detail.to {
            line("To", to, mono: detail.toIsAddress, copy: to, id: "transaction-detail-to")
        }
        line("Via", L10n.text(detail.rail.title, locale: language), glyph: detail.rail.symbol)
        fees
        if let total = detail.total { line("Total", money(total), numeric: true) }
        if detail.showsDate { line("Date", dateText) }
        if let height = confirmedBlock { line("Confirmed", L10n.text("Block {height}", height.formatted(.number.locale(locale)), locale: language)) }
        if let entered = enteredAs { line("Entered as", hidden ? "••••" : entered) }
        if let note = detail.note { line("Note", note, wraps: true) }
        if let recurring = transaction.recurring {
            link(.schedule(recurring.scheduleID), "Repeats", recurring.interval.text(locale: language))
        }
        if transaction.movementKind == "exit" { link(.exit, "Progress", L10n.text("Exit details", locale: language)) }
        else if let exit = transaction.exitFeeFor { link(.exitProgress(exit), "Progress", L10n.text("Exit details", locale: language)) }
        if let proof = detail.proof {
            line("Proof of payment", proof, mono: true, copy: proof, id: "transaction-detail-preimage")
        }
        if let txid = detail.chainTxid {
            line("Transaction ID", txid, mono: true, copy: txid, explorer: HistoryTransaction.explorerURL(txid: txid, variant: app.variant), last: true)
        }
    }

    @ViewBuilder private var fees: some View {
        switch detail.fee {
        case .free: line("Fee", L10n.text("Free", locale: language))
        case .nothing: line("Fee", L10n.text("None", locale: language))
        case .upTo(let sats): line("Fee", L10n.text("Up to {amount}", money(sats), locale: language), numeric: true)
        case .amount(let sats): line("Fee", money(sats), numeric: true)
        case .network(let sats): line("Network fee", money(sats), numeric: true)
        case .split(let ark, let network):
            line("Ark fee", money(ark), numeric: true)
            line("Network fee", money(network), numeric: true)
        case nil: EmptyView()
        }
    }

    private var confirmedBlock: UInt32? {
        guard !transaction.isPending, transaction.hasConfirmation == true else { return nil }
        return transaction.confirmationHeight
    }

    private var enteredAs: String? {
        guard let payment = transaction.repeatPayment, payment.amountMode == "FIAT", let currency = payment.fiatCurrency,
              let amount = Double(payment.amountInput) else { return nil }
        return FiatCurrency.format(amount, currency: currency, locale: locale)
    }

    private var dateText: String {
        if let label = transaction.dateLabel { return L10n.text(label, locale: language) }
        guard let date = transaction.timestamp, date.timeIntervalSince1970 > 0 else { return L10n.text("Unconfirmed", locale: language) }
        let formatter = DateFormatter()
        formatter.locale = locale
        formatter.dateStyle = .medium
        formatter.timeStyle = .short
        formatter.doesRelativeDateFormatting = true
        return formatter.string(from: date)
    }

    // MARK: Details

    private func detailsToggle(_ proxy: ScrollViewProxy) -> some View {
        Button {
            haptics.play(.selection)
            withAnimation(NoahTokens.Motion.travel(reduceMotion, NoahTokens.Motion.soft)) { detailsOpen.toggle() }
            if detailsOpen {
                Task {
                    try? await Task.sleep(for: .milliseconds(120))
                    withAnimation(NoahTokens.Motion.travel(reduceMotion, NoahTokens.Motion.soft)) { proxy.scrollTo("details", anchor: .top) }
                }
            }
        } label: {
            HStack {
                Text("Details").noahText(NoahTokens.Typography.label)
                Spacer()
                Image(systemName: "chevron.right").accessibilityHidden(true).font(.system(size: 15, weight: .semibold)).foregroundStyle(NoahTokens.Colors.muted)
                    .rotationEffect(.degrees(detailsOpen ? 90 : 0))
            }
            .frame(height: 48)
            .contentShape(Rectangle())
            .overlay(alignment: .top) { HomeHairline() }
            .overlay(alignment: .bottom) { HomeHairline() }
        }
        .buttonStyle(.plain)
        .padding(.top, 18)
        .id("details")
        .accessibilityValue(Text(detailsOpen ? "Expanded" : "Collapsed"))
        .accessibilityIdentifier("transaction-detail-details")
    }

    @ViewBuilder private var detailRows: some View {
        if let hash = transaction.paymentHash { small("Payment hash", hash, mono: true, copy: hash) }
        if transaction.direction == "incoming", let preimage = transaction.preimage {
            small("Preimage", preimage, mono: true, copy: preimage)
        }
        if let received = transaction.receivedOn, received.count == 1, let invoice = received.first?.destination,
           PaymentEncoding.invoice(invoice) != nil {
            small("Invoice", invoice, mono: true, copy: invoice)
        }
        if let offer = transaction.sentTo?.first(where: { $0.paymentMethod == "offer" })?.destination {
            small("Offer", offer, mono: true, copy: offer)
        }
        destinations("Sent to", transaction.sentTo ?? [])
        destinations("Received on", transaction.receivedOn ?? [])
        small("Payment ID", transaction.id, mono: true, copy: transaction.id)
        if let id = transaction.movementId { small("Movement ID", String(id), copy: String(id)) }
        if let kind = transaction.movementKindLabel { small("Movement type", L10n.text(kind, locale: language)) }
        if let name = transaction.subsystemName { small("Subsystem", "\(name) (\(transaction.subsystemKind ?? ""))", mono: true) }
        if let intended = transaction.intendedBalanceSat { small("Intended change", signed(intended), numeric: true) }
        if let effective = transaction.effectiveBalanceSat { small("Effective change", signed(effective), numeric: true) }
        if let anchor = transaction.chainAnchor, anchor != transaction.txid, anchor.split(separator: ":").first.map(String.init) != transaction.txid {
            small("Chain anchor", anchor, mono: true, copy: anchor)
        }
        if transaction.source == "onchain-wallet" || transaction.balanceChangeSat != nil {
            small("Chain status", L10n.text(transaction.hasConfirmation == true ? "Confirmed" : "Unconfirmed", locale: language))
            if let change = transaction.balanceChangeSat { small("Balance change", signed(change), numeric: true) }
            if let height = transaction.confirmationHeight { small("Block height", height.formatted(.number.locale(locale))) }
            if let hash = transaction.confirmationHash { small("Block hash", hash, mono: true, copy: hash) }
            if let hex = transaction.txHex { small("Raw transaction", hex, mono: true, copy: hex) }
        }
        coins("Spent coins", transaction.inputVtxos ?? [])
        coins("New coins", transaction.outputVtxos ?? [])
        coins("Exited coins", transaction.exitedVtxos ?? [])
        if let metadata = transaction.metadataJson, !metadata.isEmpty, metadata != "{}" {
            section("Metadata")
            small("JSON", metadata, mono: true, copy: metadata)
        }
    }

    /// Multi-destination payments keep "destination · amount".
    @ViewBuilder private func destinations(_ label: String, _ values: [HistoryTransaction.Destination]) -> some View {
        if !values.isEmpty {
            let text = values.count == 1 ? values[0].destination
                : values.map { "\($0.destination) · \(money($0.amount_sat))" }.joined(separator: "\n")
            small(label, text, mono: true, copy: values.count == 1 ? values[0].destination : text, wraps: values.count > 1)
        }
    }

    @ViewBuilder private func coins(_ title: String, _ ids: [String]) -> some View {
        if !ids.isEmpty {
            section(title)
            ForEach(Array(ids.enumerated()), id: \.offset) { index, id in
                Button { SettingsRoute.shared.page = .vtxo(id) } label: {
                    DetailLine(label: L10n.text("Coin {n}", index + 1, locale: language), value: id, compact: true, mono: true, chevron: true)
                }
                .buttonStyle(.plain)
                .accessibilityHint(Text("Opens coin details"))
            }
        }
    }

    private func section(_ title: String) -> some View {
        Text(LocalizedStringKey(title)).noahText(HomeText.section).foregroundStyle(NoahTokens.Colors.muted)
            .padding(.top, 18)
            .accessibilityAddTraits(.isHeader)
    }

    // MARK: Row building

    private func line(_ label: String, _ value: String, mono: Bool = false, numeric: Bool = false, glyph: String? = nil,
                      copy: String? = nil, explorer: URL? = nil, wraps: Bool = false, id: String = "", last: Bool = false) -> some View {
        DetailLine(label: L10n.text(label, locale: language), value: value, mono: mono, numeric: numeric, glyph: glyph,
                   wraps: wraps, copied: copy != nil && copied == label, onCopy: copy.map { value in { self.copy(value, label) } },
                   explorer: explorer, hairline: !last)
            .accessibilityIdentifier(id)
    }

    private func small(_ label: String, _ value: String, mono: Bool = false, numeric: Bool = false, copy: String? = nil,
                       wraps: Bool = false) -> some View {
        DetailLine(label: L10n.text(label, locale: language), value: value, compact: true, mono: mono, numeric: numeric,
                   wraps: wraps, copied: copy != nil && copied == label, onCopy: copy.map { value in { self.copy(value, label) } })
    }

    /// Home opens the page with Settings beneath, from Home's sheet or Activity alike.
    private func link(_ page: SettingsPage, _ label: String, _ value: String) -> some View {
        Button { SettingsRoute.shared.page = page } label: {
            DetailLine(label: L10n.text(label, locale: language), value: value, chevron: true)
        }
        .buttonStyle(.plain)
    }

    private func money(_ sats: Int64) -> String {
        hidden ? "₿••••" : BitcoinAmount.format(sats: sats, unit: app.amountUnit, locale: locale)
    }

    private func signed(_ sats: Int64) -> String {
        sats == 0 ? money(0) : (sats < 0 ? "−" : "+") + money(abs(sats))
    }

    // MARK: Chrome

    @ToolbarContentBuilder private var toolbar: some ToolbarContent {
        ToolbarItemGroup(placement: .topBarTrailing) {
            Button("Share", systemImage: "square.and.arrow.up", action: share)
                .onGeometryChange(for: CGRect.self) { $0.frame(in: .global) } action: { shareAnchor = $0 }
                .accessibilityIdentifier("transaction-detail-share")
            if closable {
                Button("Close", systemImage: "xmark", action: onClose)
                    .accessibilityIdentifier("transaction-detail-close")
            }
        }
    }

    /// Wide panes draw share and × themselves: the system bar's buttons would move to the Duo's far edge.
    private var paneBar: some View {
        GlassGroup(spacing: 8) {
            HStack(spacing: 8) {
                Spacer()
                Button(action: share) { ActivityCircleIcon(systemName: "square.and.arrow.up") }
                    .buttonStyle(HomePressStyle())
                    .onGeometryChange(for: CGRect.self) { $0.frame(in: .global) } action: { shareAnchor = $0 }
                    .accessibilityLabel(Text("Share"))
                    .accessibilityIdentifier("transaction-detail-share")
                if closable {
                    Button(action: onClose) { ActivityCircleIcon(systemName: "xmark") }
                        .buttonStyle(HomePressStyle())
                        .accessibilityLabel(Text("Close"))
                        .accessibilityIdentifier("transaction-detail-close")
                }
            }
        }
        .padding(.horizontal, 16).padding(.top, 14)
    }

    private func payAgainBar(_ details: RepeatPayment) -> some View {
        Button { haptics.play(.light); onRepeatPayment(details) } label: {
            Text("Pay again").frame(maxWidth: .infinity)
        }
        .buttonStyle(HomePillStyle(height: 56, font: NoahTokens.Typography.button))
        .padding(.horizontal, wide ? 32 : NoahTokens.Space.screen)
        .padding(.top, 24).padding(.bottom, wide ? 16 : 8)
        .background {
            LinearGradient(colors: [NoahTokens.Colors.background.opacity(0), NoahTokens.Colors.background],
                           startPoint: .top, endPoint: UnitPoint(x: 0.5, y: 0.46))
                .ignoresSafeArea()
        }
        .accessibilityIdentifier("repeat-payment-button")
    }

    private var toastLayer: some View {
        VStack {
            if let toast {
                HomeToastView(text: toast.text).id(toast.id).transition(.homeToast(reduceMotion))
            }
        }
        .padding(.top, 8)
        .animation(NoahTokens.Motion.travel(reduceMotion), value: toast)
        .allowsHitTesting(false)
    }

    // MARK: Actions

    private func load() async {
        await reads.load(transaction, blockTxid: detail.blockTxid, currency: app.preferredCurrency, history: history)
    }

    /// Local-only with a two-minute expiry, as before.
    private func copy(_ value: String, _ label: String) {
        UIPasteboard.general.setItems([[UIPasteboard.typeAutomatic: value]], options: [.localOnly: true, .expirationDate: Date().addingTimeInterval(120)])
        haptics.play(.success)
        withAnimation(NoahTokens.Motion.travel(reduceMotion, NoahTokens.Motion.bouncy)) { copied = label }
        toast = HomeToast(text: "Copied")
        Task {
            try? await Task.sleep(for: .seconds(1.2))
            if copied == label { withAnimation(NoahTokens.Motion.travel(reduceMotion)) { copied = nil } }
            try? await Task.sleep(for: .seconds(0.5))
            toast = nil
        }
    }

    private func share() {
        haptics.play(.light)
        // The receipt is the user's to share; it is never logged.
        let text = ActivityDetail.receipt(transaction, ownDomain: ownDomain, unit: app.amountUnit, locale: locale, date: dateText)
        // Always the measured button: Home's sheet on iPad presents it as a popover too.
        ActivityShare.present([text], anchor: shareAnchor)
    }
}

/// Phones (and Home's sheets) use the system bar; wide panes their own.
private struct DetailChrome<Bar: ToolbarContent>: ViewModifier {
    let wide: Bool
    let bar: AnyView
    @ToolbarContentBuilder let toolbar: () -> Bar

    func body(content: Content) -> some View {
        if wide {
            content.toolbar(.hidden, for: .navigationBar).safeAreaInset(edge: .top, spacing: 0) { bar }
        } else {
            content.toolbar(content: toolbar)
        }
    }
}

/// A payment's current price and chain reads, tied to the record they were read for. Wide panes reuse one
/// detail for every selection, so a read that finishes after the selection moved on, or after its task was
/// cancelled, never lands on another payment.
@MainActor @Observable
final class ActivityDetailReads {
    private(set) var recordID: String?
    private var price: Double?
    private var tip: UInt32?
    private var status: ChainTransactionStatus?

    func price(for id: String) -> Double? { recordID == id ? price : nil }
    func tip(for id: String) -> UInt32? { recordID == id ? tip : nil }
    func status(for id: String) -> ChainTransactionStatus? { recordID == id ? status : nil }

    /// A new selection starts with nothing.
    func select(_ id: String) {
        guard recordID != id else { return }
        recordID = id
        price = nil
        tip = nil
        status = nil
    }

    func load(_ transaction: HistoryTransaction, blockTxid: String?, currency: String, history: HistoryModel) async {
        let id = transaction.id
        select(id)
        func current() -> Bool { !Task.isCancelled && recordID == id }
        let today = transaction.timestamp.map { Calendar.current.isDateInToday($0) } ?? true
        if transaction.isPending || today || transaction.btcPrice == nil {
            let quote = await history.currentPrice(currency: currency)
            guard current() else { return }
            price = quote
        }
        guard let blockTxid else { return }
        let state = await history.chainState(txid: transaction.source == "onchain-wallet" ? nil : blockTxid)
        guard current() else { return }
        tip = state.tip
        status = state.status
    }
}

enum ActivityDetailText {
    static let amount = NoahTokens.TextStyle(size: 46, lineHeight: nil, weight: .semibold, display: true, tracking: -0.025, textStyle: .largeTitle)
    static let mono = Font.system(size: 14.5, weight: .regular, design: .monospaced)
    static let monoSmall = Font.system(size: 13.5, weight: .regular, design: .monospaced)
}

/// One row of the table: a muted label, then the value with its glyph, copy, explorer or chevron.
/// Settings' detail rows use it too.
struct DetailLine: View {
    let label: String
    let value: String
    var compact = false
    var mono = false
    var numeric = false
    var glyph: String?
    var wraps = false
    var chevron = false
    var copied = false
    var onCopy: (() -> Void)?
    var explorer: URL?
    var hairline = true
    var failure = false
    var glyphColor: Color?

    var body: some View {
        HStack(alignment: wraps ? .firstTextBaseline : .center, spacing: 18) {
            Text(verbatim: label)
                .noahText(compact ? NoahTokens.Typography.sub : NoahTokens.Typography.body)
                .foregroundStyle(NoahTokens.Colors.muted)
                .fixedSize()
            Spacer(minLength: 0)
            trailing
        }
        .frame(minHeight: compact ? 44 : 48)
        .padding(.vertical, wraps ? 10 : 0)
        .contentShape(Rectangle())
        .overlay(alignment: .bottom) { if hairline { HomeHairline() } }
        .accessibilityElement(children: .combine)
    }

    private var trailing: some View {
        HStack(spacing: 8) {
            if let glyph {
                Image(systemName: glyph).accessibilityHidden(true).font(.system(size: 14, weight: .medium))
                    .foregroundStyle(glyphColor ?? NoahTokens.Colors.foreground)
            }
            valueText.foregroundStyle(failure ? NoahTokens.Colors.failure : NoahTokens.Colors.foreground)
            if let onCopy {
                Button(action: onCopy) {
                    Image(systemName: copied ? "checkmark" : "doc.on.doc")
                        .font(.system(size: 15, weight: copied ? .semibold : .regular))
                        .contentTransition(.symbolEffect(.replace))
                        .frame(width: 30, height: 30)
                }
                .buttonStyle(.plain)
                .foregroundStyle(NoahTokens.Colors.muted)
                .padding(.trailing, -6)
                .accessibilityLabel(Text(L10n.text("Copy {label}", label)))
            }
            if let explorer {
                SwiftUI.Link(destination: explorer) {
                    Image(systemName: "arrow.up.right.square").font(.system(size: 15)).frame(width: 30, height: 30)
                }
                .foregroundStyle(NoahTokens.Colors.muted)
                .padding(.trailing, -6)
                .accessibilityLabel(Text("View in explorer"))
            }
            if chevron {
                Image(systemName: "chevron.right").accessibilityHidden(true).font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(NoahTokens.Colors.muted)
            }
        }
        .layoutPriority(1)
    }

    @ViewBuilder private var valueText: some View {
        let text = Text(verbatim: value)
        if mono {
            text.font(compact ? ActivityDetailText.monoSmall : ActivityDetailText.mono)
                .lineLimit(wraps ? nil : 1).truncationMode(.middle)
                .textSelection(.enabled)
                .multilineTextAlignment(.trailing)
        } else {
            text.noahText(numeric ? HomeText.amount : compact ? NoahTokens.Typography.control : NoahTokens.Typography.label)
                .monospacedDigit()
                .lineLimit(wraps ? nil : 1).truncationMode(.middle)
                .multilineTextAlignment(.trailing)
                .textSelection(.enabled)
        }
    }
}

/// One coin, read by ID: found, missing from the wallet, or a read that failed (try again). Pull to
/// refresh and each history read start lookups that can overlap: only the newest one publishes, and a
/// cancelled one writes nothing.
@MainActor @Observable
final class ActivityCoinRead {
    enum State: Equatable { case loading, coin(VtxoModel), missing, failed }
    private(set) var state = State.loading
    private var generation = 0

    func load(_ id: String, history: HistoryModel) async {
        generation += 1
        let request = generation
        let lookup = await history.coin(id)
        guard !Task.isCancelled, request == generation else { return }
        switch lookup {
        case .found(let coin): state = .coin(coin)
        case .missing: state = .missing
        case .failed: state = .failed
        }
    }
}

/// The orange dot that pulses beside a pending status line; still under Reduce Motion.
private struct ActivityOrangePulse: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        Group {
            if reduceMotion {
                Circle().fill(NoahTokens.Colors.orange)
            } else {
                Circle().fill(NoahTokens.Colors.orange)
                    .phaseAnimator([false, true]) { dot, dim in
                        dot.opacity(dim ? 0.25 : 1).scaleEffect(dim ? 0.7 : 1)
                    } animation: { _ in .easeInOut(duration: 0.8) }
            }
        }
        .frame(width: 7, height: 7)
        .accessibilityHidden(true)
    }
}

/// Send's block row for a pending on-chain payment: your transaction waits in "next", then
/// its block fills. Data from today's chain status only.
struct ActivityBlockRow: View {
    let tip: UInt32?
    let confirmedHeight: UInt32?
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.locale) private var locale
    @State private var start = Date()

    var body: some View {
        TimelineView(.animation(paused: reduceMotion || scenePhase != .active || confirmedHeight != nil)) { timeline in
            Canvas { context, size in
                let row = ChainBlockRow(x0: 0, y: 0, size: 38, tip: tip, confirmedHeight: confirmedHeight, heldAt: 0,
                                        confirmedAt: confirmedHeight == nil ? nil : -10, blinking: true,
                                        nextLabel: L10n.text("next", locale: locale.identifier), locale: locale)
                row.draw(MotionCanvas(context: context, size: size, viewBox: CGRect(x: -8, y: 0, width: 204, height: 62),
                                      colors: .theme(colorScheme)),
                         time: MotionTime(t: timeline.date.timeIntervalSince(start), reduceMotion: reduceMotion), opacity: 1, dot: true)
            }
        }
        .accessibilityHidden(true)
    }
}
