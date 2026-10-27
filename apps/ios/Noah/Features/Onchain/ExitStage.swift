import SwiftUI

/// The exit's picture (exit-v2-notes.md, "The picture: one tree, everywhere"; prototype `drawHero`):
/// your branch of the round's tree standing on the chain. The round's block is its foot, mined
/// transactions are ink blocks labelled with their height, the waiting one glows, unsent ones are
/// hollow and your coin is at the top. Each bark state has one still picture; when the wallet
/// reaches a new state the picture moves to it with the house springs, and only then.
struct ExitStageScene: Equatable {
    enum Level: Equatable {
        /// Not sent: waiting for the one below it.
        case unsent
        /// Ready for its fee child (`AwaitingCpfpBroadcast`).
        case ready
        /// Ready, but there aren't enough confirmed funds for its fee (Decision 3).
        case needsFunds
        /// In the mempool with its fee child: this wallet's, someone else's, or raised.
        case pending(theirs: Bool, bumped: Bool)
        /// In a block.
        case mined(UInt32?)

        var isMined: Bool { if case .mined = self { true } else { false } }
        var isPending: Bool { if case .pending = self { true } else { false } }
    }

    enum Destination: Equatable { case phone, other(String?) }

    /// One of your other exits leaving this branch where they stop sharing.
    struct Other: Equatable {
        var level: Int
        var amount: String
        var sent: Bool
    }

    var picture: ExitProgress.Picture
    /// The Emergency exit page's explainer: the tree before anything is sent.
    var explain = false
    /// The explainer's real count, when it draws fewer levels.
    var transactions: Int?
    /// The explainer grows once when the page opens.
    var grown = true
    /// The path's transactions, root first; the last one's output is your coin.
    var levels: [Level]
    /// Which transactions your other exits share.
    var shared: [Bool] = []
    var others: [Other] = []
    /// Levels that stand for several transactions on a path too long to draw (`folded`).
    var folds: [Fold] = []
    var tip: UInt32 = 0
    var stale = false
    var checked: UInt32?
    var stuck: UInt32 = 0
    /// The waiting transaction's fee child, formatted.
    var fee: String?
    /// Needs funds: about how much to add.
    var need: String?
    /// The on-chain balance under the phone.
    var balance: String?
    /// The leaf's block, where the timelock starts, and where it opens.
    var leaf: UInt32?
    var unlock: UInt32?
    var delta: UInt32 = 144
    var claimFee: String?
    var claimHeight: UInt32?
    var destination = Destination.phone
    /// What landed on this phone, formatted.
    var received: String?

    init(picture: ExitProgress.Picture, levels: [Level]) {
        self.picture = picture
        self.levels = levels.isEmpty ? [.unsent] : levels
    }

    /// The Emergency exit page's explainer: a branch of `count` transactions, drawn with 8 levels at most
    /// (as Review's rail) and labelled with the real count.
    static func explainer(count: Int, grown: Bool = true) -> Self {
        var scene = Self(picture: .path, levels: Array(repeating: .unsent, count: min(max(1, count), 8)))
        scene.explain = true
        scene.grown = grown
        scene.transactions = max(1, count)
        return scene
    }

    var claiming: Bool { picture == .claim || picture == .claimed }
    var locked: Bool { picture == .lock || picture == .unlocked }

    /// A drawn level standing for several transactions: the oldest confirmed ones as a stack of blocks at the
    /// base, or unsent ones between the next few and your coin as one "+N" node.
    struct Fold: Equatable {
        enum Kind: Equatable { case stack, more }
        var kind: Kind
        /// Its index in `levels`, and how many transactions it stands for.
        var index: Int
        var count: Int
        /// A stack's blocks, oldest to newest, when bark gave their heights.
        var heights: ClosedRange<UInt32>?
    }

    func fold(at index: Int) -> Fold? { folds.first { $0.index == index } }

    /// The real path's length, folds counted in full.
    var realCount: Int { levels.count + folds.reduce(0) { $0 + $1.count - 1 } }

    /// The real index of the first transaction drawn at `index`.
    func real(_ index: Int) -> Int { index + folds.filter { $0.index < index }.reduce(0) { $0 + $1.count - 1 } }

    /// The drawn level holding real transaction `real`.
    func level(holding real: Int) -> Int? {
        levels.indices.first { real >= self.real($0) && real < self.real($0) + (fold(at: $0)?.count ?? 1) }
    }

    /// The scene with at most `capacity` transactions drawn below your coin, so every level keeps its full
    /// spacing however long the path. The one being sent (or waiting, or needing funds) and the next two are
    /// always drawn on their own, and the coin; then as many of the latest confirmed ones as fit, the oldest
    /// stacked into one block; then more of the unsent ones, the rest folded into one "+N" node.
    func folded(nodes capacity: Int) -> ExitStageScene {
        let n = levels.count, nodes = n - 1
        guard !explain, folds.isEmpty, nodes > max(1, capacity) else { return self }
        let current = levels.firstIndex { !$0.isMined } ?? n, sending = current < nodes
        let confirmed = min(current, nodes), after = sending ? nodes - current - 1 : 0
        // The best fold that fits: the next unsent ones first (two), then the latest blocks, then more unsent.
        var best: (shown: Int, latest: Int)?, score = (-1, -1, -1)
        for shown in 0...after {
            for latest in 0...confirmed {
                let more = after - shown, stacked = confirmed - latest
                guard more != 1, stacked != 1 else { continue }
                let slots = (sending ? 1 : 0) + shown + (more > 0 ? 1 : 0) + latest + (stacked > 0 ? 1 : 0)
                let candidate = (min(shown, 2), latest, shown)
                if slots <= capacity, candidate > score { (best, score) = ((shown, latest), candidate) }
            }
        }
        guard let (shown, latest) = best else { return self }
        let stacked = confirmed - latest, more = after - shown
        var scene = self
        var drawn: [Int] = []   // each real node's drawn index
        scene.levels = []
        scene.folds = []
        if stacked > 0 {
            let heights = levels[0..<stacked].compactMap { if case .mined(let height) = $0 { height } else { nil } }
            scene.folds.append(.init(kind: .stack, index: 0, count: stacked,
                                     heights: heights.count == stacked ? heights.min()!...heights.max()! : nil))
            scene.levels.append(levels[stacked - 1])
            drawn += Array(repeating: 0, count: stacked)
        }
        for index in stacked..<(sending ? current + 1 + shown : confirmed) {
            drawn.append(scene.levels.count)
            scene.levels.append(levels[index])
        }
        if more > 0 {
            scene.folds.append(.init(kind: .more, index: scene.levels.count, count: more))
            drawn += Array(repeating: scene.levels.count, count: more)
            scene.levels.append(.unsent)
        }
        scene.levels.append(levels[nodes])
        drawn.append(scene.levels.count - 1)
        // What your other exits share, and where they branch, on the drawn levels.
        scene.shared = scene.levels.indices.map { level in drawn.indices.contains { drawn[$0] == level && shared[safe: $0] == true } }
        scene.others = others.map { other in
            var other = other
            other.level = (drawn[safe: other.level - 1] ?? scene.levels.count - 2) + 1
            return other
        }
        return scene
    }
}

/// The numbers one still picture stands for; two of them are mixed while the picture moves.
struct ExitStageStill: Equatable {
    var ark = 0.0
    var lift = 0.0
    var ghost = 0.0
    var grown = 1.0
    var mined: [Double] = []
    var pending: [Double] = []
    var wallet = 0.0
    var lock = 0.0
    var open = 0.0
    var dial = 0.0
    var claim = 0.0
    var claimMined = 0.0
    var cancel = 0.0
    var toOther = 0.0

    init(_ scene: ExitStageScene) {
        mined = scene.levels.map { $0.isMined ? 1 : 0 }
        pending = scene.levels.map { $0.isPending ? 1 : 0 }
        if scene.explain {
            ark = 1
            grown = scene.grown ? 1 : 0
            return
        }
        wallet = scene.levels.contains { $0 != .unsent } ? 1 : 0
        switch scene.picture {
        case .lift:
            ark = 0.4; lift = 1; ghost = 1; grown = 0; wallet = 0
        case .lock, .unlocked:
            lock = 1
            open = scene.picture == .unlocked ? 1 : 0
            if let leaf = scene.leaf, let unlock = scene.unlock, unlock > leaf {
                dial = ((Double(scene.tip) - Double(leaf)) / Double(unlock - leaf)).clamped(0, 1)
            }
            if scene.picture == .unlocked { dial = 1 }
        case .claim, .claimed:
            lock = 1; open = 1; dial = 1; claim = 1; wallet = 1
            claimMined = scene.picture == .claimed ? 1 : 0
        case .canceled:
            ark = 1; cancel = 1; wallet = 0
        case .none:
            grown = 0; wallet = 0
        case .path:
            break
        }
        if case .other = scene.destination, scene.claiming { toOther = 1 }
    }
}

