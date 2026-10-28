import SwiftUI

/// Home (docs/native-rewrite/design/home.md). Compact windows get one column;
/// wide ones (the iPhone Duo open or half-open, iPad, landscape) split into two
/// panes at the window's centre, where the Duo folds. Every action links to an
/// existing flow; nothing here changes wallet, payment or backup behaviour.
struct HomeView: View {
    enum Route: Hashable {
        case settings, codes(QRHubModel.Mode), map
        /// A Settings page: after `.settings` when it's a deep link with Settings beneath.
        case page(SettingsPage)

        var isSettings: Bool {
            switch self {
            case .settings, .page: true
            default: false
            }
        }
    }

    enum Sheet: Hashable, Identifiable {
        case balance, rounds, transaction(String)
        var id: Self { self }
    }

    @Environment(AppModel.self) private var model
    @Environment(HistoryModel.self) private var history
    @Environment(\.scenePhase) private var environmentPhase
    @Environment(\.locale) private var locale
    @Environment(\.openURL) private var openURL
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.homeFixture) private var fixture
    @State private var home: HomeModel
    /// The "new" set, shared with Activity.
    @Environment(HomeActivityTracker.self) private var tracker
    /// The foreground catch-up (`AppModel.CatchUp.finished`) history has been reloaded for.
    @State private var historyCatchUp = 0
    @State private var renew = OnchainModel(dependencies: .renew())
    @State private var haptics = OnboardingHaptics()
    @State private var path: [Route] = []
    @State private var upgradeNotices: [String] = []
    @State private var window = OnboardingLayout(size: .zero, insets: EdgeInsets())
    @State private var sheet: Sheet?
    @State private var toast: HomeToast?
    @State private var alert: String?
    @State private var emailFlowOpened = false
    @State private var settingsToast: HomeToast?
    @State private var settingsRoute = SettingsRoute.shared
    @Namespace private var settingsZoom
    @State private var scrollY: CGFloat = 0
    @State private var scrollMemory = HomeScrollMemory()
    @State private var entrancePlayed = false
    @State private var qrRevealed = false
    @State private var folding = false
    /// Automatic boarding's outcome, shown under the nudge.
    @State private var autoBoardMessage: String?
    let isActive: Bool
    let onScan: (String) -> Void
    let onOpenHistory: () -> Void
    let onRepeatPayment: (RepeatPayment) -> Void
    let onReceive: () -> Void

    init(isActive: Bool, onScan: @escaping (String) -> Void, onOpenHistory: @escaping () -> Void,
         onRepeatPayment: @escaping (RepeatPayment) -> Void, onReceive: @escaping () -> Void = {}, home: HomeModel? = nil) {
        self.isActive = isActive
        self.onScan = onScan
        self.onOpenHistory = onOpenHistory
        self.onRepeatPayment = onRepeatPayment
        self.onReceive = onReceive
        _home = State(initialValue: home ?? HomeModel())
    }

    private var observationWalletID: String? {
        scenePhase == .active && !model.isWalletSuspended
            ? summary.map { "\($0.fingerprint):\(model.walletSessionRevision)" } : nil
    }

    /// Fixture hosts sit outside a SwiftUI scene, so they count as active.
    private var scenePhase: ScenePhase { fixture == nil ? environmentPhase : .active }

    /// App state, or a screenshot fixture's stand-in for it.
    private var summary: WalletSession.Summary? { fixture.map(\.summary) ?? model.summary }
    private var walletError: String? { fixture.map(\.walletError) ?? model.walletError }
    private var needsRecoveryPhraseBackup: Bool { fixture?.needsRecoveryPhraseBackup ?? model.needsRecoveryPhraseBackup }
    private var updateRequired: Bool { fixture?.updateRequired ?? model.versionCheck.updateRequired }

    var body: some View {
        NavigationStack(path: $path) {
            root
                .toolbar(.hidden, for: .navigationBar)
                .navigationDestination(for: Route.self, destination: destination)
        }
        // Measured on the stack, which stays on screen while Settings is pushed over Home.
        .onboardingWindow($window)
        // The phone's tab bar hides while Settings is open.
        .preference(key: ActivityHidesTabBar.self, value: !window.wide && path.contains(where: \.isSettings))
        .environment(\.settings, settingsNavigator)
        .overlay(alignment: .top) { settingsToastLayer }
        // Activity's links and a recurring payment notification open their page with Settings beneath.
        .onChange(of: settingsRoute.page != nil && isActive, initial: true) { _, ready in
            guard ready else { return }
            let next = settingsRoute.take()
            guard !next.isEmpty else { return }
            sheet = nil
            path = next
        }
        .task(id: settingsToast?.id) {
            guard let current = settingsToast else { return }
            do { try await Task.sleep(for: .seconds(current.seconds)) } catch { return }
            withAnimation(NoahTokens.Motion.travel(reduceMotion)) { if settingsToast?.id == current.id { settingsToast = nil } }
        }
    }

    // MARK: Layout

    private var root: some View {
        ZStack(alignment: .topLeading) {
            NoahTokens.Colors.background.ignoresSafeArea()
            if window.wide { widePanes } else { compactColumn }
            toastLayer
            if window.wide { paneSheets }
        }
        .animation(NoahTokens.Motion.travel(reduceMotion), value: window.wide ? sheet : nil)
        .animation(NoahTokens.Motion.travel(reduceMotion), value: window.wide ? renew.quote?.id : nil)
        // The one automatic-boarding host: outside the compact and wide layouts, so a pose
        // change never rebuilds it (and its claimed deposit review) mid-flow.
        .background {
            if !model.isWalletSuspended { AutoBoardingService(message: $autoBoardMessage) }
        }
        .onChange(of: window.wide) { _, wide in scrollMemory.layoutChanged(toWide: wide) }
        .onboardingHaptics(haptics)
        .modifier(observation)
        .modifier(moments)
        .sheet(item: Binding(get: { window.wide ? nil : sheet }, set: { if !window.wide { sheet = $0 } })) { sheet in
            compactSheet(sheet)
        }
        .sheet(item: Binding(get: { window.wide ? nil : renew.quote }, set: { if !window.wide && $0 == nil { renew.invalidate() } })) { quote in
            OnchainReviewSheet(quote: quote, confirm: confirmRenew)
        }
        .alert("Error", isPresented: Binding(get: { alert != nil }, set: { if !$0 { alert = nil } })) {
            Button("OK", role: .cancel) {}
        } message: { Text(LocalizedStringKey(alert ?? "")) }
    }

    private var compactColumn: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                if model.isWalletSuspended {
                    HomeSuspendedView { openSettings(.advanced) }.padding(.top, 24).modifier(appear(1))
                } else {
                    balanceBlock(tuck: tuck)
                    switch tracker.shown {
                    case .fresh:
                        getPaidCard(qrSide: min(212, max(168, window.size.height - 500)))
                            .padding(.top, 24).modifier(appear(5))
                    case .everyday:
                        recent.padding(.top, 30).modifier(appear(5))
                            .transition(.homeRise(reduceMotion))
                    case .loading, .suspended:
                        EmptyView()
                    }
                }
            }
            .padding(.top, 10).padding(.bottom, 32)
            .onboardingPane()
        }
        .modifier(HomeScrollKeeper(area: .compact, memory: scrollMemory) { scrollY = $0 })
        .refreshable { await pullToRefresh() }
        .modifier(HomeTopBarInset(scrolled: scrollY > 2) {
            topBar(tucked: true).padding(.horizontal, 16).padding(.top, 4).padding(.bottom, 6).modifier(appear(0))
        })
    }

    private var tuck: CGFloat { min(1, max(0, (scrollY - 4) / 52)) }

    private var leadingWidth: CGFloat { max(0, window.size.width / 2 - window.insets.leading) }

    private var widePanes: some View {
        HStack(spacing: 0) {
            VStack(spacing: 0) {
                topBar(tucked: false).padding(.horizontal, 20).padding(.top, 14).modifier(appear(0))
                ScrollView {
                    VStack(alignment: .leading, spacing: 0) {
                        if model.isWalletSuspended {
                            HomeSuspendedView { openSettings(.advanced) }.padding(.top, 24).modifier(appear(1))
                        } else {
                            balanceBlock(tuck: 0)
                        }
                    }
                    .padding(.horizontal, 32).padding(.top, 12).padding(.bottom, 110)
                    .frame(maxWidth: OnboardingLayout.paneContentWidth + 64)
                    .frame(maxWidth: .infinity)
                }
                .modifier(HomeScrollKeeper(area: .leading, memory: scrollMemory))
                .refreshable { await pullToRefresh() }
            }
            .frame(width: leadingWidth)
            trailingPane
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }

    @ViewBuilder private var trailingPane: some View {
        if !model.isWalletSuspended {
            switch tracker.shown {
            case .fresh:
                // Centred when it fits, scrolling only when it can't (as FitOrScroll), with its own position.
                GeometryReader { proxy in
                    ScrollView {
                        getPaidCard(qrSide: 228)
                            .frame(maxWidth: OnboardingLayout.paneContentWidth)
                            .padding(.horizontal, 32).padding(.vertical, 28)
                            .frame(maxWidth: .infinity, minHeight: proxy.size.height)
                            .modifier(appear(5))
                    }
                    .scrollBounceBehavior(.basedOnSize)
                    .modifier(HomeScrollKeeper(area: .fresh, memory: scrollMemory))
                }
            case .everyday:
                ScrollView {
                    HomeActivityList(history: history, ownDomain: ownDomain, unit: model.amountUnit, hidden: model.isBalanceHidden,
                                     newIDs: tracker.newIDs, onRetry: retryHistory, onSelect: select)
                        .padding(.horizontal, 32).padding(.vertical, 28)
                        .frame(maxWidth: OnboardingLayout.paneContentWidth + 64)
                        .frame(maxWidth: .infinity)
                }
                .modifier(HomeScrollKeeper(area: .activity, memory: scrollMemory, rows: history.transactions.map(\.id)))
                .refreshable { await pullToRefresh() }
                .modifier(appear(5))
                .transition(.homeRise(reduceMotion))
            case .loading, .suspended:
                Color.clear
            }
        }
    }

    /// Balance, fiat, the status line and the nudge.
    @ViewBuilder private func balanceBlock(tuck: CGFloat) -> some View {
        HomeBalanceView(sats: summary?.balance.totalSats, unit: model.amountUnit, hidden: model.isBalanceHidden,
                        tuck: tuck, onDetails: openBalance, onToggleHidden: toggleHidden)
            .padding(.top, 8).modifier(appear(1))
        fiat.padding(.top, 10).modifier(appear(2))
        HomeStatusRow(line: status, balance: summary?.balance, unit: model.amountUnit, hidden: model.isBalanceHidden,
                      action: status?.tap(roundsActive: !home.activeRounds.isEmpty).map { tap in { statusTapped(tap) } })
            .modifier(appear(3))
        HomeNudgeSlot(nudge: nudge) { nudge in
            HomeNudgeCard(nudge: nudge, unit: model.amountUnit, hidden: model.isBalanceHidden, busy: busy(nudge),
                          action: { act(nudge) }, dismiss: { dismiss(nudge) })
        }
        .modifier(appear(4))
        if let autoBoardMessage {
            Text(LocalizedStringKey(autoBoardMessage))
                .noahText(HomeText.footnote)
                .foregroundStyle(NoahTokens.Colors.foreground)
                .frame(maxWidth: .infinity, alignment: .leading)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.vertical, 12).padding(.horizontal, 16)
                .glassCompat(in: RoundedRectangle(cornerRadius: 22, style: .continuous))
                .padding(.top, 12)
                .accessibilityIdentifier("home-auto-board-message")
        }
    }

    private var fiat: some View {
        Group {
            if model.isBalanceHidden {
                Text(verbatim: HomeModel.hiddenFiat(currency: model.preferredCurrency))
            } else if let sats = summary?.balance.totalSats, let price = home.btcPrice {
                Text(verbatim: FiatCurrency.display(sats: Int64(clamping: sats), btcPrice: price,
                                                    currency: model.preferredCurrency, locale: locale))
                    .contentTransition(.numericText())
            } else {
                Text(verbatim: " ")
            }
        }
        .noahText(NoahTokens.Typography.sub)
        .foregroundStyle(NoahTokens.Colors.muted)
        .animation(NoahTokens.Motion.travel(reduceMotion), value: summary?.balance.totalSats)
    }

    private func topBar(tucked: Bool) -> some View {
        HomeTopBar(address: address, variant: model.variant,
                   tucked: tucked && !model.isWalletSuspended ? tuckedBalance : nil, tuckShown: tuck > 0.7,
                   settingsZoom: window.wide ? nil : settingsZoom,
                   onProfile: { open(.page(.profile)) }, onScan: { open(.codes(.scan)) }, onSettings: { open(.settings) },
                   activity: activity, backedUpAt: backedUpAt)
    }

    private var tuckedBalance: String? {
        guard let sats = summary?.balance.totalSats else { return nil }
        if model.isBalanceHidden { return model.amountUnit == .bip177 ? "₿••••" : "•••• sats" }
        return BitcoinAmount.format(sats: Int64(clamping: sats), unit: model.amountUnit, locale: locale)
    }

    private var recent: some View {
        HomeRecentList(history: history, ownDomain: ownDomain, unit: model.amountUnit, hidden: model.isBalanceHidden,
                       newIDs: tracker.newIDs, animateRows: !entrancePlayed,
                       onSeeAll: { haptics.play(.selection); onOpenHistory() }, onRetry: retryHistory, onSelect: select)
    }

    private func getPaidCard(qrSide: CGFloat) -> some View {
        HomeGetPaidCard(address: address, qrSide: qrSide, celebration: tracker.celebration, animateQR: !qrRevealed,
                        unit: model.amountUnit, hidden: model.isBalanceHidden,
                        onCopy: copyAddress, onShare: { haptics.play(.light) }, onProfile: { open(.page(.profile)) })
            .opacity(folding ? 0 : 1)
            .scaleEffect(folding && !reduceMotion ? 0.96 : 1)
            // Reduce Motion fades the card without folding it; Recent then fades in on its own.
            .frame(height: folding && !reduceMotion ? 0 : nil, alignment: .top)
            // Clip only the fold, never the card's shadow.
            .clipShape(Rectangle().inset(by: folding ? 0 : -48))
            .task {
                do { try await Task.sleep(for: .seconds(1.6)) } catch { return }
                qrRevealed = true
            }
    }

    /// The entrance: content rises in, 40 ms apart, once per Home (not again on a pose change).
    private func appear(_ order: Int) -> HomeAppear {
        HomeAppear(order: order, animated: !entrancePlayed)
    }

    // MARK: Sheets

    @ViewBuilder private func compactSheet(_ sheet: Sheet) -> some View {
        switch sheet {
        case .balance:
            if let balance = summary?.balance {
                HomeBalanceSheet(balance: balance, unit: model.amountUnit, hidden: model.isBalanceHidden)
                    .padding(.top, 14)
                    .presentationDetents([.medium, .large])
                    .presentationDragIndicator(.visible)
            }
        case .rounds:
            PendingRoundsView(rounds: home.activeRounds) { self.sheet = nil }
        case .transaction(let id):
            if let transaction = history.transactions.first(where: { $0.id == id }) {
                NavigationStack { detail(transaction) }
                    .presentationDetents([.large])
                    .presentationDragIndicator(.visible)
            }
        }
    }

    /// Wide windows: sheets stay in the trailing pane, clear of the fold.
    @ViewBuilder private var paneSheets: some View {
        let width = max(0, window.size.width / 2 - window.insets.trailing)
        if let sheet {
            switch sheet {
            case .balance:
                if let balance = summary?.balance {
                    HomePaneSheet(width: width, onDismiss: closeSheet) {
                        HomeBalanceSheet(balance: balance, unit: model.amountUnit, hidden: model.isBalanceHidden)
                            .frame(height: max(200, min(560, window.size.height - window.insets.top - window.insets.bottom - 80)))
                    }
                }
            case .rounds:
                HomePaneSheet(width: width, fitted: false, onDismiss: closeSheet) {
                    PendingRoundsView(rounds: home.activeRounds, onClose: closeSheet)
                }
            case .transaction(let id):
                if let transaction = history.transactions.first(where: { $0.id == id }) {
                    HomePaneSheet(width: width, fitted: false, onDismiss: closeSheet) {
                        NavigationStack { detail(transaction) }
                    }
                }
            }
        }
        if let quote = renew.quote {
            HomePaneSheet(width: width, fitted: false, onDismiss: { renew.invalidate() }) {
                OnchainReviewSheet(quote: quote, confirm: confirmRenew, onCancel: { renew.invalidate() })
            }
        }
    }

    private func detail(_ transaction: HistoryTransaction) -> some View {
        TransactionDetailView(transaction: transaction, onRepeatPayment: { details in
            sheet = nil
            onRepeatPayment(details)
        }, onClose: closeSheet)
    }

    private func closeSheet() {
        withAnimation(NoahTokens.Motion.travel(reduceMotion)) { sheet = nil }
    }

    // MARK: Toast

    private var toastLayer: some View {
        let text = toast?.text ?? statusToast
        return VStack {
            if let text {
                HomeToastView(text: text)
                    .id(text)
                    .transition(.homeToast(reduceMotion))
            }
        }
        .frame(width: window.wide ? leadingWidth : nil)
        .frame(maxWidth: window.wide ? nil : .infinity)
        .padding(.top, window.wide ? 66 : 60)
        .animation(NoahTokens.Motion.travel(reduceMotion), value: text)
        .allowsHitTesting(false)
    }

    /// Automatic boarding shows for five seconds, as its banner did.
    private var statusToast: String? {
        if model.isWalletSuspended { return nil }
        if let board = home.autoBoard {
            return L10n.text("{amount} available in Ark",
                             WalletBalance.display(board.netSats, hidden: model.isBalanceHidden, unit: model.amountUnit, locale: locale),
                             locale: locale.identifier)
        }
        return nil
    }

    private func showToast(_ text: String) {
        withAnimation(NoahTokens.Motion.travel(reduceMotion)) { toast = HomeToast(text: text) }
    }

    // MARK: State
}

