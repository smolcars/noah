import SwiftUI

/// Claim (exit.md, Decision 8): every exit that's ready, in one transaction. "To" defaults to
/// another wallet, an on-chain address for this network only, checked by today's parser; Claim stays
/// off until it's valid. "This phone's on-chain wallet" claims to a fresh address of this wallet,
/// and those outputs join auto-board's handled ledger, so Noah never moves them back to Ark.
struct ExitClaimView: View {
    @State private var model: OnchainModel
    @State private var toPhone = false
    @State private var typed = ""
    @State private var scanning = false
    @State private var expired = false
    /// The review key (`reviewKey`) whose review last finished: after that, a missing quote offers Review again.
    @State private var reviewed: String?
    @State private var haptics = OnboardingHaptics()
    @FocusState private var editing: Bool
    @Environment(AppModel.self) private var app
    @Environment(\.settings) private var settings
    @Environment(\.locale) private var locale
    @Environment(\.settingsFixture) private var fixture
    @Environment(\.exitPaneHeight) private var paneHeight
    @Environment(\.scenePhase) private var scenePhase

    init(model: OnchainModel = OnchainModel()) { _model = State(initialValue: model) }

    private var language: String { locale.identifier }
    private var ready: [ExitModel] { (model.overview?.exits ?? []).filter(\.claimable) }
    private var address: String { typed.trimmingCharacters(in: .whitespacesAndNewlines) }
    private var valid: Bool { ExitRules.acceptsClaimAddress(address, network: AppVariant.current.rawValue) }
    /// Where this claim goes now: the typed address once it's valid, or this wallet's fresh address.
    private var destination: String? {
        toPhone ? (model.claimDestination.isEmpty ? nil : model.claimDestination) : valid ? address : nil
    }
    private var quote: OnchainQuote? {
        guard let quote = model.quote, quote.action == .claim, quote.destination == destination, destination != nil else { return nil }
        return quote
    }
    private var reviewKey: String { [destination ?? "", ready.map(\.id).joined(separator: ","), String(scenePhase == .active)].joined(separator: "|") }
    /// Nothing to hold: the last review for these inputs finished without a quote (it failed, the fee
    /// changed, or the app left and came back), and nothing is running.
    private var reviewAgain: Bool {
        quote == nil && model.estimateFallback == nil && destination != nil && !ready.isEmpty && !model.busy && !model.loading && reviewed == reviewKey
    }

    var body: some View {
        let money = ExitMoney(app: app, locale: locale)
        let amount = ready.reduce(0) { $0 + $1.amount }
        let receive = quote.flatMap { $0.fee == nil ? nil : $0.net }
        ExitPanes {
            ExitHeadline(amount: L10n.text("Claim {amount}", money(amount), locale: language),
                         line: ready.compactMap(\.details.claimable_since?.height).max().map {
                             L10n.text("Unlocked at block {height}", $0.formatted(.number.locale(locale)), locale: language)
                         })
            if settings.wide, let exit = ready.first {
                // Your coin, unlocked at the top of its branch.
                ExitStageView(scene: ExitText(progress: model.progress(exit), exit: exit, tip: model.overview?.tip ?? 0, overview: model.overview,
                                              attempts: model.attempts, money: money, language: language).scene, wide: true)
                    .frame(height: max(240, paneHeight - 160)).padding(.top, 10).padding(.horizontal, -12)
            }
        } trailing: {
            SettingsSection(title: "To")
            option(L10n.text("Another wallet", locale: language), selected: !toPhone, id: "exit-claim-other") { choose(phone: false) }
                .disabled(model.busy)
            otherWallet
            option(
                L10n.text("This phone’s on-chain wallet", locale: language),
                selected: toPhone,
                id: "exit-claim-phone",
                sub: L10n.text("Noah won’t move these back to Ark.", locale: language)) { choose(phone: true) }
                .disabled(model.busy)
                .padding(.top, toPhone ? 0 : 8)
            fees(receive: receive, money: money)
            SettingsFine(text: L10n.text("Every exit that’s ready goes in this one transaction. Earlier network fees aren’t taken again.", locale: language))
            if expired { SettingsFine(text: L10n.text("The estimate expired, so Noah fetched a new one. Check it and hold again.", locale: language)) }
            OnchainStatusSection(model: model).settingsReceipts().padding(.top, 18)
        } footer: {
            if reviewAgain, let destination {
                Button(L10n.text("Review again", locale: language)) { Task { await review(destination) } }
                    .buttonStyle(SettingsActionStyle(wrap: true))
                    .accessibilityIdentifier("exit-claim-review")
            } else {
                SendHoldButton(amount: money(receive ?? amount), enabled: quote != nil && !model.busy && !ready.isEmpty,
                               title: receive.map { LocalizedStringKey(L10n.text("Hold to claim {amount}", money($0), locale: language)) } ?? "Hold to claim",
                               action: "Claim {amount}", confirm: "Claim", id: "exit-hold-claim") { claim() }
                    .id(quote?.id)
            }
        }
        .settingsChrome(nil)
        .onboardingHaptics(haptics)
        .modifier(OnchainScreen(model: model, presentsReview: false))
        .sheet(isPresented: $scanning) {
            SendScanner { value in
                scanning = false
                typed = Self.address(fromScan: value)
            }
        }
        .task {
            switch fixture?.exitClaimTo {
            case "phone": choose(phone: true)
            case let address?: typed = address
            default: break
            }
        }
        // Today's claim quote for the current destination, once it can be priced, and again whenever the
        // app comes back (leaving drops the review).
        .task(id: reviewKey) {
            reviewed = nil
            guard let destination, !ready.isEmpty, scenePhase == .active else { return }
            do { try await Task.sleep(for: .milliseconds(300)) } catch { return }
            await review(destination)
        }
    }

