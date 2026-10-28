import SwiftUI

/// Every page Settings opens (docs/native-rewrite/design/settings.md). Pages live in Home's
/// navigation stack after `HomeView.Route.settings`, so deep links land on a page with Settings beneath.
enum SettingsPage: Hashable {
    case profile, lightningAddress, email
    case backup, recovery
    case vtxos, vtxo(String), onchain
    case recurring, schedule(String), editor(String?)
    /// Emergency exit: the warning, then Choose, Review, an exit's progress, Claim and the list.
    case exit, exitChoose, exitReview([String]), exitProgress(String), exitClaim, exitList
    case notifications, advanced, arkServer, esplora, logs, debug
    case feedback, about

    /// The root row a page belongs to: wide windows highlight it in the list.
    var root: Self {
        switch self {
        case .lightningAddress, .email: .profile
        case .vtxo: .vtxos
        case .schedule, .editor: .recurring
        case .exitChoose, .exitReview, .exitProgress, .exitClaim, .exitList: .exit
        case .arkServer, .esplora, .logs, .debug: .advanced
        default: self
        }
    }

    /// A page opened from another page (not from the list) gets Back in the wide trailing pane.
    var isSubpage: Bool { root != self }

    /// Emergency exit's pages take both panes on wide windows (the picture leading, the details
    /// trailing), so the list steps aside and they always show Back.
    var ownsPanes: Bool { root == .exit }
}

/// Home's stack around Settings: what's under it, where a row or a deep link leads.
enum SettingsPath {
    typealias Route = HomeView.Route

    /// What's under Settings (Home itself, usually).
    static func base(_ path: [Route]) -> [Route] { Array(path.prefix { !$0.isSettings }) }

    /// A deep link (Home's nudges, notifications): the page with Settings beneath it.
    static func deepLink(_ page: SettingsPage, from path: [Route] = []) -> [Route] { base(path) + [.settings, .page(page)] }

    /// A root row on a wide window replaces the trailing page and anything opened from it.
    static func select(_ page: SettingsPage, in path: [Route], wide: Bool) -> [Route] {
        guard path.last != .page(page) else { return path }
        return wide ? deepLink(page, from: path) : path + [.page(page)]
    }
}

/// How pages move. Home builds it: phones push with the platform slide; wide windows swap the
/// trailing page without moving the list, and sheets stay in the trailing pane.
struct SettingsNavigator {
    /// Built by Home. Pages pushed elsewhere (Receive's profile link) navigate on their own.
    var live = false
    var wide = false
    /// The leading pane's width on wide windows.
    var leadingWidth: CGFloat = 0
    /// A root row: phones push it; wide windows replace the trailing page.
    var select: (SettingsPage) -> Void = { _ in }
    /// A page from a page.
    var open: (SettingsPage) -> Void = { _ in }
    /// Back from a page.
    var back: () -> Void = {}
    /// Back to `base` (a page under this one), then opens `page` over it: a started exit replaces
    /// Choose and Review with its progress.
    var swap: (_ base: SettingsPage, _ page: SettingsPage) -> Void = { _, _ in }
    /// Back from this page after something slow finished, only if it's still the one on top:
    /// you may have moved on while it saved.
    var leave: (SettingsPage?) -> Void = { _ in }
    /// Leaves Settings for Home.
    var close: () -> Void = {}
    var myCode: () -> Void = {}
    /// BTC Map, in Home's stack as Home opened it, so Back returns to Settings.
    var map: () -> Void = {}
    var receive: () -> Void = {}
    /// A short confirmation over Settings that outlives the page that shows it.
    var toast: (String) -> Void = { _ in }

    /// Settings pages pushed outside Home (Receive's Profile link) navigate within their own stack:
    /// pages and My code push, Back dismisses.
    static func standalone(push: @escaping (SettingsPage) -> Void, myCode: @escaping () -> Void, pushed: @escaping () -> Bool,
                           dismiss: @escaping () -> Void) -> Self {
        Self(select: push, open: push, back: dismiss, swap: { _, page in push(page) },
             leave: { _ in if !pushed() { dismiss() } }, close: dismiss, myCode: myCode)
    }
}

