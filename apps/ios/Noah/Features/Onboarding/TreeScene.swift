import SwiftUI

/// The onboarding tree scene, ported from `drawWelcome`, `drawCreate`,
/// `drawRestore` and `drawConveyor` in
/// the onboarding-v4 prototype (now only in git history). Positions and timings are
/// the prototype's (design points on a 393-pt-wide phone, seconds).
/// Decorative only: it never draws text and is hidden from VoiceOver.
enum TreeScene {
    enum Mode: Equatable, Sendable { case welcome, create, restore, conveyor }

    struct State: Equatable {
        var mode: Mode
        /// The mode's clock. Pose and layout changes keep it, so the tree never restarts.
        var start: Date
        /// Restore: when the closing sweep that lights your leaves began.
        var reveal: Date?
        /// Restore: the leaves that light up.
        var lit: [Int] = TreeScene.found.map(\.leaf)
        /// Conveyor: seconds into the scene when notifications were denied; freezes and greys it.
        var frozenAt: TimeInterval?
    }

    static let orange = Color(red: 247 / 255, green: 147 / 255, blue: 26 / 255)

    // Welcome, create and restore share one depth-3 tree, so it never jumps between them.
    static let tree = VtxoTree(centerX: 196.5, width: 330, ys: [214, 318, 410, 496], depth: 3)
    static let you = 41
    static let paths = (0..<tree.leaves.count).map { tree.path(toLeaf: $0) }
    static let specks: [CGPoint] = (0..<12).map { index in
        let angle = Double(index) / 12 * 2 * .pi + 0.4, radius = 90 + Double((index * 53) % 50)
        let you = tree.leaves[Self.you].point
        return CGPoint(x: you.x + cos(angle) * radius * 1.2, y: you.y + sin(angle) * radius * 0.9 + 30)
    }

    // Creating: phase text and the move to Name.
    static let createJoining = 1.35, createReady = 2.55, createDone = 3.6

    // Restoring: your bitcoin sits on several leaves; their amounts add up to the balance.
    static let found: [(leaf: Int, weight: Double)] = [(6, 4100), (23, 6642), (41, 100_000), (50, 42000)]
    static let band = (start: 0.4, end: 2.3)
    static let restoreFinding = 0.9, restoreReady = 2.65, restoreDone = 3.7
    static func foundAt(_ leaf: Int) -> Double {
        band.start + (band.end - band.start) * Double(tree.leaves[leaf].point.x - 20) / 353
    }

    /// The balance counted up under the restore sweep, `t` seconds after the reveal.
    static func restoredAmount(total: Int64, lit: [Int], at t: Double) -> Int64 {
        let weights = found.filter { lit.contains($0.leaf) }
        let sum = weights.reduce(0) { $0 + $1.weight }
        guard total > 0, sum > 0 else { return 0 }
        if weights.allSatisfy({ t >= foundAt($0.leaf) + 0.5 }) { return total }
        let value = weights.reduce(0.0) { result, entry in
            let start = foundAt(entry.leaf)
            return result + Double(total) * entry.weight / sum * ease(clamp((t - start) / 0.5))
        }
        return min(total, Int64(value.rounded()))
    }

    // Notifications: each round's tree expires and your leaf hops to the next round's tree.
    static let conveyorCycle = 3.8, conveyorLeaf = 10
    static func conveyorTree(_ x: CGFloat) -> VtxoTree { VtxoTree(centerX: x, width: 150, ys: [226, 282, 334], depth: 2) }

    static func clamp(_ x: Double, _ low: Double = 0, _ high: Double = 1) -> Double { max(low, min(high, x)) }
    static func ease(_ x: Double) -> Double { 1 - pow(1 - x, 3) }
}

/// Where the design points land in the view, and the area the scene may use
/// (it is clipped there, so in two panes nothing crosses the fold).
struct ScenePlacement: Equatable {
    var origin: CGPoint
    var scale: CGFloat
    var bounds: CGRect

    func point(_ p: CGPoint) -> CGPoint { CGPoint(x: origin.x + p.x * scale, y: origin.y + p.y * scale) }
}

