import SwiftUI

/// Activity (docs/native-rewrite/design/activity.md). Phones push a payment's detail with a
/// zoom out of its row; wide windows (the iPhone Duo open, iPad, landscape) keep the list in
/// the leading pane and the detail in the trailing one, split at the window's centre. Each
/// pane has its own navigation bar, so nothing crosses the fold. Selection, search, filter,
/// scroll position and the Details expansion live here, so a pose change keeps them.
struct TransactionsView: View {
    enum Route: Hashable { case payment(String) }

    @Environment(AppModel.self) private var app
    @Environment(HistoryModel.self) private var history
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.activityFixture) private var fixture
    @State private var path = NavigationPath()
    @State private var detailPath = NavigationPath()
    @State private var selectedID: String?
    @State private var filter = HistoryFilter.all
    @State private var query = ""
    @State private var searching = false
    @State private var scrollAnchor: String?
    @State private var detailsOpen = false
    @State private var exporting = false
    @State private var window = OnboardingLayout(size: .zero, insets: EdgeInsets())
    @State private var haptics = OnboardingHaptics()
    @Namespace private var zoom
    let onReceive: () -> Void
    let onRepeatPayment: (RepeatPayment) -> Void

    init(onReceive: @escaping () -> Void = {}, onRepeatPayment: @escaping (RepeatPayment) -> Void) {
        self.onReceive = onReceive
        self.onRepeatPayment = onRepeatPayment
    }

    private var selected: HistoryTransaction? { history.transactions.first { $0.id == selectedID } }

    var body: some View {
        Group {
            if window.wide { widePanes } else { compactStack }
        }
        .onboardingWindow($window)
        .onChange(of: window.wide) { _, wide in layoutChanged(toWide: wide) }
        // Back (or a swipe) on the phone closes the payment.
        .onChange(of: path.isEmpty) { _, empty in if empty && !window.wide { selectedID = nil } }
        .onboardingHaptics(haptics)
        // The phone's tab bar hides while a payment is open.
        .preference(key: ActivityHidesTabBar.self, value: !window.wide && !path.isEmpty)
        .task {
            guard let fixture else { return }
            filter = fixture.filter
            query = fixture.query ?? ""
            detailsOpen = fixture.detailsOpen
            if let id = fixture.selectedID {
                selectedID = id
                if !window.wide { path.append(Route.payment(id)) }
            }
            exporting = fixture.exporting
            if fixture.query != nil {
                // The search field opens once the bar is on screen.
                try? await Task.sleep(for: .milliseconds(400))
                searching = true
            }
        }
        .sheet(isPresented: Binding(get: { exporting && !window.wide }, set: { if !window.wide { exporting = $0 } })) {
            exportSheet.presentationDetents([.height(330)]).presentationDragIndicator(.hidden)
        }
    }

    // MARK: Layouts

    private var compactStack: some View {
        NavigationStack(path: $path) {
            list(wide: false)
                .navigationDestination(for: Route.self) { route in destination(route) }
        }
    }

    private var widePanes: some View {
        HStack(spacing: 0) {
            NavigationStack {
                list(wide: true).safeAreaPadding(.bottom, 90)
            }
            .frame(width: max(0, window.size.width / 2 - window.insets.leading))
            NavigationStack(path: $detailPath) {
                Group {
                    if let selected {
                        detail(selected, wide: true)
                    } else {
                        ActivitySelectPrompt().toolbar(.hidden, for: .navigationBar)
                    }
                }
            }
            .frame(maxWidth: .infinity)
            .accessibilityIdentifier("history-detail-pane")
        }
        .overlay(alignment: .bottomTrailing) {
            // Sheets stay in the trailing pane, clear of the fold.
            if exporting {
                HomePaneSheet(width: max(0, window.size.width / 2 - window.insets.trailing), onDismiss: closeExport) {
                    exportSheet
                }
            }
        }
        .animation(NoahTokens.Motion.travel(reduceMotion), value: exporting)
    }

    private func list(wide: Bool) -> some View {
        ActivityListPane(filter: $filter, query: $query, searching: $searching, scrollAnchor: $scrollAnchor,
                         wide: wide, selectedID: wide ? selectedID : nil, zoom: zoom,
                         onSelect: select, onExport: openExport, onReceive: onReceive)
    }

    @ViewBuilder private func destination(_ route: Route) -> some View {
        switch route {
        case .payment(let id):
            if let transaction = history.transactions.first(where: { $0.id == id }) {
                detail(transaction, wide: false)
                    .navigationTransition(.zoom(sourceID: id, in: zoom))
            }
        }
    }

    private func detail(_ transaction: HistoryTransaction, wide: Bool) -> some View {
        TransactionDetailView(transaction: transaction, wide: wide, closable: wide, detailsOpen: $detailsOpen,
                              onRepeatPayment: repeatPayment, onClose: close)
    }

    // MARK: Actions

    private func select(_ transaction: HistoryTransaction) {
        haptics.play(.light)
        if window.wide {
            withAnimation(NoahTokens.Motion.travel(reduceMotion)) {
                detailPath = NavigationPath()
                detailsOpen = false
                selectedID = transaction.id
            }
        } else {
            detailsOpen = false
            selectedID = transaction.id
            path = NavigationPath([Route.payment(transaction.id)])
        }
    }

    private func close() {
        haptics.play(.light)
        if window.wide {
            withAnimation(NoahTokens.Motion.travel(reduceMotion)) { selectedID = nil; detailPath = NavigationPath() }
        } else {
            path = NavigationPath()
            selectedID = nil
        }
    }

    /// A pose change moves an open payment between the push and the trailing pane; links
    /// opened from it (coin details and the like) close.
    private func layoutChanged(toWide wide: Bool) {
        if wide {
            path = NavigationPath()
        } else {
            detailPath = NavigationPath()
            if let selectedID, selected != nil { path = NavigationPath([Route.payment(selectedID)]) }
        }
        if exporting && !wide { exporting = false; Task { exporting = true } }
    }

    private func repeatPayment(_ details: RepeatPayment) {
        path = NavigationPath()
        detailPath = NavigationPath()
        selectedID = nil
        onRepeatPayment(details)
    }

    private func openExport() {
        haptics.play(.light)
        withAnimation(NoahTokens.Motion.travel(reduceMotion)) { exporting = true }
    }

    private func closeExport() {
        withAnimation(NoahTokens.Motion.travel(reduceMotion)) { exporting = false }
    }

    private var exportSheet: some View {
        ActivityExportSheet(transactions: history.transactions.filter(filter.includes), filter: filter,
                            currency: app.preferredCurrency, onClose: closeExport)
    }
}

