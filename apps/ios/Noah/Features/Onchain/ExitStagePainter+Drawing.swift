import SwiftUI

/// The painter's drawing: the tree, its nodes, the coin, the dial and the destination.
extension ExitStagePainter {
    // MARK: Drawing

    /// `inline`: the Canvas draws the moving parts itself (while the picture moves); otherwise overlays do.
    /// When the picture is drawn, whether the Canvas draws the moving parts itself, and whether they move.
    struct Motion { var now: Double; var inline: Bool; var animating: Bool }

    func draw(_ context: GraphicsContext, from: ExitStageScene?, to scene: ExitStageScene, elapsed: Double, motion: Motion) {
        let now = motion.now, inline = motion.inline, animating = motion.animating
        if reduceMotion {
            // Every move becomes a fade between the two stills.
            let u = (elapsed / 0.18).clamped(0, 1)
            if let from, u < 1 {
                var old = context
                old.opacity = 1 - u
                paint(old, ExitStageFrame(from, from, elapsed: 60, reduceMotion: true), from, from, motion: .init(now: now, inline: true, animating: false))
            }
            var new = context
            new.opacity = from == nil ? 1 : u
            paint(new, ExitStageFrame(scene, scene, elapsed: 60, reduceMotion: true), scene, scene, motion: .init(now: now, inline: inline, animating: false))
        } else {
            let a = from ?? scene
            paint(
                context,
                ExitStageFrame(a, scene, elapsed: from == nil ? 60 : elapsed, reduceMotion: false),
                a,
                scene,
                motion: .init(now: now, inline: inline, animating: animating))
        }
    }

