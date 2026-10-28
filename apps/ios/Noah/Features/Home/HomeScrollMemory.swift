import SwiftUI

extension HomeView {
    struct TrackerInput: Equatable {
        var state: HomeLayoutState
        var ids: [String]
        var sats: UInt64
        var visible: Bool
        var wallet: String?
        /// Nil for fixtures: nothing to wait for.
        var revision: Int?
        var historyBusy: Bool
        var caughtUp: Bool
    }
}

struct HomeMoments: ViewModifier {
    let noticesKey: Bool
    let trackerInput: HomeView.TrackerInput
    /// Set while a Fresh visit waits for its first refresh before a payment can play.
    let awaitingRefresh: String?
    let requestVisitRefresh: () async -> Void
    /// The latest finished foreground catch-up; nil for fixtures.
    let catchUpID: Int?
    let reloadHistoryAfterCatchUp: () async -> Void
    let celebrationID: UUID?
    let toastID: UUID?
    let wallet: String?
    let suspended: Bool
    // swiftlint:disable:next discouraged_optional_boolean - tri-state: unknown, no, yes / nil tracks rows by offset
    let emailVerified: Bool?
    let takeNotices: () -> Void
    let update: (HomeView.TrackerInput) -> Void
    let celebrate: () async -> Void
    let expireToast: () async -> Void
    let entrance: () async -> Void
    let invalidateReview: () -> Void
    let suspendedChanged: (Bool) -> Void
    let recordsArrived: () -> Void
    // swiftlint:disable:next discouraged_optional_boolean - tri-state: unknown, no, yes / nil tracks rows by offset
    let emailVerifiedChanged: (Bool?) -> Void
    @Environment(\.scenePhase) private var scenePhase

    func body(content: Content) -> some View {
        content
            .task(id: noticesKey) { takeNotices() }
            .onChange(of: trackerInput, initial: true) { old, input in
                update(input)
                if old.state != .loading, !Set(input.ids).isSubset(of: Set(old.ids)) { recordsArrived() }
            }
            .task(id: celebrationID) { await celebrate() }
            .task(id: awaitingRefresh) {
                guard awaitingRefresh != nil else { return }
                await requestVisitRefresh()
            }
            .task(id: catchUpID) {
                guard catchUpID != nil else { return }
                await reloadHistoryAfterCatchUp()
            }
            .task(id: toastID) { await expireToast() }
            .task { await entrance() }
            // As in the VTXO screen: a review never outlives backgrounding, leaving Home or a wallet change.
            .onChange(of: scenePhase) { _, _ in invalidateReview() }
            .onChange(of: wallet) { _, _ in invalidateReview() }
            .onDisappear(perform: invalidateReview)
            .onChange(of: suspended) { _, value in suspendedChanged(value) }
            .onChange(of: emailVerified) { _, value in emailVerifiedChanged(value) }
    }
}

/// Stand-ins for app state that only a live wallet sets, for the opt-in
/// screenshot fixtures (`NoahTests/HomeShotTests`). Never set in the app.
struct HomeFixture {
    var summary: WalletSession.Summary?
    var walletError: String?
    var needsRecoveryPhraseBackup = false
    var updateRequired = false
}

extension EnvironmentValues {
    @Entry var homeFixture: HomeFixture?
}

/// Scroll offsets that survive a pose change. Each scroll area records its offset as
/// it scrolls (not observed, so scrolling doesn't re-render Home); when the layout
/// switches, the areas that leave hand their offsets to their next appearance. The
/// Fresh card and the activity list keep separate positions.
final class HomeScrollMemory {
    enum Area: Hashable { case compact, leading, fresh, activity }
    /// The lazy activity list is remembered by its first visible row: an offset can't be
    /// restored into rows the list hasn't measured yet.
    enum Position: Equatable { case offset(CGFloat), row(String) }
    private var positions: [Area: Position] = [:]
    private var restores: [Area: Position] = [:]

    func record(_ area: Area, _ position: Position) { positions[area] = position }

    func layoutChanged(toWide wide: Bool) {
        for area in wide ? [Area.compact] : [.leading, .fresh, .activity] { restores[area] = positions[area] }
    }

    func takeRestore(_ area: Area) -> Position? { restores.removeValue(forKey: area) }
}

struct HomeScrollKeeper: ViewModifier {
    let area: HomeScrollMemory.Area
    let memory: HomeScrollMemory
    /// Track rows by their String IDs (transaction IDs), in this display order, instead of
    /// the offset; the content needs `scrollTargetLayout()`.
    var rows: [String]? // swiftlint:disable:this discouraged_optional_collection - tri-state: unknown, no, yes / nil tracks rows by offset
    var onOffset: (CGFloat) -> Void = { _ in }
    @State private var position = ScrollPosition(edge: .top)
    @State private var offset: CGFloat = 0
    @State private var topRow: String?

    func body(content: Content) -> some View {
        content
            .scrollPosition($position)
            .onScrollGeometryChange(for: CGFloat.self) { $0.contentOffset.y + $0.contentInsets.top } action: { _, y in
                offset = y
                onOffset(y)
            }
            .onScrollTargetVisibilityChange(idType: String.self) { visible in
                guard let rows else { return }
                let shown = Set(visible)
                if let first = rows.first(where: shown.contains) { topRow = first }
            }
            // Keep where a user's scroll comes to rest. A pose change squeezes the pane and
            // resets its offset while idle, so those moves are never kept.
            .onScrollPhaseChange { old, new in
                guard new == .idle, old != .idle else { return }
                if rows != nil, offset > 1, let topRow {
                    memory.record(area, .row(topRow))
                } else {
                    memory.record(area, .offset(offset))
                }
            }
            // Back from the other layout: return to where this area was, once it has laid out.
            .task {
                guard let restore = memory.takeRestore(area) else { return }
                memory.record(area, restore)
                do { try await Task.sleep(for: .milliseconds(80)) } catch { return }
                switch restore {
                case .offset(let offset): if offset > 0 { position.scrollTo(y: offset) }
                case .row(let id): position.scrollTo(id: id, anchor: .top)
                }
            }
    }
}

/// The entrance rise for one block; already shown once the entrance has played.
struct HomeAppear: ViewModifier {
    let order: Int
    let animated: Bool

    func body(content: Content) -> some View {
        content.onboardingAppear(order, base: 0.06, step: 0.04, distance: 14, animated: animated)
    }
}

/// The top bar over the scrolling column. iOS 26 fades content under it with the
/// system scroll edge effect; earlier versions with a background gradient.
struct HomeTopBarInset<Bar: View>: ViewModifier {
    let scrolled: Bool
    @ViewBuilder let bar: Bar

    func body(content: Content) -> some View {
        if #available(iOS 26, *) {
            content
                .safeAreaBar(edge: .top, spacing: 0) { bar }
                .scrollEdgeEffectStyle(.soft, for: .top)
        } else {
            content.safeAreaInset(edge: .top, spacing: 0) {
                bar.background(alignment: .top) {
                    LinearGradient(stops: [.init(color: NoahTokens.Colors.background, location: 0.62),
                                           .init(color: NoahTokens.Colors.background.opacity(0), location: 1)],
                                   startPoint: .top, endPoint: .bottom)
                        .padding(.bottom, -16)
                        .ignoresSafeArea(edges: .top)
                        .opacity(scrolled ? 1 : 0)
                        .animation(.easeOut(duration: 0.2), value: scrolled)
                        .allowsHitTesting(false)
                }
            }
        }
    }
}