struct ActivityHidesTabBar: PreferenceKey {
    static let defaultValue = false
    static func reduce(value: inout Bool, nextValue: () -> Bool) { value = value || nextValue() }
}

/// Screenshot fixtures: the state Activity opens in.
struct ActivityFixture {
    var selectedID: String?
    var filter = HistoryFilter.all
    var query: String?
    var exporting = false
    var detailsOpen = false
}

extension EnvironmentValues {
    @Entry var activityFixture: ActivityFixture?
}

/// The list itself: header, states and rows. The same view sits in the phone's stack and in
/// the wide leading pane.
private struct ActivityListPane: View {
    @Environment(AppModel.self) private var app
    @Environment(HistoryModel.self) private var history
    @Environment(HomeActivityTracker.self) private var tracker: HomeActivityTracker?
    @Environment(\.locale) private var locale
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Binding var filter: HistoryFilter
    @Binding var query: String
    @Binding var searching: Bool
    @Binding var scrollAnchor: String?
    let wide: Bool
    let selectedID: String?
    let zoom: Namespace.ID
    let onSelect: (HistoryTransaction) -> Void
    let onExport: () -> Void
    let onReceive: () -> Void
    @State private var tucked = false
    /// Where the list was when search opened: clearing the query or Cancel puts it back.
    @State private var listAnchor: String?
    @State private var haptics = OnboardingHaptics()
    @State private var refreshing = false