struct TreeSceneView: View {
    let state: TreeScene.State
    let visible: Bool
    let placement: ScenePlacement

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        // Reduce Motion draws final states, so nothing needs a clock.
        let paused = !visible || reduceMotion || scenePhase != .active || state.frozenAt != nil
        TimelineView(.animation(paused: paused)) { timeline in
            Canvas { context, _ in
                context.clip(to: Path(placement.bounds))
                var painter = TreePainter(context: context, placement: placement,
                                          base: colorScheme == .dark ? .white : .black, reduceMotion: reduceMotion)
                painter.draw(state, at: timeline.date)
            }
        }
        .opacity(visible ? 1 : 0)
        .animation(.easeInOut(duration: 0.45), value: visible)
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}

/// One frame of the scene. Methods mirror the prototype's `link`, `dot`,
/// `ring`, `chain` and `drawTree`.
struct TreePainter {
    var context: GraphicsContext
    let placement: ScenePlacement
    let base: Color
    var reduceMotion: Bool
    /// Frozen conveyor: orange turns to the base colour and loses its glow.
    var muted = false

    private var scale: CGFloat { placement.scale }
    private func clamp(_ x: Double) -> Double { TreeScene.clamp(x) }
    private func ease(_ x: Double) -> Double { TreeScene.ease(x) }
    private func pr(_ t: Double, _ a: Double, _ b: Double) -> Double {
        reduceMotion ? (t >= b ? 1 : 0) : clamp((t - a) / (b - a))
    }

    mutating func draw(_ state: TreeScene.State, at date: Date) {
        let t = max(0, date.timeIntervalSince(state.start))
        switch state.mode {
        case .welcome: drawWelcome(reduceMotion ? 100 : t)
        case .create: drawCreate(reduceMotion ? 100 : t)
        case .restore:
            let reveal = state.reveal.map { reduceMotion ? 100 : max(0, date.timeIntervalSince($0)) }
            drawRestore(t, reveal: reveal, lit: state.lit)
        case .conveyor:
            if let frozen = state.frozenAt {
                muted = true
                reduceMotion = false
                drawConveyor(frozen, alpha: 0.35)
            } else if reduceMotion {
                // A still frame: both trees, your leaf and branch in the current one.
                reduceMotion = false
                drawConveyor(1.1, alpha: 1)
            } else {
                drawConveyor(t, alpha: 1)
            }
        }
    }

    // MARK: Primitives

    private func color(_ orange: Bool, _ alpha: Double) -> Color {
        (orange && !muted ? TreeScene.orange : base).opacity(min(1, max(0, alpha)))
    }

    private func shaded(_ orange: Bool) -> GraphicsContext {
        guard orange, !muted else { return context }
        var glowing = context
        glowing.addFilter(.shadow(color: TreeScene.orange.opacity(0.55), radius: 3.5 * scale))
        return glowing
    }

    /// Cubic with vertical tangents between parent `p` and child `c`.
    static func bezier(_ p: CGPoint, _ c: CGPoint, _ u: Double) -> CGPoint {
        let my = (p.y + c.y) / 2, a = 1 - u
        let x = a * a * a * p.x + 3 * a * a * u * p.x + 3 * a * u * u * c.x + u * u * u * c.x
        let y = a * a * a * p.y + 3 * a * a * u * my + 3 * a * u * u * my + u * u * u * c.y
        return CGPoint(x: x, y: y)
    }

    /// A link's ink: orange or the tree's, its opacity and its width.
    private struct LinkStyle { var orange: Bool; var alpha: Double; var width: CGFloat }
    private func link(_ p: CGPoint, _ c: CGPoint, _ u0: Double, _ u1: Double, style: LinkStyle) {
        let orange = style.orange, alpha = style.alpha, width = style.width
        guard u1 > u0, alpha > 0 else { return }
        var path = Path()
        for step in 0...14 {
            let q = placement.point(Self.bezier(p, c, u0 + (u1 - u0) * Double(step) / 14))
            if step == 0 { path.move(to: q) } else { path.addLine(to: q) }
        }
        shaded(orange).stroke(path, with: .color(color(orange, alpha)),
                              style: StrokeStyle(lineWidth: max(0.5, width * scale), lineCap: .round))
    }

    private func dot(_ p: CGPoint, _ r: Double, orange: Bool, alpha: Double) {
        guard r > 0, alpha > 0 else { return }
        let q = placement.point(p), radius = r * scale
        shaded(orange).fill(Path(ellipseIn: CGRect(x: q.x - radius, y: q.y - radius, width: radius * 2, height: radius * 2)),
                            with: .color(color(orange, alpha)))
    }