    @ViewBuilder private var otherWallet: some View {
        if !toPhone {
            field.disabled(model.busy)
            ExitWarning(
                text: L10n.text("Check the address. This can’t be undone.", locale: language),
                symbol: "exclamationmark.shield",
                muted: !(!address.isEmpty && !valid))
            if !address.isEmpty && !valid {
                SettingsFine(text: L10n.text("Enter an on-chain Bitcoin address for this network.", locale: language), failure: true)
            }
        }
    }

    @ViewBuilder private func fees(receive: UInt64?, money: ExitMoney) -> some View {
        VStack(spacing: 0) {
            ExitDetailRow(label: L10n.text("Claim fee", locale: language),
                          value: quote.map { $0.fee.map(money.about) ?? L10n.text("Unknown", locale: language) } ?? "—", numeric: true)
            ExitDetailRow(label: L10n.text("You get", locale: language), value: receive.map(money.about) ?? "—", numeric: true, hairline: false)
        }
        .padding(.top, 18)
        if quote?.feesUnknown == true {
            ExitWarning(text: L10n.text(
                "Fee estimate unavailable. Fees and amount to receive are unknown. Continue without an estimate only if you accept this uncertainty.",
                locale: language))
        } else if let quote, let fee = quote.fee, let warning = ExitRules.warning(amount: quote.amount, claimFee: fee, totalFee: fee, claimOnly: true) {
            ExitWarning(text: L10n.text(warning, locale: language))
        }
    }

    /// Today's claim review for `destination`, once any running one is done: the model drops a
    /// review asked for while it's busy.
    private func review(_ destination: String) async {
        let key = reviewKey
        while model.loading || model.busy { do { try await Task.sleep(for: .milliseconds(50)) } catch { return } }
        await model.review(.claim, ids: ready.map(\.id), destination: destination)
        reviewed = key
    }

    private var field: some View {
        HStack(spacing: 8) {
            // The whole address, wrapping: the line under it says to check it.
            TextField(text: $typed, prompt: Text(verbatim: L10n.text("Bitcoin address", locale: language)).foregroundStyle(NoahTokens.Colors.muted),
                      axis: .vertical) {
                Text(verbatim: L10n.text("Bitcoin address", locale: language))
            }
            .lineLimit(1...4)
            .font(SettingsText.mono)
            .tint(NoahTokens.Colors.orange)
            .textInputAutocapitalization(.never).autocorrectionDisabled()
            .submitLabel(.done)
            .focused($editing)
            .accessibilityIdentifier("exit-claim-address")
            PasteButton(payloadType: String.self) { values in
                Task { @MainActor in typed = values.first.map(Self.address(fromScan:)) ?? typed }
            }
            .labelStyle(.iconOnly).buttonBorderShape(.capsule).tint(NoahTokens.Colors.foreground)
            Button { scanning = true } label: { ActivityCircleIcon(systemName: "qrcode.viewfinder") }
                .buttonStyle(HomePressStyle())
                .accessibilityLabel(Text(L10n.text("Scan", locale: language)))
                .accessibilityIdentifier("exit-claim-scan")
        }
        .padding(.leading, 16).padding(.trailing, 6).padding(.vertical, 6)
        .frame(minHeight: 54)
        .glassCompat(in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .strokeBorder(NoahTokens.Colors.failure, lineWidth: 1.5)
                .opacity(!address.isEmpty && !valid ? 1 : 0)
        }
        .padding(.top, 6).padding(.bottom, 4)
        .onChange(of: typed) { _, _ in
            // Return ends editing rather than starting a line.
            if typed.contains("\n") {
                typed = typed.replacingOccurrences(of: "\n", with: "")
                editing = false
            }
            if !toPhone { model.editClaimDestination(address) }
        }
    }

    private func option(_ title: String, selected: Bool, id: String, sub: String? = nil, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            SettingsRowLabel(title: title, sub: sub, hairline: false) {
                Image(systemName: selected ? "largecircle.fill.circle" : "circle").accessibilityHidden(true)
                    .font(.system(size: 22)).foregroundStyle(selected ? NoahTokens.Colors.foreground : NoahTokens.Colors.hairline)
            }
        }
        .buttonStyle(SettingsRowPressStyle())
        .accessibilityAddTraits(selected ? .isSelected : [])
        .accessibilityIdentifier(id)
    }

    private func choose(phone: Bool) {
        guard phone != toPhone || phone && model.claimDestination.isEmpty else { return }
        haptics.play(.selection)
        toPhone = phone
        if phone {
            model.editClaimDestination("")
            Task { await model.useWalletClaimAddress() }
        } else {
            model.editClaimDestination(address)
        }
    }

    private func claim() {
        Task {
            guard let quote, destination == quote.destination else { return }
            guard quote.isCurrent else {
                expired = true
                if let destination { await review(destination) }
                return
            }
            expired = false
            // Only a submitted claim leaves; a failed one keeps its error or "Continue without estimate".
            guard await model.confirm() else {
                if model.error != nil { haptics.play(.error) }
                return
            }
            haptics.play(.success)
            settings.back()
        }
    }

    /// A scanned or pasted "bitcoin:" URI's address; anything else as it is (then validated).
    static func address(fromScan value: String) -> String {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.lowercased().hasPrefix("bitcoin:") else { return trimmed }
        let address = String(trimmed.dropFirst("bitcoin:".count).prefix { $0 != "?" })
        // QR codes often carry bech32 in capitals; base58 keeps its case.
        return address == address.uppercased() ? address.lowercased() : address
    }
}
