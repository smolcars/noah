import SwiftUI

/// The exit's picture (exit.md, "The exit in progress"; prototype `drawStage`): your branch of the
/// round's tree over the block row. Each bark state has one still picture; when the wallet reaches
/// a new state the picture moves to it with the house springs, and only then. Nothing here runs
/// on a timer of its own. Reduce Motion cross-fades the two stills over 180 ms.
struct ExitStageScene: Equatable {
    enum Current: Equatable {
        /// The next transaction hangs in the branch (waiting for its parent, or not sent yet).
        case branch
        /// It dropped into the blinking "next" block, waiting for its fee child.
        case next
        /// In the mempool with its fee: ours, someone else's (hollow), or re-bumped (bigger).
        case fee(theirs: Bool, bumped: Bool)
        /// Parked, too few confirmed funds: a red dashed ring, the wallet marked.
        case needsFunds
    }

    var picture: ExitProgress.Picture
    /// Transactions on the path; the last is your VTXO's leaf, drawn as the coin.
    var total = 4
    var confirmed = 0
    var current = Current.branch
    var heights: [UInt32?] = []
    var tip: UInt32 = 0
    /// Under the on-chain wallet: its balance, or the claim's destination.
    var wallet: String?
    var walletAlarm = false
    var leaf: UInt32?
    var unlock: UInt32?
    /// Which way each level's siblings fan out (from the txids, so it's stable).
    var digits: [Int] = [2, 2, 1]
    /// The warning's picture keeps the Ark server; Review's and Claim's don't.
    var showsServer = true

    init(picture: ExitProgress.Picture) { self.picture = picture }

    /// The path an exit takes, before one starts (the warning and Review).
    init(path tip: UInt32?, server: Bool = true) {
        picture = .path
        self.tip = tip ?? 0
        showsServer = server
    }

    init(_ progress: ExitProgress, tip: UInt32, wallet: String?, delta: UInt32?, txids: [String]) {
        picture = progress.picture
        // Before bark lists the path (Start), draw a round's usual four.
        total = progress.total > 0 ? progress.total : 4
        confirmed = progress.confirmed
        heights = progress.heights
        self.tip = tip
        self.wallet = wallet
        digits = txids.map { Int($0.unicodeScalars.first?.value ?? 0) % 4 }
        switch progress.phase {
        case .sending: current = .next
        case .mempool: current = .fee(theirs: false, bumped: progress.bumped)
        case .shared: current = .fee(theirs: true, bumped: false)
        case .needsFunds:
            current = .needsFunds
            walletAlarm = true
        default: current = .branch
        }
        unlock = progress.claimableHeight
        leaf = progress.heights.last.flatMap { $0 } ?? progress.claimableHeight.flatMap { unlock in delta.map { unlock - min(unlock, $0) } }
    }

    /// Something keeps moving at rest: the "next" block blinks, the claim ripples, the funds ring pulses.
    var animatesAtRest: Bool {
        switch picture {
        case .path: current != .branch
        case .unlocked, .claim: true
        case .lock: true
        default: false
        }
    }
}

struct ExitStageView: View {
    let scene: ExitStageScene
    @State private var from: ExitStageScene?
    @State private var changedAt = Date.distantPast
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.locale) private var locale

    var body: some View {
        TimelineView(.animation(paused: scenePhase != .active || (reduceMotion && Date().timeIntervalSince(changedAt) > 0.3))) { timeline in
            Canvas { context, size in
                let elapsed = timeline.date.timeIntervalSince(changedAt)
                let painter = ExitStagePainter(size: size, colors: .theme(colorScheme), reduceMotion: reduceMotion,
                                               now: timeline.date.timeIntervalSinceReferenceDate, next: L10n.text("next", locale: locale.identifier),
                                               round: L10n.text("round", locale: locale.identifier), locale: locale)
                if reduceMotion {
                    // Every move becomes a fade between the two stills.
                    let u = min(1, max(0, elapsed / 0.18))
                    if let from, u < 1 {
                        var old = context
                        old.opacity = 1 - u
                        painter.draw(old, painter.layout(from), painter.layout(from), 1, 1, scene: from)
                    }
                    var new = context
                    new.opacity = from == nil ? 1 : u
                    painter.draw(new, painter.layout(scene), painter.layout(scene), 1, 1, scene: scene)
                } else {
                    let a = from.map(painter.layout) ?? painter.layout(scene), b = painter.layout(scene)
                    painter.draw(context, a, b, MotionTime.spring(elapsed, .snappy), MotionTime.spring(elapsed, .bouncy), scene: scene)
                }
            }
        }
        .onChange(of: scene) { old, _ in
            from = old
            changedAt = Date()
        }
        .accessibilityHidden(true)
    }
}