/// Home's derived state, actions and Settings navigation.
extension HomeView {
    private var address: String? {
        model.server.state.lightningAddress.flatMap { $0.isEmpty ? nil : $0 }
    }

    /// Payments to this domain are to other Noah users.
    private var ownDomain: String {
        address?.split(separator: "@").last.map(String.init) ?? LightningIdentity.domain(for: model.variant)
    }

    private var layoutState: HomeLayoutState {
        .of(suspended: model.isWalletSuspended, balance: summary?.balance, historyLoaded: !history.isLoading,
            historyFailed: history.error != nil, transactions: history.transactions)
    }

    private var visible: Bool { isActive && scenePhase == .active && path.isEmpty }

    private var backupBanner: HomeBanner? {
        HomeBanner.backup(home.backup, now: home.bannerNow.timeIntervalSince1970 * 1000, locale: locale)
    }

    private var nudge: HomeNudge? {
        HomeNudge.current(.init(
            upgradeNotices: upgradeNotices, renewal: home.renewal, backup: backupBanner,
            needsRecoveryPhraseBackup: needsRecoveryPhraseBackup,
            isEmailVerified: model.server.state.isEmailVerified, isEmailPromptDismissed: model.server.state.isEmailPromptDismissed,
            updateRequired: updateRequired, suspended: model.isWalletSuspended))
    }

