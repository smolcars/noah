import SwiftUI

/// Exits (exit.md, "Exits list"; exit-v2-notes.md, Approved item 3): every exit bark tracks
/// (`listAllExits`), grouped Ready to claim, Exiting and Finished. Exits that share transactions
/// share a gutter: their paths drawn once, lying on their side like a branch. Every other row has a
/// narrow gutter with just its coin and its state. Today's status filter is in the bar.
struct ExitListView: View {
    @State private var model: OnchainModel
    @State private var filter: String?
    @State private var haptics = OnboardingHaptics()
    @State private var window = OnboardingLayout(size: .zero, insets: EdgeInsets())
    @Environment(AppModel.self) private var app
    @Environment(\.settings) private var settings
    @Environment(\.locale) private var locale

    init(model: OnchainModel = OnchainModel()) { _model = State(initialValue: model) }

    private var language: String { locale.identifier }
    private var exits: [ExitModel] { model.overview?.exits ?? [] }
    private var ready: [ExitModel] { exits.filter(\.claimable) }
    /// The gutter's step between transactions: 11 pt on the iPhone SE, 13 on wide windows.
    private var step: Double { settings.wide ? 13 : window.size.width > 0 && window.size.width < 380 ? 11 : 12 }

    var body: some View {
        let money = ExitMoney(app: app, locale: locale)
        let shown = exits.filter { filter == nil || $0.state == filter }
        ExitPanes {
            if let line = Self.subtitle(exits, language: language) {
                Text(verbatim: line).noahText(SettingsText.line).foregroundStyle(NoahTokens.Colors.muted)
                    .fixedSize(horizontal: false, vertical: true)
            }
            // Every exit's fee children, each once, as Activity's "Exit fee" rows.
            let fees = ExitFees.total(exits, childFees: model.overview?.childFees ?? [:])
            if fees > 0 {
                Text(verbatim: L10n.text("{amount} in network fees", money(fees), locale: language))
                    .noahText(SettingsText.line).foregroundStyle(NoahTokens.Colors.muted)
            }
        } trailing: {
            if exits.isEmpty && !model.loading {
                SettingsFine(text: L10n.text("No exits have been started.", locale: language))
            }
            ForEach(Self.sections(shown), id: \.title) { section in
                SettingsSection(title: LocalizedStringKey(section.title))
                ForEach(section.shared, id: \.first?.id) { members in
                    ExitListGroup(rows: members.map { item($0, money) }, step: step) { settings.open(.exitProgress($0)) }
                        .padding(.top, 4)
                    let paths = members.reduce(0) { $0 + $1.path.count }, unique = Set(members.flatMap { $0.path.map(\.txid) }).count
                    Text(verbatim: L10n.text(
                        "These {n} share part of their path: {count} transactions to send, not {total}. Shared ones are sent and paid once.",
                        members.count.formatted(),
                        unique.formatted(),
                        paths.formatted(),
                        locale: language))
                        .noahText(HomeText.footnote).foregroundStyle(NoahTokens.Colors.muted)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.leading, ExitListGroup.gutter(step)).padding(.top, 4).padding(.bottom, 2)
                }
                ForEach(Array(section.solo.enumerated()), id: \.element.id) { index, exit in
                    let row = item(exit, money)
                    ExitListRow(exit: exit, progress: row.progress, text: row.text, coin: row.coin, hairline: index < section.solo.count - 1) {
                        settings.open(.exitProgress(exit.id))
                    }
                }
            }
            OnchainStatusSection(model: model).settingsReceipts().padding(.top, 18)
        } footer: {
            if !ready.isEmpty {
                Button(L10n.text("Claim {amount}", money(ready.reduce(0) { $0 + $1.amount }), locale: language)) {
                    haptics.play(.light)
                    settings.open(.exitClaim)
                }
                .buttonStyle(SettingsActionStyle(wrap: true))
                .accessibilityIdentifier("exit-list-claim")
            }
            Button(L10n.text("Exit more VTXOs", locale: language)) { settings.open(.exitChoose) }
                .buttonStyle(.plain).noahText(NoahTokens.Typography.control).underline()
                .frame(minHeight: NoahTokens.Size.hitTarget)
                .accessibilityIdentifier("exit-list-more")
        }
        .settingsChrome("Exits") {
            Menu {
                Picker(selection: $filter) {
                    Text(verbatim: L10n.text("All", locale: language)).tag(nil as String?)
                    ForEach(ExitTimeline.labels.sorted { $0.key < $1.key }, id: \.key) { entry in
                        Text(verbatim: L10n.text(entry.value, locale: language)).tag(Optional(entry.key))
                    }
                } label: { Text(verbatim: L10n.text("Status", locale: language)) }
            } label: { ActivityCircleIcon(systemName: filter == nil ? "line.3.horizontal.decrease" : "line.3.horizontal.decrease.circle.fill") }
                .accessibilityLabel(Text(verbatim: L10n.text("Status", locale: language)))
                .accessibilityIdentifier("exit-list-filter")
        }
        .onboardingHaptics(haptics)
        .onboardingWindow($window)
        .modifier(OnchainScreen(model: model, presentsReview: false))
    }

    private func item(_ exit: ExitModel, _ money: ExitMoney) -> ExitListGroup.Row {
        let progress = model.progress(exit), tip = model.overview?.tip ?? 0
        return .init(exit: exit, progress: progress,
                     text: ExitText(
                         progress: progress,
                         exit: exit,
                         tip: tip,
                         overview: model.overview,
                         attempts: model.attempts,
                         money: money,
                         language: language),
                     coin: ExitGutterCoin(progress, tip: tip, delta: model.overview?.exitDelta ?? 144))
    }

    nonisolated static func group(_ exit: ExitModel) -> String {
        exit.claimable ? "Ready to claim" : ExitRules.live(exit.state) ? "Exiting" : "Finished"
    }

    /// Ready to claim, Exiting and Finished, each with its groups of exits that share transactions
    /// (drawn in one gutter, Approved item 3's option B) and the rest, one coin each.
    struct ListSection { let title: String; let shared: [[ExitModel]]; let solo: [ExitModel] }
    nonisolated static func sections(_ exits: [ExitModel]) -> [ListSection] {
        ["Ready to claim", "Exiting", "Finished"].compactMap { title in
            let rows = exits.filter { group($0) == title }
            guard !rows.isEmpty else { return nil }
            // A group's rows go in path order, so the transactions they share are drawn between neighbouring rows.
            let shared = title == "Finished" ? [] : ExitSharing.groups(rows).map {
                $0.sorted { $0.path.map(\.txid).lexicographicallyPrecedes($1.path.map(\.txid)) }
            }
            return ListSection(title: title, shared: shared, solo: rows.filter { exit in !shared.contains { $0.contains { $0.id == exit.id } } })
        }
    }

    /// "6 in progress · 1 ready to claim", leaving out the parts that are zero; nil when both are.
    nonisolated static func subtitle(_ exits: [ExitModel], language: String) -> String? {
        let progress = exits.count(where: { ExitRules.live($0.state) && !$0.claimable }), ready = exits.filter(\.claimable).count
        let parts = [progress > 0 ? L10n.plural("{n} in progress", count: progress, locale: language) : nil,
                     ready > 0 ? L10n.plural("{n} ready to claim", count: ready, locale: language) : nil].compactMap(\.self)
        return parts.isEmpty ? nil : parts.joined(separator: " · ")
    }
}

