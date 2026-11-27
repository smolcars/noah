import SwiftUI

/// Bitcoin → On-chain: your confirmed on-chain balance, Move to Ark (amount, Max and today's fee
/// rows in a sheet), Move automatically (auto-board), and today's receipts. While a move confirms,
/// Send's block row. Home's auto-board prompt is unchanged.
struct BoardArkView: View {
    /// Snapshots `selected` when opening the sheet so a background refresh pruning stale coins cannot silently widen it.
    private struct MoveSheet: Identifiable, Equatable {
        let id = UUID()
        let selected: [String]
    }

    @Environment(AppModel.self) private var app
    @Environment(SettingsSummary.self) private var summary: SettingsSummary?
    @Environment(\.locale) private var locale
    @State private var model: OnchainModel
    @State private var minimum: UInt64?
    @State private var transaction = PersistedTransactionState.load(from: .shared)
    @Environment(\.settingsFixture) private var fixture

    init(model: OnchainModel = OnchainModel()) { _model = State(initialValue: model) }
    @State private var selected = Set<String>()
    @State private var moving: MoveSheet?
    @State private var amount = ""
    @State private var error: String?
    @State private var haptics = OnboardingHaptics()

    private var language: String { locale.identifier }
    private var utxos: [OnchainUtxoModel] { model.overview?.utxos ?? [] }
    private var chosen: [String] {
        transaction.isAutoBoardingEnabled ? [] : utxos.filter { $0.selectable && selected.contains($0.id) }.map(\.id)
    }
    private var moveLabel: String {
        chosen.isEmpty
            ? L10n.text("Move to Ark", locale: language)
            : L10n.plural("Move {n} coins to Ark", count: chosen.count, locale: language)
    }
    /// A move that's been sent and hasn't confirmed yet.
    private var confirming: OnchainOperationJournal.Attempt? {
        model.attempts.first { attempt in
            guard attempt.quote.action == .board, let receipt = attempt.receipt, receipt.txid != nil else { return false }
            if case .confirmed = receipt.chainStatus { return false }
            return true
        }
    }

    var body: some View {
        SettingsScroll {
            balanceHeader
            SettingsGroup {
                SettingsToggleRow(icon: "arrow.triangle.2.circlepath", title: L10n.text("Move automatically", locale: language),
                                  sub: minimum.map { L10n.text("Asks when you have {amount} or more", money(max($0, 20_000)), locale: language) }
                                      ?? L10n.text("Auto-board threshold unavailable", locale: language),
                                  isOn: Binding(get: { transaction.isAutoBoardingEnabled }, set: setAutoBoard), id: "onchain-auto-board")
                if let minimum {
                    SettingsRowLabel(icon: "sailboat", title: L10n.text("Smallest move", locale: language), hairline: false) {
                        Text(verbatim: money(minimum)).noahText(HomeText.amount).monospacedDigit().foregroundStyle(NoahTokens.Colors.muted)
                    }
                }
            }
            if !transaction.isAutoBoardingEnabled, confirming == nil, !utxos.isEmpty {
                utxoSection
            }
            if let error { SettingsFine(text: L10n.text(error, locale: language), failure: true) }
            if transaction.isAutoBoardingEnabled {
                SettingsFine(text: L10n.text("Turn off Move automatically to choose individual on-chain coins to move.", locale: language))
            }
            SettingsFine(text: L10n.text("Bitcoin in Ark moves instantly and costs less to send.", locale: language))
            OnchainStatusSection(model: model).settingsReceipts().padding(.top, 18)
        }
        .settingsChrome("On-chain")
        .settingsFooter {
            if confirming == nil {
                Button(moveLabel) {
                    haptics.play(.light)
                    amount = chosen.isEmpty ? "" : "MAX"
                    moving = MoveSheet(selected: chosen)
                }
                .buttonStyle(SettingsActionStyle())
                .disabled(model.locked || (chosen.isEmpty && (model.overview?.confirmed ?? 0) == 0))
                .accessibilityIdentifier("board-open")
            }
        }
        .onboardingHaptics(haptics)
        .modifier(OnchainScreen(model: model, presentsReview: false))
        .settingsSheet(item: $moving, locked: model.busy) { sheet in
            MoveToArkSheet(model: model, amount: $amount, selected: sheet.selected, minimum: minimum, cancel: closeMove) {
                Task {
                    moving = nil
                    await model.confirm()
                    if model.error == nil {
                        selected = []
                        haptics.play(.success)
                    }
                    await summary?.refresh(suspended: app.isWalletSuspended, force: true)
                }
            }
        }
        .onChange(of: amount) { _, _ in model.invalidate() }
        .onChange(of: utxos) { _, current in selected = BoardUtxoSelection.prune(selected, in: current) }
        .task {
            if let fixture { minimum = fixture.boardingMinimum; return }
            guard !app.isWalletSuspended else { return }
            minimum = try? await app.session.boardingMinimum()
        }
    }