    private var status: HomeStatusLine? {
        HomeStatusLine.current(error: walletError, balance: summary?.balance,
                               incomingOnchain: HomeStatusLine.incomingOnchain(history.transactions), activeRounds: home.activeRounds.count)
    }

    /// What the top bar's activity glyph names while it turns: a backup, or another background task.
    private var activity: LocalizedStringKey? {
        if model.isWalletSuspended { return nil }
        if home.backup.lastBackupStatus == .inProgress { return "Backing up wallet" }
        return home.backgroundMessage.map { LocalizedStringKey($0) }
    }

    /// A backup that completed in the last few seconds, for the glyph's check.
    private var backedUpAt: Double? {
        home.backup.lastBackupStatus == .success && backupBanner != nil && !model.isWalletSuspended ? home.backup.lastBackupAt : nil
    }

    var trackerInput: TrackerInput {
        TrackerInput(state: layoutState, ids: history.transactions.map(\.id), sats: summary?.balance.totalSats ?? 0,
                     visible: visible, wallet: summary?.fingerprint, revision: fixture == nil ? model.summaryRevision : nil,
                     historyBusy: history.isLoading || history.isRefreshing,
                     caughtUp: fixture != nil || (history.error == nil && model.catchUp.isCaughtUp(
                         summaryRevision: model.summaryRevision, historyReloadedFor: historyCatchUp)))
    }

