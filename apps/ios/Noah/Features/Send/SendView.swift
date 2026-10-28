import SwiftUI

/// Send (docs/native-rewrite/design/send-receive.md): Amount → To → Method → Balance →
/// Review → Sending, on today's `SendModel` and payment controller. Compact windows get
/// one column; wide ones (the iPhone Duo open or half-open) split into two panes at the
/// centre, where it folds.
struct SendView: View {
    @Environment(\.locale) private var locale
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(AppModel.self) private var app
    @Environment(HistoryModel.self) private var history
    @Environment(\.sendReceiveFixture) private var fixture
    @State private var model: SendModel
    @State private var clock = SendMotionClock()
    @State private var holdGate = SendHoldGate()
    @State private var window = OnboardingLayout(size: .zero, insets: EdgeInsets())
    @State private var haptics = OnboardingHaptics()
    @State private var shake = 0
    /// An amount typed before an invoice set its own (for the review's note).
    @State private var typedAmount: UInt64?
    /// The screen before this one, for the slide's direction.
    @State private var lastScreen = SendScreen.amount
    @State private var railFees: [SendRail: UInt64] = [:]
    /// The chain tip, read with each receipt check, for the block row.
    @State private var chainTip: UInt32?
    @FocusState private var focus: Field?

    let isActive: Bool
    let onLeave: () -> Void
    init(model: SendModel = SendModel(), isActive: Bool = true, onLeave: @escaping () -> Void) {
        _model = State(initialValue: model)
        self.isActive = isActive
        self.onLeave = onLeave
    }

    private var visible: Bool { isActive && scenePhase == .active && !app.isWalletSuspended }
    private var receiptRefreshID: String? { visible ? model.controller.attempt?.receipt?.txid : nil }
    private var screen: SendScreen { SendScreen.of(model) }
    private var wide: Bool { window.wide }
    private var ownDomain: String { LightningIdentity.domain(for: .current) }
    private var unit: BitcoinAmountUnit { app.amountUnit }

    var body: some View {
        ZStack {
            NoahTokens.Colors.background.ignoresSafeArea()
            screenView
                .id(screen)
                .transition(reduceMotion ? .opacity : .asymmetric(
                    insertion: .opacity.combined(with: .offset(x: screen >= lastScreen ? 28 : -28)), removal: .opacity))
        }
        .animation(NoahTokens.Motion.travel(reduceMotion), value: screen)
        .toolbar(.hidden, for: .navigationBar)
        .onboardingWindow($window)
        .onboardingHaptics(haptics)
        .disabled(model.busy && screen != .sending && screen != .review)
        .task {
            model.fiatCurrency = app.preferredCurrency
            await model.load()
            if let summary = fixture?.summary {
                model.arkBalance = summary.balance.ark.spendable
                model.onchainBalance = summary.balance.onchain.confirmed
                model.onchainTotal = summary.balance.onchain.total
            }
            if let response = try? await AppServices.shared.client.fiatPrices(.init(body: .json(.init()))).ok.body.json {
                model.btcPrice = response.rates.additionalProperties[app.preferredCurrency]
            }
        }
        .task(id: visible) {
            // Arriving at Send checks a payment still to settle, on the 30 s clock it shares.
            guard visible else { return }
            await model.entryStatusCheck()
        }
        .task(id: receiptRefreshID) {
            guard receiptRefreshID != nil else { return }
            // The block row's tip on returning, without waiting for the next check.
            if chainTip == nil, let next = try? await WalletSession.shared.chainTip(), !Task.isCancelled { chainTip = next }
            await SendReceiptRefresh.observe(wait: { model.untilAutomaticCheck() }, tip: {
                if let next = try? await WalletSession.shared.chainTip(), !Task.isCancelled { chainTip = next }
            }, check: { await model.automaticStatusCheck() })
        }
        .onChange(of: app.summary) { _, summary in
            model.arkBalance = summary?.balance.ark.spendable ?? 0
            model.onchainBalance = summary?.balance.onchain.confirmed ?? 0
            model.onchainTotal = summary?.balance.onchain.total ?? 0
        }
        .onChange(of: screen) { _, new in lastScreen = new }
        .onChange(of: model.stage) { _, _ in focus = nil }
        .onChange(of: model.controller.state) { _, state in if state != .ready { focus = nil } }
        .onChange(of: scenePhase) { _, phase in if phase != .active { cancelReview() } }
        .onDisappear { cancelReview() }
        .fullScreenCover(isPresented: $model.scanning) {
            SendScanner { value in
                model.scanning = false
                captureTypedAmount()
                Task { await model.importRecipient(value) }
            }
        }
    }

