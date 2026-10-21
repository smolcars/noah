import SwiftUI

struct BoardArkView: View {
    @Environment(AppModel.self) private var app
    @State private var model = OnchainModel()
    @State private var amount = ""
    @State private var minimum: UInt64?
    @FocusState private var amountFocused: Bool
    var body: some View {
        AdaptiveForm {
            Section {
                Text("Move onchain bitcoin into Ark for fast, low-cost payments.")
                if let overview = model.overview {
                    OnchainAmountRow(title: "Confirmed onchain balance", sats: overview.confirmed)
                }
                if let minimum { OnchainAmountRow(title: "Minimum Ark amount", sats: minimum) }
            }
        } secondary: {
            Section("Amount") {
                TextField("Amount in sats", text: $amount).keyboardType(.numberPad)
                    .focused($amountFocused)
                    .accessibilityIdentifier("board-amount")
                    .disabled(model.locked)
                Button("MAX") {
                    amount = "MAX"
                }.disabled(model.locked || model.overview?.confirmed == 0)
                Button("Review boarding") {
                    Task { await model.review(.board, amount: UInt64(amount) ?? 0) }
                }
                .disabled(model.locked || (amount != "MAX" && (UInt64(amount) ?? 0) == 0))
                .accessibilityIdentifier("board-review")
            }
            OnchainStatusSection(model: model)
        }
        .settingsStyle("Board to Ark", maximumWidth: AdaptiveLayout.pairedWidth)
        .scrollDismissesKeyboard(.interactively)
        .toolbar { ToolbarItemGroup(placement: .keyboard) { Spacer(); Button("Done") { amountFocused = false } } }
        .modifier(OnchainScreen(model: model))
        .task {
            guard !app.isWalletSuspended else { return }
            minimum = try? await app.session.boardingMinimum()
        }
        .onChange(of: amount) { _, _ in model.invalidate() }
    }
}

/// Foreground confirmation once per confirmed deposit; funding is persisted before showing review.
struct AutoBoardingService: View {
    @Environment(AppModel.self) private var app
    @Environment(\.scenePhase) private var phase
    @State private var plan: OnchainQuote?
    @State private var generation = UUID()
    @State private var message: String?
    @State private var review = AutoBoardReview()
    @State private var checking = false
    @State private var busy = false
    var body: some View {
        VStack(spacing: 0) {
            if let message {
                NoahCard { Text(LocalizedStringKey(message)).font(NoahFont.callout) }
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
                confirm: {
                    busy = true
                    plan = nil
                    Task {
                        defer { busy = false }
                        defer { Task { await app.session.discardBoard(quote) } }
                        do {
                            guard !(await WalletBackgroundActivity.shared.isRunning),
                                !app.isWalletSuspended, phase == .active,
                                PersistedTransactionState.load(from: .shared).isAutoBoardingEnabled
                            else { throw OnchainFailure.unavailable }
                            message = try await app.session.executeOnchain(quote)
                            // Retain the receipt independently of admission for a later board.
                            await app.refresh()
                        } catch {
                            message =
                                (error as? OnchainFailure)?.errorDescription
                                ?? OnchainFailure.uncertain.errorDescription
                        }
                    }
                }, cancelTitle: "No, turn off",
                onCancel: {
                    var state = PersistedTransactionState.load(from: .shared)
                    state.setAutoBoarding(false)
                    do {
                        try SettingsStorage.shared.setPersistedState(
                            state, key: PersistedTransactionState.key, version: PersistedTransactionState.version)
                    } catch { message = "Couldn't save auto-board preference." }
                })
        }
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
                message = (error as? OnchainFailure)?.errorDescription
                    ?? "Unable to prepare boarding. Use Settings → Board to Ark to try again."
            }
        }
    }
    private func invalidate() {
        generation = UUID()
        if let plan, !busy { Task { await app.session.discardBoard(plan) } }
        plan = nil
    }
}