    private var ownDomain: String {
        app.server.state.lightningAddress?.split(separator: "@").last.map(String.init) ?? LightningIdentity.domain(for: app.variant)
    }

    private var filtered: [HistoryTransaction] { history.transactions.filter(filter.includes) }

    private var visible: [HistoryTransaction] {
        let language = locale.identifier
        return filtered.filter { transaction in
            guard !query.isEmpty else { return true }
            let row = HomeActivityRow(transaction, ownDomain: ownDomain)
            let title = row.titleIsVerbatim ? row.title : L10n.text(row.title, locale: language)
            return ActivityList.matches(transaction, query: query, words: [title, row.title] + ActivityList.kindWords(row, locale: language))
        }
    }

    /// The large title's scroll ID: a list at the top goes back to the top.
    private static let titleID = "activity-title"

    /// The prototype's side padding: 24 on phones, 32 in the wide pane.
    private var pad: CGFloat { wide ? 32 : NoahTokens.Space.screen }

    var body: some View {
        let list = ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 0, pinnedViews: [.sectionHeaders]) {
                    if !wide && !searching { largeTitle.padding(.horizontal, pad).id(Self.titleID) }
                    content
                }
                .scrollTargetLayout()
                .padding(.bottom, 32)
            }
            .scrollPosition(id: $scrollAnchor, anchor: .top)
            // After the update, so the list is back to full length first.
            .onChange(of: query.isEmpty || !searching) { _, showsList in
                if showsList { proxy.scrollTo(listAnchor ?? Self.titleID, anchor: .top) }
            }
        }
        .onScrollGeometryChange(for: Bool.self) { $0.contentOffset.y + $0.contentInsets.top > 42 } action: { _, value in
            tucked = value
        }
        .refreshable { await refresh() }
        .background(NoahTokens.Colors.background.ignoresSafeArea())
        .onboardingHaptics(haptics)
        if wide {
            // No system bar: on the open Duo its buttons move to the far edge, across the fold.
            list
                .toolbar(.hidden, for: .navigationBar)
                .safeAreaInset(edge: .top, spacing: 0) { header }
                .tint(NoahTokens.Colors.foreground)
        } else {
            // Search is the wide pane's field over the system bar, not `.searchable`: in a tab on iOS 27
            // the minimized toolbar field reopened as soon as its × closed it.
            list
                .navigationTitle("Activity")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar { toolbar }
                .toolbar(searching ? .hidden : .automatic, for: .navigationBar)
                .safeAreaInset(edge: .top, spacing: 0) { if searching { header } }
                .tint(NoahTokens.Colors.foreground)
        }
    }

    /// The leading pane's own bar (prototype `.ph.wide .nav`): the title and chip, then search and ⋯.
    /// Phones show it only while searching.
    private var header: some View {
        HStack(alignment: .top, spacing: 8) {
            if searching {
                ActivitySearchField(query: $query) {
                    withAnimation(NoahTokens.Motion.travel(reduceMotion)) { query = ""; searching = false }
                }
                .transition(.opacity)
            } else {
                ActivityTitleRow(style: ActivityText.wideTitle) { chip }
                    .frame(minHeight: NoahTokens.Size.hitTarget)
                    .layoutPriority(1)
                Spacer(minLength: 8)
                GlassGroup(spacing: 8) {
                    HStack(spacing: 8) {
                        Button(action: openSearch) { ActivityCircleIcon(systemName: "magnifyingglass") }
                        .buttonStyle(HomePressStyle())
                        .accessibilityLabel(Text("Search"))
                        .accessibilityIdentifier("activity-search")
                        menu(label: ActivityCircleIcon(systemName: "ellipsis"))
                    }
                }
            }
        }
        .frame(minHeight: NoahTokens.Size.hitTarget)
        .padding(.leading, pad).padding(.trailing, 16)
        .padding(.top, 14).padding(.bottom, 6)
        .background {
            LinearGradient(stops: [.init(color: NoahTokens.Colors.background, location: 0.78),
                                   .init(color: NoahTokens.Colors.background.opacity(0), location: 1)],
                           startPoint: .top, endPoint: .bottom)
                .ignoresSafeArea(edges: .top)
        }
        .animation(NoahTokens.Motion.travel(reduceMotion, NoahTokens.Motion.bouncy), value: filter)
        .animation(NoahTokens.Motion.travel(reduceMotion), value: searching)
    }

    // MARK: Header

    private var largeTitle: some View {
        ActivityTitleRow(style: ActivityText.largeTitle) { chip }
            .frame(minHeight: 46, alignment: .leading)
            .padding(.bottom, 2)
    }

    @ViewBuilder private var chip: some View {
        if filter != .all {
            ActivityFilterChip(filter: filter) { setFilter(.all) }
                .transition(reduceMotion ? .opacity : .scale(scale: 0.6).combined(with: .opacity))
        }
    }

    @ToolbarContentBuilder private var toolbar: some ToolbarContent {
        ToolbarItem(placement: .principal) {
            Text("Activity").noahText(NoahTokens.Typography.button).opacity(tucked && !searching ? 1 : 0)
                .animation(NoahTokens.Motion.travel(reduceMotion), value: tucked)
                .accessibilityHidden(!tucked)
        }
        ToolbarItem(placement: .topBarTrailing) {
            Button("Search", systemImage: "magnifyingglass", action: openSearch).accessibilityIdentifier("activity-search")
        }
        ToolbarItem(placement: .topBarTrailing) { menu(label: Label("More", systemImage: "ellipsis")) }
    }

    private func menu(label: some View) -> some View {
        Menu {
            Picker(selection: Binding(get: { filter }, set: setFilter)) {
                ForEach(HistoryFilter.allCases) { Text(LocalizedStringKey($0.title)).tag($0) }
            } label: { Text("Filter") }
            .pickerStyle(.inline)
            Section {
                Button("Export CSV", systemImage: "square.and.arrow.up", action: onExport)
                    .disabled(history.isLoading || history.error != nil)
                    .accessibilityIdentifier("activity-export")
                // Its own string: the shared "Refresh" reads as "renew" in some languages.
                Button("Refresh activity", systemImage: "arrow.clockwise") { Task { await refresh(scrollToTop: true) } }
                    .disabled(refreshing)
                    .accessibilityIdentifier("activity-refresh")
            }
        } label: {
            label
        }
        .accessibilityLabel(Text("More"))
        .accessibilityIdentifier("activity-menu")
    }

    // MARK: Content

    @ViewBuilder private var content: some View {
        if app.isWalletSuspended {
            ActivityErrorView(message: "Your wallet is suspended. Resume it to view transactions.") { Task { await refresh() } }
        } else if history.isLoading {
            ActivitySkeleton().padding(.horizontal, pad)
        } else if let error = history.error {
            ActivityErrorView(message: error) { Task { await refresh() } }
        } else if history.transactions.isEmpty {
            ActivityEmptyView(onReceive: onReceive)
        } else if visible.isEmpty {
            if !query.trimmingCharacters(in: .whitespaces).isEmpty {
                ActivityNoneView(text: "No matches", action: nil)
            } else {
                ActivityNoneView(text: filter.emptyTitle) { setFilter(.all) }
            }
        } else {
            rows
        }
    }

    private var rows: some View {
        let unseen = tracker?.unseen(history.transactions) ?? []
        let groups = ActivityList.groups(visible, now: Date())
        return ForEach(groups) { group in
            Section {
                ForEach(Array(group.transactions.enumerated()), id: \.element.id) { index, transaction in
                    let next = index + 1 < group.transactions.count ? group.transactions[index + 1].id : nil
                    ActivityRowButton(transaction: transaction, ownDomain: ownDomain, unit: app.amountUnit,
                                      hidden: app.isBalanceHidden, isNew: unseen.contains(transaction.id),
                                      selected: transaction.id == selectedID,
                                      hairline: next != nil && transaction.id != selectedID && next != selectedID,
                                      zoom: wide ? nil : zoom, onSeen: { tracker?.markSeen(transaction.id) }) { onSelect(transaction) }
                        .padding(.horizontal, pad)
                }
            } header: {
                ActivityDayHeader(title: title(group.title), first: group.id == groups.first?.id, pad: pad)
            }
        }
    }

    private func title(_ title: ActivityList.Group.Title) -> String {
        let language = locale.identifier
        switch title {
        case .pending: return L10n.text("Pending", locale: language)
        case .day(.today): return L10n.text("Today", locale: language)
        case .day(.yesterday): return L10n.text("Yesterday", locale: language)
        case .day(.date(let date)):
            return Calendar.current.isDate(date, equalTo: Date(), toGranularity: .year)
                ? date.formatted(.dateTime.month(.abbreviated).day().locale(locale))
                : date.formatted(.dateTime.year().month(.abbreviated).day().locale(locale))
        }
    }

    // MARK: Actions

    private func openSearch() {
        haptics.play(.light)
        listAnchor = scrollAnchor
        withAnimation(NoahTokens.Motion.travel(reduceMotion)) { searching = true }
    }

    private func setFilter(_ value: HistoryFilter) {
        guard value != filter else { return }
        haptics.play(.selection)
        withAnimation(NoahTokens.Motion.travel(reduceMotion, NoahTokens.Motion.bouncy)) { filter = value }
    }

    /// Today's refresh and sync.
    private func refresh(scrollToTop: Bool = false) async {
        guard !refreshing else { return }
        refreshing = true
        defer { refreshing = false }
        haptics.play(.light)
        if scrollToTop {
            let first = ActivityList.groups(visible, now: Date()).first?.transactions.first?.id
            withAnimation(NoahTokens.Motion.travel(reduceMotion)) { scrollAnchor = first }
        }
        await history.refresh(currency: app.preferredCurrency, sync: true)
    }
}