    @ViewBuilder private var balanceHeader: some View {
        Text(verbatim: model.overview.map { money($0.confirmed) } ?? " ")
            .noahText(SettingsText.big).monospacedDigit().lineLimit(1).minimumScaleFactor(0.5)
            .padding(.top, 6)
            .accessibilityIdentifier("onchain-balance")
        if let confirming {
            Text(verbatim: L10n.text("Moving {amount} to Ark", money(confirming.quote.amount), locale: language))
                .noahText(NoahTokens.Typography.sub).foregroundStyle(NoahTokens.Colors.muted).padding(.top, 3)
            ActivityBlockRow(tip: model.overview?.tip, confirmedHeight: nil)
                .frame(width: 204, height: 62).padding(.leading, -8).padding(.top, 18)
            if let tip = model.overview?.tip {
                Text(verbatim: L10n.text("Waiting for block {height}", (tip + 1).formatted(.number.locale(locale)), locale: language))
                    .noahText(NoahTokens.Typography.control).foregroundStyle(NoahTokens.Colors.muted).padding(.top, 4)
            }
        } else {
            Text(L10n.text("Confirmed", locale: language)).noahText(NoahTokens.Typography.sub).foregroundStyle(NoahTokens.Colors.muted).padding(.top, 3)
        }
    }

    @ViewBuilder private var utxoSection: some View {
        Text(L10n.text("On-chain coins", locale: language))
            .noahText(HomeText.section).foregroundStyle(NoahTokens.Colors.muted)
            .padding(.top, 18).padding(.bottom, 2)
            .accessibilityAddTraits(.isHeader)
        ForEach(Array(utxos.enumerated()), id: \.element.id) { index, utxo in
            OnchainUtxoRow(utxo: utxo, selected: selected.contains(utxo.id), disabled: model.locked, hairline: index < utxos.count - 1) {
                toggle(utxo)
            }
        }
        SettingsFine(text: L10n.text(
            // swiftlint:disable:next line_length - one literal: translations.mjs reads copy as a single string
            "Select one or more confirmed coins to move them on their own. Moving one coin at a time avoids linking your other on-chain coins, but each move pays its own network fee.",
            locale: language))
    }

    private func toggle(_ utxo: OnchainUtxoModel) {
        guard utxo.selectable else { return }
        haptics.play(.selection)
        if selected.contains(utxo.id) { selected.remove(utxo.id) } else { selected.insert(utxo.id) }
        model.invalidate()
    }

    private func closeMove() {
        model.invalidate()
        moving = nil
    }

    private func setAutoBoard(_ enabled: Bool) {
        transaction.setAutoBoarding(enabled)
        if enabled {
            selected = []
            model.invalidate()
        }
        do { try SettingsStorage.shared.setPersistedState(transaction, key: PersistedTransactionState.key, version: PersistedTransactionState.version) }
        catch { self.error = "Couldn't save auto-board preference." }
    }

    private func money(_ sats: UInt64) -> String {
        app.isBalanceHidden ? (app.amountUnit == .bip177 ? "₿••••" : "•••• sats")
            : BitcoinAmount.format(sats: Int64(clamping: sats), unit: app.amountUnit, locale: locale)
    }
}

/// One local on-chain UTXO on the On-chain screen while Move automatically is off.
private struct OnchainUtxoRow: View {
    let utxo: OnchainUtxoModel
    var selected = false
    var disabled = false
    var hairline = true
    let action: () -> Void
    @Environment(AppModel.self) private var app
    @Environment(\.locale) private var locale

