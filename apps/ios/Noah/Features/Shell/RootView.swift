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
                phaseView
            }
        }
        .onOpenURL { model.paymentLink.receive($0, gate: model.paymentLinkGate) }
        // Launch ending in onboarding or a gate drops a link held through it. Initial: the phase swaps this Group's child.
        .onChange(of: model.paymentLinkGate, initial: true) { _, gate in if gate == .ignore { _ = model.paymentLink.take(gate) } }
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
        .sheet(isPresented: $showingDelete) { DeleteWalletSheet().padding(.top, 22).presentationDetents([.medium]) }
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
                Task { await lock.refresh() } // Face ID enrolment or permission may have changed in Settings.
                Task {
                    if model.phase == .wallet && lock.policy.locked { await lock.unlock() }
                    await model.appBecameActive()
                }
            }
        }
    }

    @ViewBuilder private var phaseView: some View {
        switch model.phase {
        case .launching:
            if model.customServerUnresponsive {
                VStack(spacing: 16) {
                    Text(model.switchingToDefaultServer ? L10n.text("The server isn't responding; switching back…") : L10n
                        .text("The custom server isn't responding."))
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
                message: LocalizedStringKey(model
                    .walletError ??
                    "Noah couldn't finish or undo an interrupted restore. Your wallet files were left as they are. Restart Noah to try again."))
        }
    }
}

/// Home, Receive, Send, Activity, each with its own navigation stack. Wide
/// windows (the iPhone Duo open or half-open) hide the system tab bar, which
/// would cross the fold, and draw the same tabs as a glass bar in the leading pane.
struct WalletTabs: View {
    enum Tab: Hashable, CaseIterable {
        case home, receive, send, history

        var title: LocalizedStringKey {
            switch self {
            case .home: "Home"
            case .receive: "Receive"
            case .send: "Send"
            case .history: "Activity"
            }
        }

        var symbol: String {
            switch self {
            case .home: "house"
            case .receive: "arrow.down.left"
            case .send: "arrow.up.right"
            case .history: "list.bullet"
            }
        }
    }

    @Environment(AppModel.self) private var app
    @Environment(\.scenePhase) private var scenePhase
    @State private var history: HistoryModel
    /// Home's in-memory "new" set; Activity shares it.
    @State private var tracker = HomeActivityTracker()
    /// Settings' live row values, for every tab that opens a Settings page.
    @State private var settingsSummary = SettingsSummary()
    @State private var activityHidesBar = false
    /// Settings, pushed on Home, hides the phone's tab bar.
    @State private var homeHidesBar = false
    /// Home's and Receive's models; screenshot fixtures pass fakes for these and Send's.
    private let home: HomeModel?
    private let receive: ReceiveModel?
    @State private var pendingRepeatPayment: RepeatPayment?
    @State private var send: SendModel
    @State private var scanBlocked = false
    /// The blocked request was an OS link, not a scan.
    @State private var linkBlocked = false
    @State private var selection: Tab
    @State private var settingsRoute = SettingsRoute.shared
    @State private var window = OnboardingLayout(size: .zero, insets: EdgeInsets())

    init(history: HistoryModel? = nil, home: HomeModel? = nil, send: SendModel? = nil, receive: ReceiveModel? = nil,
         tab: Tab = .home) {
        _history = State(initialValue: history ?? HistoryModel(dependencies: .live(
            session: .shared, client: AppServices.shared.client, variant: .current)))
        self.home = home
        self.receive = receive
        _send = State(initialValue: send ?? SendModel())
        _selection = State(initialValue: tab)
    }