    private func ring(_ p: CGPoint, _ r: Double, style: LinkStyle) {
        let orange = style.orange, alpha = style.alpha, width = style.width
        guard r > 0, alpha > 0 else { return }
        let q = placement.point(p), radius = r * scale
        shaded(orange).stroke(Path(ellipseIn: CGRect(x: q.x - radius, y: q.y - radius, width: radius * 2, height: radius * 2)),
                              with: .color(color(orange, alpha)), lineWidth: max(0.5, width * scale))
    }

    /// The Bitcoin line at the root, with block ticks drifting by `cam`.
    private func chain(_ y: CGFloat, cam: Double, grow: Double = 1, alpha: Double = 1) {
        let bounds = placement.bounds
        let center = placement.point(CGPoint(x: 196.5, y: y))
        let half = max(center.x - bounds.minX, bounds.maxX - center.x) * grow
        var line = Path()
        line.move(to: CGPoint(x: center.x - half, y: center.y))
        line.addLine(to: CGPoint(x: center.x + half, y: center.y))
        context.stroke(line, with: .color(base.opacity(0.18 * alpha)), lineWidth: 1)
        let offset = (cam.truncatingRemainder(dividingBy: 26) + 26).truncatingRemainder(dividingBy: 26)
        let left = Double((bounds.minX - placement.origin.x) / scale), right = Double((bounds.maxX - placement.origin.x) / scale)
        var x = -offset + (left / 26).rounded(.down) * 26
        let size = 4 * scale
        while x <= right {
            let tick = placement.point(CGPoint(x: x, y: y))
            if abs(tick.x - center.x) <= half {
                context.fill(Path(CGRect(x: tick.x - size / 2, y: tick.y - size / 2, width: size, height: size)),
                             with: .color(base.opacity(0.2 * alpha)))
            }
            x += 26
        }
    }

    /// `grow[d]`: progress of the links leaving level `d`, counted from the root.
    private func drawTree(_ tree: VtxoTree, grow: [Double], alpha: Double = 1, rootScale: Double = 1) {
        let depth = tree.depth
        for d in 0..<depth where grow[d] > 0 {
            let tier = depth == 3 ? d : d + 1
            let width: CGFloat = [1.3, 1, 0.7][tier], opacity = [0.24, 0.2, 0.16][tier]
            for parent in tree.levels[depth - d] {
                for child in parent.children {
                    link(
                        parent.point,
                        tree.levels[depth - d - 1][child].point,
                        0,
                        grow[d],
                        style: LinkStyle(orange: false, alpha: opacity * alpha, width: width))
                }
            }
        }
        for d in 1..<depth where grow[d - 1] >= 1 {
            for node in tree.levels[depth - d] { dot(node.point, 1.8, orange: false, alpha: 0.45 * alpha) }
        }
        dot(tree.root.point, 4 * rootScale, orange: false, alpha: 0.9 * alpha)
    }

    private func along(_ path: [CGPoint], _ q: Double) -> CGPoint {
        let segments = path.count - 1
        let k = min(segments - 1, Int((q * Double(segments)).rounded(.down)))
        return Self.bezier(path[k], path[k + 1], clamp(q * Double(segments) - Double(k)))
    }

    // MARK: Scenes

    private func drawWelcome(_ t: Double) {
        let tree = TreeScene.tree, you = tree.leaves[TreeScene.you].point
        chain(496, cam: reduceMotion ? 0 : t * 7, grow: ease(pr(t, 0, 0.6)))
        drawTree(tree, grow: [ease(pr(t, 0.3, 0.75)), ease(pr(t, 0.6, 1.05)), ease(pr(t, 0.9, 1.4))], rootScale: ease(pr(t, 0.15, 0.4)))
        // Idle pulses run up a branch and flash its leaf; finished ones are skipped.
        var flash: [Int: Double] = [:]
        if !reduceMotion {
            var n = max(0, Int(((t - 3.215) / 1.2).rounded(.down)))
            while true {
                let start = 2 + Double(n) * 1.2
                if start > t { break }
                let q = (t - start) / 0.9
                var leaf = (n * 37 + 11) % 64
                if leaf == TreeScene.you { leaf = (leaf + 5) % 64 }
                n += 1
                if q >= 1.35 { continue }
                if q < 1 { dot(along(TreeScene.paths[leaf], ease(q)), 1.9, orange: false, alpha: 0.85) }
                else { flash[leaf] = 1 - (q - 1) / 0.35 }
            }
        }
        for (index, leaf) in tree.leaves.enumerated() where index != TreeScene.you {
            let k = abs(Double(index) - 31.5) * 0.006, pop = pr(t, 1.25 + k, 1.5 + k)
            guard pop > 0 else { continue }
            let twinkle = reduceMotion ? 0 : pow(max(0, sin(t * 0.8 + Double(index) * 2.17)), 12) * 0.35
            let f = flash[index] ?? 0
            dot(leaf.point, 1.5 * ease(pop) + 1.2 * f, orange: false, alpha: 0.38 + twinkle + 0.45 * f)
        }
        let slot = pr(t, 1.55, 1.95)
        if slot > 0 {
            let breathe = reduceMotion || t < 1.95 ? 1 : 1 + 0.18 * sin((t - 1.95) * 3.6)
            ring(you, 3.4 * ease(slot) * breathe, style: LinkStyle(orange: true, alpha: 1, width: 1.4))
        }
    }