/// One exit: its amount and phase line, wrapping, beside its gutter. On its own the gutter is 44 pt
/// with the coin and its state; in a shared group the group's tree draws it.
struct ExitListRow: View {
    let exit: ExitModel
    let progress: ExitProgress
    let text: ExitText
    var coin: ExitGutterCoin?
    var gutter = 44.0
    var hairline = true
    let action: () -> Void
    @Environment(AppModel.self) private var app
    @Environment(\.locale) private var locale

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 1) {
                    Text(verbatim: ExitMoney(app: app, locale: locale)(exit.amount)).noahText(HomeText.amount).monospacedDigit()
                    Text(verbatim: text.listLine).noahText(HomeText.footnote).monospacedDigit()
                        .foregroundStyle(text.statusKind == .failure ? NoahTokens.Colors.failure : text.statusKind == .ready ? NoahTokens.Colors
                            .foreground : NoahTokens.Colors.muted)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .opacity(progress.live ? 1 : 0.55)
                Image(systemName: "chevron.right").accessibilityHidden(true).font(.system(size: 14, weight: .semibold)).foregroundStyle(NoahTokens.Colors.muted)
            }
            .padding(.leading, gutter)
            .padding(.vertical, 9)
            .frame(minHeight: 64)
            .background(alignment: .leading) { if let coin { ExitGutterCoinView(coin: coin).frame(width: gutter) } }
            .contentShape(Rectangle())
            .overlay(alignment: .bottom) { if hairline { HomeHairline().padding(.leading, gutter) } }
        }
        .buttonStyle(SettingsRowPressStyle())
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("exit-row-\(exit.id)")
    }
}

