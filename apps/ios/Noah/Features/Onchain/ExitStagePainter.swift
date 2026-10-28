import SwiftUI

/// Draws the picture: a still, or two stills mixed while it moves (prototype `drawHero`, `heroLabels`).
struct ExitStagePainter {
    let geometry: ExitStageGeometry
    let colors: MotionColors
    let reduceMotion: Bool
    let locale: Locale

    var language: String { locale.identifier }
    func tr(_ source: String, _ arguments: Any...) -> String {
        arguments.isEmpty ? L10n.text(source, locale: language)
            : String(format: L10n.text(source, locale: language), locale: locale, arguments: arguments.map { String(describing: $0) })
    }
    func height(_ value: UInt32) -> String { value.formatted(.number.locale(locale)) }

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
            let minedCount = scene.levels.prefix(while: \.isMined).count
            var firstUnsent: Int?
            for j in 1...n {
                if [.unsent, .ready].contains(scene.levels[j - 1]) {
                    if firstUnsent == nil { firstUnsent = j }
                } else if let label = levelLabel(scene, index: j, y: Y(j), minedCount: minedCount) {
                    items.append(label)
                }
            }
            if let first = firstUnsent, first != n, scene.picture != .lift {
                // Counting every transaction still to send, folded ones too; beside a later unsent one (up to the "+N"
                // node) when the "Next block" pill takes this one's room.
                var more = ExitStageLabel(
                    y: Y(first),
                    lines: [.init(text: L10n.plural("{n} more to send", count: scene.realCount - scene.real(first - 1), locale: language))],
                    priority: 1.5)
                more.fallbacks = ((first + 1)..<n).map { var label = more; label.y = Y($0); return label }
                items.append(more)
            }
            if let coin = coinLabel(scene) { items.append(coin) }
        } else {
            items.append(claimLabel(scene))
        }
        return place(items, server: [.lift, .canceled].contains(scene.picture))
    }

    /// One transaction's label, for every level but an unsent one.
    private func levelLabel(_ scene: ExitStageScene, index j: Int, y: Double, minedCount: Int) -> ExitStageLabel? {
        let n = scene.levels.count, shared = scene.shared[safe: j - 1] == true ? " " + tr("· shared") : ""
        switch scene.levels[j - 1] {
        case .mined(let block):
            guard !(j == n && scene.locked), let block else { return nil }
            if let stack = scene.fold(at: j - 1) {
                // A stack of the oldest blocks: their range, ahead of the round's caption (which then takes one line).
                guard let heights = stack.heights else { return nil }
                return .init(y: y, lines: [.init(text: tr("Blocks {from}–{to}", height(heights.lowerBound), height(heights.upperBound)) + shared,
                                                 tone: .ink)], priority: j == minedCount ? 2.2 : 2.1, block: heights.upperBound,
                             first: heights.lowerBound)
            }
            return .init(y: y, lines: [.init(text: tr("Block {height}", height(block)) + shared, tone: .ink)],
                         priority: j == minedCount ? 2.2 : 1, block: block)
        case .needsFunds:
            var lines = [ExitStageLabel.Line(text: tr("Needs a fee"), tone: .failureStrong)]
            if let need = scene.need { lines.append(.init(text: tr("add {amount} on-chain", need), tone: .failure)) }
            return .init(y: y, lines: lines, priority: 4, pill: .failure)
        case .pending(let theirs, let bumped):
            var sub = theirs ? tr("sent by someone else") : scene.fee.map { tr("fee {amount}", $0) + shared }
            if scene.stuck > 0 { sub = L10n.plural("{n} blocks without it", count: Int(scene.stuck), locale: language) }
            if bumped { sub = tr("a block came without it") }
            if scene.stale, let checked = scene.checked { sub = tr("last checked at {height}", height(checked)) }
            return .init(y: y, lines: [.init(text: tr("Next block"), tone: .strong)] + (sub.map { [.init(text: $0)] } ?? []),
                         priority: 4, pill: scene.stale ? .grey : .orange)
        case .unsent, .ready:
            return nil
        }
    }

    /// The coin's label: locked until its block, or your VTXO waiting at the top.
    private func coinLabel(_ scene: ExitStageScene) -> ExitStageLabel? {
        let g = geometry, n = scene.levels.count
        if scene.locked, let unlock = scene.unlock {
            let left = unlock > scene.tip ? unlock - scene.tip : 0
            return .init(y: g.coinY, lines: scene.picture == .unlocked
                ? [.init(text: tr("Unlocked"), tone: .strong), .init(text: tr("since block {height}", height(unlock)))]
                : [.init(text: L10n.plural("Locked · {n} blocks", count: Int(left), locale: language), tone: .strong),
                   .init(text: tr("opens at {height}", height(unlock)))], priority: 3.5)
        }
        guard [.unsent, .ready].contains(scene.levels[n - 1]) else { return nil }
        return .init(
            y: g.coinY,
            lines: [.init(text: scene.picture == .canceled ? tr("Back in Ark") : tr("Your VTXO"), tone: .strong)],
            priority: 3.5)
    }

    private func claimLabel(_ scene: ExitStageScene) -> ExitStageLabel {
        let g = geometry
        let node = g.claimNode
        let lines: [ExitStageLabel.Line] = scene.picture == .claimed
            ? [.init(text: scene.claimHeight.map { tr("Block {height}", height($0)) } ?? tr("In a block"), tone: .strong), .init(text: tr("claim"))]
            : [.init(text: tr("Next block"), tone: .strong)] + (scene.claimFee.map { [.init(text: tr("claim fee {amount}", $0))] } ?? [])
        return .init(y: node.y, lines: lines, priority: 4, pill: scene.picture == .claimed ? nil : .orange, trailing: node.x - 16)
    }

    /// `server`: the Ark line and its server show, so labels keep clear of its badge too.
    private func place(_ items: [ExitStageLabel], server: Bool) -> [ExitStageLabel] {
        let fitted = { (label: ExitStageLabel) in fit(label, server: server) }
        return ExitStageLabels.place(items.map(fitted), lineHeight: geometry.lineHeight, scale: geometry.scale, bounds: geometry.labelBounds, fit: fitted) {
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
            ExitStageText.width(
                line.text,
                size: (index < label.head ? g.fontSize : g.subSize) * label.size,
                strong: [.strong, .failureStrong].contains(line.tone))
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
            guard level > 0, let origin = f.origin[safe: level - 1].flatMap(\.self), let from = before[safe: origin + 1] else { return to[level] }
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
        guard s.picture != .blank || s.explain else { return result }
        func Y(_ level: Int) -> Double { ys[safe: level] ?? g.coinY }
        result.shapes = fixedShapes(s, ys: ys, lock: lock, labels: labels)
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
                                           textFrame(sibling.other.amount, at: CGPoint(x: sibling.coin.x - 10, y: sibling.coin.y), size: size, trailing: true))
            guard clear(shape) else { continue }
            result.siblings[index].amount = size
            result.shapes.append(shape)
        }
        // The caption, under the lowest stubs.
        if let caption = caption(ys), clear(caption.shape) {
            result.caption = caption.lines
            result.captionY = caption.y
            result.shapes.append(caption.shape)
        }
        return result
    }

    /// What every still draws: the round, its nodes, your coin, the wallet badge and its lines, the texts and labels.
    private func fixedShapes(_ s: ExitStageScene, ys: [Double], lock: Double, labels: [ExitStageLabel]) -> [ExitStageShape] {
        let g = geometry, n = s.levels.count, L = g.scale
        func Y(_ level: Int) -> Double { ys[safe: level] ?? g.coinY }
        var shapes: [ExitStageShape] = [.box(
            .round,
            "round",
            CGRect(x: g.trunk - g.roundSide / 2, y: g.ground - g.roundSide, width: g.roundSide, height: g.roundSide))]
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
                shapes.append(.box(
                    .text,
                    "badge",
                    textFrame(line.text, at: CGPoint(x: g.width - 12, y: line.y), size: line.size, strong: line.strong, trailing: true)))
            }
        }
        shapes.append(.box(.text, "bitcoin", textFrame(tr("Bitcoin"), at: CGPoint(x: 14, y: g.ground + 12 * L), size: 10.5 * L)))
        if s.explain || [.lift, .canceled].contains(s.picture) {
            shapes.append(.box(.text, "ark", textFrame(tr("Ark"), at: CGPoint(x: 14, y: g.coinY - 10), size: 11 * L)))
            shapes.append(.circle(.server, "server", CGPoint(x: g.width - 30, y: g.coinY), 14))
        }
        for (index, label) in labels.enumerated() {
            shapes.append(.box(.label, "label\(index)", frame(label)))
            if let brace = label.brace { shapes.append(.box(
                .brace,
                "label\(index)",
                CGRect(x: g.trunk + 27, y: brace.lowerBound, width: 5, height: brace.upperBound - brace.lowerBound))) }
        }
        return shapes
    }

    private struct CaptionPlacement { let lines: [String]; let y: Double; let shape: ExitStageShape }

    /// The caption's lines and frame between the round and the first transaction, when it fits at all.
    private func caption(_ ys: [Double]) -> CaptionPlacement? {
        let g = geometry, L = g.scale
        func Y(_ level: Int) -> Double { ys[safe: level] ?? g.coinY }
        if let lines = g.caption(tr("Other people’s coins in this round"), ys, width: { ExitStageText.width($0, size: 10 * L) }) {
            let lineHeight = 12.5 * L, top = Y(1) + 9, room = (g.ground - g.roundSide - 5) - top
            let y0 = top + (room - Double(lines.count) * lineHeight) / 2 + lineHeight / 2, right = g.trunk - g.roundSide / 2 - 9
            let width = lines.map { ExitStageText.width($0, size: 10 * L) }.max() ?? 0
            let shape = ExitStageShape.box(
                .caption,
                "caption",
                CGRect(x: right - width, y: y0 - lineHeight / 2, width: width, height: Double(lines.count) * lineHeight))
            return CaptionPlacement(lines: lines, y: y0, shape: shape)
        }
        return nil
    }

    private func textFrame(_ string: String, at point: CGPoint, size: Double, strong: Bool = false, trailing: Bool = false) -> CGRect {
        let width = ExitStageText.width(string, size: size, strong: strong), height = size * 1.25
        return CGRect(x: trailing ? point.x - width : point.x, y: point.y - height / 2, width: width, height: height)
    }

    // MARK: Pulses

    /// What moves while the still stands: the waiting transaction, coin or claim, its pill, the dial's
    /// tip and a needs-funds ring. Overlays draw them; the Canvas draws them itself only while it moves.
    func pulses(_ scene: ExitStageScene) -> [ExitPulse] {
        guard !scene.explain, scene.picture != .blank else { return [] }
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
}