/// Screenshot fixtures: fakes for what Settings' pages read from the wallet and the server.
struct SettingsFixture {
    var summary: WalletSession.Summary?
    var storage: SettingsStorage
    var onchain: @MainActor () -> OnchainModel
    var phrase: PhraseGate.Dependencies
    var executor: RecurringExecutor
    var backups: [Components.Schemas.BackupObjectInfo] = []
    var vtxoLifetime: UInt32?
    var boardingMinimum: UInt64?
    /// A root sheet open on arrival (Delete wallet).
    var sheet: SettingsRootSheet?
    /// Emergency exit's Choose opens in Choose with these VTXOs selected.
    var exitSelection: [String]? // swiftlint:disable:this discouraged_optional_collection - nil opens Choose with nothing preselected
    /// Emergency exit's Claim opens to this phone ("phone") or with this address typed.
    var exitClaimTo: String?
}

extension EnvironmentValues {
    @Entry var settingsFixture: SettingsFixture?
    @Entry var settings = SettingsNavigator()
    /// The page was opened from another page: the wide trailing pane shows Back.
    @Entry var settingsSubpage = false
    /// The page this view is in.
    @Entry var settingsPage: SettingsPage?
    /// Wide windows: how much of the page is the leading pane, which pane sheets keep clear of.
    @Entry var settingsSheetLeading: CGFloat = 0
    /// A payment request scanned outside Home (the QR hub opened from Receive's Profile) goes to Send.
    @Entry var scanPayment = ScanPaymentAction { _ in }
}

/// Send's handler for a scanned payment request. A type, not a bare closure, so `@Entry` doesn't
/// invalidate every reader on each update.
struct ScanPaymentAction {
    let handle: (String) -> Void
    func callAsFunction(_ request: String) { handle(request) }
}

/// One Settings screen in Home's stack. Phones show the list (root) or one page. Wide windows show
/// the list leading and the page trailing, each with its own bar so nothing crosses the fold.
/// The page sits in the same place in both layouts, so a pose change keeps its state.
struct SettingsHost: View {
    /// nil is Settings itself.
    let page: SettingsPage?
    @Environment(\.settings) private var home
    @Environment(\.dismiss) private var dismiss
    /// Display's pickers and Delete wallet, opened from the list; wide windows show them in the trailing pane.
    @State private var sheet: SettingsRootSheet?
    /// A delete in progress stays locked through pose changes.
    @State private var deletion = WalletDeletion()
    /// The list's scroll position, kept as it moves between the phone's page and the wide leading pane.
    @State private var listOffset: CGFloat = 0
    /// Outside Home's Settings: the page this one opened, or My code.
    @State private var standalonePage: SettingsPage?
    @State private var standaloneMyCode = false
    @Environment(\.settingsFixture) private var fixture
    @Environment(\.scanPayment) private var scanPayment

    private var settings: SettingsNavigator {
        home.live ? home : .standalone(push: { standalonePage = $0 }, myCode: { standaloneMyCode = true },
                                       pushed: { standalonePage != nil || standaloneMyCode }, dismiss: { dismiss() })
    }

    var body: some View {
        HStack(spacing: 0) {
            if settings.wide && page?.ownsPanes != true {
                SettingsList(selected: (page ?? .profile).root, sheet: $sheet, offset: $listOffset, leading: true)
                    .frame(width: settings.leadingWidth)
                    .safeAreaPadding(.bottom, 90)
                    .disabled(deletion.deleting)
            }
            content
                .modifier(SettingsPaneEntrance(wide: settings.wide))
                .frame(maxWidth: .infinity)
                .settingsSheet(item: $sheet, locked: deletion.deleting) { item in
                    SettingsRootSheetView(sheet: item, current: $sheet, deletion: deletion)
                }
        }
        .background(NoahTokens.Colors.background.ignoresSafeArea())
        .navigationBarBackButtonHidden(settings.wide)
        .environment(\.settings, settings)
        .navigationDestination(item: $standalonePage) { Self(page: $0) }
        .navigationDestination(isPresented: $standaloneMyCode) { QRHubView(isActive: true, mode: .myCode, onScan: scanPayment.callAsFunction) }
        .task { if page == nil, let open = fixture?.sheet { sheet = open } }
        .foregroundStyle(NoahTokens.Colors.foreground)
        .tint(NoahTokens.Colors.foreground)
    }

