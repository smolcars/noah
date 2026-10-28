import LocalAuthentication
import SwiftUI
import UserNotifications

/// The live values on Settings' rows. One instance under Home, shared by the list in every pane.
@MainActor @Observable
final class SettingsSummary {
    struct Dependencies {
        var overview: () async throws -> OnchainOverview
        var notificationsOn: () async -> Bool
        var storage: SettingsStorage
        /// Where the exit pages' reads and submissions meet (`OnchainModel`).
        var shared: SharedOverview?

        static var live: Self {
            Self(overview: { try await WalletSession.shared.onchainOverview() },
                 notificationsOn: {
                     let status = await UNUserNotificationCenter.current().notificationSettings().authorizationStatus
                     return [.authorized, .provisional, .ephemeral].contains(status)
                 },
                 storage: .shared, shared: .shared)
        }
    }

    /// What the rows show: Settings' own read, or, once you cancel, claim or start something after it began, the
    /// newest read a page made since, so Back to Settings shows your exits as you left them.
    var overview: OnchainOverview? {
        // Read first, so a page's read updates the rows.
        let page = dependencies.shared?.overview
        guard let page, page.walletID == own?.walletID, ownEpoch != dependencies.shared?.epoch else { return own }
        return page
    }
    private var own: OnchainOverview?
    // swiftlint:disable:next discouraged_optional_boolean - nil until the permission is read
    private(set) var notificationsOn: Bool?
    private(set) var backup = PersistedBackupState()
    private(set) var displayName = ""
    private(set) var debug = false
    @ObservationIgnored private let dependencies: Dependencies
    @ObservationIgnored private var loadedAt = Date.distantPast
    /// The submissions counted (`SharedOverview.epoch`) when Settings' own read began.
    @ObservationIgnored private var ownEpoch: Int?
    @ObservationIgnored private var loading = false

    init(dependencies: Dependencies = .live) {
        self.dependencies = dependencies
        reloadLocal()
    }

    /// Wallet reads at most every 10 s unless forced (after a renew or a board) or a page has submitted something since.
    func refresh(suspended: Bool, force: Bool = false) async {
        reloadLocal()
        let epoch = dependencies.shared?.epoch
        guard !loading, force || epoch != ownEpoch || Date().timeIntervalSince(loadedAt) > 10 else { return }
        loading = true
        defer { loading = false }
        notificationsOn = await dependencies.notificationsOn()
        if suspended { own = nil }
        else if let latest = try? await dependencies.overview() { own = latest; ownEpoch = epoch }
        loadedAt = Date()
    }

    /// Settings storage only: backup state, your name, debug mode.
    func reloadLocal() {
        let storage = dependencies.storage
        backup = storage.persistedState(PersistedBackupState.self, key: PersistedBackupState.key) ?? .init()
        displayName = storage.persistedState(PersistedProfileState.self, key: PersistedProfileState.key)?.displayName ?? ""
        debug = DebugMode.isOn(storage)
    }

    func setDebug(_ on: Bool) throws {
        try DebugMode.set(on, dependencies.storage)
        debug = on
    }
}

/// Root sheets: Display's three pickers and Delete wallet.
enum SettingsRootSheet: String, Identifiable, Equatable {
    case unit, currency, language, delete
    var id: Self { self }
}

/// Settings itself: you, then Security, Bitcoin, Display, App, Help and Delete wallet, then the
/// version. Rows are a title and a live value; their explanations live on the pages they open.
struct SettingsList: View {
    /// Wide windows highlight the open page's row.
    let selected: SettingsPage?
    @Binding var sheet: SettingsRootSheet?
    @Binding var offset: CGFloat
    /// In a wide window's leading pane, with its own bar.
    var leading = false
    /// Starts where the other layout's list was, so a pose change keeps the scroll.
    @State private var position: ScrollPosition

    init(selected: SettingsPage?, sheet: Binding<SettingsRootSheet?>, offset: Binding<CGFloat>, leading: Bool = false) {
        self.selected = selected
        _sheet = sheet
        _offset = offset
        self.leading = leading
        _position = State(initialValue: ScrollPosition(y: offset.wrappedValue))
    }

