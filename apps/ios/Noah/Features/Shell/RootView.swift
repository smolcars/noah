import SwiftUI

/// Picks onboarding, the wallet tabs or a blocking message from `AppModel`.
struct RootView: View {
    @Environment(AppModel.self) private var model
    private var lock: WalletLock { model.walletLock }
    @State private var showingDelete = false
    @Environment(\.scenePhase) private var scenePhase

    /// One onboarding instance across these phases, so a restore's closing
    /// moment survives the switch to `.wallet`.
    private var showsOnboarding: Bool {
        model.phase == .onboarding || model.phase == .blocked(.seedWithoutWalletData) || model.holdsOnboardingAfterRestore
    }

    var body: some View {
        Group {
            if showsOnboarding {
                OnboardingFlow(model: model, storedSeed: model.phase == .blocked(.seedWithoutWalletData))
            } else {
                switch model.phase {
                case .launching:
                    if model.customServerUnresponsive {
                        VStack(spacing: 16) {
                            Text(model.switchingToDefaultServer ? L10n.text("The server isn't responding; switching back…") : L10n.text("The custom server isn't responding."))
                            Button("Use default server") { model.useDefaultEsploraServer() }
                                .disabled(model.switchingToDefaultServer)
                        }.padding()
                    } else { NoahColor.background.ignoresSafeArea() }
                case .onboarding:
                    EmptyView()
                case .wallet:
                    if model.pushPermission.blocksWallet(initialized: true) {
                        PushNotificationsRequiredView()
                    } else {
                        WalletTabs()
                    }
                case .blocked(let state):
                    LaunchMessageView(state: state)
                case .restoreIncomplete:
                    LaunchMessageView(
                        title: model.walletError == nil ? "Restore didn't finish" : "Wallet cleanup didn't finish",
                        message: LocalizedStringKey(model.walletError ?? "Noah couldn't finish or undo an interrupted restore. Your wallet files were left as they are. Restart Noah to try again."))
                }
            }
        }
        .onReceive(NotificationCenter.default.publisher(for: AppServices.esploraReopened)) { _ in
            Task { await model.walletConfigurationChanged() }
        }
        .onReceive(NotificationCenter.default.publisher(for: UIApplication.protectedDataDidBecomeAvailableNotification)) { _ in
            Task { await model.retrySecureStorage() }
        }
        .safeAreaInset(edge: .bottom) {
            if model.phase == .blocked(.secureStorageUnavailable) {
                Button("Retry") { Task { await model.retrySecureStorage() } }
                    .padding()
            }
            if model.phase == .blocked(.seedWithoutWalletData) || model.phase == .blocked(.walletDataWithoutSeed) {
                Button("Delete Wallet", role: .destructive) { showingDelete = true }
                    .padding()
            }
        }
        .sheet(isPresented: $showingDelete) { DeleteWalletSheet() }
        .modifier(WalletContentProtection(covered: model.phase == .wallet && lock.policy.locked))
        .environment(lock)
        .environment(\.locale, Locale(identifier: model.localeIdentifier))
        .background(WalletLockOverlay(lock: lock, visible: model.phase == .wallet && lock.policy.locked,
                                      protectOnBackground: model.phase == .wallet && lock.policy.enabled))
        .task {
            await model.start()
            if model.phase == .wallet && lock.policy.locked && scenePhase == .active { await lock.unlock() }
        }
        .onChange(of: scenePhase) { _, phase in
            if phase != .active { model.appBecameInactive() }
            if phase == .background { lock.background() }
            if phase == .active {
                model.localeIdentifier = LegacyLocale.resolved()
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
    @State private var send = SendModel()
    @State private var scanBlocked = false
    @State private var selection: Tab = .home
    @State private var recurringRoute = RecurringNotificationRoute.shared

    var body: some View {
        TabView(selection: $selection) {
            SwiftUI.Tab("Home", systemImage: "house", value: .home) {
                HomeView(isActive: selection == .home, onScan: scanned, onOpenHistory: { selection = .history }, onRepeatPayment: repeatPayment)
            }
            SwiftUI.Tab("Receive", systemImage: "arrow.down.left", value: .receive) {
                NavigationStack { ReceiveView(isActive: selection == .receive) { selection = .home } }
            }
            SwiftUI.Tab("Send", systemImage: "arrow.up.right", value: .send) {
                NavigationStack { SendView(model: send, isActive: selection == .send) }
            }
            SwiftUI.Tab("History", systemImage: "clock.arrow.circlepath", value: .history) {
                NavigationStack { TransactionsView(onRepeatPayment: repeatPayment) }
            }
        }
        .onChange(of: pendingRepeatPayment) { _, _ in consumeRepeatPayment() }
        .onChange(of: send.controller.locked) { _, _ in consumeRepeatPayment() }
        .onChange(of: send.busy) { _, _ in consumeRepeatPayment() }
        .alert("Payment in progress", isPresented: $scanBlocked) {
            Button("OK", role: .cancel) {}
        } message: { Text("Finish the current payment before scanning another request.") }
        .environment(history)
        .sheet(isPresented: $recurringRoute.presented) {
            NavigationStack {
                RecurringPaymentsView()
                    .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { recurringRoute.presented = false } } }
            }
        }
        .task(id: app.summary == nil ? nil : "\(app.preferredCurrency):\(app.walletSessionRevision)") {
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

    private func scanned(_ value: String) {
        selection = .send
        Task { if !(await QRScanRoute.apply(value, to: send)) { scanBlocked = true } }
    }

    private func consumeRepeatPayment() {
        send.consumeRepeatPayment(&pendingRepeatPayment, currency: app.preferredCurrency)
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
        GeometryReader { geometry in
            ScrollView {
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
                .readableWidth()
                .frame(minHeight: geometry.size.height)
            }
        }
        .background(NoahColor.background.ignoresSafeArea())
    }

    private static func title(for state: LaunchState) -> LocalizedStringKey {
        switch state {
        case .seedWithoutWalletData: "Recovery phrase found"
        case .walletDataWithoutSeed: "Recovery phrase needed"
        case .secureStorageUnavailable: "Waiting for secure storage"
        case .invalidSeed: "Wallet recovery needed"
        case .seedConflict: "Conflicting wallet data"
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