    var body: some View {
        TabView(selection: $selection) {
            SwiftUI.Tab(Tab.home.title, systemImage: Tab.home.symbol, value: .home) {
                HomeView(isActive: selection == .home, onScan: scanned, onOpenHistory: { selection = .history },
                         onRepeatPayment: repeatPayment, onReceive: { selection = .receive }, home: home)
                    .onPreferenceChange(ActivityHidesTabBar.self) { homeHidesBar = $0 }
                    .modifier(WideTabContent(wide: window.wide, padsForBar: false, barHidden: homeHidesBar))
            }
            SwiftUI.Tab(Tab.receive.title, systemImage: Tab.receive.symbol, value: .receive) {
                NavigationStack { ReceiveView(isActive: selection == .receive, model: receive) }
                    .modifier(WideTabContent(wide: window.wide, padsForBar: false))
            }
            SwiftUI.Tab(Tab.send.title, systemImage: Tab.send.symbol, value: .send) {
                NavigationStack { SendView(model: send, isActive: selection == .send) { selection = .home } }
                    .modifier(WideTabContent(wide: window.wide, padsForBar: false, barHidden: send.hidesTabBar))
            }
            SwiftUI.Tab(Tab.history.title, systemImage: Tab.history.symbol, value: .history) {
                TransactionsView(onReceive: { selection = .receive }, onRepeatPayment: repeatPayment)
                    .onPreferenceChange(ActivityHidesTabBar.self) { activityHidesBar = $0 }
                    .modifier(WideTabContent(wide: window.wide, padsForBar: false, barHidden: activityHidesBar))
            }
        }
        // The bar is monochrome like the wide bar; WideTabContent gives the tabs back the brand tint.
        .tint(NoahTokens.Colors.foreground)
        .overlay(alignment: .bottomLeading) {
            if window.wide {
                WideTabBar(selection: $selection)
                    .frame(width: max(0, window.size.width / 2 - window.insets.leading))
                    .padding(.bottom, 18)
                    .ignoresSafeArea(.keyboard)
            }
        }
        .onboardingWindow($window)
        .onChange(of: pendingRepeatPayment) { _, _ in consumeRepeatPayment() }
        .onChange(of: send.controller.locked) { _, _ in consumeRepeatPayment() }
        .onChange(of: send.busy) { _, _ in consumeRepeatPayment() }
        .alert("Payment in progress", isPresented: $scanBlocked) {
            Button("OK", role: .cancel) {}
        } message: { Text(Self.blockedMessage(link: linkBlocked)) }
        .onChange(of: app.paymentLink.pending != nil && app.paymentLinkGate == .open, initial: true) { _, ready in
            if ready, let link = app.paymentLink.take(.open) { open(link, link: true) }
        }
        .environment(history)
        .environment(tracker)
        .environment(settingsSummary)
        .environment(\.scanPayment, ScanPaymentAction(handle: scanned))
        // Activity's links and a recurring payment notification: Home opens the page with Settings beneath.
        .onChange(of: settingsRoute.page != nil, initial: true) { _, pending in if pending { selection = .home } }
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

    static func blockedMessage(link: Bool) -> LocalizedStringKey {
        link ? "Finish the current payment before opening another payment request." : "Finish the current payment before scanning another request."
    }

    private func scanned(_ value: String) { open(value, link: false) }

    private func open(_ value: String, link: Bool) {
        selection = .send
        Task {
            let applied = link ? await PaymentLinkRoute.apply(value, to: send) : await QRScanRoute.apply(value, to: send)
            if !applied { linkBlocked = link; scanBlocked = true }
        }
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
            // swiftlint:disable:next line_length - one literal: translations.mjs reads copy as a single string
            "Wallet data exists on this device, but its recovery phrase is missing. Noah won't replace these files. Keep your recovery phrase and contact support for help recovering this wallet."
        case .secureStorageUnavailable:
            "Noah can't read its secure storage yet. Unlock your device to continue."
        case .invalidSeed:
            // swiftlint:disable:next line_length - one literal: translations.mjs reads copy as a single string
            "Support code: SEED_INVALID. The stored recovery phrase could not be validated. Keep this app installed and contact Noah support at github.com/smolcars/noah. Never send anyone your recovery phrase, private keys, or a wallet database."
        case .seedConflict:
            // swiftlint:disable:next line_length - one literal: translations.mjs reads copy as a single string
            "Support code: WALLET_CONFLICT. Noah found wallet records that cannot be safely matched. Keep this app installed and contact Noah support at github.com/smolcars/noah. Never send anyone your recovery phrase, private keys, or a wallet database."
        case .existingWallet, .onboarding:
            ""
        }
    }
}

/// Wide windows: no system tab bar, and other tabs keep their content clear of the glass bar.
/// Home, Receive and Send keep their own leading pane clear of it. Send hides the phone
/// tab bar on its steps after Amount. Every tab's content keeps the brand tint the bar dropped.
private struct WideTabContent: ViewModifier {
    let wide: Bool
    var padsForBar = true
    var barHidden = false

    func body(content: Content) -> some View {
        content
            .tint(NoahColor.brand)
            .toolbarVisibility(wide || barHidden ? .hidden : .automatic, for: .tabBar)
            .safeAreaPadding(.bottom, wide && padsForBar ? WideTabBar.clearance : 0)
    }
}

/// The four tabs as a Liquid Glass bar at the bottom of the leading pane (prototype `.ph.duo .tabs`).
private struct WideTabBar: View {
    static let clearance: CGFloat = 80
    @Binding var selection: WalletTabs.Tab
    @Namespace private var lens
    @State private var haptics = OnboardingHaptics()
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        GlassGroup {
            HStack(spacing: 0) {
                ForEach(WalletTabs.Tab.allCases, id: \.self) { tab in
                    Button {
                        guard selection != tab else { return }
                        haptics.play(.selection)
                        withAnimation(NoahTokens.Motion.travel(reduceMotion)) { selection = tab }
                    } label: {
                        VStack(spacing: 2) {
                            Image(systemName: tab.symbol).accessibilityHidden(true).font(.system(size: 19, weight: .medium)).frame(height: 24)
                            Text(tab.title).noahText(HomeText.tab).lineLimit(1)
                        }
                        .foregroundStyle(NoahTokens.Colors.foreground)
                        .frame(maxWidth: .infinity, minHeight: 52)
                        .background {
                            if selection == tab {
                                Capsule().fill(NoahTokens.Colors.foreground.opacity(0.08))
                                    .matchedGeometryEffect(id: "lens", in: lens)
                            }
                        }
                        .contentShape(Capsule())
                    }
                    .buttonStyle(.plain)
                    .accessibilityAddTraits(selection == tab ? .isSelected : [])
                    .accessibilityIdentifier("wide-tab-\(tab)")
                }
            }
            .padding(.horizontal, 6)
            .frame(width: 288, height: 62)
            .glassCompat(in: Capsule())
        }
        .onboardingHaptics(haptics)
        .accessibilityElement(children: .contain)
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