extension ActivityList {
    /// The kind and status words a row shows, for search.
    static func kindWords(_ row: HomeActivityRow, locale: String) -> [String] {
        let kind: String? = switch row.kind {
        case .noahUser: "Noah user"
        case .lightning: "Lightning"
        case .onchain: "On-chain"
        case .ark: "Ark"
        case .recurring: "Recurring"
        case nil: nil
        }
        let status: String? = switch row.status {
        case .failed: "Failed"
        case .refunded: "Refunded · back in your wallet"
        case .canceled: "Canceled"
        case .sending: "Sending"
        case .confirming: "Confirming"
        case .inRound: "In a round"
        case .exiting: "Exiting"
        case .done: nil
        }
        return [kind, status].compactMap { $0 }.flatMap { [$0, L10n.text($0, locale: locale)] }
    }
}

enum ActivityText {
    static let largeTitle = NoahTokens.TextStyle(size: 34, lineHeight: 40, weight: .semibold, display: false, tracking: -0.02, textStyle: .largeTitle)
    static let wideTitle = NoahTokens.TextStyle(size: 28, lineHeight: 34, weight: .semibold, display: false, tracking: -0.015, textStyle: .title1)
    static let chip = NoahTokens.TextStyle(size: 13, lineHeight: 18, weight: .semibold, display: false, tracking: 0, textStyle: .footnote)
    static let stateTitle = NoahTokens.TextStyle(size: 20, lineHeight: 26, weight: .semibold, display: false, tracking: 0, textStyle: .title3)
    static let emptyTitle = NoahTokens.TextStyle(size: 22, lineHeight: 28, weight: .semibold, display: false, tracking: -0.01, textStyle: .title2)
}