    // MARK: Actions

    private func open(_ route: Route) {
        haptics.play(.light)
        if route == .page(.email) { emailFlowOpened = true }
        path.append(route)
    }

    /// A nudge's page, with Settings beneath it.
    private func openSettings(_ page: SettingsPage) {
        haptics.play(.light)
        path = SettingsPath.deepLink(page, from: path)
    }

    private func openBalance() {
        guard summary != nil else { return }
        haptics.play(.light)
        withAnimation(window.wide ? NoahTokens.Motion.travel(reduceMotion) : nil) { sheet = .balance }
    }

    private func toggleHidden() {
        haptics.play(.medium)
        withAnimation(NoahTokens.Motion.travel(reduceMotion, NoahTokens.Motion.bouncy)) { model.isBalanceHidden.toggle() }
    }

    private func statusTapped(_ tap: HomeStatusLine.Tap) {
        switch tap {
        case .balance: openBalance()
        case .rounds:
            haptics.play(.light)
            withAnimation(window.wide ? NoahTokens.Motion.travel(reduceMotion) : nil) { sheet = .rounds }
        }
    }

    private func select(_ transaction: HistoryTransaction) {
        haptics.play(.light)
        withAnimation(window.wide ? NoahTokens.Motion.travel(reduceMotion) : nil) { sheet = .transaction(transaction.id) }
    }