    private var language: String { locale.identifier }
    private var shortPoint: String { "\(utxo.txid.prefix(6))…:\(utxo.vout)" }
    private var subtitle: String {
        utxo.confirmationHeight.map {
            L10n.text("Block {height} · {id}", $0.formatted(.number.locale(locale)), shortPoint, locale: language)
        } ?? shortPoint
    }

    var body: some View {
        Button(action: action) {
            HStack(spacing: 14) {
                Image(systemName: selected ? "checkmark.circle.fill" : "circle").accessibilityHidden(true)
                    .font(.system(size: 24))
                    .foregroundStyle(utxo.selectable ? NoahTokens.Colors.foreground : NoahTokens.Colors.hairline)
                    .frame(width: 42, height: 42)
                VStack(alignment: .leading, spacing: 1) {
                    Text(verbatim: utxo.confirmed ? L10n.text("Confirmed", locale: language) : L10n.text("Unconfirmed", locale: language))
                        .noahText(SettingsText.value)
                        .foregroundStyle(utxo.confirmed ? NoahTokens.Colors.foreground : NoahTokens.Colors.muted)
                    Text(verbatim: subtitle).noahText(HomeText.footnote).foregroundStyle(NoahTokens.Colors.muted)
                }
                .lineLimit(1)
                .frame(maxWidth: .infinity, alignment: .leading)
                Text(verbatim: app.isBalanceHidden
                    ? (app.amountUnit == .bip177 ? "₿••••" : "•••• sats")
                    : BitcoinAmount.format(sats: Int64(clamping: utxo.amount), unit: app.amountUnit, locale: locale))
                    .noahText(HomeText.amount).monospacedDigit()
                    .foregroundStyle(utxo.confirmed ? NoahTokens.Colors.foreground : NoahTokens.Colors.muted)
            }
            .frame(height: 66)
            .opacity(utxo.selectable ? 1 : 0.4)
            .contentShape(Rectangle())
            .overlay(alignment: .bottom) { if hairline { HomeHairline().padding(.leading, 56) } }
        }
        .buttonStyle(SettingsRowPressStyle())
        .disabled(disabled || !utxo.selectable)
        .accessibilityAddTraits(selected ? .isSelected : [])
        .accessibilityIdentifier("onchain-utxo-\(utxo.txid.prefix(8))-\(utxo.vout)")
    }
}

/// Move to Ark: the amount (or Max), then today's board review rows in the same sheet. The
/// signed funding transaction expires after two minutes, as today; editing the amount drops it.
/// When `selected` is non-empty, the board spends only those confirmed UTXOs and opens on Max.
private struct MoveToArkSheet: View {
    let model: OnchainModel
    @Binding var amount: String
    let selected: [String]
    let minimum: UInt64?
    let cancel: () -> Void
    let confirm: () -> Void
    @Environment(AppModel.self) private var app
    @Environment(\.locale) private var locale
    @FocusState private var focused: Bool

    private var language: String { locale.identifier }
    private var max: Bool { amount == "MAX" }
    /// Whole, positive sats as typed: "1.5" or "-1000" are refused, never rewritten.
    private var requested: UInt64? { max ? 0 : OnchainAmountInput.sats(amount) }
    private var subtitle: String {
        selected.isEmpty
            ? L10n.text("From your on-chain balance.", locale: language)
            : L10n.plural("From {n} selected coins.", count: selected.count, locale: language)
    }