    // swiftlint:disable:next function_body_length cyclomatic_complexity
    private func paint(_ context: GraphicsContext, _ f: ExitStageFrame, _ a: ExitStageScene, _ s: ExitStageScene, motion: Motion) {
        let now = motion.now, inline = motion.inline, animating = motion.animating
        guard s.picture != .blank || s.explain else { return }
        let g = geometry, n = s.levels.count, c = colors, L = g.scale
        let canvas = MotionCanvas(context: context, size: CGSize(width: g.width, height: g.height),
                                  viewBox: CGRect(x: 0, y: 0, width: g.width, height: g.height), colors: c)
        let breathe = ExitPulse.breathe(now, animating: animating && inline)
        let (ys, before) = positions(f, from: a, to: s), TX = g.trunk, yC = g.coinY
        let target = ExitStageStill(s), new = labels(s, ys: g.levels(target.mined, lock: target.lock))
        let arrangement = arrange(s, ys: ys, lock: f.lock, labels: new)
        func Y(_ level: Int) -> Double { ys[safe: level] ?? yC }
        func text(_ string: String, _ x: Double, _ y: Double, size: Double, color: Color, strong: Bool = false, anchor: UnitPoint = .leading,
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
                                  with: .color(c.orange.opacity(0.7 * alpha)), style: StrokeStyle(
                                      lineWidth: 1.4,
                                      lineCap: .round,
                                      dash: sibling.other.sent ? [] : [3, 3]))
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
            node(
                canvas,
                p,
                look: NodeLook(level: s.levels[index], mined: f.mined[index], stamp: f.stamp[index], ripple: f.ripple[index], pending: f.pending[index],
                               side: z,
                               pop: min(
                                   1.3,
                                   pop), alpha: alpha, grey: s.stale),
                inline: inline,
                ink: ExitPulseInk(now: now, breathe: breathe, animating: animating && inline, colors: colors))
            if s.fold(at: index)?.kind == .stack, f.mined[index] > 0.9 {
                // The oldest blocks stacked into one: an ink block in layers.
                for offset in [-z / 6, z / 6] {
                    canvas.stroke(
                        canvas.line(CGPoint(x: p.x - z / 2, y: p.y + offset), CGPoint(x: p.x + z / 2, y: p.y + offset)),
                        c.background.opacity(alpha),
                        1)
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
            node(
                canvas,
                g.claimNode,
                look: NodeLook(level: claim, mined: f.claimMined, stamp: f.claimStamp, ripple: f.claimRipple, pending: f.claimSend, side: 13, pop: 1, alpha: 1,
                               grey: false),
                inline: inline,
                ink: ExitPulseInk(now: now, breathe: breathe, animating: animating && inline, colors: colors))
        }
        coin(canvas, f, s, inline: inline, ink: ExitPulseInk(now: now, breathe: breathe, animating: animating && inline, colors: colors))
        dial(canvas, f, s, inline: inline, breathe: breathe)
        destination(canvas, f, s)
        // Labels: the right-hand column says which block each transaction is in, and what's waiting.
        let old = f.fadeIn < 1 ? labels(a, ys: before) : []
        func same(_ x: ExitStageLabel, _ y: ExitStageLabel) -> Bool { x.lines == y.lines && x.y == y.y && x.pill == y.pill }
        drawLabels(
            context,
            old.filter { label in !new.contains { same($0, label) } },
            style: LabelStyle(tree: 1 - f.fadeIn, trailing: 1 - f.fadeIn, inline: true, breathe: breathe, flash: 0))
        drawLabels(
            context,
            new,
            style: LabelStyle(
                tree: f.labels,
                trailing: f.claimSend,
                fade: { label in old.contains { same($0, label) } ? 1 : f.fadeIn },
                inline: inline,
                breathe: breathe,
                flash: f.flash))
    }

    /// How a set of labels fades in and pulses.
    struct LabelStyle {
        var tree: Double
        var trailing: Double
        var fade: (ExitStageLabel) -> Double = { _ in 1 }
        var inline: Bool
        var breathe: Double
        var flash: Double
    }

    private func drawLabels(_ context: GraphicsContext, _ labels: [ExitStageLabel], style: LabelStyle) {
        let tree = style.tree, trailing = style.trailing, fade = style.fade, inline = style.inline, breathe = style.breathe, flash = style.flash
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
    /// How one node looks at this frame: its level and how far each of its moves has run.
    struct NodeLook {
        var level: ExitStageScene.Level
        var mined: Double
        var stamp: Double
        var ripple: Double
        var pending: Double
        var side: Double
        var pop: Double
        var alpha: Double
        var grey: Bool
    }

    private func node(_ canvas: MotionCanvas, _ p: CGPoint, look: NodeLook, inline: Bool, ink: ExitPulseInk) {
        let level = look.level, mined = look.mined, stamp = look.stamp, ripple = look.ripple, pending = look.pending
        let z = look.side, pop = look.pop, alpha = look.alpha, grey = look.grey
        let now = ink.now, breathe = ink.breathe, animating = ink.animating
        guard alpha > 0.01 else { return }
        var c = canvas
        c.context.opacity *= alpha
        let colors = colors
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
            if inline && !grey { ExitPulsePainter.back(
                c.context,
                kind: .square(z),
                center: p,
                ink: ExitPulseInk(now: now, breathe: breathe, animating: animating && pending >= 1, colors: colors),
                strength: pending) }
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
            if level == .needsFunds && inline { ExitPulsePainter.front(
                c.context,
                kind: .alarm(z * 0.95),
                center: p,
                ink: ExitPulseInk(now: now, breathe: breathe, animating: false, colors: colors)) }
        } else {
            let r = (z >= 13 ? 6.2 : z * 0.42) * pop
            c.fill(c.circle(p.x, p.y, r), colors.background)
            c.stroke(c.circle(p.x, p.y, r), colors.orange, 1.6)
        }
    }

    /// Your coin: the leaf transaction's output at the top of the branch; at the claim it travels the stem.
    private func coin(_ canvas: MotionCanvas, _ f: ExitStageFrame, _ s: ExitStageScene, inline: Bool, ink: ExitPulseInk) {
        let now = ink.now, breathe = ink.breathe, animating = ink.animating
        let g = geometry, colors = colors, n = s.levels.count, last = n - 1
        var center = CGPoint(x: g.trunk, y: g.coinY - 5 * f.lift), r = g.coinRadius
        if f.land > 0.001 {
            center = g.stem(f.land.clamped(0, 1))
            r = g.coinRadius + (g.coinRadius * 0.62 - g.coinRadius) * f.land.clamped(0, 1)
        }
        let pending = f.pending[last], mined = f.mined[last], waiting = pending > 0.001 && mined < 1 && s.levels[last] != .needsFunds
        if waiting && inline && !s.stale {
            ExitPulsePainter.back(
                canvas.context,
                kind: .coin(r),
                center: center,
                ink: ExitPulseInk(now: now, breathe: breathe, animating: animating && pending >= 1, colors: colors),
                strength: pending)
        }
        if s.picture == .unlocked && inline && f.claimSend < 0.5 {
            ExitPulsePainter.back(
                canvas.context,
                kind: .glow(r),
                center: center,
                ink: ExitPulseInk(now: now, breathe: breathe, animating: false, colors: colors),
                strength: f.open.clamped(0, 1))
        }
        if waiting && (inline || s.stale) {
            canvas.context.stroke(
                canvas.circle(center.x, center.y, r + 5),
                with: .color((s.stale ? colors.muted : colors.orange).opacity(pending * (s.stale ? 0.6 : 0.6 + 0.4 * breathe))),
                style: StrokeStyle(lineWidth: 1.6, dash: [3, 2.6], dashPhase: animating && !s.stale ? ExitPulsePainter.march(now, 5.6) : 0))
        }
        if s.levels[last] == .needsFunds && inline {
            ExitPulsePainter.front(
                canvas.context,
                kind: .alarm(r + 5),
                center: center,
                ink: ExitPulseInk(now: now, breathe: breathe, animating: false, colors: colors))
        }
        let fade = 1 - ((f.land - 0.85) / 0.13).clamped(0, 1)
        if f.land < 0.98 {
            let radius = r * (1 + 0.18 * (1 - f.stamp[last]) * (mined > 0 ? 1 : 0))
            canvas.fill(canvas.circle(center.x, center.y, radius), (mined > 0.5 ? colors.foreground : colors.soft).opacity(fade))
            canvas.stroke(canvas.circle(center.x, center.y, radius), colors.orange.opacity(fade), (radius * 0.19).clamped(1.6, 4))
        }
        // Its old spot, once it has gone: a dashed "spent" ring.
        if f.land > 0.5 {
            canvas.context.stroke(
                canvas.circle(g.trunk, g.coinY, g.coinRadius * 0.8),
                with: .color(colors.muted.opacity(0.45 * ((f.land - 0.5) * 2).clamped(0, 1) * f.tree)),
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
        let g = geometry, colors = colors, R = g.coinRadius + 10, alpha = f.lock * (1 - f.stem), cx = g.trunk, cy = g.coinY
        let start = -Double.pi / 2, ticks = Self.ticks(s.delta), done = (f.dial * Double(s.delta)).rounded() / Double(max(1, s.delta))
        var c = canvas
        c.context.opacity *= alpha
        c.stroke(c.circle(cx, cy, R), colors.hairline, 2.2)
        for tick in 0..<ticks.count {
            let angle = start + Double(tick) * 2 * .pi / Double(ticks.count), r0 = R + 3.5, r1 = R + (ticks.hours && tick.isMultiple(of: 6) ? 8 : 5.5)
            c.stroke(c.line(CGPoint(x: cx + cos(angle) * r0, y: cy + sin(angle) * r0), CGPoint(x: cx + cos(angle) * r1, y: cy + sin(angle) * r1)),
                     colors.muted.opacity(Double(tick) / Double(ticks.count) < done ? 0.75 : 0.35), 1)
        }
        if done > 0 {
            var arc = Path()
            arc.addArc(
                center: CGPoint(x: cx, y: cy),
                radius: R,
                startAngle: .radians(start),
                endAngle: .radians(start + 2 * .pi * min(1, done) * f.lock),
                clockwise: false)
            c.context.stroke(arc, with: .color(f.open > 0.001 ? colors.orange : colors.foreground), style: StrokeStyle(lineWidth: 2.6, lineCap: .round))
        }
        if done < 1 && f.lock >= 0.999 && inline {
            let angle = start + 2 * .pi * done
            c.fill(c.circle(cx + cos(angle) * R, cy + sin(angle) * R, 3.4), colors.orange.opacity(0.55 + 0.45 * breathe))
        }
        let px = cx + g.coinRadius * 0.78, py = cy + g.coinRadius * 0.72
        canvas.layer(center: CGPoint(x: px, y: py + 7), opacity: 1 - f.stem, scale: 15 / 24 * max(0, f.padlock)) { pad in
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
        let g = geometry, colors = colors, claiming = s.claiming, alarm = s.levels.contains(.needsFunds)
        let alpha = claiming ? 1 : f.wallet
        guard alpha > 0.01 else { return }
        let center = CGPoint(x: g.walletX, y: g.walletY), grow = 1 + 0.12 * f.destPop.clamped(0, 1.2)
        canvas.layer(center: center, opacity: alpha * (1 - f.toOther), scale: grow) { badge in
            badge.fill(badge.circle(center.x, center.y, 15), colors.background)
            badge.stroke(badge.circle(center.x, center.y, 15), alarm ? NoahTokens.Colors.failure : colors.hairline, 1.5)
            badge.symbol("iphone", center.x, center.y, size: 15, color: colors.foreground)
        }
        if f.toOther > 0.01 {
            canvas.layer(center: center, opacity: f.toOther, scale: grow) { badge in
                badge.fill(badge.circle(center.x, center.y, 15), colors.background)
                badge.stroke(badge.circle(center.x, center.y, 15), colors.hairline, 1.5)
                badge.symbol("wallet.bifold", center.x, center.y, size: 14, color: colors.foreground)
            }
        }
        if f.destRing > 0.001 {
            var ring = Path()
            ring.addArc(
                center: center,
                radius: 20 * grow,
                startAngle: .degrees(-90),
                endAngle: .degrees(-90 + 360 * f.destRing.clamped(0, 1)),
                clockwise: false)
            canvas.context.stroke(ring, with: .color(colors.orange), lineWidth: 2.2)
        }
        if f.destPop > 0.001 {
            canvas.layer(center: CGPoint(x: center.x + 15, y: center.y - 15), opacity: 1, scale: 0.9 * f.destPop.clamped(0, 1.2)) { mark in
                mark.checkBadge(center.x + 15, center.y - 15)
            }
        }
        if alarm {
            canvas.fill(canvas.circle(center.x + 12, center.y - 12, 6.5), NoahTokens.Colors.failure)
            canvas.context.draw(
                Text(verbatim: "!").font(.system(size: 10, weight: .bold)).foregroundColor(.white),
                at: CGPoint(x: center.x + 12, y: center.y - 12))
        }
        var layer = canvas.context
        layer.opacity *= alpha
        for (index, line) in destinationLines(s, landed: f.land > 0.9).enumerated() {
            layer.draw(Text(verbatim: line.text).font(ExitStageText.font(line.size, strong: line.strong))
                .foregroundColor(index == 0 ? (claiming ? colors.foreground : colors.muted) : alarm ? NoahTokens.Colors.failure : claiming ? colors
                    .muted : colors.foreground),
                at: CGPoint(x: g.width - 12, y: line.y), anchor: .trailing)
        }
    }

    /// The two lines under the phone's (or other wallet's) badge: what it is, then the balance, the address or what
    /// landed. Right of the "Bitcoin" label, however long the translation.
    struct DestinationLine { var text: String; var size: Double; var strong: Bool; var y: Double }
    func destinationLines(_ s: ExitStageScene, landed: Bool) -> [DestinationLine] {
        let g = geometry, L = g.scale, claiming = s.claiming
        // While claiming the lines are a little larger, where they still fit above the picture's bottom.
        let scale = claiming && g.ground + 24 * L * 1.1 + 11 * L * 1.1 * 0.625 <= g.height - 1 ? 1.1 : 1.0
        let other: String? = if case .other(let address) = s.destination { address } else { nil }
        let first = !claiming ? tr("On-chain") : s.destination == .phone ? tr("This phone") : tr("Another wallet")
        let second = claiming ? (s.destination != .phone ? other : landed ? s.received ?? s.balance : s.balance) : s.balance
        let room = g.width - 12 - (14 + ExitStageText.width(tr("Bitcoin"), size: 10.5 * L) + 12)
        var lines = [DestinationLine(text: first, size: Self.shrunk(first, 10.5 * L * scale, room: room, strong: claiming), strong: claiming,
                                     y: g.ground + 11 * L)]
        if let second {
            lines.append(DestinationLine(
                text: second,
                size: Self.shrunk(second, 11 * L * scale, room: room, strong: true),
                strong: true,
                y: min(g.ground + 24 * L * scale, g.height - 11 * L * scale * 0.625 - 1)))
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
            let old = f.origin[safe: index].flatMap(\.self).flatMap { a.levels[safe: $0] }
            switch (old, level) {
            case (_, .pending(let theirs, let bumped)):
                if bumped, case .pending(_, false)? = old { fly(index + 1, u: 1, radius: 3.4, hollow: false, alpha: f.oldFee * f.tree) }
                fly(index + 1, u: f.fly, radius: bumped ? 4.8 : 3.4, hollow: theirs, alpha: f.wallet * (1 - f.mined[index]))
            case (.pending(let theirs, let bumped), .mined):
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