    private func copyAddress() {
        guard let address else { return }
        UIPasteboard.general.string = address
        haptics.play(.success)
        showToast("Copied")
    }

    private func busy(_ nudge: HomeNudge) -> Bool {
        switch nudge {
        case .renew: renew.busy
        case .backupFailed: home.isRetryingBackup
        default: false
        }
    }

    private func act(_ nudge: HomeNudge) {
        switch nudge {
        case .notice: break
        case .renew(let renewal):
            haptics.play(.light)
            // Opens the refresh review for exactly the coins that are still eligible (coins can expire,
            // lock or renew automatically after the nudge appeared); nothing is submitted without confirmation.
            Task {
                let ids = HomeRenewal.eligible(renewal.ids, in: await home.currentRenewal())
                guard !ids.isEmpty else { return }
                await renew.review(.refresh, ids: ids)
                if let error = renew.error { alert = error }
            }
        case .backupFailed:
            haptics.play(.light)
            Task { await home.retryBackup() }
        case .backUp: openSettings(.recovery)
        case .email: open(.page(.email))
        case .update:
            haptics.play(.light)
            openURL(AppVersionCheck.updateURL(variant: model.variant))
        }
    }

    private func dismiss(_ nudge: HomeNudge) {
        haptics.play(.light)
        switch nudge {
        case .notice(let text): upgradeNotices.removeAll { $0 == text }
        case .email: model.server.dismissEmailPrompt()
        default: break
        }
    }