    @Environment(AppModel.self) private var app
    @Environment(WalletLock.self) private var lock
    @Environment(SettingsSummary.self) private var summary
    @Environment(\.settings) private var settings
    @Environment(\.locale) private var locale
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var executor = RecurringExecutor.shared
    @State private var unlock = DebugUnlock()
    @State private var haptics = OnboardingHaptics()
    @State private var error: String?
    @Environment(\.settingsFixture) private var fixture

    private var language: String { locale.identifier }
    private var walletSummary: WalletSession.Summary? { fixture?.summary ?? app.summary }

    var body: some View {
        let list = ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                me
                security
                bitcoin
                display
                appGroup
                SettingsGroup(label: "Help") {
                    row("Feedback", icon: "bubble.left", page: .feedback, id: "settings-feedback")
                    row("About Noah", icon: "sailboat", page: .about, id: "settings-about", last: true)
                }
                SettingsGroup {
                    SettingsRow(icon: "trash", title: L10n.text("Delete wallet", locale: language), danger: true, hairline: false,
                                id: "settings-delete-wallet") { open(.delete) }
                }
                if let error {
                    Text(LocalizedStringKey(error)).noahText(NoahTokens.Typography.sub).foregroundStyle(NoahTokens.Colors.failure).padding(.top, 12)
                }
                versionMark
            }
            .padding(.horizontal, leading ? 32 : NoahTokens.Space.screen)
            .padding(.top, leading ? 6 : 0)
            .padding(.bottom, 40)
            .frame(maxWidth: 640)
            .frame(maxWidth: .infinity)
        }
        .background(NoahTokens.Colors.background.ignoresSafeArea())
        .scrollPosition($position)
        .onScrollGeometryChange(for: CGFloat.self) { $0.contentOffset.y + $0.contentInsets.top } action: { _, y in offset = y }
        .onboardingHaptics(haptics)
        .task(id: scenePhase == .active) {
            guard scenePhase == .active else { return }
            await summary.refresh(suspended: app.isWalletSuspended)
            // Backup runs in the background; its row follows it.
            while !Task.isCancelled {
                do { try await Task.sleep(for: .seconds(2)) } catch { return }
                summary.reloadLocal()
            }
        }
        .task {
            if let fixture { executor = fixture.executor }
            await executor.reload()
        }
        .accessibilityIdentifier("settings-list")
        if leading {
            list.safeAreaInset(edge: .top, spacing: 0) {
                SettingsPaneBar(title: "Settings", back: settings.close) { EmptyView() }
            }
        } else {
            list.settingsChrome("Settings")
        }
    }

    private var appGroup: some View {
        SettingsGroup(label: "App") {
            row(
                "Notifications",
                icon: "bell",
                page: .notifications,
                id: "settings-notifications",
                value: notificationsValue,
                dot: summary.notificationsOn == false ? .failure : .hidden,
                failure: summary.notificationsOn == false)
            row(
                "Advanced",
                icon: "slider.horizontal.3",
                page: .advanced,
                id: "settings-advanced",
                value: app.isWalletSuspended ? L10n.text("Suspended", locale: language) : nil,
                failure: app.isWalletSuspended,
                last: true)
        }
    }

    // MARK: You

    private var username: String? { app.server.state.lightningAddress?.split(separator: "@").first.map(String.init) }

    private var me: some View {
        Button { open(.profile) } label: {
            HStack(spacing: 16) {
                SettingsInitial(name: username, size: 52)
                VStack(alignment: .leading, spacing: 0) {
                    HStack(spacing: 8) {
                        Text(verbatim: summary.displayName.isEmpty ? (username ?? L10n.text("Profile", locale: language)) : summary.displayName)
                            .noahText(SettingsText.name).lineLimit(1)
                        if let badge = app.variant.badge {
                            Text(verbatim: badge).noahText(HomeText.badge)
                                .foregroundStyle(NoahTokens.Colors.orangeText)
                                .padding(.horizontal, 7).padding(.vertical, 2)
                                .background(NoahTokens.Colors.orangeTint, in: Capsule())
                        }
                    }
                    if let address = app.server.state.lightningAddress {
                        Text(verbatim: address).noahText(NoahTokens.Typography.sub).foregroundStyle(NoahTokens.Colors.muted).lineLimit(1)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                Image(systemName: "chevron.right").font(.system(size: 14, weight: .semibold)).foregroundStyle(NoahTokens.Colors.muted)
            }
            .padding(.top, 8).padding(.bottom, 10)
            .background {
                RoundedRectangle(cornerRadius: 20, style: .continuous).fill(NoahTokens.Colors.soft)
                    .padding(.horizontal, -12)
                    .opacity(selected == .profile ? 1 : 0)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(SettingsRowPressStyle())
        .accessibilityLabel(Text("Profile"))
        .accessibilityIdentifier("settings-profile")
    }

    // MARK: Groups

    private var security: some View {
        SettingsGroup(label: "Security") {
            let backup = BackupStatus(summary.backup, enabled: app.server.state.isBackupEnabled == true)
            row(
                "Backup",
                icon: "icloud.and.arrow.up",
                page: .backup,
                id: "settings-backup",
                value: L10n.text(backup.rowValue(enabled: app.server.state.isBackupEnabled == true), locale: language),
                dot: backup.mark == .failed ? .failure : .hidden,
                failure: backup.mark == .failed)
            row(
                "Recovery phrase",
                icon: "key",
                page: .recovery,
                id: "settings-recovery",
                value: app.needsRecoveryPhraseBackup ? L10n.text("Not saved", locale: language) : nil,
                dot: app.needsRecoveryPhraseBackup ? .attention : .hidden,
                last: !(lock.available || lock.policy.enabled))
            if lock.available || lock.policy.enabled {
                SettingsToggleRow(icon: lock.biometry.symbol, title: L10n.text(lock.biometry.title, locale: language),
                                  isOn: Binding(get: { lock.policy.enabled }, set: setLock), hairline: false, id: "settings-app-lock")
            }
        }
        .padding(.top, 16)
    }

    private var bitcoin: some View {
        SettingsGroup(label: "Bitcoin") {
            vtxosRow
            row(
                "On-chain",
                icon: "link",
                page: .onchain,
                id: "settings-onchain",
                value: walletSummary.map { money($0.balance.onchain.confirmed) },
                numeric: true)
            recurringRow
            row(
                "Emergency exit",
                icon: "rectangle.portrait.and.arrow.right",
                page: .exit,
                id: "settings-emergencyExit",
                value: ExitRules.settingsValue(summary.overview?.exits ?? []).map { L10n.text($0, locale: language) })
            SettingsRow(icon: "map", title: L10n.text("Spend bitcoin nearby", locale: language), hairline: false, id: "settings-btcmap") {
                haptics.play(.light)
                settings.map()
            }
        }
    }

    @ViewBuilder private var vtxosRow: some View {
        let vtxos = summary.overview?.vtxos ?? []
        let due = !VtxoList.due(vtxos).isEmpty
        row(
            "VTXOs",
            icon: "cylinder.split.1x2",
            page: .vtxos,
            id: "settings-vtxos",
            value: summary.overview == nil ? nil : due ? L10n.text("Renew", locale: language) : vtxos.count.formatted(.number.locale(locale)),
            dot: due ? .attention : .hidden)
    }

    @ViewBuilder private var recurringRow: some View {
        let approvals = executor.schedules.count(where: { $0.status == "needs_approval" })
        let active = executor.schedules.count(where: { $0.status == "active" })
        row(
            "Recurring payments",
            icon: "arrow.triangle.2.circlepath",
            page: .recurring,
            id: "settings-recurring-payments",
            value: approvals > 0 ? L10n.text("Approve", locale: language) : active > 0 ? L10n.text("{n} active", active, locale: language) : nil,
            dot: approvals > 0 ? .attention : .hidden)
    }

    private var display: some View {
        SettingsGroup(label: "Display") {
            SettingsRow(icon: "₿", title: L10n.text("Bitcoin unit", locale: language), value: app.amountUnit == .bip177 ? "₿" : "sats",
                        id: "settings-bitcoinUnit") { open(.unit) }
            SettingsRow(icon: "banknote", title: L10n.text("Currency", locale: language), value: app.preferredCurrency,
                        id: "settings-currency") { open(.currency) }
            SettingsRow(icon: "globe", title: L10n.text("Language", locale: language), value: SettingsLanguageSheet.name(app.localeIdentifier),
                        id: "settings-language") { open(.language) }
            SettingsToggleRow(icon: "eye.slash", title: L10n.text("Hide amounts", locale: language),
                              isOn: Binding(get: { app.isBalanceHidden }, set: { hidden in
                                  haptics.play(.medium)
                                  withAnimation(NoahTokens.Motion.travel(reduceMotion, NoahTokens.Motion.bouncy)) { app.isBalanceHidden = hidden }
                              }), hairline: false, id: "settings-hide-amounts")
        }
    }

    private var notificationsValue: String? {
        summary.notificationsOn.map { L10n.text($0 ? "On" : "Off", locale: language) }
    }

    /// The small wordmark and version; five taps turn debug mode on.
    private var versionMark: some View {
        Button(action: versionTapped) {
            VStack(spacing: 4) {
                Text(verbatim: "Noah").noahText(HomeText.tucked)
                Text(verbatim: Self.version + (summary.debug ? " · " + L10n.text("debug", locale: language) : ""))
                    .noahText(HomeText.footnote).foregroundStyle(NoahTokens.Colors.muted)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 12)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .padding(.top, 30)
        .accessibilityIdentifier("settings-version")
    }

    static var version: String {
        let build = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String
        return AppServices.shared.appVersion + (build.map { " (\($0))" } ?? "")
    }

    // MARK: Actions

    private func row(_ title: String, icon: String, page: SettingsPage, id: String, value: String? = nil, numeric: Bool = false,
                     dot: SettingsRow.Dot = .hidden, failure: Bool = false, last: Bool = false) -> some View {
        SettingsRow(icon: icon, title: L10n.text(title, locale: language), value: value, numeric: numeric, dot: dot, failure: failure,
                    selected: selected == page, hairline: !last, id: id) {
            haptics.play(.light)
            settings.select(page)
        }
    }

    private func open(_ sheet: SettingsRootSheet) {
        haptics.play(.light)
        withAnimation(settings.wide ? NoahTokens.Motion.travel(reduceMotion) : nil) { self.sheet = sheet }
    }

    private func open(_ page: SettingsPage) {
        haptics.play(.light)
        settings.select(page)
    }

    private func money(_ sats: UInt64) -> String {
        app.isBalanceHidden ? (app.amountUnit == .bip177 ? "₿••••" : "•••• sats")
            : BitcoinAmount.format(sats: Int64(clamping: sats), unit: app.amountUnit, locale: locale)
    }

    /// The switch flips only after Face ID (or the passcode) succeeds.
    private func setLock(_ enabled: Bool) {
        error = nil
        Task {
            do { try await lock.setEnabled(enabled) } catch { self.error = "Couldn't save authentication preference." }
        }
    }

    private func versionTapped() {
        haptics.play(.selection)
        guard !summary.debug else { settings.toast("Debug is on"); return }
        if unlock.tap() {
            do {
                try summary.setDebug(true)
                haptics.play(.success)
                settings.toast("Debug on")
            } catch { self.error = "Couldn't save debug setting." }
        } else if unlock.count > 1 {
            settings.toast(L10n.text("{n} more to turn on debug", unlock.remaining, locale: language))
        }
    }
}

/// Your initial in an orange ring (Home's name chip, larger).
struct SettingsInitial: View {
    let name: String?
    var size: CGFloat = 52

    var body: some View {
        ZStack {
            Circle().strokeBorder(NoahTokens.Colors.orange, lineWidth: size > 60 ? 2.5 : 2)
            if let initial = name?.first {
                Text(verbatim: String(initial).lowercased())
                    .font(NoahTokens.Typography.amount.font(size: size * 0.42))
            } else {
                Image(systemName: "person").font(.system(size: size * 0.38, weight: .medium))
            }
        }
        .foregroundStyle(NoahTokens.Colors.foreground)
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}

extension WalletLock {
    /// The device's biometric, for the Security row: Face ID, Touch ID or Optic ID, else the passcode lock.
    var biometry: (title: String, symbol: String) {
        switch biometryType {
        case .faceID: return ("Face ID", "faceid")
        case .touchID: return ("Touch ID", "touchid")
        case .opticID: return ("Optic ID", "opticid")
        default: return ("App lock", "lock")
        }
    }
}

// MARK: Root sheets

struct SettingsRootSheetView: View {
    let sheet: SettingsRootSheet
    @Binding var current: SettingsRootSheet?
    let deletion: WalletDeletion

    var body: some View {
        switch sheet {
        case .unit: SettingsUnitSheet(close: close)
        case .currency: SettingsCurrencySheet(close: close)
        case .language: SettingsLanguageSheet(close: close)
        case .delete: DeleteWalletSheet(deletion: deletion, close: close)
        }
    }

    private func close() { current = nil }
}

/// Two cards with your balance written both ways.
private struct SettingsUnitSheet: View {
    let close: () -> Void
    @Environment(\.settingsFixture) private var fixture
    @Environment(AppModel.self) private var app
    @Environment(\.locale) private var locale
    @State private var haptics = OnboardingHaptics()

    var body: some View {
        SettingsSheetBody(title: L10n.text("Bitcoin unit", locale: locale.identifier), line: L10n.text("How amounts are written.", locale: locale.identifier)) {
            VStack(spacing: 10) {
                ForEach(BitcoinAmountUnit.allCases, id: \.self) { unit in
                    Button { pick(unit) } label: {
                        HStack {
                            VStack(alignment: .leading, spacing: 4) {
                                Text(verbatim: BitcoinAmount.format(
                                    sats: Int64(clamping: (fixture?.summary ?? app.summary)?.balance.totalSats ?? 0),
                                    unit: unit,
                                    locale: locale))
                                    .noahText(NoahTokens.Typography.code).monospacedDigit()
                                Text(LocalizedStringKey(unit == .bip177 ? "Bitcoin (BIP-177)" : "Satoshis"))
                                    .noahText(NoahTokens.Typography.sub).foregroundStyle(NoahTokens.Colors.muted)
                            }
                            Spacer()
                            SettingsCheck(on: app.amountUnit == unit)
                        }
                        .padding(18)
                        .glassCompat(in: RoundedRectangle(cornerRadius: 22, style: .continuous))
                        .overlay {
                            RoundedRectangle(cornerRadius: 22, style: .continuous)
                                .strokeBorder(NoahTokens.Colors.foreground, lineWidth: 2).opacity(app.amountUnit == unit ? 1 : 0)
                        }
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityAddTraits(app.amountUnit == unit ? .isSelected : [])
                    .accessibilityIdentifier("preference-\(unit.rawValue)")
                }
            }
            .padding(.top, 16)
        } actions: { EmptyView() }
            .onboardingHaptics(haptics)
    }

    private func pick(_ unit: BitcoinAmountUnit) {
        haptics.play(.selection)
        try? app.updateProfile { $0.bitcoinAmountUnit = unit.rawValue }
        Task { try? await Task.sleep(for: .milliseconds(260)); close() }
    }
}

private struct SettingsCurrencySheet: View {
    let close: () -> Void
    @Environment(AppModel.self) private var app
    @Environment(\.locale) private var locale
    @State private var haptics = OnboardingHaptics()

    var body: some View {
        SettingsSheetBody(title: L10n.text("Currency", locale: locale.identifier),
                          line: L10n.text("For prices next to your bitcoin.", locale: locale.identifier)) {
            VStack(spacing: 0) {
                ForEach(FiatCurrency.supported, id: \.self) { code in
                    SettingsOption(title: locale.localizedString(forCurrencyCode: code) ?? code, detail: "\(code) · \(symbol(code))",
                                   on: app.preferredCurrency == code, id: "preference-\(code)") {
                        haptics.play(.selection)
                        try? app.updateProfile { $0.preferredCurrency = code }
                        Task { try? await Task.sleep(for: .milliseconds(260)); close() }
                    }
                }
            }
            .padding(.top, 10)
        } actions: { EmptyView() }
            .onboardingHaptics(haptics)
    }

    private func symbol(_ code: String) -> String {
        let formatter = NumberFormatter()
        formatter.numberStyle = .currency
        formatter.locale = locale
        formatter.currencyCode = code
        return formatter.currencySymbol ?? code
    }
}

struct SettingsLanguageSheet: View {
    static let codes = ["en", "es", "ja", "ko", "sv"]
    let close: () -> Void
    @Environment(AppModel.self) private var app
    @State private var haptics = OnboardingHaptics()

    /// Each language in its own words.
    static func name(_ code: String) -> String {
        let base = String(code.prefix(2))
        let own = Locale(identifier: base)
        return own.localizedString(forLanguageCode: base)?.capitalized(with: own) ?? base
    }

    var body: some View {
        SettingsSheetBody(title: L10n.text("Language", locale: app.localeIdentifier)) {
            VStack(spacing: 0) {
                ForEach(Self.codes, id: \.self) { code in
                    SettingsOption(title: Self.name(code), detail: code.uppercased(), on: app.localeIdentifier.hasPrefix(code), id: "preference-\(code)") {
                        haptics.play(.selection)
                        app.setLanguage(code)
                        Task { try? await Task.sleep(for: .milliseconds(260)); close() }
                    }
                    .environment(\.locale, Locale(identifier: code))
                }
            }
            .padding(.top, 10)
        } actions: { EmptyView() }
            .onboardingHaptics(haptics)
    }
}

/// A radio row: title, a small line, and a check when chosen.
struct SettingsOption: View {
    let title: String
    var detail: String?
    let on: Bool
    var failure = false
    var id = ""
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 14) {
                VStack(alignment: .leading, spacing: 1) {
                    Text(verbatim: title).noahText(SettingsText.value)
                        .foregroundStyle(failure ? NoahTokens.Colors.failure : NoahTokens.Colors.foreground)
                    if let detail { Text(verbatim: detail).noahText(HomeText.footnote).foregroundStyle(NoahTokens.Colors.muted) }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                SettingsCheck(on: on)
            }
            .frame(minHeight: 54)
            .overlay(alignment: .bottom) { HomeHairline() }
            .contentShape(Rectangle())
        }
        .buttonStyle(SettingsRowPressStyle())
        .accessibilityAddTraits(on ? [.isSelected, .isButton] : .isButton)
        .accessibilityIdentifier(id)
    }
}

struct SettingsCheck: View {
    let on: Bool
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        Image(systemName: "checkmark").font(.system(size: 16, weight: .semibold))
            .foregroundStyle(NoahTokens.Colors.foreground)
            .scaleEffect(on || reduceMotion ? 1 : 0.5)
            .opacity(on ? 1 : 0)
            .animation(NoahTokens.Motion.travel(reduceMotion, NoahTokens.Motion.bouncy), value: on)
            .accessibilityHidden(true)
    }
}

// MARK: Pages

/// Each page by route.
struct SettingsPageView: View {
    let page: SettingsPage
    @Environment(\.settingsFixture) private var fixture

    private var onchain: OnchainModel { fixture?.onchain() ?? OnchainModel() }
    private var executor: RecurringExecutor { fixture?.executor ?? .shared }

    var body: some View {
        switch page {
        case .profile: ProfileView()
        case .lightningAddress: LightningAddressPage()
        case .email: EmailPage()
        case .backup: BackupSettingsView()
        case .recovery: RecoveryPhrasePage()
        case .vtxos: VtxosView(model: onchain)
        case .vtxo(let id): VtxoDetailView(id: id, model: onchain)
        case .onchain: BoardArkView(model: onchain)
        case .recurring: RecurringPaymentsView(executor: executor)
        case .schedule(let id): RecurringSchedulePage(id: id, executor: executor)
        case .editor(let id):
            RecurringPaymentEditorView(executor: executor, schedule: id.flatMap { id in executor.schedules.first { $0.id == id } })
        case .exit: UnilateralExitView(model: onchain)
        case .exitChoose: ExitChooseView(model: onchain)
        case .exitReview(let ids): ExitReviewView(ids: ids, model: onchain)
        case .exitProgress(let id): ExitVtxoDetailView(id: id, model: fixture?.onchain())
        case .exitClaim: ExitClaimView(model: onchain)
        case .exitList: ExitListView(model: onchain)
        case .notifications: NotificationsPage()
        case .advanced: AdvancedPage()
        case .arkServer: ArkInfoView()
        case .esplora: EsploraSettingsScreen()
        case .logs: LogView()
        case .debug: DebugView()
        case .feedback: FeedbackView()
        case .about: NoahStoryView()
        }
    }
}