/// One moment of the picture: two stills mixed `elapsed` seconds after the state changed.
struct ExitStageFrame {
    var ark = 0.0, lift = 0.0, ghost = 0.0, labels = 1.0, fadeIn = 1.0, tree = 1.0, dim = 0.0
    var cancel = 0.0, wallet = 0.0, lock = 0.0, padlock = 1.0, open = 0.0, dial = 0.0
    var stem = 0.0, claimSend = 0.0, claimMined = 0.0, claimStamp = 1.0, claimRipple = 0.0
    var land = 0.0, destPop = 0.0, destRing = 0.0, toOther = 0.0, flash = 0.0
    var fly = 1.0, oldFee = 0.0
    /// Per link 1…n (index 0 unused): how far each has grown.
    var grow: [Double] = []
    /// Per transaction 1…n-1 (index 0 unused): the pop as the stroke reaches it.
    var pop: [Double] = []
    var mined: [Double] = [], stamp: [Double] = [], ripple: [Double] = [], pending: [Double] = []
    /// For each of `b`'s drawn levels, `a`'s level holding the same transaction, matched by its real index: on a
    /// folded path each transaction moves, not each slot.
    var origin: [Int?] = []
    /// Transactions drawn on their own in `a` that fold into a stack or a "+N" node in `b`: they sink into it.
    var merging: [Merge] = []
    /// How far each transaction has moved from where `a` drew it.
    var travel = 1.0

    struct Merge: Equatable {
        var from: Int
        var into: Int
        var mined: Bool
    }

    /// `a` moving to `b`. Growth staggers 0.26 s a link; a block fills, settles from 1.22× and
    /// ripples once; the claim's stem draws (calm), then its coin travels into the destination.
    init(_ a: ExitStageScene, _ b: ExitStageScene, elapsed e: Double, reduceMotion: Bool) {
        let x = ExitStageStill(a), y = ExitStageStill(b), n = b.levels.count
        func spring(_ delay: Double, _ kind: MotionTime.Spring) -> Double { MotionTime.spring(e - delay, kind) }
        func mix(_ from: Double, _ to: Double, _ u: Double) -> Double { from + (to - from) * u }
        let snappy = spring(0, .snappy), soft = spring(0, .soft)
        let growing = x.grown < 0.5 && y.grown > 0.5, step = Self.growStep(n), grown = step * Double(n)
        grow = (0...n).map { j in growing ? ((e - Double(j - 1) * step) / step).clamped(0, 1) : y.grown }
        pop = (0...n).map { j in growing ? spring(Double(j) * step - 0.05, .bouncy) : y.grown }
        ghost = growing ? 0.5 * x.ghost * (1 - (e / grown).clamped(0, 1)) : 0.5 * mix(x.ghost, y.ghost, soft)
        ark = growing && y.ark < x.ark ? x.ark * (1 - ((e - grown) / 0.7).clamped(0, 1)) : mix(x.ark, y.ark, soft)
        lift = mix(x.lift, y.lift, snappy)
        if b.explain { labels = growing ? ((e - grown + 0.1) / 0.5).clamped(0, 1) : y.grown }
        // The same path: match by real transaction. Otherwise (Start's usual four, then bark's real path) by place.
        let same = a.realCount == b.realCount, coin = a.levels.count - 1
        origin = (0..<n).map { j -> Int? in
            if j == n - 1 { return coin }
            guard same else { return j < coin ? j : nil }
            return a.level(holding: b.real(j)).flatMap { $0 < coin ? $0 : nil }
        }
        if same {
            for i in 0..<coin where a.fold(at: i) == nil {
                guard let j = b.level(holding: a.real(i)), b.fold(at: j) != nil, b.real(j) != a.real(i) else { continue }
                merging.append(.init(from: i, into: j, mined: (x.mined[safe: i] ?? 0) > 0.5))
            }
        }
        travel = snappy
        for j in 0..<n {
            let was = origin[j].flatMap { x.mined[safe: $0] } ?? 0, now = y.mined[j]
            mined.append(mix(was, now, snappy))
            let fresh = was < 0.5 && now > 0.5
            stamp.append(fresh ? spring(0, .bouncy) : 1)
            ripple.append(fresh && !reduceMotion ? (e / 0.8).clamped(0, 1) : 0)
            pending.append(mix(origin[j].flatMap { x.pending[safe: $0] } ?? 0, y.pending[j], snappy))
        }
        dim = mix(x.claim, y.claim, soft)
        tree = 1 - 0.6 * dim
        if !b.explain { labels = 1 - dim }
        cancel = mix(x.cancel, y.cancel, soft)
        wallet = mix(x.wallet, y.wallet, soft)
        lock = mix(x.lock, y.lock, soft)
        padlock = x.lock < 0.5 && y.lock > 0.5 ? spring(0, .bouncy) : y.lock
        open = mix(x.open, y.open, spring(0, .bouncy))
        dial = mix(x.dial, y.dial, snappy)
        stem = mix(x.claim, y.claim, spring(0, .calm))
        claimSend = x.claim < 0.5 && y.claim > 0.5 ? spring(0.45, .snappy) : y.claim
        claimMined = mix(x.claimMined, y.claimMined, snappy)
        let landing = x.claimMined < 0.5 && y.claimMined > 0.5
        claimStamp = landing ? spring(0, .bouncy) : 1
        claimRipple = landing && !reduceMotion ? (e / 0.8).clamped(0, 1) : 0
        land = landing ? spring(0.5, .soft) : y.claimMined
        destPop = landing ? spring(1.25, .bouncy) : y.claimMined
        destRing = landing ? spring(1.35, .soft) : y.claimMined
        toOther = mix(x.toOther, y.toOther, soft)
        // A block came without it: the pill's outline flashes ink once.
        flash = b.stuck > a.stuck && e < 0.5 ? 1 : 0
        // The fee child flies from the phone once its transaction is in the mempool; a raised fee replaces it.
        if let j = b.levels.firstIndex(where: \.isPending) {
            let before = origin[j].flatMap { a.levels[safe: $0] }
            let bumped = { (level: ExitStageScene.Level?) in if case .pending(_, true)? = level { true } else { false } }
            if !(before?.isPending ?? false) { fly = spring(0.35, .soft) }
            else if bumped(b.levels[j]) && !bumped(before) { fly = spring(0.15, .soft); oldFee = 1 - soft }
        }
        // Labels that change cross-fade.
        if a != b && !b.explain { fadeIn = (e / 0.25).clamped(0, 1) }
    }

    /// Each link's share of the grow: 0.26 s, closer together on a long path, so any path grows in about
    /// a second and its labels follow by about 1.5 s.
    static func growStep(_ count: Int) -> Double { min(0.26, 1.05 / Double(max(1, count))) }

    /// When a move from `a` to `b` has settled and the picture can stand still.
    static func duration(_ a: ExitStageScene?, _ b: ExitStageScene, reduceMotion: Bool) -> Double {
        guard let a, !reduceMotion else { return reduceMotion ? 0.3 : 0 }
        let x = ExitStageStill(a), y = ExitStageStill(b)
        var duration = 1.4
        if x.grown < 0.5 && y.grown > 0.5 { duration = max(duration, growStep(b.levels.count) * Double(b.levels.count) + 1.4) }
        if x.claim < 0.5 && y.claim > 0.5 { duration = max(duration, 1.8) }
        if x.claimMined < 0.5 && y.claimMined > 0.5 { duration = max(duration, 2.6) }
        return duration
    }
}

struct ExitStageView: View {
    let scene: ExitStageScene
    var wide = false
    /// The picture as one VoiceOver element; nil hides it.
    var accessibilityText: String?
    /// The explainer has grown, kept by a page whose layout changes rebuild this view (compact and wide panes), so it
    /// grows once per visit. Without one, this view keeps it, which covers Back.
    var grew: Binding<Bool>?
    @State private var from: ExitStageScene?
    @State private var changedAt = Date.distantPast
    @State private var settled = true
    @State private var visible = true
    @State private var grewHere = false
    @ScaledMetric(relativeTo: .caption) private var textScale = 1.0
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.locale) private var locale

    var body: some View {
        GeometryReader { proxy in
            // A path too long for the picture is drawn folded; VoiceOver and the rows keep the real counts.
            let fit = ExitStageGeometry.fitting(scene, size: proxy.size, scale: textScale, wide: wide)
            let drawn = fit.scene, capacity = fit.geometry.foldCapacity
            let painter = ExitStagePainter(geometry: fit.geometry, colors: .theme(colorScheme), reduceMotion: reduceMotion, locale: locale)
            // At rest the tree stands still; what waits for a block moves in small overlays.
            let pulses = settled ? painter.pulses(drawn) : []
            let animating = visible && scenePhase == .active && !reduceMotion
            ZStack(alignment: .topLeading) {
                ForEach(pulses) { ExitPulseView(pulse: $0, front: false, animating: animating, colors: painter.colors) }
                TimelineView(.animation(paused: settled || scenePhase != .active)) { timeline in
                    Canvas { context, _ in
                        let elapsed = timeline.date.timeIntervalSince(changedAt), now = timeline.date.timeIntervalSinceReferenceDate
                        painter.draw(context, from: settled ? nil : from?.folded(nodes: capacity), to: drawn, elapsed: elapsed, now: now, inline: !settled,
                                     animating: animating)
                    }
                }
                ForEach(pulses) { ExitPulseView(pulse: $0, front: true, animating: animating, colors: painter.colors) }
            }
        }
        .onAppear {
            // The explainer grows once when the page opens, not again on Back or a layout change; Reduce Motion shows it grown.
            let once = grew ?? $grewHere
            guard scene.explain, !once.wrappedValue, !reduceMotion else { return }
            once.wrappedValue = true
            var seed = scene
            seed.grown = false
            begin(from: seed)
        }
        .onChange(of: scene) { old, new in
            // The wallet and the exit can arrive one read after the other: the second keeps the move going.
            if !settled, ExitStageStill(old) == ExitStageStill(new) { return }
            begin(from: old)
        }
        .task(id: changedAt) {
            guard !settled else { return }
            let duration = ExitStageFrame.duration(from, scene, reduceMotion: reduceMotion)
            do { try await Task.sleep(for: .seconds(duration)) } catch { return }
            settled = true
        }
        .onScrollVisibilityChange(threshold: 0.1) { visible = $0 }
        .modifier(ExitStageAccessibility(text: accessibilityText))
    }

    private func begin(from old: ExitStageScene) {
        from = old
        changedAt = Date()
        settled = false
    }
}