    /// Submits only while every reviewed coin is still refreshable and inside the window;
    /// `WalletSession.executeRenewal` checks that under the wallet's locks. Submission isn't
    /// renewal, so there's no success toast: the nudge folds away once nothing is left to renew.
    private func confirmRenew() {
        Task {
            await renew.confirm()
            if let error = renew.error { alert = error }
            await home.refreshRenewal()
        }
    }

    private func retryHistory() {
        Task { await history.refresh(currency: model.preferredCurrency) }
    }

    private func pullToRefresh() async {
        guard !model.isWalletSuspended else { return }
        await model.sync()
        guard !model.isWalletSuspended else { return }
        await history.refresh(currency: model.preferredCurrency)
        await home.refresh(currency: model.preferredCurrency)
    }

    /// The first payment: the receipt shows on the card for about 2.4 s, then the
    /// card folds away and Recent takes its place.
    private func playCelebration() async {
        guard let celebration = tracker.celebration else { folding = false; return }
        let elapsed = Date().timeIntervalSince(celebration.start)
        if elapsed < 0.3 { haptics.play(.success) }
        do { try await Task.sleep(for: .seconds(max(0, 2.4 - elapsed))) } catch { return }
        withAnimation(NoahTokens.Motion.travel(reduceMotion, NoahTokens.Motion.soft)) { folding = true }
        do { try await Task.sleep(for: .milliseconds(520)) } catch { return }
        withAnimation(NoahTokens.Motion.travel(reduceMotion)) {
            folding = false
            tracker.finishCelebration(celebration.id)
        }
        haptics.play(.light)
    }