    @ViewBuilder private var screenView: some View {
        switch screen {
        case .amount: amountScreen
        case .recipient: recipientScreen
        case .method: methodScreen
        case .source: sourceScreen
        case .review: reviewScreen
        case .sending: sendingScreen
        }
    }

    // MARK: Layout

    /// Two panes split at the window centre when wide, one column otherwise.
    private func panes(@ViewBuilder leading: () -> some View, @ViewBuilder trailing: () -> some View) -> some View {
        OnboardingPanes(wide: wide, column: .leadingFits, sideInsets: (window.insets.leading, window.insets.trailing)) {
            leading()
                .padding(.horizontal, wide ? 34 : 20)
                // The wide tab bar sits at the bottom of the leading pane.
                .padding(.bottom, wide ? 96 : 0)
                .frame(maxWidth: wide ? OnboardingLayout.paneContentWidth + 68 : nil)
                .frame(maxWidth: .infinity, maxHeight: wide ? .infinity : nil, alignment: .top)
            trailing()
                .padding(.leading, wide ? 40 : 20).padding(.trailing, wide ? 34 : 20)
                // Wide: the trailing content starts level with the leading content under its nav row.
                .padding(.top, wide ? 66 : 0)
                .padding(.bottom, wide ? 24 : 12)
                .frame(maxWidth: wide ? OnboardingLayout.paneContentWidth + 74 : nil)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        }
    }

    /// Back, a title and scan (prototype `.nav`).
    private func nav(back: Bool = true, title: LocalizedStringKey? = nil, scan: Bool = true, paste: Bool = false) -> some View {
        HStack(spacing: 0) {
            if back {
                SendIconButton(systemName: "chevron.left", label: "Back", id: "send-back", action: goBack)
            } else {
                Color.clear.frame(width: NoahTokens.Size.hitTarget, height: NoahTokens.Size.hitTarget)
            }
            Spacer(minLength: 8)
            if let title { Text(title).noahText(SendText.navTitle).foregroundStyle(NoahTokens.Colors.foreground) }
            Spacer(minLength: 8)
            if paste {
                // The system paste control reads the clipboard without a prompt.
                PasteButton(payloadType: String.self) { values in
                    guard let value = values.first else { return }
                    importPasted(value)
                }
                .labelStyle(.titleOnly)
                .buttonBorderShape(.capsule)
                .controlSize(.small)
                .tint(NoahTokens.Colors.foreground)
                .accessibilityIdentifier("send-paste-from-amount")
                .padding(.trailing, 6)
            }
            if scan {
                SendIconButton(systemName: "qrcode.viewfinder", label: "Scan QR code", id: "send-scan") {
                    focus = nil
                    model.scanning = true
                }
            } else {
                Color.clear.frame(width: NoahTokens.Size.hitTarget, height: NoahTokens.Size.hitTarget)
            }
        }
        .frame(height: NoahTokens.Size.hitTarget)
        .padding(.horizontal, -2)
        .padding(.top, wide ? 14 : 4)
    }

    private var errorText: String? {
        model.error.map { model.destinationError?.message(locale: locale.identifier) ?? L10n.text($0, locale: locale.identifier) }
    }

    // MARK: Amount

    /// The most one balance can pay; Max and on-chain sources pick between them later.
    private var available: UInt64 { max(model.arkBalance, model.onchainBalance) }
    /// Funds may already be held for an in-progress invoice, so it's never over the balance.
    private var payingPendingInvoice: Bool {
        let invoice = model.parsed.bip321?["lightningInvoice"] ?? model.destination
        return PaymentEncoding.invoice(SendDestination.cleaned(invoice))?.hash.map { model.pendingLightningHashes.contains($0) } ?? false
    }
    private var overBalance: Bool { (fixture?.summary ?? app.summary) != nil && model.sats > available && !payingPendingInvoice }
    private var canMax: Bool {
        model.parsed.isAmountEditable
            && (model.destination.isEmpty || SendFlow.canMax(type: model.parsed.destinationType, request: model.parsed.bip321))
            && !(model.arkBalance == 0 && model.onchainTotal == 0)
    }