private struct ExitStageAccessibility: ViewModifier {
    let text: String?

    func body(content: Content) -> some View {
        if let text {
            content.accessibilityElement().accessibilityLabel(Text(verbatim: text)).accessibilityAddTraits(.isImage)
        } else {
            content.accessibilityHidden(true)
        }
    }
}

/// Draws the picture: a still, or two stills mixed while it moves (prototype `drawHero`, `heroLabels`).
struct ExitStagePainter {
    let geometry: ExitStageGeometry
    let colors: MotionColors
    let reduceMotion: Bool
    let locale: Locale

    private var language: String { locale.identifier }
    private func tr(_ source: String, _ arguments: Any...) -> String {
        arguments.isEmpty ? L10n.text(source, locale: language)
            : String(format: L10n.text(source, locale: language), locale: locale, arguments: arguments.map { String(describing: $0) })
    }
    private func height(_ value: UInt32) -> String { value.formatted(.number.locale(locale)) }

    // MARK: Labels

    /// The labels for a still, laid out by priority.
    func labels(_ scene: ExitStageScene, ys: [Double]) -> [ExitStageLabel] {
        let g = geometry, n = scene.levels.count, lh = g.lineHeight
        func Y(_ level: Int) -> Double { ys[safe: level] ?? g.coinY }
        var items: [ExitStageLabel] = []
        // The round's block, on the ground: bark gives no round height, so it says what it is (as Android).
        // Where two lines don't fit (the root waiting on a small picture), one.
        var round = ExitStageLabel(y: g.roundY - lh + 3, lines: [.init(text: tr("The round,"), tone: .strong), .init(text: tr("already on Bitcoin"))],
                                   priority: 2)
        round.fallbacks = [ExitStageLabel(y: g.roundY, lines: [.init(text: [tr("The round,"), tr("already on Bitcoin")]
                                                                        .joined(separator: language.hasPrefix("ja") ? "" : " "))],
                                          priority: 2, shrinks: true)]
        if scene.explain {
            items.append(.init(y: g.coinY, lines: [.init(text: tr("Your VTXO"), tone: .strong), .init(text: tr("in Ark"))], priority: 3))
            if n > 1 {
                items.append(.init(y: (Y(1) + Y(n - 1)) / 2 - 7 * g.scale,
                                   lines: [.init(text: L10n.plural("{n} transactions", count: scene.transactions ?? n, locale: language), tone: .strong),
                                           .init(text: tr("one block each,")), .init(text: tr("root first"))],
                                   priority: 2, brace: min(Y(n) + g.coinRadius + 5, Y(1) + 7)...(Y(1) + 7)))
            }
            items.append(round)
            return place(items, server: true)
        }
        if !scene.claiming {
            items.append(round)
            let minedCount = scene.levels.prefix { $0.isMined }.count
            var firstUnsent: Int?
            for j in 1...n {
                let y = Y(j), shared = scene.shared[safe: j - 1] == true ? " " + tr("· shared") : ""
                switch scene.levels[j - 1] {
                case .mined(let block):
                    guard !(j == n && scene.locked), let block else { continue }
                    if let stack = scene.fold(at: j - 1) {
                        // A stack of the oldest blocks: their range, ahead of the round's caption (which then takes one line).
                        guard let heights = stack.heights else { continue }
                        items.append(.init(y: y, lines: [.init(text: tr("Blocks {from}–{to}", height(heights.lowerBound), height(heights.upperBound)) + shared,
                                                               tone: .ink)], priority: j == minedCount ? 2.2 : 2.1, block: heights.upperBound,
                                           first: heights.lowerBound))
                        continue
                    }
                    items.append(.init(y: y, lines: [.init(text: tr("Block {height}", height(block)) + shared, tone: .ink)],
                                       priority: j == minedCount ? 2.2 : 1, block: block))
                case .needsFunds:
                    var lines = [ExitStageLabel.Line(text: tr("Needs a fee"), tone: .failureStrong)]
                    if let need = scene.need { lines.append(.init(text: tr("add {amount} on-chain", need), tone: .failure)) }
                    items.append(.init(y: y, lines: lines, priority: 4, pill: .failure))
                case .pending(let theirs, let bumped):
                    var sub = theirs ? tr("sent by someone else") : scene.fee.map { tr("fee {amount}", $0) + shared }
                    if scene.stuck > 0 { sub = L10n.plural("{n} blocks without it", count: Int(scene.stuck), locale: language) }
                    if bumped { sub = tr("a block came without it") }
                    if scene.stale, let checked = scene.checked { sub = tr("last checked at {height}", height(checked)) }
                    items.append(.init(y: y, lines: [.init(text: tr("Next block"), tone: .strong)] + (sub.map { [.init(text: $0)] } ?? []),
                                       priority: 4, pill: scene.stale ? .grey : .orange))
                case .unsent, .ready:
                    if firstUnsent == nil { firstUnsent = j }
                }
            }
            if let first = firstUnsent, first != n, scene.picture != .lift {
                // Counting every transaction still to send, folded ones too; beside a later unsent one (up to the "+N"
                // node) when the "Next block" pill takes this one's room.
                var more = ExitStageLabel(y: Y(first), lines: [.init(text: L10n.plural("{n} more to send", count: scene.realCount - scene.real(first - 1), locale: language))], priority: 1.5)
                more.fallbacks = ((first + 1)..<n).map { var label = more; label.y = Y($0); return label }
                items.append(more)
            }
            if scene.locked, let unlock = scene.unlock {
                let left = unlock > scene.tip ? unlock - scene.tip : 0
                items.append(.init(y: g.coinY, lines: scene.picture == .unlocked
                                   ? [.init(text: tr("Unlocked"), tone: .strong), .init(text: tr("since block {height}", height(unlock)))]
                                   : [.init(text: L10n.plural("Locked · {n} blocks", count: Int(left), locale: language), tone: .strong),
                                      .init(text: tr("opens at {height}", height(unlock)))], priority: 3.5))
            } else if [.unsent, .ready].contains(scene.levels[n - 1]) {
                items.append(.init(y: g.coinY, lines: [.init(text: scene.picture == .canceled ? tr("Back in Ark") : tr("Your VTXO"), tone: .strong)], priority: 3.5))
            }
        } else {
            let node = g.claimNode
            let lines: [ExitStageLabel.Line] = scene.picture == .claimed
                ? [.init(text: scene.claimHeight.map { tr("Block {height}", height($0)) } ?? tr("In a block"), tone: .strong), .init(text: tr("claim"))]
                : [.init(text: tr("Next block"), tone: .strong)] + (scene.claimFee.map { [.init(text: tr("claim fee {amount}", $0))] } ?? [])
            items.append(.init(y: node.y, lines: lines, priority: 4, pill: scene.picture == .claimed ? nil : .orange, trailing: node.x - 16))
        }
        return place(items, server: [.lift, .canceled].contains(scene.picture))
    }

    /// `server`: the Ark line and its server show, so labels keep clear of its badge too.
    private func place(_ items: [ExitStageLabel], server: Bool) -> [ExitStageLabel] {
        let fit = { (label: ExitStageLabel) in self.fit(label, server: server) }
        return ExitStageLabels.place(items.map(fit), lineHeight: geometry.lineHeight, scale: geometry.scale, bounds: geometry.labelBounds, fit: fit) {
            tr("Blocks {from}–{to}", height($0), height($1))
        }
    }