    @ViewBuilder private func destination(_ route: Route) -> some View {
        switch route {
        case .settings:
            // Phones zoom out of the gear; wide windows swap the panes.
            SettingsHost(page: nil).navigationTransition(.zoom(sourceID: "settings", in: settingsZoom))
        case .codes(let mode): QRHubView(isActive: isActive, mode: mode, onScan: onScan)
        case .map: BtcMapView()
        case .page(let page): SettingsHost(page: page)
        }
    }

    // MARK: Settings

    /// Phones push Settings' pages with the platform slide. Wide windows keep the list where it
    /// is and swap the trailing page, so those moves skip the push animation.
    private var settingsNavigator: SettingsNavigator {
        SettingsNavigator(
            live: true, wide: window.wide, leadingWidth: leadingWidth,
            select: { page in
                let next = SettingsPath.select(page, in: path, wide: window.wide)
                if window.wide { quietly { path = next } } else { path = next }
            },
            open: { page in
                if window.wide { quietly { path.append(.page(page)) } } else { path.append(.page(page)) }
            },
            back: {
                guard !path.isEmpty else { return }
                if window.wide { quietly { _ = path.removeLast() } } else { path.removeLast() }
            },
            swap: { base, page in
                let next = (path.lastIndex(of: .page(base)).map { Array(path.prefix(through: $0)) } ?? path) + [.page(page)]
                if window.wide { quietly { path = next } } else { path = next }
            },
            leave: { page in
                guard let page, path.last == .page(page) else { return }
                if window.wide { quietly { _ = path.removeLast() } } else { path.removeLast() }
            },
            close: { path = SettingsPath.base(path) },
            myCode: { path.append(.codes(.myCode)) },
            map: { path.append(.map) },
            receive: onReceive,
            toast: { text in withAnimation(NoahTokens.Motion.travel(reduceMotion)) { settingsToast = HomeToast(text: text) } })
    }

    private func quietly(_ change: () -> Void) {
        var transaction = Transaction()
        transaction.disablesAnimations = true
        withTransaction(transaction, change)
    }

    /// Settings' toasts sit over the page (the trailing pane on wide windows) and outlive it.
    private var settingsToastLayer: some View {
        HStack(spacing: 0) {
            if window.wide { Color.clear.frame(width: leadingWidth) }
            VStack {
                if let settingsToast {
                    HomeToastView(text: settingsToast.text).id(settingsToast.id).transition(.homeToast(reduceMotion))
                }
            }
            .frame(maxWidth: .infinity)
        }
        .padding(.top, 8)
        .animation(NoahTokens.Motion.travel(reduceMotion), value: settingsToast)
        .allowsHitTesting(false)
    }

    // MARK: Moments