    private func drawCreate(_ t: Double) {
        let tree = TreeScene.tree, you = tree.leaves[TreeScene.you].point, branch = TreeScene.paths[TreeScene.you]
        chain(496, cam: reduceMotion ? 0 : t * 7)
        drawTree(tree, grow: [1, 1, 1])
        let wave = t - 2.55
        for (index, leaf) in tree.leaves.enumerated() where index != TreeScene.you {
            var f = 0.0
            if wave > 0 && !reduceMotion {
                let distance = (abs(Double(leaf.point.x - you.x)) - wave * 260) / 14
                f = exp(-(distance * distance)) * clamp(1 - wave / 1.4)
            }
            dot(leaf.point, 1.5 + f * 1.4, orange: false, alpha: 0.38 + 0.5 * f)
        }
        // Twelve specks become your key.
        for (index, speck) in TreeScene.specks.enumerated() {
            let q = pr(t, 0.15 + Double(index) * 0.06, 0.7 + Double(index) * 0.06)
            guard q > 0, q < 1 else { continue }
            let e = q * q
            dot(CGPoint(x: speck.x + (you.x - speck.x) * e, y: speck.y + (you.y - speck.y) * e), 1.8, orange: true, alpha: 0.95)
        }
        ring(you, 3.4, style: LinkStyle(orange: true, alpha: 1, width: 1.4))
        let fill = pr(t, 1, 1.3)
        if fill > 0 { dot(you, 4.2 * ease(fill), orange: true, alpha: 1) }
        // Your exit branch traces up from the root.
        if pr(t, 1.4, 1.75) > 0 { dot(branch[0], 4.4, orange: true, alpha: 1) }
        for (k, segment) in [(1.4, 1.75), (1.75, 2.1), (2.1, 2.45)].enumerated() {
            let u = ease(pr(t, segment.0, segment.1))
            link(branch[k], branch[k + 1], 0, u, style: LinkStyle(orange: true, alpha: 0.95, width: 1.6))
            if u >= 1 && k < 2 { dot(branch[k + 1], 2.6, orange: true, alpha: 1) }
        }
        if !reduceMotion {
            for k in 0..<2 {
                let q = pr(t, 2.55 + Double(k) * 0.22, 3.45 + Double(k) * 0.22)
                if q > 0 && q < 1 { ring(you, 4 + 30 * ease(q), style: LinkStyle(orange: true, alpha: 0.7 * (1 - q), width: 1.2)) }
            }
        }
    }

    /// `reveal`: seconds since the restore succeeded. Until then the band keeps scanning.
    private func drawRestore(_ t: Double, reveal: Double?, lit: [Int]) {
        let tree = TreeScene.tree, band = TreeScene.band
        chain(496, cam: reduceMotion ? 0 : t * 7)
        drawTree(tree, grow: [1, 1, 1])
        guard let r = reveal else {
            if !reduceMotion {
                let cycle = t.truncatingRemainder(dividingBy: band.end)
                if cycle > band.start { scanBand(20 + 353 * (cycle - band.start) / (band.end - band.start)) }
            }
            for leaf in tree.leaves { dot(leaf.point, 1.5, orange: false, alpha: 0.38) }
            return
        }
        let bx = 20 + 353 * pr(r, band.start, band.end)
        if !reduceMotion && r > band.start && r < band.end + 0.15 { scanBand(bx) }
        for (index, leaf) in tree.leaves.enumerated() where !(lit.contains(index) && leaf.point.x <= bx) {
            dot(leaf.point, 1.5, orange: false, alpha: 0.38)
        }
        for index in lit {
            let leaf = tree.leaves[index].point
            guard leaf.x <= bx else { continue }
            let start = TreeScene.foundAt(index), u = ease(pr(r, start, start + 0.45)), path = TreeScene.paths[index]
            for k in stride(from: 2, through: 0, by: -1) {
                let su = clamp(u * 3 - Double(2 - k))
                if su > 0 { link(path[k], path[k + 1], 1 - su, 1, style: LinkStyle(orange: true, alpha: 0.9, width: 1.4)) }
            }
            if u >= 1 { dot(path[0], 4.4, orange: true, alpha: 1) }
            dot(leaf, 3.6, orange: true, alpha: 1)
        }
        if !reduceMotion && r > band.end + 0.3 {
            let q = clamp((r - band.end - 0.3) / 0.9)
            for index in lit { ring(tree.leaves[index].point, 4 + 18 * ease(q), style: LinkStyle(orange: true, alpha: 0.6 * (1 - q), width: 1.1)) }
        }
    }