    /// A label wrapped to the room it has (translations and large text run long): its column, narrower
    /// beside the phone's badge, or for the claim, right of your coin. A pill's text keeps 8 pt inside it.
    func fit(_ label: ExitStageLabel, server: Bool = false) -> ExitStageLabel {
        let g = geometry
        var label = label
        // Fallbacks that can't fit are no help.
        label.fallbacks = label.fallbacks.map { fit($0, server: server) }.filter { !$0.shrinks || $0.size >= 0.75 }
        if label.shrinks, let line = label.lines.first {
            let width = ExitStageText.width(line.text, size: g.fontSize, strong: [.strong, .failureStrong].contains(line.tone))
            let top = label.y - g.lineHeight / 2, room = label.trailing.map(g.claimColumn) ?? g.column(top: top, bottom: top + g.lineHeight, server: server)
            label.size = min(1, room / max(1, width))
            return label
        }
        func wrapped(_ width: Double) -> ExitStageLabel {
            var out = label
            out.lines = []
            for (index, line) in label.lines.enumerated() {
                let size = index == 0 ? g.fontSize : g.subSize, strong = [.strong, .failureStrong].contains(line.tone)
                let parts = ExitStageLabels.wrap(line.text, width: width - (index == 0 && label.pill != nil ? 8 : 0)) {
                    ExitStageText.width($0, size: size, strong: strong)
                }
                out.lines += parts.map { .init(text: $0, tone: line.tone) }
                if index == 0 { out.head = parts.count }
            }
            return out
        }
        if let x = label.trailing { return wrapped(g.claimColumn(x)) }
        let full = wrapped(g.width - 12 - g.labelX), top = full.y - g.lineHeight / 2
        let bottom = top + Double(full.lines.count) * g.lineHeight + (full.pill == nil ? 0 : 5 * g.scale)
        let room = g.column(top: top, bottom: bottom, server: server)
        return room < g.width - 12 - g.labelX ? wrapped(room) : full
    }

    /// A pill's frame around a label's first line, however many lines it wrapped to.
    func pill(_ label: ExitStageLabel) -> CGRect {
        let g = geometry, head = max(1, label.head)
        let width = label.lines.prefix(head).map { ExitStageText.width($0.text, size: g.fontSize, strong: true) }.max() ?? 0
        let height = Double(head - 1) * g.lineHeight + g.lineHeight + 5
        let x = label.trailing.map { $0 - width - 8 } ?? g.labelX - 8
        return CGRect(x: x, y: label.y - (g.lineHeight + 5) / 2, width: width + 16, height: height)
    }

    /// A label's outline: its pill, and its lines at their sizes.
    func frame(_ label: ExitStageLabel) -> CGRect {
        let g = geometry
        let width = label.lines.enumerated().map { index, line in
            ExitStageText.width(line.text, size: (index < label.head ? g.fontSize : g.subSize) * label.size, strong: [.strong, .failureStrong].contains(line.tone))
        }.max() ?? 0
        let box = CGRect(x: label.trailing.map { $0 - width } ?? g.labelX, y: label.top, width: width, height: label.bottom - label.top)
        return label.pill == nil ? box : box.union(pill(label))
    }

    // MARK: Positions

    /// Each level's y at this moment, every transaction moving from where `a` drew it (matched by `f.origin`) to
    /// where `b` draws it; and `a`'s own levels, for what folds away and for `a`'s labels.
    func positions(_ f: ExitStageFrame, from a: ExitStageScene, to b: ExitStageScene) -> (ys: [Double], before: [Double]) {
        let x = ExitStageStill(a), y = ExitStageStill(b), to = geometry.levels(y.mined, lock: y.lock)
        let before = geometry.with(count: a.levels.count).levels(x.mined, lock: x.lock)
        guard f.travel < 1 else { return (to, before) }
        let ys = to.indices.map { level -> Double in
            guard level > 0, let origin = f.origin[safe: level - 1] ?? nil, let from = before[safe: origin + 1] else { return to[level] }
            return from + (to[level] - from) * f.travel
        }
        return (ys, before)
    }

    // MARK: Arrangement

    /// Every drawn element's outline, the required ones first (the round's block, your path, your coin, the
    /// destination, the server, the texts and labels), then the optional ones where they're clear of
    /// everything before them: the other people's stubs, your other exits' coins (slid down their branch or
    /// further out if they must be), their amounts, and the caption. So amounts give way first, then those
    /// coins, then stubs. `ys` and `lock` are the picture's at this moment; `labels` are placed.
    func arrange(_ s: ExitStageScene, ys: [Double], lock: Double, labels: [ExitStageLabel]) -> ExitStageArrangement {
        let g = geometry, n = s.levels.count, L = g.scale
        var result = ExitStageArrangement()
        guard s.picture != .none || s.explain else { return result }
        func Y(_ level: Int) -> Double { ys[safe: level] ?? g.coinY }
        func text(_ string: String, x: Double, y: Double, size: Double, strong: Bool = false, trailing: Bool = false) -> CGRect {
            let width = ExitStageText.width(string, size: size, strong: strong), height = size * 1.25
            return CGRect(x: trailing ? x - width : x, y: y - height / 2, width: width, height: height)
        }
        var shapes: [ExitStageShape] = [.box(.round, "round", CGRect(x: g.trunk - g.roundSide / 2, y: g.ground - g.roundSide, width: g.roundSide, height: g.roundSide))]
        for level in 1..<max(1, n) {
            shapes.append(.box(.node, "node\(level)", CGRect(x: g.trunk - g.side / 2, y: Y(level) - g.side / 2, width: g.side, height: g.side)))
        }
        // Your coin with its dashed ring or, while locked, the timelock's dial; while claiming, the coin alone.
        shapes.append(.circle(.coin, "coin", CGPoint(x: g.trunk, y: g.coinY - (s.picture == .lift ? 5 : 0)), s.claiming ? g.coinRadius : g.coinReserve(lock)))
        if s.claiming {
            shapes.append(.box(.claimNode, "claim", CGRect(x: g.claimNode.x - 6.5, y: g.claimNode.y - 6.5, width: 13, height: 13)))
        }
        if s.claiming || ExitStageStill(s).wallet > 0.5 {
            let center = CGPoint(x: g.walletX, y: g.walletY)
            shapes.append(.circle(.badge, "badge", center, s.picture == .claimed ? 24 : 15))
            if s.picture == .claimed { shapes.append(.circle(.badge, "badge", CGPoint(x: center.x + 15, y: center.y - 15), 9)) }
            if s.levels.contains(.needsFunds) { shapes.append(.circle(.badge, "badge", CGPoint(x: center.x + 12, y: center.y - 12), 6.5)) }
            for line in destinationLines(s, landed: s.picture == .claimed) {
                shapes.append(.box(.text, "badge", text(line.text, x: g.width - 12, y: line.y, size: line.size, strong: line.strong, trailing: true)))
            }
        }
        shapes.append(.box(.text, "bitcoin", text(tr("Bitcoin"), x: 14, y: g.ground + 12 * L, size: 10.5 * L)))
        if s.explain || [.lift, .canceled].contains(s.picture) {
            shapes.append(.box(.text, "ark", text(tr("Ark"), x: 14, y: g.coinY - 10, size: 11 * L)))
            shapes.append(.circle(.server, "server", CGPoint(x: g.width - 30, y: g.coinY), 14))
        }
        for (index, label) in labels.enumerated() {
            shapes.append(.box(.label, "label\(index)", frame(label)))
            if let brace = label.brace { shapes.append(.box(.brace, "label\(index)", CGRect(x: g.trunk + 27, y: brace.lowerBound, width: 5, height: brace.upperBound - brace.lowerBound))) }
        }
        result.shapes = shapes
        func clear(_ shape: ExitStageShape) -> Bool {
            CGRect(x: 0, y: 0, width: g.width, height: g.height).contains(shape.frame)
                && !result.shapes.contains { $0.group != shape.group && $0.overlaps(shape, margin: ExitStageGeometry.clearance) }
        }
        // The other people's stubs.
        let othersAt = Dictionary(grouping: s.others, by: \.level)
        for level in 1..<max(1, n) {
            for tip in g.stubs(ys, level: level, others: othersAt[level]?.count ?? 0) {
                let shape = ExitStageShape.circle(.stub, "stub\(level)", tip, 3)
                guard clear(shape) else { continue }
                result.stubs.append(.init(level: level, tip: tip))
                result.shapes.append(shape)
            }
        }
        // Your other exits' coins: at their place, else slid down their branch toward it, else further out.
        for level in othersAt.keys.sorted() {
            for (index, other) in (othersAt[level] ?? []).prefix(2).enumerated() {
                let base = g.other(ys, level: level, index: index), group = "sibling\(level).\(index)"
                var spots: [CGPoint] = []
                for shift in [0.0, 0.45, 0.9] {
                    for y in stride(from: Double(base.y), through: max(Double(base.y), Y(level) - 10), by: 3) {
                        spots.append(CGPoint(x: Double(base.x) - shift * g.spread, y: y))
                    }
                }
                guard let spot = spots.first(where: { clear(.circle(.sibling, group, $0, 6)) }) else { continue }
                result.siblings.append(.init(other: other, index: index, coin: spot))
                result.shapes.append(.circle(.sibling, group, spot, 6))
            }
        }
        // Their amounts, left of each coin: the list carries the numbers when there's no room.
        for index in result.siblings.indices {
            let sibling = result.siblings[index]
            guard let size = Self.fitted(sibling.other.amount, 9.5 * L, room: sibling.coin.x - 14) else { continue }
            let shape = ExitStageShape.box(.amount, "sibling\(sibling.other.level).\(sibling.index)",
                                           text(sibling.other.amount, x: sibling.coin.x - 10, y: sibling.coin.y, size: size, trailing: true))
            guard clear(shape) else { continue }
            result.siblings[index].amount = size
            result.shapes.append(shape)
        }
        // The caption, under the lowest stubs.
        if let lines = g.caption(tr("Other people’s coins in this round"), ys, width: { ExitStageText.width($0, size: 10 * L) }) {
            let lineHeight = 12.5 * L, top = Y(1) + 9, room = (g.ground - g.roundSide - 5) - top
            let y0 = top + (room - Double(lines.count) * lineHeight) / 2 + lineHeight / 2, right = g.trunk - g.roundSide / 2 - 9
            let width = lines.map { ExitStageText.width($0, size: 10 * L) }.max() ?? 0
            let shape = ExitStageShape.box(.caption, "caption", CGRect(x: right - width, y: y0 - lineHeight / 2, width: width, height: Double(lines.count) * lineHeight))
            if clear(shape) {
                result.caption = lines
                result.captionY = y0
                result.shapes.append(shape)
            }
        }
        return result
    }