    var body: some View {
        SettingsSheetBody(title: L10n.text("Move to Ark", locale: language), line: subtitle) {
            amountField
            if let quote = model.quote {
                quoteRows(quote)
            } else if !amount.isEmpty && requested == nil {
                SettingsFine(text: L10n.text("Enter whole sats.", locale: language), failure: true)
            } else if let minimum {
                SettingsFine(text: L10n.text(
                    "At least {amount}.",
                    BitcoinAmount.format(sats: Int64(clamping: minimum), unit: app.amountUnit, locale: locale),
                    locale: language))
            }
            if let error = model.error { SettingsFine(text: L10n.text(error, locale: language), failure: true) }
        } actions: {
            if model.quote == nil {
                SettingsSheetButtons(cancel: L10n.text("Cancel", locale: language), action: L10n.text("Review", locale: language),
                                     enabled: requested != nil, busy: model.busy, actionID: "board-review",
                                     onCancel: cancel) {
                    focused = false
                    Task { await model.review(.board, ids: selected, amount: requested ?? 0) }
                }
            } else {
                SettingsSheetButtons(cancel: L10n.text("Cancel", locale: language), action: L10n.text("Move", locale: language),
                                     busy: model.busy, actionID: "onchain-confirm", onCancel: cancel, onAction: confirm)
            }
        }
        .onAppear { if !max { focused = true } }
    }

    private var amountField: some View {
        HStack(alignment: .firstTextBaseline) {
            if max {
                Text(L10n.text("Max", locale: language)).noahText(SettingsText.big)
            } else {
                TextField(text: $amount, prompt: Text(verbatim: "0").foregroundStyle(NoahTokens.Colors.muted)) { Text("Amount in sats") }
                    .noahText(SettingsText.big)
                    .keyboardType(.numberPad)
                    .tint(NoahTokens.Colors.orange)
                    .focused($focused)
                    .accessibilityIdentifier("board-amount")
                Text(verbatim: "sats").noahText(NoahTokens.Typography.label).foregroundStyle(NoahTokens.Colors.muted)
            }
            Spacer()
            Button(L10n.text("Max", locale: language)) { amount = max ? "" : "MAX" }
                .buttonStyle(OnboardingChipStyle(bold: max))
                .disabled(model.locked || (selected.isEmpty && (model.overview?.confirmed ?? 0) == 0))
                .accessibilityIdentifier("board-max")
        }
        .padding(.top, 16)
        .disabled(model.busy)
    }

    @ViewBuilder private func quoteRows(_ quote: OnchainQuote) -> some View {
        VStack(spacing: 0) {
            row("Amount", quote.amount)
            if let fee = quote.fee, let chainFee = quote.chainFee {
                row("Ark fee", fee)
                row("Network fee", chainFee)
                row("Arrives in Ark", quote.net)
                row("Total", quote.amount + chainFee)
            }
            if let prepared = quote.prepared {
                SettingsDetailRow(label: L10n.text("Signed transaction size", locale: language), value: "\(prepared.vsize) vB", compact: true)
                SettingsDetailRow(label: L10n.text("Funding inputs", locale: language), value: "\(prepared.inputCount)", compact: true, hairline: false)
            }
        }
        .padding(.top, 8)
        SettingsFine(text: L10n.text(
            // swiftlint:disable:next line_length - one literal: translations.mjs reads copy as a single string
            "Move onchain bitcoin into Ark using this exact signed funding transaction. The boarding amount includes the Ark fee; the onchain fee is paid in addition. Funds become spendable after the board completes. This review expires after two minutes.",
            locale: language)
            + OnchainReviewSheet.boardSelectionNote(quote, locale: language))
    }

    private func row(_ label: String, _ sats: UInt64) -> some View {
        SettingsDetailRow(
            label: L10n.text(label, locale: language),
            value: BitcoinAmount.format(sats: Int64(clamping: sats), unit: app.amountUnit, locale: locale),
            numeric: true)
    }
}

enum OnchainAmountInput {
    /// A positive whole number of sats, exactly as typed (ASCII digits only), or nil.
    static func sats(_ text: String) -> UInt64? {
        guard !text.isEmpty, text.allSatisfy({ $0.isASCII && $0.isNumber }), let value = UInt64(text), value > 0 else { return nil }
        return value
    }
}