/// Home's row, with the wide pane's soft selection and the zoom's source rectangle.
private struct ActivityRowButton: View {
    let transaction: HistoryTransaction
    let ownDomain: String
    let unit: BitcoinAmountUnit
    let hidden: Bool
    let isNew: Bool
    let selected: Bool
    let hairline: Bool
    let zoom: Namespace.ID?
    let onSeen: () -> Void
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HomeActivityRowView(transaction: transaction, ownDomain: ownDomain, unit: unit, hidden: hidden,
                                isNew: isNew, grouped: true)
                .background {
                    RoundedRectangle(cornerRadius: 16, style: .continuous)
                        .fill(NoahTokens.Colors.soft)
                        .padding(.vertical, 3).padding(.horizontal, -12)
                        .opacity(selected ? 1 : 0)
                }
        }
        .buttonStyle(ActivityRowPressStyle())
        .overlay(alignment: .bottom) {
            if hairline { HomeHairline().padding(.leading, 56) }
        }
        .modifier(ZoomSource(id: transaction.id, namespace: zoom))
        // The dot shrinks away once the row has been on screen for a moment (cancelled if it
        // scrolls away first). Not `onScrollVisibilityChange`: in the lazy list it left rows
        // showing stale records.
        .task(id: isNew) {
            guard isNew else { return }
            do { try await Task.sleep(for: .seconds(1.4)) } catch { return }
            onSeen()
        }
        .accessibilityAddTraits(selected ? .isSelected : [])
        .accessibilityIdentifier("activity-row-\(transaction.id)")
    }
}