    private func scanBand(_ x: Double) {
        let left = placement.point(CGPoint(x: x - 28, y: 196)), right = placement.point(CGPoint(x: x + 28, y: 496))
        let gradient = Gradient(stops: [.init(color: TreeScene.orange.opacity(0), location: 0),
                                        .init(color: TreeScene.orange.opacity(0.18), location: 0.5),
                                        .init(color: TreeScene.orange.opacity(0), location: 1)])
        context.fill(Path(CGRect(x: left.x, y: left.y, width: right.x - left.x, height: right.y - left.y)),
                     with: .linearGradient(gradient, startPoint: CGPoint(x: left.x, y: 0), endPoint: CGPoint(x: right.x, y: 0)))
    }

    private func drawConveyor(_ t: Double, alpha oa: Double) {
        let cycle = TreeScene.conveyorCycle, mine = TreeScene.conveyorLeaf
        let k = (t / cycle).rounded(.down), u = t - k * cycle
        let cam = 180 * k + 180 * (reduceMotion ? (u >= 3.4 ? 1 : 0) : ease(clamp((u - 2.6) / 0.8)))
        chain(334, cam: cam)
        let a = TreeScene.conveyorTree(110 + 180 * k - cam), b = TreeScene.conveyorTree(110 + 180 * (k + 1) - cam)
        let w = pr(u, 1.9, 2.7), g = ease(pr(u, 0, 1))
        // The expiring tree is swept; the next round's tree grows.
        drawTree(a, grow: [1 - clamp(w * 2 - 1), 1 - clamp(w * 2)], alpha: 1 - 0.7 * w)
        for (index, leaf) in a.leaves.enumerated() where index != mine || u >= 1.1 {
            dot(leaf.point, 1.4 * (1 - w), orange: false, alpha: 0.38 * (1 - w))
        }
        drawTree(b, grow: [clamp(g * 2), clamp(g * 2 - 1)], rootScale: clamp(g * 3))
        for (index, leaf) in b.leaves.enumerated() where !(index == mine && u >= 1.8) {
            dot(leaf.point, 1.4 * pr(u, 0.9, 1.1), orange: false, alpha: 0.38)
        }
        let pathA = a.path(toLeaf: mine), pathB = b.path(toLeaf: mine)
        let fade = (u < 1.1 ? 1 : 1 - pr(u, 1.1, 1.5)) * (1 - w)
        for s in 0..<2 { link(pathA[s], pathA[s + 1], 0, 1, style: LinkStyle(orange: true, alpha: 0.9 * fade * oa, width: 1.4)) }
        let trace = ease(pr(u, 1.8, 2.2))
        for s in 0..<2 { link(pathB[s], pathB[s + 1], 0, clamp(trace * 2 - Double(s)), style: LinkStyle(orange: true, alpha: 0.9 * oa, width: 1.4)) }
        // Your leaf hops across.
        let from = a.leaves[mine].point, to = b.leaves[mine].point
        func hop(_ h: Double) -> CGPoint {
            CGPoint(x: from.x + (to.x - from.x) * h, y: from.y + (to.y - from.y) * h - sin(.pi * h) * 46)
        }
        let h = ease(pr(u, 1.1, 1.8))
        if h > 0 && h < 1 && !reduceMotion {
            for i in 1...3 { dot(hop(ease(pr(u - 0.04 * Double(i), 1.1, 1.8))), 3 - Double(i) * 0.6, orange: true, alpha: 0.25 * oa) }
        }
        dot(hop(h), 3.4, orange: true, alpha: oa)
    }
}