    // MARK: Pulses

    /// What moves while the still stands: the waiting transaction, coin or claim, its pill, the dial's
    /// tip and a needs-funds ring. Overlays draw them; the Canvas draws them itself only while it moves.
    func pulses(_ scene: ExitStageScene) -> [ExitPulse] {
        guard !scene.explain, scene.picture != .none else { return [] }
        let g = geometry, still = ExitStageStill(scene), ys = g.levels(still.mined, lock: still.lock), n = scene.levels.count
        var pulses: [ExitPulse] = []
        if !scene.claiming && scene.picture != .canceled {
            for (index, level) in scene.levels.enumerated() {
                let center = CGPoint(x: g.trunk, y: ys[safe: index + 1] ?? g.coinY), coin = index == n - 1
                switch level {
                case .pending:
                    guard !scene.stale else { continue }
                    pulses.append(.init(id: "node\(index)", kind: coin ? .coin(g.coinRadius) : .square(g.side), center: center))
                case .needsFunds:
                    pulses.append(.init(id: "alarm\(index)", kind: .alarm(coin ? g.coinRadius + 5 : g.side * 0.95), center: center))
                default: break
                }
            }
        }
        if scene.picture == .unlocked { pulses.append(.init(id: "glow", kind: .glow(g.coinRadius), center: CGPoint(x: g.trunk, y: g.coinY))) }
        if scene.picture == .lock, still.dial < 1 {
            let angle = -Double.pi / 2 + 2 * .pi * still.dial, r = g.coinRadius + 10
            pulses.append(.init(id: "dot", kind: .dot, center: CGPoint(x: g.trunk + cos(angle) * r, y: g.coinY + sin(angle) * r)))
        }
        if scene.picture == .claim { pulses.append(.init(id: "claim", kind: .square(13), center: g.claimNode)) }
        for label in labels(scene, ys: ys) where label.pill == .orange {
            let frame = pill(label)
            pulses.append(.init(id: "pill\(Int(label.y))", kind: .pill(frame.size), center: CGPoint(x: frame.midX, y: frame.midY)))
        }
        return pulses
    }

    // MARK: Drawing

    /// `inline`: the Canvas draws the moving parts itself (while the picture moves); otherwise overlays do.
    func draw(_ context: GraphicsContext, from: ExitStageScene?, to scene: ExitStageScene, elapsed: Double, now: Double, inline: Bool, animating: Bool) {
        if reduceMotion {
            // Every move becomes a fade between the two stills.
            let u = (elapsed / 0.18).clamped(0, 1)
            if let from, u < 1 {
                var old = context
                old.opacity = 1 - u
                paint(old, ExitStageFrame(from, from, elapsed: 60, reduceMotion: true), from, from, now: now, inline: true, animating: false)
            }
            var new = context
            new.opacity = from == nil ? 1 : u
            paint(new, ExitStageFrame(scene, scene, elapsed: 60, reduceMotion: true), scene, scene, now: now, inline: inline, animating: false)
        } else {
            let a = from ?? scene
            paint(context, ExitStageFrame(a, scene, elapsed: from == nil ? 60 : elapsed, reduceMotion: false), a, scene, now: now, inline: inline, animating: animating)
        }
    }