private struct ZoomSource: ViewModifier {
    let id: String
    let namespace: Namespace.ID?

    func body(content: Content) -> some View {
        if let namespace {
            // The source clips even at rest: outset it so the pending ring and the pressed highlight (12 pt past the row) stay whole.
            content.padding(.horizontal, 12).matchedTransitionSource(id: id, in: namespace) {
                $0.background(NoahTokens.Colors.background).clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
            }
            .padding(.horizontal, -12)
        } else {
            content
        }
    }
}

/// The prototype's `.rw:active`: the soft rounded surface while pressed.
private struct ActivityRowPressStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .background {
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .fill(NoahTokens.Colors.soft)
                    .padding(.vertical, 3).padding(.horizontal, -12)
                    .opacity(configuration.isPressed ? 0.7 : 0)
            }
            .contentShape(Rectangle())
    }
}

private struct ActivityDayHeader: View {
    let title: String
    let first: Bool
    let pad: CGFloat

    var body: some View {
        Text(verbatim: title)
            .noahText(HomeText.section)
            .foregroundStyle(NoahTokens.Colors.muted)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.top, first ? 8 : 14).padding(.bottom, 4)
            .padding(.horizontal, pad)
            .background(NoahTokens.Colors.background)
            .accessibilityAddTraits(.isHeader)
    }
}

/// Nine pulsing skeleton rows; still under Reduce Motion.
private struct ActivitySkeleton: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    private static let widths: [(CGFloat, CGFloat)] = [(0.58, 0.30), (0.44, 0.36), (0.66, 0.26), (0.50, 0.40), (0.38, 0.32),
                                                      (0.62, 0.28), (0.47, 0.34), (0.55, 0.24), (0.41, 0.38)]

    var body: some View {
        TimelineView(.animation(paused: reduceMotion)) { timeline in
            VStack(spacing: 0) {
                ForEach(Self.widths.indices, id: \.self) { index in
                    row(Self.widths[index])
                        .opacity(reduceMotion ? 1 : pulse(timeline.date, delay: Double(index) * 0.08))
                }
            }
        }
        .padding(.top, 8)
        .accessibilityElement()
        .accessibilityLabel(Text("Loading activity"))
    }

    /// 1.2 s each way between full and 45%, eased (prototype `skp`).
    private func pulse(_ date: Date, delay: Double) -> Double {
        let phase = (date.timeIntervalSinceReferenceDate - delay).truncatingRemainder(dividingBy: 2.4) / 1.2
        let t = phase < 1 ? phase : 2 - phase
        return 1 - 0.55 * (0.5 - 0.5 * cos(.pi * t))
    }

    private func row(_ widths: (CGFloat, CGFloat)) -> some View {
        HStack(spacing: 14) {
            Circle().fill(NoahTokens.Colors.soft).frame(width: 42, height: 42)
            GeometryReader { proxy in
                VStack(alignment: .leading, spacing: 8) {
                    Capsule().fill(NoahTokens.Colors.soft).frame(width: proxy.size.width * widths.0, height: 12)
                    Capsule().fill(NoahTokens.Colors.soft).frame(width: proxy.size.width * widths.1, height: 12)
                }
                .frame(maxHeight: .infinity, alignment: .center)
            }
            Capsule().fill(NoahTokens.Colors.soft).frame(width: 64, height: 14)
        }
        .frame(height: 66)
    }
}

