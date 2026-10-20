import SwiftUI

/// Picks onboarding, the wallet tabs or a blocking message from `AppModel`.
struct RootView: View {
    @Environment(AppModel.self) private var model
    @State private var lock = WalletLock()
    @State private var showingDelete = false
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        Group {
            switch model.phase {
            case .launching:
                NoahColor.background.ignoresSafeArea()
            case .onboarding:
                OnboardingFlow()
            case .wallet:
                WalletTabs()
            case .blocked(let state):
                if state == .seedWithoutWalletData {
                    NavigationStack { RestoreWalletScreen(useStoredSeed: true) }
                        .tint(NoahColor.brand)
                } else {
                    LaunchMessageView(state: state)
                }
            case .restoreIncomplete:
                LaunchMessageView(
                    title: model.walletError == nil ? "Restore didn't finish" : "Wallet cleanup didn't finish",
                    message: LocalizedStringKey(model.walletError ?? "Noah couldn't finish or undo an interrupted restore. Your wallet files were left as they are. Restart Noah to try again."))
            }
        }
        .safeAreaInset(edge: .bottom) {
            if model.phase == .blocked(.seedWithoutWalletData) || model.phase == .blocked(.walletDataWithoutSeed) {
                Button("Delete Wallet", role: .destructive) { showingDelete = true }
                    .padding()
            }
        }
        .sheet(isPresented: $showingDelete) { DeleteWalletSheet() }
        .accessibilityHidden(model.phase == .wallet && lock.policy.locked)
        .environment(lock)
        .environment(\.locale, Locale(identifier: model.localeIdentifier))
        .background(WalletLockOverlay(lock: lock, visible: model.phase == .wallet && lock.policy.locked))
        .task {
            await model.start()
            if model.phase == .wallet && lock.policy.locked && scenePhase == .active { await lock.unlock() }
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .background { lock.background() }
            if phase == .active {
                Task {
                    if model.phase == .wallet && lock.policy.locked { await lock.unlock() }
                    await model.appBecameActive()
                }
            }
        }
    }
}

/// Home, Receive, Send, History, each with its own navigation stack.
struct WalletTabs: View {
    enum Tab: Hashable {
        case home, receive, send, history
    }

    @Environment(AppModel.self) private var app
    @Environment(\.scenePhase) private var scenePhase
    @State private var history = HistoryModel(dependencies: .live(
        session: .shared, client: AppServices.shared.client, variant: .current))
    @State private var pendingRepeatPayment: RepeatPayment?
    @State private var selection: Tab = .home
    @State private var recurringRoute = RecurringNotificationRoute.shared

    var body: some View {
        TabView(selection: $selection) {
            SwiftUI.Tab("Home", systemImage: "house", value: .home) {
                HomeView(onOpenHistory: { selection = .history }, onRepeatPayment: repeatPayment)
            }
            SwiftUI.Tab("Receive", systemImage: "arrow.down.left", value: .receive) {
                NavigationStack { ReceiveView(isActive: selection == .receive) { selection = .home } }
            }
            SwiftUI.Tab("Send", systemImage: "arrow.up.right", value: .send) {
                // TODO: hand `pendingRepeatPayment` to SendView once it takes a prefill
                // (after the Send safety fixes, which also reset routes on prefill).
                NavigationStack { SendView() }
            }
            SwiftUI.Tab("History", systemImage: "clock.arrow.circlepath", value: .history) {
                NavigationStack { TransactionsView(onRepeatPayment: repeatPayment) }
            }
        }
        .environment(history)
        .sheet(isPresented: $recurringRoute.presented) {
            NavigationStack {
                RecurringPaymentsView()
                    .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { recurringRoute.presented = false } } }
            }
        }
        .task(id: app.summary == nil ? nil : app.preferredCurrency) {
            guard app.summary != nil else { return }
            await history.observe(currency: app.preferredCurrency)
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active {
                Task { await history.refresh(currency: app.preferredCurrency) }
            }
        }
        .tint(NoahColor.brand)
        .tabBarMinimizeOnScrollIfAvailable()
    }

    private func repeatPayment(_ details: RepeatPayment) {
        pendingRepeatPayment = details
        selection = .send
    }
}

private struct LaunchMessageView: View {
    let title: LocalizedStringKey
    let message: LocalizedStringKey

    init(title: LocalizedStringKey, message: LocalizedStringKey) {
        self.title = title
        self.message = message
    }

    init(state: LaunchState) {
        self.init(title: Self.title(for: state), message: Self.message(for: state))
    }

    var body: some View {
        VStack(spacing: 12) {
            Text(title)
                .font(NoahFont.title)
                .foregroundStyle(NoahColor.foreground)
            Text(message)
                .font(NoahFont.body)
                .foregroundStyle(NoahColor.mutedForeground)
                .multilineTextAlignment(.center)
                .textSelection(.enabled)
        }
        .padding(32)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(NoahColor.background.ignoresSafeArea())
    }

    private static func title(for state: LaunchState) -> LocalizedStringKey {
        switch state {
        case .seedWithoutWalletData: "Recovery phrase found"
        case .walletDataWithoutSeed: "Recovery phrase needed"
        case .secureStorageUnavailable: "Waiting for secure storage"
        case .invalidSeed: "Wallet recovery needed"
        case .seedConflict: "Conflicting wallet data"
        case .incompatibleStorage: "Wallet settings need recovery"
        case .existingWallet, .onboarding: ""
        }
    }

    private static func message(for state: LaunchState) -> LocalizedStringKey {
        switch state {
        case .seedWithoutWalletData:
            "Use the recovery phrase stored on this device to restore your backup."
        case .walletDataWithoutSeed:
            "Wallet data exists on this device, but its recovery phrase is missing. Noah won't replace these files. Keep your recovery phrase and contact support for help recovering this wallet."
        case .secureStorageUnavailable:
            "Noah can't read its secure storage yet. Unlock your device to continue."
        case .invalidSeed:
            "Support code: SEED_INVALID. The stored recovery phrase could not be validated. Keep this app installed and contact Noah support at github.com/smolcars/noah. Never send anyone your recovery phrase, private keys, or a wallet database."
        case .seedConflict:
            "Support code: WALLET_CONFLICT. Noah found wallet records that cannot be safely matched. Keep this app installed and contact Noah support at github.com/smolcars/noah. Never send anyone your recovery phrase, private keys, or a wallet database."
        case .incompatibleStorage:
            "Support code: STORAGE_UNSUPPORTED. Noah couldn't safely read your saved settings. Keep this app installed and contact support at github.com/smolcars/noah. Never share your recovery phrase, private keys, or a wallet database."
        case .existingWallet, .onboarding:
            ""
        }
    }
}

private extension View {
    @ViewBuilder
    func tabBarMinimizeOnScrollIfAvailable() -> some View {
        if #available(iOS 26, *) {
            tabBarMinimizeBehavior(.onScrollDown)
        } else {
            self
        }
    }
}