    private func format(_ sats: UInt64) -> String {
        BitcoinAmount.format(sats: Int64(clamping: sats), unit: unit, locale: locale)
    }

    private var amountDisplay: (text: String, empty: Bool) {
        if model.useFiat {
            let formatter = NumberFormatter()
            formatter.numberStyle = .currency
            formatter.currencyCode = model.fiatCurrency
            formatter.locale = locale
            return ((formatter.currencySymbol ?? "") + (model.amount.isEmpty ? "0" : model.amount), model.amount.isEmpty)
        }
        return (format(model.sats), model.amount.isEmpty)
    }

    private var fiatLine: String {
        if model.useFiat { return format(model.sats) }
        guard let price = model.btcPrice else {
            return L10n.text("{currency} rate unavailable", model.fiatCurrency, locale: locale.identifier)
        }
        return FiatCurrency.display(sats: Int64(clamping: model.sats), btcPrice: price, currency: model.fiatCurrency, locale: locale)
    }

    private var amountScreen: some View {
        let short = window.size.height < 760
        return panes {
            VStack(spacing: 0) {
                nav(back: model.progress.entry != .amountFirst && model.progress.recipientConfirmed, scan: true, paste: true)
                // A recipient chosen first (or pasted here) shows above the amount.
                if model.progress.recipientConfirmed && !model.destination.isEmpty {
                    recipientLine.frame(maxWidth: .infinity).padding(.top, 6).accessibilityIdentifier("send-amount-recipient")
                }
                if wide { Spacer(minLength: 0) }
                balanceLines(short: short)
                SendAmountDisplay(text: amountDisplay.text, empty: amountDisplay.empty, value: Double(model.sats), shake: shake)
                    .frame(height: 72)
                    .padding(.top, wide || short ? 14 : 30)
                    .accessibilityIdentifier("send-amount")
                amountControls
                if !model.parsed.isAmountEditable {
                    Text("Amount set by payment request").noahText(HomeText.footnote).foregroundStyle(NoahTokens.Colors.muted)
                        .padding(.top, 8)
                }
                if let errorText { error(errorText).multilineTextAlignment(.center).padding(.top, 8) }
                if wide { Spacer(minLength: 0) }
            }
        } trailing: {
            GeometryReader { proxy in
                VStack(spacing: 0) {
                    Spacer(minLength: 0)
                    if model.parsed.isAmountEditable {
                        SendKeypad(amount: $model.amount, decimal: model.useFiat,
                                   keyHeight: min(60, max(40, (proxy.size.height - 80) / 4 - 2))) { _ in keyPressed() }
                    }
                    Button {
                        haptics.play(.light)
                        captureTypedAmount()
                        Task { await model.nextFromAmount() }
                    } label: {
                        Text("Next").frame(maxWidth: .infinity)
                    }
                    .buttonStyle(HomePillStyle(height: 52, font: NoahTokens.Typography.button))
                    .disabled(overBalance || model.busy)
                    .accessibilityIdentifier("send-continue")
                    .padding(.top, 12)
                    if wide { Spacer(minLength: 0) }
                }
                .frame(maxHeight: .infinity)
            }
        }
    }

    @ViewBuilder private func balanceLines(short: Bool) -> some View {
        Group {
            if overBalance {
                Text(verbatim: L10n.text("Only {amount} available", format(available), locale: locale.identifier))
                    .foregroundStyle(NoahTokens.Colors.failure)
            } else {
                Text(verbatim: L10n.text("{amount} available", format(available), locale: locale.identifier))
                    .foregroundStyle(NoahTokens.Colors.muted)
            }
        }
        .noahText(NoahTokens.Typography.sub)
        .frame(height: 20)
        .padding(.top, wide || short ? 14 : 28)
        .accessibilityIdentifier("send-available")
        if let balance = app.summary?.balance,
           let message = balance.pendingRoundMessage(amount: format(balance.ark.pendingInRound)) {
            Text(LocalizedStringKey(message)).noahText(HomeText.footnote).foregroundStyle(NoahTokens.Colors.muted)
                .multilineTextAlignment(.center).padding(.top, 4)
        }
    }