/// One still picture, in points; two of them are mixed while it moves.
struct ExitStageLayout {
    struct Node { var x, y, alpha, inBlock: Double }
    var arkAlpha = 1.0
    var server = 1.0
    var nodes: [Node] = []
    var unroll = 1.0
    var coin = Node(x: 0, y: 0, alpha: 1, inBlock: 0)
    var coinRadius = 0.0
    var rowAlpha = 1.0
    var rowTip = 0.0
    var wallet = CGPoint.zero
    var walletAlpha = 0.0
    var walletScale = 1.0
    var fee = CGPoint.zero
    var feeAlpha = 0.0
    var lock = 0.0
    var open = 0.0
    var bar = 0.0
    var finale = 0.0
    var alarm = 0.0
    var cancel = 0.0
}

struct ExitStagePainter {
    let size: CGSize
    let colors: MotionColors
    let reduceMotion: Bool
    let now: Double
    let next: String
    let round: String
    let locale: Locale

    // Prototype `geo`.
    var W: Double { size.width }
    var H: Double { min(size.height, 430) }
    var top: Double { (size.height - H) / 2 }
    var s: Double { (W * 0.098).clamped(32, 44) }
    var st: Double { s + 12 }
    var yRowT: Double { top + H - s - 30 }
    var yRowC: Double { yRowT + s / 2 }
    var NX: Double { (W / 2).rounded() }
    var yRoot: Double { yRowT - 38 }
    var RX: Double { 8 + s / 2 }
    var WX: Double { W - 24 }
    var rC: Double { (W * 0.045).clamped(14, 19).rounded() }
    let rN = 6.0
    var lockY: Double { (top + H * 0.38).rounded() }
    func gap(_ n: Int) -> Double { min(64, (yRoot - (top + (H * 0.16).clamped(40, 50))) / Double(max(1, n - 1))) }
    func yUp(_ n: Int) -> Double { yRoot - Double(n - 1) * gap(n) }
    func yArk(_ n: Int) -> Double { yUp(n) + 14 }
    /// A mined block of `height` while the row shows `rowTip`; "next" sits at NX.
    func blockX(_ height: UInt32, _ rowTip: Double) -> Double { NX - (rowTip - Double(height) + 1) * st }

    /// The still picture for a state.
    func layout(_ scene: ExitStageScene) -> ExitStageLayout {
        var l = ExitStageLayout()
        let n = scene.total
        l.rowTip = Double(scene.tip)
        l.wallet = CGPoint(x: WX, y: yRowC)
        l.coinRadius = rC
        let lifted = scene.picture != .canceled
        l.server = lifted ? 0 : 1
        // The branch steps down one level for each transaction in a block.
        let k = Double(scene.confirmed)
        func home(_ j: Int) -> Double { yRoot - (Double(j) - k) * gap(n) }
        func place(_ j: Int) -> ExitStageLayout.Node {
            if j < scene.confirmed, let height = scene.heights[safe: j] ?? nil {
                return .init(x: blockX(height, l.rowTip), y: yRowC, alpha: 1, inBlock: 1)
            }
            if j == scene.confirmed, scene.current != .branch, scene.current != .needsFunds {
                return .init(x: NX, y: yRowC, alpha: 1, inBlock: 0)
            }
            return .init(x: NX, y: home(j), alpha: 1, inBlock: 0)
        }
        l.nodes = (0..<max(0, n - 1)).map(place)
        l.coin = place(n - 1)
        if l.coin.y < yRowT { l.coin.y = home(n - 1) }
        switch scene.picture {
        case .lift:
            l.nodes = l.nodes.map { var node = $0; node.alpha = 0; return node }
            l.unroll = 0
            l.coin = .init(x: NX, y: yArk(n) - 14, alpha: 1, inBlock: 0)
        case .canceled:
            l.nodes = l.nodes.map { var node = $0; if node.inBlock == 0 { node.alpha = 0.35 }; return node }
            l.coin = .init(x: NX, y: yArk(n), alpha: 1, inBlock: 0)
            l.cancel = 1
        case .path:
            l.walletAlpha = scene.confirmed > 0 || scene.current != .branch ? 1 : 0
            if case .fee = scene.current {
                let anchor = l.nodes[safe: scene.confirmed] ?? l.coin
                l.fee = CGPoint(x: anchor.x - 4 + 11, y: anchor.y + 6)
                l.feeAlpha = 1
            } else {
                l.fee = CGPoint(x: WX - 14, y: yRowC - 6)
            }
            if scene.current == .needsFunds { l.alarm = 1 }
            if scene.confirmed >= n { l.coin.inBlock = 1 }
        case .lock, .unlocked:
            l.arkAlpha = 0; l.rowAlpha = 0; l.unroll = 0
            l.nodes = l.nodes.map { var node = $0; node.alpha = 0; return node }
            l.coin = .init(x: NX, y: lockY, alpha: 1, inBlock: 0)
            l.coinRadius = rC * 2.3
            l.lock = 1
            l.open = scene.picture == .unlocked ? 1 : 0
            if let leaf = scene.leaf, let unlock = scene.unlock, unlock > leaf {
                l.bar = (Double(scene.tip) - Double(leaf)) / Double(unlock - leaf)
            }
            l.bar = scene.picture == .unlocked ? 1 : l.bar.clamped(0, 1)
        case .claim, .claimed:
            l.arkAlpha = 0; l.unroll = 0
            l.nodes = l.nodes.map { var node = $0; node.alpha = 0; return node }
            l.walletAlpha = 1
            l.coin = .init(x: NX, y: yRowC, alpha: 1, inBlock: 0)
            l.coinRadius = s * 0.3
            if scene.picture == .claimed {
                l.coin = .init(x: NX, y: lockY - 4, alpha: 0, inBlock: 1)
                l.wallet = CGPoint(x: NX, y: lockY - 4)
                l.walletScale = 1.75
                l.finale = 1
                l.rowAlpha = 0
            }
        case .none:
            l.arkAlpha = 0; l.rowAlpha = 0; l.unroll = 0; l.server = 0
            l.nodes = l.nodes.map { var node = $0; node.alpha = 0; return node }
            l.coin.alpha = 0
        }
        if !scene.showsServer { l.server = -1 }
        return l
    }

