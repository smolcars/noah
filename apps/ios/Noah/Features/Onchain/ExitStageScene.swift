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
    enum FoldKind: Equatable { case stack, more }
    struct Fold: Equatable {
        var kind: FoldKind
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
    func folded(nodes capacity: Int) -> Self {
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
        var drawn: [Int] = [] // each real node's drawn index
        scene.levels = []
        scene.folds = []
        if stacked > 0 {
            let heights = levels[0..<stacked].compactMap { if case .mined(let height) = $0 { height } else { nil } }
            scene.folds.append(.init(kind: .stack, index: 0, count: stacked,
                                     heights: heights.count == stacked ? heights.min().flatMap { low in heights.max().map { low...$0 } } : nil))
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
        case .blank:
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
                        painter.draw(
                            context,
                            from: settled ? nil : from?.folded(nodes: capacity),
                            to: drawn,
                            elapsed: elapsed,
                            motion: .init(now: now, inline: !settled, animating: animating))
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

extension Array {
    subscript(safe index: Int) -> Element? { indices.contains(index) ? self[index] : nil }
}