    @ViewBuilder private var content: some View {
        if let page {
            SettingsPageView(page: page).environment(\.settingsSubpage, page.isSubpage || page.ownsPanes).environment(\.settingsPage, page)
                .environment(\.settingsSheetLeading, page.ownsPanes && settings.wide ? settings.leadingWidth : 0)
        } else if settings.wide {
            SettingsPageView(page: .profile)
        } else {
            SettingsList(selected: nil, sheet: $sheet, offset: $listOffset)
        }
    }
}

/// Wide windows: the trailing page fades and rises in once (prototype `wideSwap`); a pose change doesn't replay it.
private struct SettingsPaneEntrance: ViewModifier {
    let wide: Bool
    @State private var shown = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func body(content: Content) -> some View {
        content
            .opacity(shown || !wide ? 1 : 0)
            .offset(y: shown || !wide || reduceMotion ? 0 : 10)
            .onAppear {
                guard !shown else { return }
                withAnimation(NoahTokens.Motion.travel(reduceMotion).delay(0.06)) { shown = true }
            }
    }
}

extension View {
    /// The page's bar. Phones: the system bar with a large title (inline and empty for pages
    /// with their own header). Wide: the pane's own bar. Modifiers don't branch on the layout,
    /// so the page keeps its identity and state through a pose change.
    func settingsChrome(_ title: LocalizedStringKey?, @ViewBuilder trailing: () -> some View = { EmptyView() }) -> some View {
        modifier(SettingsChrome(title: title, trailing: trailing()))
    }
}

private struct SettingsChrome<Trailing: View>: ViewModifier {
    let title: LocalizedStringKey?
    let trailing: Trailing
    @Environment(\.settings) private var settings
    @Environment(\.settingsSubpage) private var subpage

    func body(content: Content) -> some View {
        content
            .navigationTitle(title ?? "")
            .navigationBarTitleDisplayMode(title == nil || settings.wide ? .inline : .large)
            .toolbar {
                if !settings.wide {
                    ToolbarItem(placement: .topBarTrailing) { trailing }
                }
            }
            .toolbarVisibility(settings.wide ? .hidden : .automatic, for: .navigationBar)
            .safeAreaInset(edge: .top, spacing: 0) {
                if settings.wide { SettingsPaneBar(title: title, back: settings.back, showsBack: subpage) { trailing } }
            }
    }
}

/// A wide pane's own bar: Back, the title at 28 pt, then the pane's buttons.
struct SettingsPaneBar<Trailing: View>: View {
    let title: LocalizedStringKey?
    let back: () -> Void
    var showsBack = true
    @ViewBuilder var trailing: Trailing

    var body: some View {
        HStack(spacing: 10) {
            if showsBack {
                Button(action: back) { ActivityCircleIcon(systemName: "chevron.left") }
                    .buttonStyle(HomePressStyle())
                    .accessibilityLabel(Text("Back"))
                    .accessibilityIdentifier("settings-pane-back")
            }
            if let title {
                Text(title).noahText(ActivityText.wideTitle).lineLimit(1).accessibilityAddTraits(.isHeader)
            }
            Spacer(minLength: 0)
            GlassGroup(spacing: 8) { HStack(spacing: 8) { trailing } }
        }
        .frame(minHeight: NoahTokens.Size.hitTarget)
        .padding(.leading, 20).padding(.trailing, 16).padding(.top, 14).padding(.bottom, 6)
        .background(NoahTokens.Colors.background)
    }
}