    // MARK: Drawing

    /// Onboarding's branch link: an S-curve with its handles at the middle height (prototype `bz`).
    static func link(_ a: CGPoint, _ b: CGPoint) -> Path {
        var path = Path()
        path.move(to: a)
        path.addCurve(to: b, control1: CGPoint(x: a.x, y: (a.y + b.y) / 2), control2: CGPoint(x: b.x, y: (a.y + b.y) / 2))
        return path
    }

    /// Draws `a` moving to `b`: `u` is the snappy spring, `drop` the bouncy one for falls.
    /// Every node of `b`, moving from its place in `a`. One `a` doesn't have (a real path longer than
    /// Start's four) fades in where it lands.
    func nodes(_ a: ExitStageLayout, _ b: ExitStageLayout, _ u: Double, _ drop: Double) -> [ExitStageLayout.Node] {
        let mix = { (x: Double, y: Double) in x + (y - x) * u }
        return b.nodes.indices.map { index in
            let new = b.nodes[index], old = a.nodes[safe: index] ?? .init(x: new.x, y: new.y, alpha: 0, inBlock: new.inBlock)
            return .init(x: mix(old.x, new.x), y: new.y == yRowC && old.y != yRowC && new.inBlock == 0 ? old.y + (new.y - old.y) * drop : mix(old.y, new.y),
                         alpha: mix(old.alpha, new.alpha), inBlock: mix(old.inBlock, new.inBlock))
        }
    }