    @ViewBuilder private var amountControls: some View {
        Button {
            haptics.play(.selection)
            model.toggleCurrency()
        } label: {
            HStack(spacing: 8) {
                Text(verbatim: fiatLine).noahText(NoahTokens.Typography.body)
                Image(systemName: "arrow.up.arrow.down").font(.system(size: 14, weight: .medium))
            }
            .foregroundStyle(NoahTokens.Colors.muted)
            .frame(minHeight: NoahTokens.Size.hitTarget)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(model.btcPrice == nil)
        .accessibilityLabel(Text("Switch amount currency"))
        .accessibilityValue(Text(verbatim: fiatLine))
        .accessibilityIdentifier("send-currency-toggle")
        .padding(.top, 2)
        if canMax {
            Button("Max") {
                haptics.play(.light)
                model.maxSend()
            }
            .buttonStyle(SendChipStyle())
            .accessibilityIdentifier("send-max")
            .padding(.top, 4)
        }
    }

    private func keyPressed() {
        haptics.play(.selection)
        if overBalance {
            haptics.play(.error)
            shake += 1
        }
    }

    // MARK: To
}

/// Recipient, method, balance, review and sending screens.
extension SendView {
    private var recipientLabel: SendRecipientLabel { .of(destination: model.destination, ownDomain: ownDomain) }
    private var parsedLive: SendDestination { SendDestination.parse(model.destination) }

    private var summaryAmount: String? {
        if model.isMax { return L10n.text("Maximum available", locale: locale.identifier) }
        return model.sats > 0 ? format(model.sats) : nil
    }