    // swiftlint:disable:next function_body_length cyclomatic_complexity
    private func paint(_ context: GraphicsContext, _ f: ExitStageFrame, _ a: ExitStageScene, _ s: ExitStageScene,
                       now: Double, inline: Bool, animating: Bool) {
        guard s.picture != .none || s.explain else { return }
        let g = geometry, n = s.levels.count, c = colors, L = g.scale
        let canvas = MotionCanvas(context: context, size: CGSize(width: g.width, height: g.height),
                                  viewBox: CGRect(x: 0, y: 0, width: g.width, height: g.height), colors: c)
        let breathe = ExitPulse.breathe(now, animating: animating && inline)
        let (ys, before) = positions(f, from: a, to: s), TX = g.trunk, yC = g.coinY
        let target = ExitStageStill(s), new = labels(s, ys: g.levels(target.mined, lock: target.lock))
        let arrangement = arrange(s, ys: ys, lock: f.lock, labels: new)
        func Y(_ level: Int) -> Double { ys[safe: level] ?? yC }
        func text(_ string: String, _ x: Double, _ y: Double, size: Double, strong: Bool = false, color: Color, anchor: UnitPoint = .leading,
                  opacity: Double = 1) {
            guard opacity > 0.01 else { return }
            var layer = context
            layer.opacity *= opacity
            layer.draw(Text(verbatim: string).font(ExitStageText.font(size, strong: strong)).foregroundColor(color), at: CGPoint(x: x, y: y), anchor: anchor)
        }

        // Ark: a faint line at the top with the server. Your VTXO sits on it until the exit starts; a cancel brings it back.
        if f.ark > 0.01 {
            canvas.stroke(canvas.line(CGPoint(x: 14, y: yC), CGPoint(x: g.width - 50, y: yC)), c.hairline.opacity(f.ark), 1)
            text(tr("Ark"), 14, yC - 10, size: 11 * L, color: c.muted, opacity: f.ark)
            canvas.layer(center: CGPoint(x: g.width - 30, y: yC), opacity: f.ark * (s.explain ? 1 : 1 - f.lift * 0.6)) { badge in
                badge.fill(badge.circle(g.width - 30, yC, 14), c.background)
                badge.stroke(badge.circle(g.width - 30, yC, 14), c.hairline, 1.5, dash: f.lift > 0.5 ? [3, 3] : [])
                badge.symbol("server.rack", g.width - 30, yC, size: 13, color: c.foreground)
            }
        }
        // The chain: the ground line, with the round's transaction in its block as the trunk's foot.
        canvas.stroke(canvas.line(CGPoint(x: 12, y: g.ground + 0.5), CGPoint(x: g.width - 12, y: g.ground + 0.5)), c.foreground.opacity(0.32), 1.2)
        text(tr("Bitcoin"), 14, g.ground + 12 * L, size: 10.5 * L, color: c.muted)
        // Everyone else's part of the round: one level of short faint stubs per transaction, and one caption,
        // where they're clear of everything else (`arrange`).
        for stub in arrangement.stubs {
            let alpha = f.tree * (f.pop[safe: stub.level] ?? 1) * (1 - 0.6 * f.cancel), tip = stub.tip
            guard alpha > 0.02 else { continue }
            canvas.context.stroke(Self.link(CGPoint(x: tip.x, y: tip.y + 3), CGPoint(x: TX, y: Y(stub.level) - 8)),
                                  with: .color(c.foreground.opacity(0.22 * alpha)), lineWidth: 1)
            canvas.fill(canvas.circle(tip.x, tip.y, 2.6), c.background.opacity(alpha))
            canvas.stroke(canvas.circle(tip.x, tip.y, 2.6), c.foreground.opacity(0.34 * alpha), 1)
        }
        let captionAlpha = f.tree * (f.pop[safe: 1] ?? 1) * (1 - 0.6 * f.cancel) * (s.explain ? f.labels : 1 - f.dim)
        if captionAlpha > 0.02, let lines = arrangement.caption {
            for (index, line) in lines.enumerated() {
                text(line, TX - g.roundSide / 2 - 9, arrangement.captionY + Double(index) * 12.5 * L, size: 10 * L, color: c.muted, anchor: .trailing,
                     opacity: captionAlpha * 0.9)
            }
        }
        // Your other exits from this round leave your branch where they stop sharing.
        for sibling in arrangement.siblings {
            let p = sibling.coin, level = sibling.other.level, alpha = f.tree * (f.pop[safe: level] ?? 1)
            canvas.context.stroke(Self.link(CGPoint(x: p.x, y: p.y + 6), CGPoint(x: TX, y: Y(level) - 4)),
                                  with: .color(c.orange.opacity(0.7 * alpha)), style: StrokeStyle(lineWidth: 1.4, lineCap: .round, dash: sibling.other.sent ? [] : [3, 3]))
            canvas.fill(canvas.circle(p.x, p.y, 6), c.soft.opacity(alpha))
            canvas.stroke(canvas.circle(p.x, p.y, 6), c.orange.opacity(alpha), 1.6)
            if let size = sibling.amount {
                text(sibling.other.amount, p.x - 10, p.y, size: size, color: c.muted, anchor: .trailing, opacity: alpha * f.labels)
            }
        }
        // Before bark lists the path (Start): a ghost of the branch, what the exit will send.
        if f.ghost > 0.01 {
            canvas.context.stroke(Self.straight(CGPoint(x: TX, y: g.ground - g.roundSide), CGPoint(x: TX, y: yC + g.coinRadius)),
                                  with: .color(c.muted.opacity(f.ghost)), style: StrokeStyle(lineWidth: 1.2, lineCap: .round, dash: [2, 4]))
            for level in 1..<max(1, n) {
                canvas.fill(canvas.circle(TX, Y(level), 4), c.background.opacity(f.ghost * 2))
                canvas.stroke(canvas.circle(TX, Y(level), 4), c.muted.opacity(f.ghost), 1.2)
            }
        }
        // Your branch: ink below each transaction once it's in a block, orange while it's on its way.
        for level in 1...n {
            let u = f.grow[safe: level] ?? 1, mined = f.mined[level - 1]
            guard u > 0.001 else { continue }
            let p0 = CGPoint(x: TX, y: level == 1 ? g.ground - g.roundSide : Y(level - 1) - g.side / 2)
            let p1 = CGPoint(x: TX, y: Y(level) + (level == n ? g.coinRadius : g.side / 2))
            guard p0.y - p1.y >= 1 else { continue }
            let width = 4.6 + (2.2 - 4.6) * Double(level - 1) / Double(max(1, n - 1))
            let alpha = f.tree * (mined < 1 ? 1 - 0.6 * f.cancel : 1)
            let path = Self.straight(p0, p1)
            if mined < 1 {
                canvas.context.stroke(path.trimmedPath(from: mined, to: max(mined, u)), with: .color(c.orange.opacity(0.95 * alpha)),
                                      style: StrokeStyle(lineWidth: width, lineCap: .round))
            }
            if mined > 0 {
                canvas.context.stroke(path.trimmedPath(from: 0, to: min(u, mined)), with: .color(c.foreground.opacity(alpha)),
                                      style: StrokeStyle(lineWidth: width + 0.3, lineCap: .round))
            }
        }
        // The round's block, already on-chain.
        canvas.fill(Path(roundedRect: CGRect(x: TX - g.roundSide / 2, y: g.ground - g.roundSide, width: g.roundSide, height: g.roundSide), cornerRadius: 4.5),
                    c.foreground.opacity(f.tree))
        // The transactions.
        for level in 1..<max(1, n) {
            let index = level - 1, pop = f.pop[safe: level] ?? 1
            guard pop > 0.001 else { continue }
            let alpha = f.tree * (f.mined[index] < 1 ? 1 - 0.65 * f.cancel : 1), p = CGPoint(x: TX, y: Y(level)), z = g.side
            if s.fold(at: index)?.kind == .more {
                // Unsent transactions folded into one: a dashed hollow node ("N more to send" says how many).
                let r = z / 2 * min(1, pop)
                canvas.fill(canvas.circle(p.x, p.y, r), c.background.opacity(alpha))
                canvas.stroke(canvas.circle(p.x, p.y, r), c.orange.opacity(alpha), 1.4, dash: [1.6, 1.6])
                continue
            }
            node(canvas, p, level: s.levels[index], mined: f.mined[index], stamp: f.stamp[index], ripple: f.ripple[index],
                 pending: f.pending[index], side: z, pop: min(1.3, pop), alpha: alpha, grey: s.stale, inline: inline, now: now, breathe: breathe,
                 animating: animating && inline)
            if s.fold(at: index)?.kind == .stack, f.mined[index] > 0.9 {
                // The oldest blocks stacked into one: an ink block in layers.
                for offset in [-z / 6, z / 6] {
                    canvas.stroke(canvas.line(CGPoint(x: p.x - z / 2, y: p.y + offset), CGPoint(x: p.x + z / 2, y: p.y + offset)), c.background.opacity(alpha), 1)
                }
            }
        }
        // Transactions folding into a stack or a "+N" node sink into it.
        for merge in f.merging where f.travel < 1 {
            let y0 = before[safe: merge.from + 1] ?? yC, y1 = Y(merge.into + 1), p = CGPoint(x: TX, y: y0 + (y1 - y0) * f.travel)
            var ghost = canvas
            ghost.context.opacity *= f.tree * (1 - f.travel.clamped(0, 1))
            if merge.mined {
                ghost.fill(Path(roundedRect: CGRect(x: p.x - g.side / 2, y: p.y - g.side / 2, width: g.side, height: g.side), cornerRadius: 3.5), c.foreground)
            } else {
                ghost.fill(ghost.circle(p.x, p.y, g.side * 0.42), c.background)
                ghost.stroke(ghost.circle(p.x, p.y, g.side * 0.42), c.orange, 1.6)
            }
        }
        // Needs funds: a dashed red line from the phone to the transaction it can't pay for.
        if let index = s.levels.firstIndex(of: .needsFunds), f.wallet > 0.01 {
            var path = Path()
            path.move(to: CGPoint(x: g.walletX - 17, y: g.walletY - 4))
            path.addQuadCurve(to: CGPoint(x: TX + 16, y: Y(index + 1)), control: CGPoint(x: g.walletX - 30, y: Y(index + 1)))
            canvas.context.stroke(path, with: .color(NoahTokens.Colors.failure.opacity(0.45 * f.wallet)), style: StrokeStyle(lineWidth: 1.2, dash: [2, 4]))
        }
        feeDot(canvas, f, a, s, ys: ys)
        // The claim: a stem from your coin to where it goes, with the claim transaction on it.
        if f.stem > 0.001 {
            canvas.context.stroke(stemPath(to: f.stem), with: .color(c.orange), style: StrokeStyle(lineWidth: 2, lineCap: .round))
            if f.claimMined > 0.001 {
                canvas.context.stroke(stemPath(to: f.claimMined), with: .color(c.foreground), style: StrokeStyle(lineWidth: 2.3, lineCap: .round))
            }
        }
        if f.claimSend > 0.001 || f.claimMined > 0.001 {
            let claim: ExitStageScene.Level = s.picture == .claimed ? .mined(nil) : .pending(theirs: false, bumped: false)
            node(canvas, g.claimNode, level: claim, mined: f.claimMined, stamp: f.claimStamp, ripple: f.claimRipple, pending: f.claimSend, side: 13,
                 pop: 1, alpha: 1, grey: false, inline: inline, now: now, breathe: breathe, animating: animating && inline)
        }
        coin(canvas, f, s, inline: inline, now: now, breathe: breathe, animating: animating && inline)
        dial(canvas, f, s, inline: inline, breathe: breathe)
        destination(canvas, f, s)
        // Labels: the right-hand column says which block each transaction is in, and what's waiting.
        let old = f.fadeIn < 1 ? labels(a, ys: before) : []
        func same(_ x: ExitStageLabel, _ y: ExitStageLabel) -> Bool { x.lines == y.lines && x.y == y.y && x.pill == y.pill }
        drawLabels(context, old.filter { label in !new.contains { same($0, label) } }, tree: 1 - f.fadeIn, trailing: 1 - f.fadeIn,
                   inline: true, breathe: breathe, flash: 0)
        drawLabels(context, new, tree: f.labels, trailing: f.claimSend, fade: { label in old.contains { same($0, label) } ? 1 : f.fadeIn },
                   inline: inline, breathe: breathe, flash: f.flash)
    }

    private func drawLabels(_ context: GraphicsContext, _ labels: [ExitStageLabel], tree: Double, trailing: Double, fade: (ExitStageLabel) -> Double = { _ in 1 },
                            inline: Bool, breathe: Double, flash: Double) {
        let g = geometry, c = colors
        for label in labels {
            let alpha = (label.trailing != nil ? trailing : tree).clamped(0, 1) * fade(label)
            guard alpha > 0.01 else { continue }
            var layer = context
            layer.opacity *= alpha
            let x = label.trailing ?? g.labelX
            if let pill = label.pill {
                let frame = self.pill(label), shape = Path(roundedRect: frame, cornerRadius: frame.height / 2)
                switch pill {
                case .orange:
                    if inline {
                        layer.fill(shape, with: .color(c.background))
                        layer.fill(shape, with: .color(c.orange.opacity(0.06 + 0.16 * breathe)))
                        layer.stroke(shape, with: .color(flash > 0 ? c.foreground : c.orange.opacity(0.6 + 0.4 * breathe)), lineWidth: 1.4)
                    }
                case .grey, .failure:
                    layer.fill(shape, with: .color(c.background))
                    layer.stroke(shape, with: .color((pill == .grey ? c.muted : NoahTokens.Colors.failure).opacity(0.9)), lineWidth: 1.4)
                }
            }
            for (index, line) in label.lines.enumerated() {
                let y = label.y + Double(index) * g.lineHeight + (index >= label.head && label.pill != nil ? 4 * g.scale : 0)
                let color: Color = switch line.tone {
                case .strong, .ink: c.foreground
                case .muted: c.muted
                case .failure, .failureStrong: NoahTokens.Colors.failure
                }
                layer.draw(Text(verbatim: line.text).font(ExitStageText.font((index < label.head ? g.fontSize : g.subSize) * label.size,
                                                                              strong: [.strong, .failureStrong].contains(line.tone)))
                            .foregroundColor(color), at: CGPoint(x: x, y: y), anchor: label.trailing == nil ? .leading : .trailing)
            }
            if let brace = label.brace {
                var path = Path()
                path.move(to: CGPoint(x: g.trunk + 27, y: brace.upperBound))
                path.addLine(to: CGPoint(x: g.trunk + 32, y: brace.upperBound))
                path.addLine(to: CGPoint(x: g.trunk + 32, y: brace.lowerBound))
                path.addLine(to: CGPoint(x: g.trunk + 27, y: brace.lowerBound))
                layer.stroke(path, with: .color(c.foreground.opacity(0.5)), lineWidth: 1)
            }
        }
    }