extension ExitText {
    /// The list's one line.
    var listLine: String {
        switch progress.phase {
        case .claimed: return L10n.text(
                "Claimed · block {height}",
                (progress.claimedHeight ?? tip).formatted(.number.locale(Locale(identifier: language))),
                locale: language)
        case .canceled: return L10n.text("Canceled · still in Ark", locale: language)
        case .waiting:
            let left = progress.left(tip: tip) ?? 0
            guard left > 0 else {
                return L10n.text(
                    "Unlocks at block {height}",
                    (progress.claimableHeight ?? tip).formatted(.number.locale(Locale(identifier: language))),
                    locale: language)
            }
            return L10n.plural("Waiting · {n} blocks · {time}", count: Int(left), ExitText.about(left, language), locale: language)
        case .sending, .mempool, .shared, .confirmed:
            let shared = ExitSharing.groups(overview?.exits ?? []).contains { $0.contains { $0.id == exit.id } }
            let line = L10n.text("Sending {n} of {total}", (progress.step ?? progress.confirmed + 1).formatted(), progress.total.formatted(), locale: language)
            return shared ? L10n.text("{status} · shared", line, locale: language) : line
        case .needsFunds: return L10n.text("Needs on-chain funds", locale: language)
        default: return status
        }
    }
}

/// An exit's coin in a gutter, carrying its state (Approved item 3): a glow when ready, the dial
/// while locked, a check when claimed, a red "!" when it needs funds, muted when it ended otherwise.
struct ExitGutterCoin: Equatable {
    /// Its last transaction is in a block.
    var ink = false
    /// Its last transaction, or the claim, waits for the next block.
    var waiting = false
    var ready = false
    /// The timelock's fill while locked.
    var lock: Double?
    var claimed = false
    var canceled = false
    var alarm = false

    init() { /* All parts start hidden. */ }

    init(_ progress: ExitProgress, tip: UInt32, delta: UInt32) {
        let last = progress.total > 0 && progress.step == progress.total
        waiting = last && [.mempool, .shared].contains(progress.phase) || progress.phase == .claiming
        ready = progress.phase == .claimable
        claimed = progress.phase == .claimed
        canceled = [.canceled, .alreadySpent, .swept].contains(progress.phase)
        alarm = progress.phase == .needsFunds
        ink = progress.total > 0 && progress.confirmed >= progress.total && !claimed && !ready
        if [.waiting, .onchain].contains(progress.phase), let unlock = progress.claimableHeight {
            let start = unlock - min(unlock, delta)
            lock = unlock > start ? ((Double(tip) - Double(start)) / Double(unlock - start)).clamped(0, 1) : 1
        }
    }

    var finished: Bool { claimed || canceled }

    /// What breathes: a waiting coin ripples, a ready one glows.
    func pulses(at center: CGPoint, id: String) -> [ExitPulse] {
        if waiting { return [.init(id: id + "wait", kind: .coin(9), center: center)] }
        if ready { return [.init(id: id + "ready", kind: .glow(9.2), center: center)] }
        return []
    }
}

