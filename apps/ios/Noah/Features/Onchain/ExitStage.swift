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
    /// The explainer grows once when the page opens.
    var grown = true
    /// The path's transactions, root first; the last one's output is your coin.
    var levels: [Level]
    /// Which transactions your other exits share.
    var shared: [Bool] = []
    var others: [Other] = []
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

    /// The Emergency exit page's explainer.
    static func explainer(count: Int, grown: Bool = true) -> Self {
        var scene = Self(picture: .path, levels: Array(repeating: .unsent, count: max(1, count)))
        scene.explain = true
        scene.grown = grown
        return scene
    }

    var claiming: Bool { picture == .claim || picture == .claimed }
    var locked: Bool { picture == .lock || picture == .unlocked }
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

    /// `a` moving to `b`. Growth staggers 0.26 s a link; a block fills, settles from 1.22× and
    /// ripples once; the claim's stem draws (calm), then its coin travels into the destination.
    init(_ a: ExitStageScene, _ b: ExitStageScene, elapsed e: Double, reduceMotion: Bool) {
        let x = ExitStageStill(a), y = ExitStageStill(b), n = b.levels.count
        func spring(_ delay: Double, _ kind: MotionTime.Spring) -> Double { MotionTime.spring(e - delay, kind) }
        func mix(_ from: Double, _ to: Double, _ u: Double) -> Double { from + (to - from) * u }
        let snappy = spring(0, .snappy), soft = spring(0, .soft)
        let growing = x.grown < 0.5 && y.grown > 0.5, step = 0.26, grown = step * Double(n)
        grow = (0...n).map { j in growing ? ((e - Double(j - 1) * step) / step).clamped(0, 1) : y.grown }
        pop = (0...n).map { j in growing ? spring(Double(j) * step - 0.05, .bouncy) : y.grown }
        ghost = growing ? 0.5 * x.ghost * (1 - (e / grown).clamped(0, 1)) : 0.5 * mix(x.ghost, y.ghost, soft)
        ark = growing && y.ark < x.ark ? x.ark * (1 - ((e - grown) / 0.7).clamped(0, 1)) : mix(x.ark, y.ark, soft)
        lift = mix(x.lift, y.lift, snappy)
        if b.explain { labels = growing ? ((e - grown + 0.1) / 0.5).clamped(0, 1) : y.grown }
        for j in 0..<n {
            let was = x.mined[safe: j] ?? 0, now = y.mined[j]
            mined.append(mix(was, now, snappy))
            let fresh = was < 0.5 && now > 0.5
            stamp.append(fresh ? spring(0, .bouncy) : 1)
            ripple.append(fresh && !reduceMotion ? (e / 0.8).clamped(0, 1) : 0)
            pending.append(mix(x.pending[safe: j] ?? 0, y.pending[j], snappy))
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
            let bumped = { (s: ExitStageScene) in if case .pending(_, true) = s.levels[safe: j] { true } else { false } }
            if !(a.levels[safe: j]?.isPending ?? false) { fly = spring(0.35, .soft) }
            else if bumped(b) && !bumped(a) { fly = spring(0.15, .soft); oldFee = 1 - soft }
        }
        // Labels that change cross-fade.
        if a != b && !b.explain { fadeIn = (e / 0.25).clamped(0, 1) }
    }

    /// When a move from `a` to `b` has settled and the picture can stand still.
    static func duration(_ a: ExitStageScene?, _ b: ExitStageScene, reduceMotion: Bool) -> Double {
        guard let a, !reduceMotion else { return reduceMotion ? 0.3 : 0 }
        let x = ExitStageStill(a), y = ExitStageStill(b)
        var duration = 1.4
        if x.grown < 0.5 && y.grown > 0.5 { duration = max(duration, 0.26 * Double(b.levels.count) + 1.4) }
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
    @State private var from: ExitStageScene?
    @State private var changedAt = Date.distantPast
    @State private var settled = true
    @State private var visible = true
    @ScaledMetric(relativeTo: .caption) private var textScale = 1.0
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.locale) private var locale

    var body: some View {
        GeometryReader { proxy in
            let painter = ExitStagePainter(geometry: ExitStageGeometry(size: proxy.size, scale: textScale, wide: wide, count: scene.levels.count),
                                           colors: .theme(colorScheme), reduceMotion: reduceMotion, locale: locale)
            // At rest the tree stands still; what waits for a block moves in small overlays.
            let pulses = settled ? painter.pulses(scene) : []
            let animating = visible && scenePhase == .active && !reduceMotion
            ZStack(alignment: .topLeading) {
                ForEach(pulses) { ExitPulseView(pulse: $0, front: false, animating: animating, colors: painter.colors) }
                TimelineView(.animation(paused: settled || scenePhase != .active)) { timeline in
                    Canvas { context, _ in
                        let elapsed = timeline.date.timeIntervalSince(changedAt), now = timeline.date.timeIntervalSinceReferenceDate
                        painter.draw(context, from: settled ? nil : from, to: scene, elapsed: elapsed, now: now, inline: !settled, animating: animating)
                    }
                }
                ForEach(pulses) { ExitPulseView(pulse: $0, front: true, animating: animating, colors: painter.colors) }
            }
        }
        .onAppear {
            // The explainer grows once when the page opens; Reduce Motion shows it grown.
            guard scene.explain, !reduceMotion else { return }
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
        let round = ExitStageLabel(y: g.roundY - lh + 3, lines: [.init(text: tr("The round,"), tone: .strong), .init(text: tr("already on Bitcoin"))],
                                   priority: 2)
        if scene.explain {
            items.append(.init(y: g.coinY, lines: [.init(text: tr("Your VTXO"), tone: .strong), .init(text: tr("in Ark"))], priority: 3))
            if n > 1 {
                items.append(.init(y: (Y(1) + Y(n - 1)) / 2 - 7 * g.scale,
                                   lines: [.init(text: L10n.plural("{n} transactions", count: n, locale: language), tone: .strong),
                                           .init(text: tr("one block each,")), .init(text: tr("root first"))],
                                   priority: 2, brace: min(Y(n) + g.coinRadius + 5, Y(1) + 7)...(Y(1) + 7)))
            }
            items.append(round)
            return place(items)
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
                items.append(.init(y: Y(first), lines: [.init(text: tr("{n} more to send", n - first + 1))], priority: 1.5))
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
        return place(items)
    }

    private func place(_ items: [ExitStageLabel]) -> [ExitStageLabel] {
        ExitStageLabels.place(items.map(fit), lineHeight: geometry.lineHeight, scale: geometry.scale, bounds: geometry.labelBounds, fit: fit) {
            tr("Blocks {from}–{to}", height($0), height($1))
        }
    }

    /// A label wrapped to the room it has (translations and large text run long): its column, narrower
    /// beside the phone's badge, or for the claim, right of your coin. A pill's text keeps 8 pt inside it.
    func fit(_ label: ExitStageLabel) -> ExitStageLabel {
        let g = geometry
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
        return g.column(top: top, bottom: bottom) < g.width - 12 - g.labelX ? wrapped(g.column(top: top, bottom: bottom)) : full
    }

    /// A pill's frame around a label's first line, however many lines it wrapped to.
    func pill(_ label: ExitStageLabel) -> CGRect {
        let g = geometry, head = max(1, label.head)
        let width = label.lines.prefix(head).map { ExitStageText.width($0.text, size: g.fontSize, strong: true) }.max() ?? 0
        let height = Double(head - 1) * g.lineHeight + g.lineHeight + 5
        let x = label.trailing.map { $0 - width - 8 } ?? g.labelX - 8
        return CGRect(x: x, y: label.y - (g.lineHeight + 5) / 2, width: width + 16, height: height)
    }

    // MARK: Pulses

    /// What moves while the still stands: the waiting transaction, coin or claim, its pill, the dial's
    /// tip and a needs-funds ring. Overlays draw them; the Canvas draws them itself only while it moves.
    func pulses(_ scene: ExitStageScene) -> [ExitPulse] {
        guard !scene.explain, scene.picture != .none else { return [] }
        let g = geometry, still = ExitStageStill(scene), ys = g.levels(still.mined), n = scene.levels.count
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
        let ys = g.levels(f.mined), TX = g.trunk, yC = g.coinY
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
        // Everyone else's part of the round: one level of short faint stubs per transaction, and one caption.
        let othersAt = Dictionary(grouping: s.others, by: \.level)
        for level in 1..<max(1, n) {
            let alpha = f.tree * (f.pop[safe: level] ?? 1) * (1 - 0.6 * f.cancel)
            guard alpha > 0.02 else { continue }
            for tip in g.stubs(ys, level: level, others: othersAt[level]?.count ?? 0) {
                canvas.context.stroke(Self.link(CGPoint(x: tip.x, y: tip.y + 3), CGPoint(x: TX, y: Y(level) - 8)),
                                      with: .color(c.foreground.opacity(0.22 * alpha)), lineWidth: 1)
                canvas.fill(canvas.circle(tip.x, tip.y, 2.6), c.background.opacity(alpha))
                canvas.stroke(canvas.circle(tip.x, tip.y, 2.6), c.foreground.opacity(0.34 * alpha), 1)
            }
        }
        let captionAlpha = f.tree * (f.pop[safe: 1] ?? 1) * (1 - 0.6 * f.cancel) * (s.explain ? f.labels : 1 - f.dim)
        if captionAlpha > 0.02, let lines = g.caption(tr("Other people's coins in this round"), ys, width: { ExitStageText.width($0, size: 10 * L) }) {
            let lineHeight = 12.5 * L, top = Y(1) + 9, room = (g.ground - g.roundSide - 5) - top
            let y0 = top + (room - Double(lines.count) * lineHeight) / 2 + lineHeight / 2
            for (index, line) in lines.enumerated() {
                text(line, TX - g.roundSide / 2 - 9, y0 + Double(index) * lineHeight, size: 10 * L, color: c.muted, anchor: .trailing, opacity: captionAlpha * 0.9)
            }
        }
        // Your other exits from this round leave your branch where they stop sharing.
        for (level, others) in othersAt {
            for (index, other) in others.prefix(2).enumerated() {
                let p = g.other(ys, level: level, index: index), alpha = f.tree * (f.pop[safe: level] ?? 1)
                canvas.context.stroke(Self.link(CGPoint(x: p.x, y: p.y + 6), CGPoint(x: TX, y: Y(level) - 4)),
                                      with: .color(c.orange.opacity(0.7 * alpha)), style: StrokeStyle(lineWidth: 1.4, lineCap: .round, dash: other.sent ? [] : [3, 3]))
                canvas.fill(canvas.circle(p.x, p.y, 6), c.soft.opacity(alpha))
                canvas.stroke(canvas.circle(p.x, p.y, 6), c.orange.opacity(alpha), 1.6)
                text(other.amount, p.x - 10, p.y, size: Self.shrunk(other.amount, 9.5 * L, room: p.x - 14), color: c.muted, anchor: .trailing,
                     opacity: alpha * f.labels)
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
            let p0 = CGPoint(x: TX, y: level == 1 ? g.ground - g.roundSide : Y(level - 1) - 7)
            let p1 = CGPoint(x: TX, y: Y(level) + (level == n ? g.coinRadius : 7))
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
            let alpha = f.tree * (f.mined[index] < 1 ? 1 - 0.65 * f.cancel : 1)
            node(canvas, CGPoint(x: TX, y: Y(level)), level: s.levels[index], mined: f.mined[index], stamp: f.stamp[index], ripple: f.ripple[index],
                 pending: f.pending[index], side: g.side, pop: min(1.3, pop), alpha: alpha, grey: s.stale, inline: inline, now: now, breathe: breathe,
                 animating: animating && inline)
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
        let new = labels(s, ys: g.levels(ExitStageStill(s).mined))
        let old = f.fadeIn < 1 ? labels(a, ys: g.levels(ExitStageStill(a).mined)) : []
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
                layer.draw(Text(verbatim: line.text).font(ExitStageText.font(index < label.head ? g.fontSize : g.subSize, strong: [.strong, .failureStrong].contains(line.tone)))
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

    /// A one-line text's size, smaller (down to 60 %) when it would be wider than `room`.
    static func shrunk(_ text: String, _ size: Double, room: Double, strong: Bool = false) -> Double {
        let width = ExitStageText.width(text, size: size, strong: strong)
        return width <= room ? size : max(size * 0.6, size * room / width)
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
        let other: String? = if case .other(let address) = s.destination { address } else { nil }
        let first = !claiming ? tr("On-chain") : s.destination == .phone ? tr("This phone") : tr("Another wallet")
        let second = claiming ? (s.destination != .phone ? other : f.land > 0.9 ? s.received ?? s.balance : s.balance) : s.balance
        let size = claiming ? 1.1 : 1.0
        // Right of the "Bitcoin" label, however long the translation.
        let room = g.width - 12 - (14 + ExitStageText.width(tr("Bitcoin"), size: 10.5 * L) + 12)
        var layer = canvas.context
        layer.opacity *= alpha
        layer.draw(Text(verbatim: first).font(ExitStageText.font(Self.shrunk(first, 10.5 * L * size, room: room, strong: claiming), strong: claiming))
                    .foregroundColor(claiming ? colors.foreground : colors.muted),
                   at: CGPoint(x: g.width - 12, y: g.ground + 11 * L), anchor: .trailing)
        if let second {
            layer.draw(Text(verbatim: second).font(ExitStageText.font(Self.shrunk(second, 11 * L * size, room: room, strong: true), strong: true))
                        .foregroundColor(alarm ? NoahTokens.Colors.failure : claiming ? colors.muted : colors.foreground),
                       at: CGPoint(x: g.width - 12, y: g.ground + 24 * L * size), anchor: .trailing)
        }
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
            let old = a.levels[safe: index]
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