/// Ghost rows showing the three avatar kinds, "No activity yet" and Receive.
private struct ActivityEmptyView: View {
    let onReceive: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            VStack(spacing: 0) {
                ghost(avatar: AnyView(Text(verbatim: "a").noahText(HomeText.initial)), widths: (0.62, 0.34)).opacity(1)
                ghost(avatar: AnyView(Image(systemName: "bolt").font(.system(size: 17, weight: .medium))), widths: (0.48, 0.40)).opacity(0.72)
                ghost(avatar: AnyView(Image(systemName: "link").font(.system(size: 16, weight: .medium))), widths: (0.56, 0.28)).opacity(0.44)
            }
            .frame(maxWidth: 300)
            .accessibilityHidden(true)
            Text("No activity yet").noahText(ActivityText.emptyTitle)
                .foregroundStyle(NoahTokens.Colors.foreground)
                .padding(.top, 30).padding(.bottom, 18)
            Button("Receive", action: onReceive)
                .buttonStyle(HomePillStyle(height: 40))
                .accessibilityIdentifier("activity-empty-receive")
        }
        .frame(maxWidth: .infinity)
        .padding(.top, 44)
        .padding(.horizontal, NoahTokens.Space.screen)
    }

    private func ghost(avatar: AnyView, widths: (CGFloat, CGFloat)) -> some View {
        HStack(spacing: 14) {
            avatar
                .foregroundStyle(NoahTokens.Colors.muted)
                .frame(width: 42, height: 42)
                .overlay(Circle().strokeBorder(NoahTokens.Colors.hairline, lineWidth: 1.5))
            GeometryReader { proxy in
                VStack(alignment: .leading, spacing: 7) {
                    Capsule().fill(NoahTokens.Colors.soft).frame(width: proxy.size.width * widths.0, height: 10)
                    Capsule().fill(NoahTokens.Colors.soft).frame(width: proxy.size.width * widths.1, height: 10)
                }
                .frame(maxHeight: .infinity, alignment: .center)
            }
            Capsule().fill(NoahTokens.Colors.soft).frame(width: 54, height: 12)
        }
        .frame(height: 60)
    }
}

/// "Couldn't load activity", today's message and "Try again".
private struct ActivityErrorView: View {
    let message: String
    let retry: () -> Void

    var body: some View {
        VStack(spacing: 6) {
            Text("Couldn't load activity").noahText(ActivityText.stateTitle)
            Text(LocalizedStringKey(message)).noahText(NoahTokens.Typography.sub)
                .foregroundStyle(NoahTokens.Colors.muted)
                .multilineTextAlignment(.center)
                .frame(maxWidth: 270)
                .padding(.bottom, 16)
            Button("Try again", action: retry)
                .buttonStyle(HomePillStyle(prominent: false, height: 40))
                .accessibilityIdentifier("activity-retry")
        }
        .foregroundStyle(NoahTokens.Colors.foreground)
        .frame(maxWidth: .infinity)
        .padding(.top, 120)
        .padding(.horizontal, NoahTokens.Space.screen)
    }
}

/// "No matches", or a filter's empty state with "Show all".
private struct ActivityNoneView: View {
    let text: String
    let action: (() -> Void)?

    var body: some View {
        VStack(spacing: 10) {
            Text(LocalizedStringKey(text)).noahText(NoahTokens.Typography.sub).foregroundStyle(NoahTokens.Colors.muted)
            if let action {
                Button("Show all", action: action)
                    .noahText(HomeText.strong)
                    .foregroundStyle(NoahTokens.Colors.foreground)
                    .frame(minHeight: NoahTokens.Size.hitTarget)
                    .accessibilityIdentifier("activity-show-all")
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.top, 56)
    }
}

/// "Activity" and the filter chip side by side, or the chip on its own line when large text can't fit both:
/// the chip never shrinks to its ×.
struct ActivityTitleRow<Chip: View>: View {
    let style: NoahTokens.TextStyle
    @ViewBuilder let chip: Chip