/// Draws the gutters' coins and paths (prototype `drawGut`).
enum ExitGutterPainter {
    static func coin(_ context: GraphicsContext, _ p: CGPoint, _ coin: ExitGutterCoin, colors: MotionColors) {
        let canvas = MotionCanvas(context: context, size: CGSize(width: 1, height: 1), viewBox: CGRect(x: 0, y: 0, width: 1, height: 1), colors: colors)
        if coin.ready { canvas.stroke(canvas.circle(p.x, p.y, 14), colors.orange, 2) }
        if let lock = coin.lock {
            canvas.stroke(canvas.circle(p.x, p.y, 14), colors.hairline, 2)
            var arc = Path()
            arc.addArc(center: p, radius: 14, startAngle: .degrees(-90), endAngle: .degrees(-90 + 360 * lock), clockwise: false)
            context.stroke(arc, with: .color(colors.foreground), style: StrokeStyle(lineWidth: 2.2, lineCap: .round))
        }
        let alpha = coin.finished ? 0.6 : 1
        canvas.fill(canvas.circle(p.x, p.y, 10), (coin.ink ? colors.foreground : colors.soft).opacity(alpha))
        canvas.stroke(canvas.circle(p.x, p.y, 10), (coin.canceled ? colors.muted : colors.orange).opacity(alpha), 1.9)
        if coin.claimed {
            canvas.layer(center: CGPoint(x: p.x + 8, y: p.y + 8), opacity: 1, scale: 0.62) { $0.checkBadge(p.x + 8, p.y + 8) }
        }
        if coin.lock != nil {
            canvas.layer(center: CGPoint(x: p.x + 8, y: p.y + 15), opacity: 1, scale: 10 / 24) { pad in
                pad.padlockBody(p.x + 8, p.y + 8)
                pad.shackle(p.x + 8, p.y + 8, open: 0)
            }
        }
        if coin.alarm {
            canvas.fill(canvas.circle(p.x + 8, p.y + 8, 6), NoahTokens.Colors.failure)
            context.draw(Text(verbatim: "!").font(.system(size: 9, weight: .bold)).foregroundColor(.white), at: CGPoint(x: p.x + 8, y: p.y + 8))
        }
    }
}

/// A row's gutter on its own: the coin and its state.
struct ExitGutterCoinView: View {
    let coin: ExitGutterCoin
    @State private var visible = true
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        GeometryReader { proxy in
            let colors = MotionColors.theme(colorScheme), center = CGPoint(x: proxy.size.width / 2, y: proxy.size.height / 2)
            let pulses = coin.pulses(at: center, id: "coin"), animating = visible && scenePhase == .active && !reduceMotion
            ZStack(alignment: .topLeading) {
                ForEach(pulses) { ExitPulseView(pulse: $0, front: false, animating: animating, colors: colors) }
                Canvas { context, _ in ExitGutterPainter.coin(context, center, coin, colors: colors) }
                ForEach(pulses) { ExitPulseView(pulse: $0, front: true, animating: animating, colors: colors) }
            }
        }
        .onScrollVisibilityChange(threshold: 0.1) { visible = $0 }
        .accessibilityHidden(true)
    }
}

/// Exits that share transactions: their paths drawn once in a shared gutter, lying on their side,
/// from the round's block at the left to each coin at the right. The tree reads each row's centre,
/// so it stretches with Dynamic Type.
struct ExitListGroup: View {
    struct Row {
        var exit: ExitModel
        var progress: ExitProgress
        var text: ExitText
        var coin: ExitGutterCoin
    }

    let rows: [Row]
    var step = 12.0
    let open: (String) -> Void

    /// The shared gutter's width for a step.
    nonisolated static func gutter(_ step: Double) -> Double { 40 + 6 * step }

    var body: some View {
        let gutter = Self.gutter(step)
        VStack(spacing: 0) {
            ForEach(Array(rows.enumerated()), id: \.element.exit.id) { index, row in
                ExitListRow(exit: row.exit, progress: row.progress, text: row.text, gutter: gutter, hairline: index < rows.count - 1) { open(row.exit.id) }
                    .anchorPreference(key: ExitRowBounds.self, value: .bounds) { [row.exit.id: $0] }
            }
        }
        .backgroundPreferenceValue(ExitRowBounds.self) { anchors in
            GeometryReader { proxy in
                let ys = rows.map { row in anchors[row.exit.id].map { Double(proxy[$0].midY) } ?? 0 }
                ExitGutterTree(paths: rows.map { ExitGutterTree.Route($0.exit, $0.progress, coin: $0.coin) }, ys: ys, gutter: gutter, step: step)
            }
        }
    }
}

struct ExitRowBounds: PreferenceKey {
    static let defaultValue: [String: Anchor<CGRect>] = [:]
    static func reduce(value: inout [String: Anchor<CGRect>], nextValue: () -> [String: Anchor<CGRect>]) {
        value.merge(nextValue()) { $1 }
    }
}