    func draw(_ context: GraphicsContext, _ a: ExitStageLayout, _ b: ExitStageLayout, _ u: Double, _ drop: Double, scene: ExitStageScene) {
        let canvas = MotionCanvas(context: context, size: size, viewBox: CGRect(origin: .zero, size: size), colors: colors)
        let mix = { (x: Double, y: Double) in x + (y - x) * u }
        let blink = reduceMotion ? 1 : 0.55 + 0.45 * cos(now / 0.65 * .pi)
        let rowTip = abs(b.rowTip - a.rowTip) > 3 ? b.rowTip : mix(a.rowTip, b.rowTip)
        let n = b.nodes.count + 1
        let arkAlpha = mix(a.arkAlpha, b.arkAlpha), yA = yArk(n)
        // Ark: a faint line, the server (greyed once you leave: an exit doesn't need it) and the label.
        if arkAlpha > 0.01 {
            canvas.stroke(canvas.line(CGPoint(x: 16, y: yA), CGPoint(x: W - 48, y: yA)), colors.hairline.opacity(arkAlpha), 1)
            canvas.text("Ark", 26, yA - 9, size: 11, weight: .medium, color: colors.muted.opacity(arkAlpha), display: false)
            if b.server >= 0 {
                let lit = mix(max(0, a.server), b.server)
                var badge = canvas
                badge.context.opacity *= arkAlpha * (0.38 + 0.62 * lit)
                badge.fill(badge.circle(W - 26, yA, 15), colors.background)
                badge.stroke(badge.circle(W - 26, yA, 15), colors.hairline, 1.5, dash: lit < 0.5 ? [3, 3] : [])
                badge.symbol("server.rack", W - 26, yA, size: 13, color: colors.foreground)
            }
        }
        // The block row: the round's old block, the latest blocks, then the blinking "next" one.
        let rowAlpha = mix(a.rowAlpha, b.rowAlpha)
        if rowAlpha > 0.01 {
            var row = canvas
            row.context.opacity *= rowAlpha
            if b.lock == 0 && b.finale == 0 && b.walletScale == 1 && b.coinRadius != s * 0.3 {
                row.fill(Path(roundedRect: CGRect(x: RX - s / 2, y: yRowT, width: s, height: s), cornerRadius: 7), colors.foreground.opacity(0.38))
                row.text(round, RX, yRowT + s + 14, size: 10.5, weight: .medium, color: colors.muted, display: false)
            }
            let base = UInt32(max(0, rowTip.rounded(.up)))
            for height in (base > 3 ? base - 3 : 0)...max(base, 1) where Double(height) <= b.rowTip && b.rowTip > 0 {
                let x = blockX(height, rowTip)
                guard x < NX - 1, x > RX + s / 2 + 8 else { continue }
                let fade = min(1, (x - RX - s / 2 - 8) / st)
                let pop = Double(height) > a.rowTip ? 1 + 0.2 * (1 - drop) : 1
                var block = row
                block.context.opacity *= fade
                let side = s * pop
                block.fill(Path(roundedRect: CGRect(x: x - side / 2, y: yRowC - side / 2, width: side, height: side), cornerRadius: 7), colors.foreground)
                block.text(height.formatted(.number.locale(locale)), x, yRowT + s + 14, size: 10.5, weight: .medium,
                           color: Double(height) == b.rowTip ? colors.foreground : colors.muted)
            }
            var pending = row
            if b.walletAlpha > 0 && b.feeAlpha + (b.alarm == 0 && b.nodes.contains { $0.y == yRowC && $0.inBlock == 0 } ? 1 : 0) > 0 { pending.context.opacity *= blink }
            pending.stroke(Path(roundedRect: CGRect(x: NX - s / 2, y: yRowT, width: s, height: s), cornerRadius: 6), colors.foreground, 2, dash: [4, 4])
            row.text(next, NX, yRowT + s + 14, size: 10.5, weight: .medium, color: colors.muted, display: false)
        }
        // Your branch: links unroll in orange, everyone else's branches are faint stubs.
        let unroll = mix(a.unroll, b.unroll)
        let cancel = mix(a.cancel, b.cancel)
        let nodes = self.nodes(a, b, u, drop)
        var coin = ExitStageLayout.Node(x: mix(a.coin.x, b.coin.x), y: mix(a.coin.y, b.coin.y), alpha: mix(a.coin.alpha, b.coin.alpha),
                                        inBlock: mix(a.coin.inBlock, b.coin.inBlock))
        if b.coin.y == yRowC && a.coin.y != yRowC { coin.y = a.coin.y + (b.coin.y - a.coin.y) * drop }
        let chain = nodes + [coin]
        if unroll > 0.01 {
            let orange = colors.orange.opacity(0.9 * (1 - cancel * 0.65))
            for j in 0..<max(0, chain.count - 1) {
                let lower = chain[j], upper = chain[j + 1]
                guard upper.y < yRowT - 1, upper.alpha > 0.01 else { continue }
                let progress = (unroll * Double(chain.count) - Double(chain.count - 2 - j)).clamped(0, 1)
                let from = CGPoint(x: upper.x, y: upper.y + (j + 1 == chain.count - 1 ? mix(a.coinRadius, b.coinRadius) : rN))
                let to = lower.y < yRowT - 1 ? CGPoint(x: lower.x, y: lower.y - rN) : CGPoint(x: lower.x, y: yRowT)
                canvas.context.stroke(Self.link(from, to).trimmedPath(from: 0, to: progress), with: .color(orange),
                                      style: StrokeStyle(lineWidth: 1.6, lineCap: .round))
            }
            if let root = chain.first, root.y < yRowT - 1 {
                let path = Self.link(CGPoint(x: root.x, y: root.y + rN), CGPoint(x: RX, y: yRowT))
                canvas.context.stroke(path.trimmedPath(from: 0, to: ((unroll - 0.5) * 2).clamped(0, 1)), with: .color(orange),
                                      style: StrokeStyle(lineWidth: 1.4, lineCap: .round))
            }
            // Stubs: three siblings per node, faint, never drawn out.
            for (j, node) in nodes.enumerated() where node.y < yRowT - 1 && node.alpha > 0.01 {
                let own = scene.digits[safe: j] ?? [2, 2, 1][j % 3]
                for sibling in 0..<4 where sibling != own {
                    let ex = node.x + Double(sibling - own) * min(22, W * 0.06), ey = node.y - gap(n) * 0.6
                    canvas.stroke(canvas.line(CGPoint(x: ex, y: ey), CGPoint(x: node.x, y: node.y - rN)), colors.foreground.opacity(0.2 * unroll * node.alpha), 1)
                    canvas.fill(canvas.circle(ex, ey, 2), colors.foreground.opacity(0.32 * unroll * node.alpha))
                }
            }
        }
        // The transactions: hollow circles until they're in a block.
        for node in nodes where node.alpha > 0.01 {
            var c = canvas
            c.context.opacity *= node.alpha
            if node.inBlock > 0.5 {
                c.fill(c.circle(node.x - 4, node.y, 4.6), colors.background)
            } else {
                let x = node.y == yRowC ? node.x - 4 : node.x
                c.fill(c.circle(x, node.y, rN), colors.background)
                c.stroke(c.circle(x, node.y, rN), colors.foreground, 1.7)
            }
        }
        // Needs funds: the next transaction waits under a red dashed ring.
        let alarm = mix(a.alarm, b.alarm)
        if alarm > 0.01, let waiting = chain.first(where: { $0.inBlock < 0.5 }) {
            var ring = canvas
            ring.context.opacity *= alarm * (0.6 + 0.4 * blink)
            ring.stroke(ring.circle(waiting.x, waiting.y, rN + 6), NoahTokens.Colors.failure, 1.5, dash: [3, 3])
            ring.stroke(ring.line(CGPoint(x: WX - 22, y: yRowC), CGPoint(x: NX + s / 2 + 8, y: yRowC)), NoahTokens.Colors.failure.opacity(0.55), 1.2, dash: [2, 4])
        }
        // The fee child: a dot from your on-chain wallet that docks on the anchor.
        let feeAlpha = mix(a.feeAlpha, b.feeAlpha)
        if feeAlpha > 0.01 {
            let start = a.feeAlpha > 0.5 ? a.fee : CGPoint(x: WX - 14, y: yRowC - 6)
            let p = b.feeAlpha > 0.5 ? CGPoint(x: start.x + (b.fee.x - start.x) * u, y: start.y + (b.fee.y - start.y) * u - sin(u.clamped(0, 1) * .pi) * 26) : b.fee
            var dot = canvas
            dot.context.opacity *= feeAlpha
            if case .fee(true, _) = scene.current {
                dot.stroke(dot.circle(p.x, p.y, 3.6), colors.muted, 1.4)
            } else {
                let bumped: Bool = if case .fee(_, true) = scene.current { true } else { false }
                dot.fill(dot.circle(p.x, p.y, bumped ? 4.8 : 3.6), colors.foreground)
            }
        }
        // Your coin: the leaf transaction's output.
        let radius = mix(a.coinRadius, b.coinRadius)
        if coin.alpha > 0.01 {
            var c = canvas
            c.context.opacity *= coin.alpha
            let r = coin.y == yRowC || coin.inBlock > 0.5 && b.lock == 0 ? s * 0.3 : radius
            c.fill(c.circle(coin.x, coin.y, r), coin.inBlock > 0.5 && b.lock == 0 ? colors.foreground : colors.soft)
            c.stroke(c.circle(coin.x, coin.y, r), colors.orange, (r * 0.19).clamped(2, 4.2))
        }
        // The timelock: the coin padlocked, and a bar counting the blocks.
        let lock = mix(a.lock, b.lock)
        if lock > 0.01, let leaf = scene.leaf, let unlock = scene.unlock {
            let open = reduceMotion ? b.open : a.open + (b.open - a.open) * drop
            let R = radius
            var pad = canvas
            pad.context.opacity *= lock * (1 - b.open * 0.7)
            pad.padlockBody(NX + R * 0.72, lockY + R * 0.62 + 4)
            pad.shackle(NX + R * 0.72, lockY + R * 0.62 + 4, open: open)
            if b.open > 0.5 {
                if reduceMotion {
                    canvas.stroke(canvas.circle(NX, lockY, R + 7), colors.orange.opacity(0.35 * lock), 1.5)
                } else {
                    for i in 0..<2 {
                        let q = ((now - Double(i) * 0.5).truncatingRemainder(dividingBy: 2.2)) / 2.2
                        canvas.stroke(canvas.circle(NX, lockY, R + 4 + q * 30), colors.orange.opacity(0.55 * (1 - q) * lock), 1.5)
                    }
                }
            }
            let x0 = 24.0, x1 = W - 24, y = yRowC - 2, width = x1 - x0, bar = mix(a.bar, b.bar)
            var track = canvas
            track.context.opacity *= lock
            track.fill(Path(roundedRect: CGRect(x: x0, y: y, width: width, height: 6), cornerRadius: 3), colors.soft)
            if bar > 0 { track.fill(Path(roundedRect: CGRect(x: x0, y: y, width: max(6, width * bar), height: 6), cornerRadius: 3), colors.foreground) }
            for hour in 1..<24 {
                track.fill(Path(CGRect(x: x0 + width * Double(hour) / 24 - 0.5, y: y + 10, width: 1, height: hour % 6 == 0 ? 6 : 3)), colors.muted.opacity(0.5))
            }
            if bar < 1 {
                let hx = x0 + width * bar
                track.fill(track.circle(hx, y + 3, 4.5), colors.orange.opacity(blink))
                track.text(scene.tip.formatted(.number.locale(locale)), hx.clamped(x0 + 22, x1 - 22), y - 10, size: 10.5, weight: .medium, color: colors.foreground)
            }
            track.text(leaf.formatted(.number.locale(locale)), x0 + 22, y + 30, size: 10.5, weight: .medium, color: colors.muted)
            track.text(unlock.formatted(.number.locale(locale)), x1 - 22, y + 30, size: 10.5, weight: .medium, color: bar >= 1 ? colors.foreground : colors.muted)
        }
        // Your on-chain wallet: fees leave it, the claim lands in it.
        let walletAlpha = mix(a.walletAlpha, b.walletAlpha)
        if walletAlpha > 0.01 {
            let center = CGPoint(x: mix(a.wallet.x, b.wallet.x), y: mix(a.wallet.y, b.wallet.y)), scale = mix(a.walletScale, b.walletScale)
            canvas.layer(center: center, scale: scale, opacity: walletAlpha) { badge in
                badge.fill(badge.circle(center.x, center.y, 16), colors.background)
                badge.stroke(badge.circle(center.x, center.y, 16), alarm > 0.5 ? NoahTokens.Colors.failure : colors.hairline, 1.5)
                badge.symbol("link", center.x, center.y, size: 15, color: colors.foreground)
            }
            if b.finale < 0.5, let label = scene.wallet {
                canvas.text(label, WX - 14, yRowC + 31, size: 10.5, weight: .medium,
                            color: (alarm > 0.5 ? NoahTokens.Colors.failure : colors.muted).opacity(walletAlpha))
            }
            if alarm > 0.5 {
                canvas.fill(canvas.circle(WX + 12, yRowC - 12, 6), NoahTokens.Colors.failure)
                canvas.text("!", WX + 12, yRowC - 12, size: 10, weight: .bold, color: .white, display: false)
            }
            let finale = mix(a.finale, b.finale)
            if finale > 0.01 {
                let r = 16 * scale + 7
                var ring = Path()
                ring.addArc(center: center, radius: r, startAngle: .degrees(-90), endAngle: .degrees(-90 + 360 * finale), clockwise: false)
                canvas.context.stroke(ring, with: .color(colors.orange), lineWidth: 2.5)
                canvas.layer(center: CGPoint(x: center.x + 19 * scale, y: center.y - 19 * scale), scale: drop.clamped(0, 1.2), opacity: finale) { mark in
                    mark.checkBadge(center.x + 19 * scale, center.y - 19 * scale)
                }
            }
        }
    }
}

private extension Double {
    func clamped(_ low: Double, _ high: Double) -> Double { Swift.min(high, Swift.max(low, self)) }
}

extension Array {
    subscript(safe index: Int) -> Element? { indices.contains(index) ? self[index] : nil }
}