/// Foreground confirmation once per confirmed deposit; funding is persisted before showing review.
struct AutoBoardingService: View {
    /// Where the outcome shows; nil draws it here as a card.
    var message: Binding<String?>?
    @Environment(AppModel.self) private var app
    @Environment(\.scenePhase) private var phase
    @State private var plan: OnchainQuote?
    @State private var generation = UUID()
    @State private var ownMessage: String?
    init(message: Binding<String?>? = nil) { self.message = message }
    @State private var review = AutoBoardReview()
    @State private var checking = false
    @State private var busy = false
    var body: some View {
        VStack(spacing: 0) {
            if message == nil, let ownMessage {
                NoahCard { Text(LocalizedStringKey(ownMessage)).font(NoahFont.callout) }
            }
        }
        .task(id: app.summaryRevision) { await check() }
        .onChange(of: phase) { _, value in
            if value != .active { invalidate() } else { Task { await check() } }
        }
        .onChange(of: app.isWalletSuspended) { _, value in if value { invalidate() } }
        .onChange(of: app.summary?.fingerprint) { _, _ in invalidate() }
        .onDisappear { invalidate() }
        .onChange(of: plan) { old, _ in
            if let old, !busy { Task { await app.session.discardBoard(old) } }
        }
        .task(id: plan?.id) {
            guard plan != nil else { return }
            do { try await Task.sleep(for: .seconds(120)) } catch { return }
            invalidate()
        }
        .sheet(item: $plan) { quote in
            OnchainReviewSheet(
                quote: quote,
                confirm: { confirmAutoBoard(quote) }, cancelTitle: "No, turn off",
                onCancel: turnOffAutoBoard)
        }
    }

    private func confirmAutoBoard(_ quote: OnchainQuote) {
        busy = true
        plan = nil
        Task<Void, Never> {
            defer { busy = false; Task { await check() } }
            defer { Task { await app.session.discardBoard(quote) } }
            // A board changes what every page shows, as the pages' own submissions do (SharedOverview).
            SharedOverview.shared.changed()
            defer { SharedOverview.shared.changed() }
            do {
                guard !(await WalletBackgroundActivity.shared.isRunning),
                      !app.isWalletSuspended, phase == .active,
                      PersistedTransactionState.load(from: .shared).isAutoBoardingEnabled
                else { throw OnchainFailure.unavailable }
                report(try await app.session.executeOnchain(quote, funding: review.fundingFor(quote)))
                // Retain the receipt independently of admission for a later board.
                await app.refresh()
            } catch {
                let failure = error
                try? await review.failedConfirm(quote, changed: (failure as? OnchainFailure) == .changed) {
                    try await app.session.abandonUnsubmittedAutoBoard(quote)
                }
                report((error as? OnchainFailure)?.errorDescription
                    ?? OnchainFailure.uncertain.errorDescription)
            }
        }
    }

    private func turnOffAutoBoard() {
        var state = PersistedTransactionState.load(from: .shared)
        state.setAutoBoarding(false)
        do {
            try SettingsStorage.shared.setPersistedState(
                state, key: PersistedTransactionState.key, version: PersistedTransactionState.version)
        } catch { report("Couldn't save auto-board preference.") }
    }
    private func check() async {
        guard !checking, !busy, plan == nil, !app.isWalletSuspended, phase == .active else { return }
        let state = PersistedTransactionState.load(from: .shared)
        guard state.isAutoBoardingEnabled,
              (app.summary?.balance.onchain.confirmed ?? 0) >= 20_000
        else { return }
        checking = true
        let token = generation
        defer { checking = false }
        do {
            _ = try await review.prepare(allowed: {
                token == generation && !Task.isCancelled && !busy && plan == nil &&
                    !app.isWalletSuspended && phase == .active &&
                    PersistedTransactionState.load(from: .shared).isAutoBoardingEnabled &&
                    (app.summary?.balance.onchain.confirmed ?? 0) >= 20_000
            }, funding: {
                guard !(await WalletBackgroundActivity.shared.isRunning) else { return nil }
                return try await app.session.autoBoardFunding()
            }, quote: { try await app.session.autoBoardQuote() }, discard: { await app.session.discardBoard($0) },
            present: { plan = $0 })
        } catch {
            if token == generation, !Task.isCancelled {
                report((error as? OnchainFailure)?.errorDescription
                    ?? "Unable to prepare boarding. Use Settings → Board to Ark to try again.")
            }
        }
    }
    private func report(_ text: String?) {
        if let message { message.wrappedValue = text } else { ownMessage = text }
    }
    private func invalidate() {
        generation = UUID()
        if let plan, !busy { Task { await app.session.discardBoard(plan) } }
        plan = nil
    }
}