/// The shared gutter's tree.
struct ExitGutterTree: View {
    enum Status: Equatable { case mined, pending, unsent, needsFunds }

    struct Route: Equatable {
        var txids: [String]
        var statuses: [Status]
        var coin: ExitGutterCoin
        var canceled: Bool { coin.canceled }

        init(txids: [String], statuses: [Status], coin: ExitGutterCoin) {
            self.txids = txids
            self.statuses = statuses
            self.coin = coin
        }

        /// Where its current transaction is: the first not in a block.
        var current: Int { statuses.firstIndex { $0 != .mined } ?? statuses.count }

        /// The route along a group's `plan` (`ExitGutterTree.plan`): each place alone or each run of places as one
        /// step, then the coin. A step's id names its column, so routes share it exactly where they share the transaction.
        func drawn(plan: [ClosedRange<Int>]) -> [Step] {
            let places = txids.count - 1
            var steps: [Step] = []
            for (column, run) in plan.enumerated() where run.lowerBound < places {
                let first = run.lowerBound, status = statuses[safe: first] ?? .unsent
                if run.count == 1 {
                    steps.append(Step(id: "\(column)/" + txids[first], status: status, fold: nil))
                } else {
                    let kind: ExitStageScene.FoldKind = status == .mined ? .stack : .more
                    steps.append(Step(id: "\(column)/\(kind)/" + txids[first], status: status == .mined ? .mined : .unsent, fold: kind))
                }
            }
            return steps + [Step(id: "coin/" + (txids.last ?? ""), status: statuses.last ?? .unsent, fold: nil)]
        }

        /// The route on its own in at most `capacity` steps before its coin, folded as the exit's picture folds a long
        /// path: the oldest blocks stacked, the one being sent and the next unsent on their own, the rest one "+N" node.
        func drawn(capacity: Int) -> [Step] {
            let scene = ExitStageScene(picture: .path, levels: statuses.map { status -> ExitStageScene.Level in
                switch status {
                case .mined: .mined(nil)
                case .pending: .pending(theirs: false, bumped: false)
                case .needsFunds: .needsFunds
                case .unsent: .unsent
                }
            }).folded(nodes: capacity)
            return scene.levels.indices.map { index in
                let fold = scene.fold(at: index), txid = txids[safe: scene.real(index)] ?? ""
                let status: Status = switch scene.levels[index] {
                case .mined: .mined
                case .pending: .pending
                case .needsFunds: .needsFunds
                default: .unsent
                }
                return Step(id: "\(index)/" + (fold.map { "\($0.kind)\($0.count)/" } ?? "") + txid, status: status, fold: fold?.kind)
            }
        }

        init(_ exit: ExitModel, _ progress: ExitProgress, coin: ExitGutterCoin) {
            txids = exit.path.map(\.txid)
            statuses = exit.path.indices.map { index in
                if index < progress.confirmed { return .mined }
                guard index == progress.confirmed else { return .unsent }
                switch progress.phase {
                case .mempool, .shared: return .pending
                case .needsFunds: return .needsFunds
                default: return .unsent
                }
            }
            self.coin = coin
        }
    }

    /// One place on a route: its column's id, its status and what it folds.
    struct Step {
        var id: String
        var status: Status
        var fold: ExitStageScene.FoldKind?
    }

    struct Node: Equatable {
        var id: String
        var point: CGPoint
        var status: Status
        var alpha: Double
        /// Stands for several transactions: the oldest blocks stacked, or unsent ones as "+N".
        var fold: ExitStageScene.FoldKind?
    }

    struct Link: Equatable {
        var from: CGPoint
        var to: CGPoint
        var ink: Bool
        var alpha: Double
    }

    /// Where everything goes, in the gutter's own coordinates (it starts 6 pt left of the rows).
    struct Layout: Equatable {
        var root: CGPoint
        var nodes: [Node] = []
        var links: [Link] = []
        var coins: [CGPoint] = []
    }