    private var recipientScreen: some View {
        panes {
            VStack(alignment: .leading, spacing: 0) {
                nav(title: "Send")
                if let summaryAmount {
                    Text(verbatim: summaryAmount).noahText(SendText.summary).foregroundStyle(NoahTokens.Colors.foreground)
                        .lineLimit(1).minimumScaleFactor(0.5).padding(.top, 6)
                        .accessibilityIdentifier("send-summary-amount")
                }
                toField.padding(.top, 18)
                if let message = parsedLive.error?.message(locale: locale.identifier) ?? errorText {
                    error(message).padding(.top, 10)
                }
                if let route = model.matchingRoute, route.commentAllowed > 0 { noteField(route).padding(.top, 12) }
            }
        } trailing: {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    if SendDestination.cleaned(model.destination).isEmpty {
                        let recents = SendRecent.from(history.transactions, ownDomain: ownDomain)
                        if !recents.isEmpty {
                            SendSectionTitle(title: "Recent").padding(.top, wide ? 0 : 22).padding(.bottom, 8)
                            recentCircles(recents)
                        }
                    } else {
                        SendSectionTitle(title: "Results").padding(.top, wide ? 0 : 22).padding(.bottom, 2)
                        results
                    }
                }
            }
            .scrollDismissesKeyboard(.interactively)
            .scrollBounceBehavior(.basedOnSize)
        }
        .task(id: model.destination) {
            async let suggestions: Void = loadSuggestions()
            await model.previewRecipient()
            await suggestions
        }
    }

    private var toField: some View {
        HStack(spacing: 10) {
            Text("To").noahText(NoahTokens.Typography.label).foregroundStyle(NoahTokens.Colors.muted)
            TextField("Name, address or invoice", text: $model.destination)
                .noahText(NoahTokens.Typography.body)
                .textInputAutocapitalization(.never).autocorrectionDisabled()
                .submitLabel(.next)
                .onSubmit(acceptRecipient)
                .focused($focus, equals: .recipient)
                .accessibilityIdentifier("send-recipient")
            if !model.destination.isEmpty {
                Button { model.destination = "" } label: {
                    Image(systemName: "xmark.circle.fill").font(.system(size: 17)).foregroundStyle(NoahTokens.Colors.muted)
                        .frame(width: 32, height: 44)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Text("Clear"))
            }
            PasteButton(payloadType: String.self) { values in
                guard let value = values.first else { return }
                importPasted(value)
            }
            .labelStyle(.titleOnly)
            .buttonBorderShape(.capsule)
            .controlSize(.small)
            .tint(NoahTokens.Colors.foreground)
            .accessibilityIdentifier("send-paste")
        }
        .padding(.leading, 18).padding(.trailing, 8)
        .frame(minHeight: 52)
        .glassCompat(in: Capsule())
        .contentShape(Capsule())
        .onTapGesture { focus = .recipient }
    }

    private func noteField(_ route: SendRecipientRoute) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 10) {
                TextField("Add a note", text: $model.comment, axis: .vertical)
                    .noahText(NoahTokens.Typography.body)
                    .lineLimit(1...3)
                    .focused($focus, equals: .note)
                Text(verbatim: "\(model.comment.utf16.count)/\(route.commentAllowed)")
                    .noahText(HomeText.footnote).monospacedDigit()
                    .foregroundStyle(model.comment.utf16.count > route.commentAllowed ? NoahTokens.Colors.failure : NoahTokens.Colors.muted)
            }
            .padding(.horizontal, 18).padding(.vertical, 14)
            .glassCompat(in: RoundedRectangle(cornerRadius: 22, style: .continuous))
            if !model.comment.isEmpty && route.arkAddress != nil {
                Text("Adding a note will send this payment over Lightning.").noahText(HomeText.footnote)
                    .foregroundStyle(NoahTokens.Colors.muted).padding(.horizontal, 4)
            }
        }
    }

    private func recentCircles(_ recents: [SendRecent]) -> some View {
        HStack(alignment: .top, spacing: 16) {
            ForEach(recents) { recent in
                Button {
                    haptics.play(.light)
                    focus = nil
                    model.destination = recent.destination
                    acceptRecipient()
                } label: {
                    VStack(spacing: 6) {
                        SendAvatar(label: recent.label, size: 56)
                        Text(verbatim: recent.label.name.split(separator: "@").first.map(String.init) ?? recent.label.name)
                            .noahText(SendText.small).foregroundStyle(NoahTokens.Colors.foreground)
                            .lineLimit(1).truncationMode(.middle)
                    }
                    .frame(width: 60)
                    .contentShape(Rectangle())
                }
                .buttonStyle(HomePressStyle())
                .accessibilityLabel(Text(verbatim: recent.label.name))
            }
            Spacer(minLength: 0)
        }
    }

    /// While typing: what the text is, today's suggestions and matching recents.
    @ViewBuilder private var results: some View {
        let typed = SendDestination.cleaned(model.destination)
        let parsed = parsedLive
        let suggestions = model.suggestions.filter { $0.lowercased() != typed.lowercased() }
        let recents = SendRecent.matching(SendRecent.from(history.transactions, ownDomain: ownDomain, limit: 12), query: typed)
            .filter { $0.destination.lowercased() != typed.lowercased() && !suggestions.contains($0.destination) }
        // An invoice already says what it is; its line says what it asks for (prototype `REC.inv`).
        let asks = parsed.destinationType == "lightning" ? parsed.amount.map {
            // swiftlint:disable:next legacy_objc_type - Decimal converts to an integer only through NSDecimalNumber
            L10n.text("Asks for {amount}", format(NSDecimalNumber(decimal: $0).uint64Value), locale: locale.identifier)
        } : nil
        let primary = parsed.error == nil ? asks ?? Self.typeName(parsed.destinationType, destination: typed, ownDomain: ownDomain) : nil
        VStack(spacing: 0) {
            if let primary {
                resultRow(label: recipientLabel, type: primary, busy: model.busy || model.resolvingRecipient,
                          verified: model.matchingRoute != nil, id: "send-result-primary") { acceptRecipient() }
            }
            ForEach(suggestions, id: \.self) { suggestion in
                resultRow(label: .of(destination: suggestion, ownDomain: ownDomain),
                          type: Self.typeName("lnurl", destination: suggestion, ownDomain: ownDomain) ?? "Lightning address") {
                    model.destination = suggestion
                    acceptRecipient()
                }
            }
            ForEach(recents) { recent in
                resultRow(label: recent.label, type: Self.typeName(SendDestination.parse(recent.destination).destinationType,
                                                                   destination: recent.destination, ownDomain: ownDomain) ?? "") {
                    model.destination = recent.destination
                    acceptRecipient()
                }
            }
            if primary == nil && suggestions.isEmpty && recents.isEmpty && parsed.error == nil && model.error == nil {
                Text("No matches").noahText(HomeText.footnote).foregroundStyle(NoahTokens.Colors.muted)
                    .frame(maxWidth: .infinity, alignment: .leading).padding(.top, 12)
            }
        }
    }

    private func resultRow(label: SendRecipientLabel, type: String, busy: Bool = false, verified: Bool = false,
                           id: String = "", action: @escaping () -> Void) -> some View {
        Button {
            haptics.play(.light)
            focus = nil
            action()
        } label: {
            HStack(spacing: 14) {
                SendAvatar(label: label)
                VStack(alignment: .leading, spacing: 0) {
                    Group { if label.nameIsVerbatim { Text(verbatim: label.name) } else { Text(LocalizedStringKey(label.name)) } }
                        .noahText(NoahTokens.Typography.body).foregroundStyle(NoahTokens.Colors.foreground)
                        .lineLimit(1).truncationMode(.middle)
                    Text(LocalizedStringKey(type)).noahText(HomeText.footnote).foregroundStyle(NoahTokens.Colors.muted).lineLimit(1)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                if busy { ProgressView() } else if verified {
                    Image(systemName: "checkmark.circle.fill").foregroundStyle(NoahTokens.Colors.foreground)
                        .accessibilityLabel(Text("Recipient verified"))
                }
            }
            .frame(height: 64)
            .overlay(alignment: .bottom) { Rectangle().fill(NoahTokens.Colors.hairline).frame(height: 1).padding(.leading, 58) }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(busy && model.busy)
        .accessibilityIdentifier(id)
    }

    private func acceptRecipient() {
        focus = nil
        captureTypedAmount()
        Task { await model.continueStage() }
    }

    /// Paste on Amount or To: today's import, keeping the amount you typed for the review's note.
    private func importPasted(_ value: String) {
        focus = nil
        captureTypedAmount()
        Task { await model.importRecipient(value) }
    }

    /// Keeps the amount you typed for the review's invoice note. Taken on every way to a
    /// recipient (Next, a scan, a paste or a tapped result), before one can set its own amount.
    private func captureTypedAmount() { typedAmount = Self.typedAmount(model, keeping: typedAmount) }

    private func loadSuggestions() async {
        model.suggestions = []
        guard let query = SendRecipientService.suggestionQuery(model.destination, domain: ownDomain) else { return }
        do {
            try await Task.sleep(for: .milliseconds(200))
            let result = try await AppServices.shared.client.lnAddressSuggestions(.init(body: .json(.init(query: query)))).ok.body.json
            guard !Task.isCancelled else { return }
            let own = SettingsStorage.shared.persistedState(PersistedServerState.self, key: PersistedServerState.key)?
                .lightningAddress?.lowercased()
            model.suggestions = result.suggestions.filter { $0.lowercased() != own }
        } catch { /* Suggestions are optional; never log the recipient. */ }
    }

    // MARK: Method and balance

    private var amountForTitle: String { model.isMax ? L10n.text("Maximum available", locale: locale.identifier) : format(model.sats) }

    private var methodScreen: some View {
        panes {
            VStack(alignment: .leading, spacing: 0) {
                nav(scan: false)
                Text(verbatim: L10n.text("How to send {amount}", amountForTitle, locale: locale.identifier))
                    .noahText(SendText.big).foregroundStyle(NoahTokens.Colors.foreground)
                    .lineLimit(2).minimumScaleFactor(0.6).padding(.top, 18)
                recipientLine.padding(.top, 12)
            }
        } trailing: {
            ScrollView {
                VStack(spacing: 10) {
                    let best = SendFlow.recommend(model.rails, availability: model.availability)
                    ForEach(model.rails, id: \.self) { rail in
                        railCard(rail, best: rail == best && model.rails.count > 1)
                    }
                    if let errorText { error(errorText).frame(maxWidth: .infinity, alignment: .leading) }
                }
                .padding(.top, wide ? 0 : 22)
            }
            .scrollBounceBehavior(.basedOnSize)
        }
        .task(id: "\(model.sats):\(model.destination):\(model.comment.isEmpty)") { await loadRailFees() }
    }

    private var recipientLine: some View {
        let label = recipientLabel
        return HStack(spacing: 10) {
            SendAvatar(label: label, size: 28)
            Group { if label.nameIsVerbatim { Text(verbatim: label.name) } else { Text(LocalizedStringKey(label.name)) } }
                .noahText(NoahTokens.Typography.body).foregroundStyle(NoahTokens.Colors.muted).lineLimit(1).truncationMode(.middle)
        }
    }

    private func railSubtitle(_ rail: SendRail) -> String {
        let language = locale.identifier
        guard model.availability[rail.rawValue] == true else { return L10n.text("Insufficient funds for this payment method.", locale: language) }
        // Today's estimates, marked as such; a rail without one (still loading or failed) shows only its speed.
        let fee = railFees[rail].map { "≈ " + format($0) }
        switch rail {
        case .ark:
            guard let fee else { return L10n.text("Instantly", locale: language) }
            return railFees[rail] == 0 ? L10n.text("Instant · free", locale: language) : L10n.text("Instant · {fee} fee", fee, locale: language)
        case .lightning:
            return fee.map { L10n.text("Seconds · {fee} fee", $0, locale: language) } ?? L10n.text("In seconds", locale: language)
        case .onchain:
            return fee.map { L10n.text("About 20 min · {fee} fee", $0, locale: language) } ?? L10n.text("In about 20 minutes", locale: language)
        }
    }

    private func railCard(_ rail: SendRail, best: Bool) -> some View {
        let enabled = model.availability[rail.rawValue] == true
        return Button {
            haptics.play(.selection)
            model.progress.selectedRail = rail
            Task { await model.continueStage() }
        } label: {
            HStack(spacing: 14) {
                Image(systemName: Self.glyph(rail)).accessibilityHidden(true).font(.system(size: 18, weight: .medium))
                    .frame(width: 40, height: 40)
                    .background(NoahTokens.Colors.background, in: Circle())
                VStack(alignment: .leading, spacing: 0) {
                    Text(LocalizedStringKey(rail.label)).noahText(NoahTokens.Typography.button)
                    Text(verbatim: railSubtitle(rail)).noahText(HomeText.footnote).foregroundStyle(NoahTokens.Colors.muted)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                if best {
                    Text("Best").noahText(HomeText.badge).foregroundStyle(NoahTokens.Colors.orangeText)
                        .padding(.horizontal, 8).padding(.vertical, 3)
                        .background(NoahTokens.Colors.orangeTint, in: Capsule())
                }
            }
            .foregroundStyle(NoahTokens.Colors.foreground)
            .padding(.vertical, 14).padding(.horizontal, 16)
            .contentShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
            .glassCompat(in: RoundedRectangle(cornerRadius: 22, style: .continuous), interactive: enabled)
        }
        .buttonStyle(HomePressStyle(scale: 0.98))
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.4)
        .accessibilityIdentifier("send-rail-\(rail.rawValue)")
    }

    private func loadRailFees() async {
        railFees = [:]
        var request = model.parsed.bip321 ?? [:]
        if model.parsed.destinationType == "lnurl", let route = model.matchingRoute {
            if let ark = route.arkAddress { request["arkAddress"] = ark }
            request["lightningInvoice"] = route.destination
        }
        guard !request.isEmpty, model.sats > 0 else { return }
        if let fees = fixture?.fees, !fees.isEmpty { railFees = fees; return }
        let fees = await WalletSession.shared.sendRailEstimates(amount: model.sats, request: request)
        guard !Task.isCancelled else { return }
        railFees = fees
    }

    private var sourceScreen: some View {
        panes {
            VStack(alignment: .leading, spacing: 0) {
                nav(scan: false)
                Text("Choose balance").noahText(SendText.big).foregroundStyle(NoahTokens.Colors.foreground)
                    .lineLimit(2).minimumScaleFactor(0.6).padding(.top, 18)
                if model.isMax || model.sats > 0 {
                    Text(verbatim: amountForTitle).noahText(NoahTokens.Typography.body).foregroundStyle(NoahTokens.Colors.muted)
                        .padding(.top, 12)
                }
            }
        } trailing: {
            ScrollView {
                VStack(spacing: 10) {
                    ForEach(SendSource.allCases, id: \.self) { source in sourceCard(source) }
                    if let errorText { error(errorText).frame(maxWidth: .infinity, alignment: .leading) }
                }
                .padding(.top, wide ? 0 : 22)
            }
            .scrollBounceBehavior(.basedOnSize)
        }
    }

    private func sourceCard(_ source: SendSource) -> some View {
        let enabled = model.sources.contains(source)
        let balance = source == .offchain ? model.arkBalance : model.onchainBalance
        return Button {
            haptics.play(.selection)
            model.source = source
            Task { await model.continueStage() }
        } label: {
            HStack(spacing: 14) {
                Image(systemName: source == .offchain ? "sailboat" : "link").accessibilityHidden(true).font(.system(size: 18, weight: .medium))
                    .frame(width: 40, height: 40)
                    .background(NoahTokens.Colors.background, in: Circle())
                VStack(alignment: .leading, spacing: 0) {
                    Text(LocalizedStringKey(source.label)).noahText(NoahTokens.Typography.button)
                    Text(verbatim: L10n.text("{amount} available", format(balance), locale: locale.identifier))
                        .noahText(HomeText.footnote).foregroundStyle(NoahTokens.Colors.muted)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .foregroundStyle(NoahTokens.Colors.foreground)
            .padding(.vertical, 14).padding(.horizontal, 16)
            .contentShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
            .glassCompat(in: RoundedRectangle(cornerRadius: 22, style: .continuous), interactive: enabled)
        }
        .buttonStyle(HomePressStyle(scale: 0.98))
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.4)
        .accessibilityIdentifier("send-source-\(source.rawValue)")
    }

    // MARK: Review and sending

    /// "The invoice sets the amount to ₿X" when you had typed a different amount first.
    private var invoiceNote: String? {
        guard Self.showsInvoiceNote(typed: typedAmount, fixed: model.parsed.amount) else { return nil }
        return L10n.text("The invoice sets the amount to {amount}", format(model.sats), locale: locale.identifier)
    }

    @ViewBuilder private var reviewScreen: some View {
        if let payment = model.payment, let quote = model.quote, model.showReview {
            SendConfirmation(
                confirm: {
                    clock.start()
                    model.controller.submit(payment, quote: quote)
                },
                btcPrice: model.btcPrice, fiatCurrency: model.fiatCurrency, payment: payment, quote: quote,
                controller: model.controller, cancel: cancelReview, done: finish, onLeave: onLeave,
                unit: unit, recipient: .of(destination: model.destination, ownDomain: ownDomain, payment: payment),
                invoiceNote: invoiceNote, wide: wide, sideInsets: (window.insets.leading, window.insets.trailing), clock: clock,
                gate: holdGate, chainTip: chainTip)
        } else {
            // Today's quote is being prepared.
            panes {
                VStack(alignment: .leading, spacing: 0) {
                    nav(scan: false)
                    Text(verbatim: L10n.text("Send {amount}", amountForTitle, locale: locale.identifier))
                        .noahText(SendText.big).lineLimit(1).minimumScaleFactor(0.5).padding(.top, 18)
                    recipientLine.padding(.top, 16)
                }
            } trailing: {
                VStack {
                    Spacer()
                    ProgressView().frame(maxWidth: .infinity)
                    Spacer()
                }
            }
        }
    }

    private var sendingScreen: some View {
        SendOutcomeView(controller: model.controller, btcPrice: model.btcPrice, fiatCurrency: model.fiatCurrency,
                        done: finish, clock: clock, unit: unit,
                        recipient: (model.controller.attempt?.payment ?? model.payment).map { .of(
                            destination: model.destination,
                            ownDomain: ownDomain,
                            payment: $0) },
                        wide: wide, sideInsets: (window.insets.leading, window.insets.trailing), onLeave: onLeave,
                        reviewed: model.payment, chainTip: chainTip)
    }

    // MARK: Navigation

    private func goBack() {
        haptics.play(.light)
        focus = nil
        switch screen {
        case .amount: model.stage = .recipient
        case .recipient:
            if model.isMax { model.stage = .source }
            else if model.progress.entry == .amountFirst { model.stage = .amount }
            else { model.reset(); typedAmount = nil }
        case .method: model.stage = .recipient
        case .source: if model.isMax { model.reset() } else { model.stage = .recipient }
        case .review: cancelReview()
        case .sending: break
        }
    }

    /// Today's review dismissal: the quote is discarded and the flow returns to the recipient.
    private func cancelReview() {
        guard !model.controller.locked, model.showReview || model.stage == .review else { return }
        model.showReview = false
        model.discardReview()
        if model.stage == .review { model.stage = model.isMax ? .source : .recipient }
    }

    /// Done: back to Amount, with the tab bar.
    private func finish() {
        model.showReview = false
        model.reset()
        clock.reset()
        typedAmount = nil
        Task {
            await app.refresh()
            await model.load()
        }
    }
}