    /// Upgrade notices, the tracker, timers for the first payment and toasts, and the review lifecycle.
    private var moments: some ViewModifier {
        HomeMoments(
            noticesKey: scenePhase == .active && isActive, trackerInput: trackerInput,
            awaitingRefresh: fixture == nil && visible && tracker.shown == .fresh && !tracker.armed
                ? "\(summary?.fingerprint ?? ""):\(model.walletSessionRevision)" : nil,
            requestVisitRefresh: {
                // Give the app's own launch or foreground refresh a moment, then ask for the same
                // read-only refresh; a failure retries (backing off) rather than leaving detection off.
                // It can't arm a visit on its own while a foreground catch-up is still running.
                var delay = 3.0
                while !Task.isCancelled && !tracker.armed {
                    do { try await Task.sleep(for: .seconds(delay)) } catch { return }
                    await model.refresh()
                    delay = min(delay * 2, 30)
                }
            },
            catchUpID: fixture == nil ? model.catchUp.finished : nil,
            reloadHistoryAfterCatchUp: {
                // Read what the catch-up imported before arming, retrying a failed read.
                let finished = model.catchUp.finished
                var delay = 3.0
                while historyCatchUp != finished && !Task.isCancelled {
                    await history.refresh(currency: model.preferredCurrency)
                    if history.error == nil && !Task.isCancelled { historyCatchUp = finished; return }
                    do { try await Task.sleep(for: .seconds(delay)) } catch { return }
                    delay = min(delay * 2, 30)
                }
            },
            celebrationID: tracker.celebration?.id, toastID: toast?.id, wallet: summary?.fingerprint,
            suspended: model.isWalletSuspended, emailVerified: model.server.state.isEmailVerified,
            takeNotices: {
                guard scenePhase == .active, isActive, upgradeNotices.isEmpty else { return }
                upgradeNotices = SettingsStorage.shared.takeUpgradeNotices()
            },
            update: { input in
                withAnimation(NoahTokens.Motion.travel(reduceMotion)) {
                    tracker.update(state: input.state, transactions: history.transactions, balanceSats: input.sats,
                                   visible: input.visible, walletKey: input.wallet, summaryRevision: input.revision,
                                   historyBusy: input.historyBusy, caughtUp: input.caughtUp)
                }
            },
            celebrate: playCelebration,
            expireToast: {
                guard let current = toast else { return }
                do { try await Task.sleep(for: .seconds(current.seconds)) } catch { return }
                withAnimation(NoahTokens.Motion.travel(reduceMotion)) { if toast?.id == current.id { toast = nil } }
            },
            entrance: {
                do { try await Task.sleep(for: .seconds(1)) } catch { return }
                entrancePlayed = true
            },
            invalidateReview: { renew.invalidate() },
            suspendedChanged: { suspended in
                guard suspended else { return }
                renew.invalidate()
                sheet = nil
            },
            recordsArrived: {
                // The balance follows its records (the same read the 60 s foreground refresh does),
                // so a payment's receipt and balance land together.
                guard fixture == nil, scenePhase == .active else { return }
                Task { await model.refresh() }
            },
            emailVerifiedChanged: { verified in
                guard verified == true, emailFlowOpened else { return }
                emailFlowOpened = false
                haptics.play(.success)
                showToast("Email added")
            })
    }

    // MARK: Observation

    /// Today's refresh moments: Home appearing, wallet events, round checks, banner expiry.
    private var observation: HomeObservation {
        HomeObservation(home: home, observationWalletID: observationWalletID, isActive: isActive)
    }
}

private extension HomeView {
    struct HomeObservation: ViewModifier {
        let home: HomeModel
        let observationWalletID: String?
        let isActive: Bool
        @Environment(AppModel.self) private var model

        func body(content: Content) -> some View {
            content
                .task { await model.versionCheck.checkOnce() }
                .task(id: observationWalletID) {
                    guard observationWalletID != nil else { return }
                    await home.refresh(currency: model.preferredCurrency)
                }
                .task(id: observationWalletID) {
                    guard observationWalletID != nil else { return }
                    await home.observeBackground()
                }
                .task(id: observationWalletID) {
                    guard observationWalletID != nil else { return }
                    await home.observeWallet()
                }
                .task(id: observationWalletID) {
                    guard observationWalletID != nil else { return }
                    await home.observeBackup()
                }
                .task(id: observationWalletID) {
                    guard observationWalletID != nil else { return }
                    await home.observeBoarding()
                }
                .task(id: observationWalletID == nil ? nil : home.nextRoundCheck) {
                    guard observationWalletID != nil else { return }
                    await home.waitForRoundCheck()
                }
                .task(id: observationWalletID == nil ? nil : home.nextBannerExpiry) {
                    guard observationWalletID != nil else { return }
                    await home.waitForBannerExpiry()
                }
                .task(id: model.preferredCurrency) { await home.loadPrice(currency: model.preferredCurrency) }
        }
    }
}