    var body: some View {
        ViewThatFits(in: .horizontal) {
            HStack(spacing: 10) { title; chip }
            VStack(alignment: .leading, spacing: 6) { title; chip }
        }
    }

    private var title: some View {
        Text("Activity").noahText(style).accessibilityAddTraits(.isHeader).fixedSize()
    }
}

/// The active filter's name with ×; tapping it clears the filter.
struct ActivityFilterChip: View {
    let filter: HistoryFilter
    let onClear: () -> Void

    var body: some View {
        Button(action: onClear) {
            HStack(spacing: 4) {
                Text(LocalizedStringKey(filter.title)).noahText(ActivityText.chip)
                Image(systemName: "xmark").font(.system(size: 11, weight: .semibold)).foregroundStyle(NoahTokens.Colors.muted)
            }
            .foregroundStyle(NoahTokens.Colors.foreground)
            .padding(.leading, 12).padding(.trailing, 9)
            .frame(minHeight: 30)
            .fixedSize()
            .glassCompat(in: Capsule(), interactive: true)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text(LocalizedStringKey(filter.title)))
        .accessibilityHint(Text("Clear filter"))
        .accessibilityIdentifier("activity-filter-chip")
    }
}

/// A 40-pt glass circle for the wide panes' own bars (Home's top-bar buttons).
struct ActivityCircleIcon: View {
    let systemName: String

    var body: some View {
        Image(systemName: systemName)
            .font(.system(size: 19, weight: .medium))
            .foregroundStyle(NoahTokens.Colors.foreground)
            .frame(width: 40, height: 40)
            .contentShape(Circle())
            .glassCompat(in: Circle(), interactive: true)
            .frame(width: NoahTokens.Size.hitTarget, height: NoahTokens.Size.hitTarget)
    }
}

/// The wide pane's search field (prototype `.srch`), opened over its bar with the keyboard.
private struct ActivitySearchField: View {
    @Binding var query: String
    let onCancel: () -> Void
    @FocusState private var focused: Bool

    var body: some View {
        HStack(spacing: 8) {
            HStack(spacing: 8) {
                Image(systemName: "magnifyingglass").font(.system(size: 16, weight: .medium)).foregroundStyle(NoahTokens.Colors.muted)
                TextField("Search", text: $query)
                    .noahText(NoahTokens.Typography.body)
                    .focused($focused)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .submitLabel(.search)
                    .accessibilityIdentifier("activity-search-field")
                if !query.isEmpty {
                    Button { query = ""; focused = true } label: {
                        Image(systemName: "xmark.circle.fill").font(.system(size: 16)).foregroundStyle(NoahTokens.Colors.muted)
                            .frame(width: 30, height: 30)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(Text("Clear search"))
                }
            }
            .padding(.leading, 14).padding(.trailing, 6)
            .frame(height: NoahTokens.Size.hitTarget)
            .glassCompat(in: Capsule())
            Button("Cancel", action: onCancel)
                .noahText(NoahTokens.Typography.body)
                .foregroundStyle(NoahTokens.Colors.foreground)
                .padding(.leading, 6)
        }
        // Opening search brings the keyboard; coming back from a result keeps it down over the results.
        .onAppear { if query.isEmpty { focused = true } }
    }
}

/// The wide trailing pane before a payment is chosen.
struct ActivitySelectPrompt: View {
    var body: some View {
        VStack(spacing: 14) {
            Image(systemName: "list.bullet").font(.system(size: 22, weight: .medium))
                .frame(width: 56, height: 56)
                .background(NoahTokens.Colors.soft, in: Circle())
            Text("Select a payment").noahText(NoahTokens.Typography.control)
        }
        .foregroundStyle(NoahTokens.Colors.muted)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(NoahTokens.Colors.background.ignoresSafeArea())
    }
}

private extension ToolbarContent {
    @ToolbarContentBuilder
    func sharedBackgroundVisibilityIfAvailable(_ visibility: Visibility) -> some ToolbarContent {
        if #available(iOS 26, *) {
            sharedBackgroundVisibility(visibility)
        } else {
            self
        }
    }
}