    /// A transaction. Hollow circle: not sent yet. Glowing dashed square: in the mempool, waiting for
    /// the next block. Ink square: in a block. (Circles are off-chain, blocks on-chain, as v1.)
    private func node(_ canvas: MotionCanvas, _ p: CGPoint, level: ExitStageScene.Level, mined: Double, stamp: Double, ripple: Double,
                      pending: Double, side z: Double, pop: Double, alpha: Double, grey: Bool, inline: Bool, now: Double, breathe: Double, animating: Bool) {
        guard alpha > 0.01 else { return }
        var c = canvas
        c.context.opacity *= alpha
        let colors = self.colors
        func square(_ side: Double) -> Path { Path(roundedRect: CGRect(x: p.x - side / 2, y: p.y - side / 2, width: side, height: side), cornerRadius: 3.5) }
        if mined > 0.001 {
            let h = z * (1 + 0.22 * (1 - stamp))
            if mined < 1 { c.fill(square(z), colors.background) }
            var clip = c.context
            clip.clip(to: square(h))
            let fill = h * (mined * 1.6).clamped(0, 1)
            clip.fill(Path(CGRect(x: p.x - h / 2, y: p.y + h / 2 - fill, width: h, height: fill)), with: .color(colors.foreground))
            if mined < 1 { c.stroke(square(h), colors.foreground, 1.6) }
            if ripple > 0 && ripple < 1 {
                let grow = 10 * ripple
                c.context.stroke(Path(roundedRect: CGRect(x: p.x - h / 2 - grow, y: p.y - h / 2 - grow, width: h + 2 * grow, height: h + 2 * grow),
                                      cornerRadius: 4 + 6 * ripple), with: .color(colors.foreground.opacity(0.4 * (1 - ripple))), lineWidth: 1.2)
            }
        } else if pending > 0.001 {
            if inline && !grey { ExitPulsePainter.back(c.context, kind: .square(z), center: p, now: now, breathe: breathe, animating: animating && pending >= 1, colors: colors, strength: pending) }
            if pending < 1 { c.stroke(c.circle(p.x, p.y, 5.5 * (1 - pending)), colors.orange, 1.6) }
            let h = z * (0.6 + 0.4 * pending)
            c.fill(square(h), colors.background)
            if inline || grey {
                c.context.stroke(square(h), with: .color((grey ? colors.muted.opacity(0.8) : colors.orange.opacity(0.7 + 0.3 * breathe)).opacity(pending)),
                                 style: StrokeStyle(lineWidth: 1.8, dash: [3, 2.4], dashPhase: grey || !animating ? 0 : ExitPulsePainter.march(now, 5.4)))
            }
        } else if level == .ready || level == .needsFunds {
            c.fill(square(z), colors.background)
            c.stroke(square(z), colors.orange, 1.6)
            if level == .needsFunds && inline { ExitPulsePainter.front(c.context, kind: .alarm(z * 0.95), center: p, now: now, breathe: breathe, animating: false, colors: colors) }
        } else {
            let r = (z >= 13 ? 6.2 : z * 0.42) * pop
            c.fill(c.circle(p.x, p.y, r), colors.background)
            c.stroke(c.circle(p.x, p.y, r), colors.orange, 1.6)
        }
    }

    /// Your coin: the leaf transaction's output at the top of the branch; at the claim it travels the stem.
    private func coin(_ canvas: MotionCanvas, _ f: ExitStageFrame, _ s: ExitStageScene, inline: Bool, now: Double, breathe: Double, animating: Bool) {
        let g = geometry, colors = self.colors, n = s.levels.count, last = n - 1
        var center = CGPoint(x: g.trunk, y: g.coinY - 5 * f.lift), r = g.coinRadius
        if f.land > 0.001 {
            center = g.stem(f.land.clamped(0, 1))
            r = g.coinRadius + (g.coinRadius * 0.62 - g.coinRadius) * f.land.clamped(0, 1)
        }
        let pending = f.pending[last], mined = f.mined[last], waiting = pending > 0.001 && mined < 1 && s.levels[last] != .needsFunds
        if waiting && inline && !s.stale {
            ExitPulsePainter.back(canvas.context, kind: .coin(r), center: center, now: now, breathe: breathe, animating: animating && pending >= 1, colors: colors, strength: pending)
        }
        if s.picture == .unlocked && inline && f.claimSend < 0.5 {
            ExitPulsePainter.back(canvas.context, kind: .glow(r), center: center, now: now, breathe: breathe, animating: false, colors: colors, strength: f.open.clamped(0, 1))
        }
        if waiting && (inline || s.stale) {
            canvas.context.stroke(canvas.circle(center.x, center.y, r + 5), with: .color((s.stale ? colors.muted : colors.orange).opacity(pending * (s.stale ? 0.6 : 0.6 + 0.4 * breathe))),
                                  style: StrokeStyle(lineWidth: 1.6, dash: [3, 2.6], dashPhase: animating && !s.stale ? ExitPulsePainter.march(now, 5.6) : 0))
        }
        if s.levels[last] == .needsFunds && inline {
            ExitPulsePainter.front(canvas.context, kind: .alarm(r + 5), center: center, now: now, breathe: breathe, animating: false, colors: colors)
        }
        let fade = 1 - ((f.land - 0.85) / 0.13).clamped(0, 1)
        if f.land < 0.98 {
            let radius = r * (1 + 0.18 * (1 - f.stamp[last]) * (mined > 0 ? 1 : 0))
            canvas.fill(canvas.circle(center.x, center.y, radius), (mined > 0.5 ? colors.foreground : colors.soft).opacity(fade))
            canvas.stroke(canvas.circle(center.x, center.y, radius), colors.orange.opacity(fade), (radius * 0.19).clamped(1.6, 4))
        }
        // Its old spot, once it has gone: a dashed "spent" ring.
        if f.land > 0.5 {
            canvas.context.stroke(canvas.circle(g.trunk, g.coinY, g.coinRadius * 0.8), with: .color(colors.muted.opacity(0.45 * ((f.land - 0.5) * 2).clamped(0, 1) * f.tree)),
                                  style: StrokeStyle(lineWidth: 1.3, dash: [3, 3]))
        }
        let ripple = f.ripple[last]
        if mined > 0 && mined < 1 && ripple > 0 && ripple < 1 {
            canvas.stroke(canvas.circle(center.x, center.y, r + 4 + 12 * ripple), colors.foreground.opacity(0.4 * (1 - ripple)), 1.2)
        }
    }

    /// The timelock: a dial around your coin, one tick per 6 blocks, and a padlock.
    private func dial(_ canvas: MotionCanvas, _ f: ExitStageFrame, _ s: ExitStageScene, inline: Bool, breathe: Double) {
        guard f.lock > 0.001, f.stem < 1 else { return }
        let g = geometry, colors = self.colors, R = g.coinRadius + 10, alpha = f.lock * (1 - f.stem), cx = g.trunk, cy = g.coinY
        let start = -Double.pi / 2, ticks = Self.ticks(s.delta), done = (f.dial * Double(s.delta)).rounded() / Double(max(1, s.delta))
        var c = canvas
        c.context.opacity *= alpha
        c.stroke(c.circle(cx, cy, R), colors.hairline, 2.2)
        for tick in 0..<ticks.count {
            let angle = start + Double(tick) * 2 * .pi / Double(ticks.count), r0 = R + 3.5, r1 = R + (ticks.hours && tick % 6 == 0 ? 8 : 5.5)
            c.stroke(c.line(CGPoint(x: cx + cos(angle) * r0, y: cy + sin(angle) * r0), CGPoint(x: cx + cos(angle) * r1, y: cy + sin(angle) * r1)),
                     colors.muted.opacity(Double(tick) / Double(ticks.count) < done ? 0.75 : 0.35), 1)
        }
        if done > 0 {
            var arc = Path()
            arc.addArc(center: CGPoint(x: cx, y: cy), radius: R, startAngle: .radians(start), endAngle: .radians(start + 2 * .pi * min(1, done) * f.lock), clockwise: false)
            c.context.stroke(arc, with: .color(f.open > 0.001 ? colors.orange : colors.foreground), style: StrokeStyle(lineWidth: 2.6, lineCap: .round))
        }
        if done < 1 && f.lock >= 0.999 && inline {
            let angle = start + 2 * .pi * done
            c.fill(c.circle(cx + cos(angle) * R, cy + sin(angle) * R, 3.4), colors.orange.opacity(0.55 + 0.45 * breathe))
        }
        let px = cx + g.coinRadius * 0.78, py = cy + g.coinRadius * 0.72
        canvas.layer(center: CGPoint(x: px, y: py + 7), scale: 15 / 24 * max(0, f.padlock), opacity: 1 - f.stem) { pad in
            pad.padlockBody(px, py)
            pad.shackle(px, py, open: f.open)
        }
    }