    let paths: [Route]
    let ys: [Double]
    let gutter: Double
    var step = 12.0
    @State private var visible = true
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.scenePhase) private var scenePhase

    /// The least step between two transactions in a gutter: an 8 pt square beside one with a needs-funds ring
    /// (6 pt radius), and the clearance.
    nonisolated static let minimumStep = 12.0

    /// How many steps a route draws before its coin in a gutter `gutter` wide: each at least `minimumStep` apart, the
    /// last clear of the coin's 14 pt ring.
    nonisolated static func capacity(gutter: Double) -> Int { max(3, Int(((gutter + 14 - 30) - 36) / minimumStep)) }

    // swiftlint:disable discouraged_optional_collection - nil: no plan fits the capacity
    /// One fold for a whole group, by place along the paths, so a place is drawn alike on every row and what rows share
    /// stays drawn once: each row's current transaction gets a place of its own, then the next unsent ones, the latest
    /// blocks and the rest while they fit; runs of places left over become one step each. nil when the rows' current
    /// transactions are too far apart to fit.
    nonisolated static func plan(_ routes: [Route], capacity: Int) -> [ClosedRange<Int>]? {
        // swiftlint:enable discouraged_optional_collection
        let lengths = routes.map { $0.txids.count - 1 }, places = lengths.max() ?? 0
        guard places > capacity else { return (0..<places).map { $0...$0 } }
        func runs(_ shown: Set<Int>) -> [ClosedRange<Int>] {
            var runs: [ClosedRange<Int>] = [], start: Int?
            for place in 0...places {
                if place < places && !shown.contains(place) { start = start ?? place }
                else if let first = start { runs.append(first...(place - 1)); start = nil }
            }
            return runs
        }
        let currents = zip(routes, lengths).compactMap { $0.current < $1 ? $0.current : nil }
        var shown = Set(currents)
        guard shown.count + runs(shown).count <= capacity else { return nil }
        var wanted: [Int] = []
        for (route, length) in zip(routes, lengths) { wanted += [route.current + 1, route.current + 2].filter { $0 < length } }
        for (route, length) in zip(routes, lengths) { wanted += Array(stride(from: min(route.current, length) - 1, through: 0, by: -1)) }
        wanted += Array(0..<places)
        for place in wanted where !shown.contains(place) {
            let next = shown.union([place])
            if next.count + runs(next).count <= capacity { shown = next }
        }
        return (shown.map { $0...$0 } + runs(shown)).sorted { $0.lowerBound < $1.lowerBound }
    }

    /// Lays out the shared paths: each step drawn once, at the middle of the rows it leads to. Long paths fold along one
    /// plan for the group (`plan`), so steps stay `minimumStep` apart however long they are; where no plan fits, each
    /// row draws its own folded path at its own height.
    nonisolated static func layout(_ paths: [Route], ys: [Double], gutter: Double, step: Double) -> Layout {
        let width = gutter + 14, coinX = width - 30, x0 = 8.0, capacity = capacity(gutter: gutter)
        let routes = plan(paths, capacity: capacity).map { plan in paths.map { $0.drawn(plan: plan) } }
            ?? paths.enumerated().map { row, path in path.drawn(capacity: capacity).map { Step(id: "row\(row)/" + $0.id, status: $0.status, fold: $0.fold) } }
        let longest = routes.map(\.count).max() ?? 1, d = min(max(step, minimumStep), (coinX - 36) / Double(max(1, longest - 1)))
        var rows: [String: [Double]] = [:]
        for (route, y) in zip(routes, ys) { for step in route.dropLast() { rows[step.id, default: []].append(y) } }
        func y(_ id: String) -> Double { rows[id].map { $0.reduce(0, +) / Double($0.count) } ?? 0 }
        var layout = Layout(root: CGPoint(x: x0, y: ys.isEmpty ? 0 : ys.reduce(0, +) / Double(ys.count)))
        var drawn = Set<String>()
        for ((path, route), rowY) in zip(zip(paths, routes), ys) {
            let ended = path.coin.finished
            var previous = CGPoint(x: x0 + 4, y: layout.root.y), key = "root"
            for (index, step) in route.enumerated() {
                let last = index == route.count - 1
                let point = CGPoint(x: last ? coinX : x0 + Double(index + 1) * d + 6, y: last ? rowY : y(step.id))
                let ink = step.status == .mined, alpha = path.canceled && !ink ? 0.3 : ended ? 0.55 : 1
                if drawn.insert(key + ">" + step.id).inserted {
                    layout.links.append(.init(from: previous, to: CGPoint(x: point.x - (last ? 11 : 4), y: point.y), ink: ink, alpha: alpha))
                }
                if !last, drawn.insert(step.id).inserted {
                    layout.nodes.append(.init(id: step.id, point: point, status: step.status, alpha: alpha, fold: step.fold))
                }
                previous = CGPoint(x: point.x + 4, y: point.y)
                key = step.id
            }
            layout.coins.append(CGPoint(x: coinX, y: rowY))
        }
        return layout
    }

    var body: some View {
        let layout = Self.layout(paths, ys: ys, gutter: gutter, step: step), colors = MotionColors.theme(colorScheme)
        let pulses = layout.nodes.filter { $0.status == .pending }.map { ExitPulse(id: $0.id, kind: .square(8), center: $0.point) }
            + zip(paths, layout.coins).enumerated().flatMap { index, item in item.0.coin.pulses(at: item.1, id: "\(index)") }
        let animating = visible && scenePhase == .active && !reduceMotion
        ZStack(alignment: .topLeading) {
            ForEach(pulses) { ExitPulseView(pulse: $0, front: false, animating: animating, colors: colors) }
            Canvas { context, _ in draw(context, layout, colors) }
            ForEach(pulses) { ExitPulseView(pulse: $0, front: true, animating: animating, colors: colors) }
        }
        .frame(width: gutter + 14)
        .offset(x: -6)
        .onScrollVisibilityChange(threshold: 0.1) { visible = $0 }
        .accessibilityHidden(true)
    }

    private func draw(_ context: GraphicsContext, _ layout: Layout, _ colors: MotionColors) {
        for link in layout.links {
            context.stroke(ExitStagePainter.sideways(link.from, link.to), with: .color((link.ink ? colors.foreground : colors.orange).opacity(link.alpha)),
                           style: StrokeStyle(lineWidth: link.ink ? 2 : 1.6, lineCap: .round))
        }
        let allEnded = paths.allSatisfy(\.coin.finished)
        context.fill(Path(roundedRect: CGRect(x: layout.root.x - 4.5, y: layout.root.y - 4.5, width: 9, height: 9), cornerRadius: 2),
                     with: .color(colors.foreground.opacity(allEnded ? 0.55 : 1)))
        for node in layout.nodes {
            var c = context
            c.opacity *= node.alpha
            let p = node.point, square = Path(roundedRect: CGRect(x: p.x - 4, y: p.y - 4, width: 8, height: 8), cornerRadius: 2.5)
            switch node.status {
            case .mined:
                c.fill(square, with: .color(colors.foreground))
                if node.fold == .stack {
                    // The oldest blocks stacked into one.
                    for offset in [-4.0 / 3, 4.0 / 3] {
                        var line = Path()
                        line.move(to: CGPoint(x: p.x - 4, y: p.y + offset))
                        line.addLine(to: CGPoint(x: p.x + 4, y: p.y + offset))
                        c.stroke(line, with: .color(colors.background), lineWidth: 1)
                    }
                }
            case .unsent where node.fold == .more:
                // Unsent ones folded into one.
                let ring = Path(ellipseIn: CGRect(x: p.x - 4, y: p.y - 4, width: 8, height: 8))
                c.fill(ring, with: .color(colors.background))
                c.stroke(ring, with: .color(colors.orange), style: StrokeStyle(lineWidth: 1.4, dash: [1.6, 1.6]))
            case .pending:
                c.fill(square, with: .color(colors.background))
            case .needsFunds:
                c.fill(square, with: .color(colors.background))
                c.stroke(square, with: .color(colors.orange), lineWidth: 1.6)
                c.stroke(Path(ellipseIn: CGRect(x: p.x - 6, y: p.y - 6, width: 12, height: 12)), with: .color(NoahTokens.Colors.failure.opacity(0.8)),
                         style: StrokeStyle(lineWidth: 1.5, dash: [3, 3]))
            case .unsent:
                let dot = Path(ellipseIn: CGRect(x: p.x - 1.85, y: p.y - 1.85, width: 3.7, height: 3.7))
                c.fill(dot, with: .color(colors.background))
                c.stroke(dot, with: .color(colors.orange), lineWidth: 1.6)
            }
        }
        for (path, point) in zip(paths, layout.coins) { ExitGutterPainter.coin(context, point, path.coin, colors: colors) }
    }
}