    /// A one-line text's size, smaller (down to 60 %) when it would be wider than `room`; nil when even that is too wide.
    static func fitted(_ text: String, _ size: Double, room: Double, strong: Bool = false) -> Double? {
        let width = ExitStageText.width(text, size: size, strong: strong)
        if width <= room { return size }
        return room / width >= 0.6 ? size * room / width : nil
    }

    /// As `fitted`, for text that must show: never below 60 %.
    static func shrunk(_ text: String, _ size: Double, room: Double, strong: Bool = false) -> Double {
        fitted(text, size, room: room, strong: strong) ?? size * 0.6
    }

    /// One tick per 6 blocks, 12 to 48 of them; below 72 blocks there's no hour reading.
    static func ticks(_ delta: UInt32) -> (count: Int, hours: Bool) {
        let perSix = Int((Double(delta) / 6).rounded())
        return delta < 72 ? (max(1, perSix), false) : (min(48, max(12, perSix)), true)
    }

    /// Your on-chain wallet: fees leave it. At the claim it's the destination: this phone, or another wallet.
    private func destination(_ canvas: MotionCanvas, _ f: ExitStageFrame, _ s: ExitStageScene) {
        let g = geometry, colors = self.colors, L = g.scale, claiming = s.claiming, alarm = s.levels.contains(.needsFunds)
        let alpha = claiming ? 1 : f.wallet
        guard alpha > 0.01 else { return }
        let center = CGPoint(x: g.walletX, y: g.walletY), grow = 1 + 0.12 * f.destPop.clamped(0, 1.2)
        canvas.layer(center: center, scale: grow, opacity: alpha * (1 - f.toOther)) { badge in
            badge.fill(badge.circle(center.x, center.y, 15), colors.background)
            badge.stroke(badge.circle(center.x, center.y, 15), alarm ? NoahTokens.Colors.failure : colors.hairline, 1.5)
            badge.symbol("iphone", center.x, center.y, size: 15, color: colors.foreground)
        }
        if f.toOther > 0.01 {
            canvas.layer(center: center, scale: grow, opacity: f.toOther) { badge in
                badge.fill(badge.circle(center.x, center.y, 15), colors.background)
                badge.stroke(badge.circle(center.x, center.y, 15), colors.hairline, 1.5)
                badge.symbol("wallet.bifold", center.x, center.y, size: 14, color: colors.foreground)
            }
        }
        if f.destRing > 0.001 {
            var ring = Path()
            ring.addArc(center: center, radius: 20 * grow, startAngle: .degrees(-90), endAngle: .degrees(-90 + 360 * f.destRing.clamped(0, 1)), clockwise: false)
            canvas.context.stroke(ring, with: .color(colors.orange), lineWidth: 2.2)
        }
        if f.destPop > 0.001 {
            canvas.layer(center: CGPoint(x: center.x + 15, y: center.y - 15), scale: 0.9 * f.destPop.clamped(0, 1.2), opacity: 1) { mark in
                mark.checkBadge(center.x + 15, center.y - 15)
            }
        }
        if alarm {
            canvas.fill(canvas.circle(center.x + 12, center.y - 12, 6.5), NoahTokens.Colors.failure)
            canvas.context.draw(Text(verbatim: "!").font(.system(size: 10, weight: .bold)).foregroundColor(.white), at: CGPoint(x: center.x + 12, y: center.y - 12))
        }
        var layer = canvas.context
        layer.opacity *= alpha
        for (index, line) in destinationLines(s, landed: f.land > 0.9).enumerated() {
            layer.draw(Text(verbatim: line.text).font(ExitStageText.font(line.size, strong: line.strong))
                        .foregroundColor(index == 0 ? (claiming ? colors.foreground : colors.muted) : alarm ? NoahTokens.Colors.failure : claiming ? colors.muted : colors.foreground),
                       at: CGPoint(x: g.width - 12, y: line.y), anchor: .trailing)
        }
    }

    /// The two lines under the phone's (or other wallet's) badge: what it is, then the balance, the address or what
    /// landed. Right of the "Bitcoin" label, however long the translation.
    func destinationLines(_ s: ExitStageScene, landed: Bool) -> [(text: String, size: Double, strong: Bool, y: Double)] {
        let g = geometry, L = g.scale, claiming = s.claiming
        // While claiming the lines are a little larger, where they still fit above the picture's bottom.
        let scale = claiming && g.ground + 24 * L * 1.1 + 11 * L * 1.1 * 0.625 <= g.height - 1 ? 1.1 : 1.0
        let other: String? = if case .other(let address) = s.destination { address } else { nil }
        let first = !claiming ? tr("On-chain") : s.destination == .phone ? tr("This phone") : tr("Another wallet")
        let second = claiming ? (s.destination != .phone ? other : landed ? s.received ?? s.balance : s.balance) : s.balance
        let room = g.width - 12 - (14 + ExitStageText.width(tr("Bitcoin"), size: 10.5 * L) + 12)
        var lines = [(text: first, size: Self.shrunk(first, 10.5 * L * scale, room: room, strong: claiming), strong: claiming, y: g.ground + 11 * L)]
        if let second {
            lines.append((second, Self.shrunk(second, 11 * L * scale, room: room, strong: true), true, min(g.ground + 24 * L * scale, g.height - 11 * L * scale * 0.625 - 1)))
        }
        return lines
    }

    /// The fee child: a dot flies from your phone to the transaction's anchor and rides with it into the block.
    private func feeDot(_ canvas: MotionCanvas, _ f: ExitStageFrame, _ a: ExitStageScene, _ s: ExitStageScene, ys: [Double]) {
        let g = geometry, n = s.levels.count
        guard f.wallet > 0.01, !s.claiming, s.picture != .canceled else { return }
        func anchor(_ level: Int) -> CGPoint {
            CGPoint(x: g.trunk + (level == n ? g.coinRadius * 0.8 : 9), y: (ys[safe: level] ?? g.coinY) + (level == n ? g.coinRadius * 0.62 : 7))
        }
        func fly(_ level: Int, u: Double, radius: Double, hollow: Bool, alpha: Double) {
            guard alpha > 0.01, u > 0.001 else { return }
            let to = anchor(level), from = CGPoint(x: g.walletX - 8, y: g.walletY - 10), k = u.clamped(0, 1)
            let p = CGPoint(x: from.x + (to.x - from.x) * u, y: from.y + (to.y - from.y) * u - sin(k * .pi) * 34)
            if hollow {
                canvas.fill(canvas.circle(p.x, p.y, radius), colors.background.opacity(alpha))
                canvas.stroke(canvas.circle(p.x, p.y, radius), colors.muted.opacity(alpha), 1.4)
            } else {
                canvas.fill(canvas.circle(p.x, p.y, radius), colors.foreground.opacity(alpha))
            }
        }
        for (index, level) in s.levels.enumerated() {
            let old = f.origin[safe: index].flatMap { $0 }.flatMap { a.levels[safe: $0] }
            switch (old, level) {
            case (_, .pending(let theirs, let bumped)):
                if bumped, case .pending(_, false)? = old { fly(index + 1, u: 1, radius: 3.4, hollow: false, alpha: f.oldFee * f.tree) }
                fly(index + 1, u: f.fly, radius: bumped ? 4.8 : 3.4, hollow: theirs, alpha: f.wallet * (1 - f.mined[index]))
            case (.pending(let theirs, let bumped)?, .mined):
                fly(index + 1, u: 1, radius: bumped ? 4.8 : 3.4, hollow: theirs, alpha: 1 - f.mined[index])
            default: break
            }
        }
    }

    private func stemPath(to u: Double) -> Path {
        var path = Path()
        path.move(to: geometry.stem(0))
        for step in 1...30 { path.addLine(to: geometry.stem(u * Double(step) / 30)) }
        return path
    }

    /// Onboarding's branch link: an S-curve with its handles at the middle height.
    static func link(_ a: CGPoint, _ b: CGPoint) -> Path {
        var path = Path()
        path.move(to: a)
        path.addCurve(to: b, control1: CGPoint(x: a.x, y: (a.y + b.y) / 2), control2: CGPoint(x: b.x, y: (a.y + b.y) / 2))
        return path
    }

    /// The same link lying on its side (the Exits list's gutters).
    static func sideways(_ a: CGPoint, _ b: CGPoint) -> Path {
        var path = Path()
        path.move(to: a)
        path.addCurve(to: b, control1: CGPoint(x: (a.x + b.x) / 2, y: a.y), control2: CGPoint(x: (a.x + b.x) / 2, y: b.y))
        return path
    }

    static func straight(_ a: CGPoint, _ b: CGPoint) -> Path {
        var path = Path()
        path.move(to: a)
        path.addLine(to: b)
        return path
    }
}

extension Array {
    subscript(safe index: Int) -> Element? { indices.contains(index) ? self[index] : nil }
}
