import SwiftUI
import UIKit

/// Where the exit's picture puts things (exit-v2-notes.md, "The picture"; prototype `heroGeo`,
/// `levelYs`, `heroLabels`): your branch of the round's tree standing on a "Bitcoin" ground line,
/// the round's block at its foot and your coin at the top. Pure, so the tests read the numbers the
/// painter draws.
struct ExitStageGeometry: Equatable {
    let width: Double
    let height: Double
    /// Dynamic Type for the picture's labels, capped at 1.25.
    let scale: Double
    let wide: Bool
    /// Transactions on the path; the last one's output is your coin.
    let count: Int
    /// A transaction's square: 14 pt where the levels have 22 pt or more, else 11, and smaller still on
    /// a long path so every level keeps its spacing with the timelock's dial reserved (7 pt at least).
    private(set) var side = 14.0

    init(size: CGSize, scale: Double = 1, wide: Bool = false, count: Int = 4) {
        width = size.width
        height = size.height
        self.scale = min(1.25, max(1, scale))
        self.wide = wide
        self.count = max(1, count)
        var side = room / Double(self.count) < 22 ? 11.0 : 14.0
        while side > 7, (1...self.count).reduce(0, { $0 + minimumGap($1, side: side, reserve: coinReserve(1)) }) > room { side -= 1 }
        self.side = side
    }

    var ground: Double { (height - 24 - 13 * scale).rounded() }
    let roundSide = 22.0
    /// Level 0: the round's block, on the ground.
    var roundY: Double { ground - roundSide / 2 }
    var trunk: Double { (width * (wide ? 0.40 : 0.34)).rounded() }
    var labelX: Double { trunk + 46 }
    var coinRadius: Double { (width * 0.045).clamped(15, 19).rounded() }
    /// Level `count`: your coin, on the Ark line.
    var coinY: Double { (12 + coinRadius + 11).rounded() }
    var walletX: Double { width - 32 }
    var walletY: Double { ground - 21 }
    var room: Double { roundY - coinY }
    /// Sideways spacing of the other people's stubs.
    var spread: Double { min(26, width * 0.068) }
    /// What every two drawn elements keep between them.
    static let clearance = 2.0

    /// The same picture for a path of `count` transactions.
    func with(count: Int) -> Self { Self(size: CGSize(width: width, height: height), scale: scale, wide: wide, count: count) }

    /// The most transactions that can be drawn below your coin with every level at full spacing: 7 pt nodes,
    /// the timelock's dial reserved. A longer path is folded to fit (`ExitStageScene.folded`).
    var nodeCapacity: Int {
        let bottom = roundSide / 2 + 3.5 + Self.clearance, top = 3.5 + coinReserve(1) + Self.clearance
        return max(1, Int(((room - bottom - top) / (7 + Self.clearance)).rounded(.down)) + 1)
    }

    /// How many transactions a path draws below your coin at most: the deep tree's eight (nine transactions), or
    /// `nodeCapacity` where fewer fit. Longer paths fold, so their levels stay far enough apart to read.
    var foldCapacity: Int { min(nodeCapacity, 8) }

    /// The geometry for `scene` in `size`, and the scene as drawn there: folded to `foldCapacity` when its path is longer.
    static func fitting(_ scene: ExitStageScene, size: CGSize, scale: Double = 1, wide: Bool = false) -> (geometry: Self, scene: ExitStageScene) {
        let drawn = scene.folded(nodes: Self(size: size, scale: scale, wide: wide, count: scene.levels.count).foldCapacity)
        return (Self(size: size, scale: scale, wide: wide, count: drawn.levels.count), drawn)
    }

    /// The room your coin keeps around it: its dashed ring, or the timelock's dial while it's locked (`lock`, 0…1).
    func coinReserve(_ lock: Double = 0) -> Double { coinRadius + 5 + 13 * lock.clamped(0, 1) }

    /// The least gap `level` (between levels `level - 1` and `level`) can be: half of each neighbour, the
    /// round's block, a node or your coin with its reserve, and the clearance, so the final link always shows.
    func minimumGap(_ level: Int, side: Double, reserve: Double) -> Double {
        (level == 1 ? roundSide / 2 : side / 2) + (level == count ? reserve : side / 2) + Self.clearance
    }
    var fontSize: Double { 11.5 * scale }
    var subSize: Double { 10.5 * scale }
    var lineHeight: Double { 14 * scale }

    /// Each level's y, root first: 0 is the round's block, `count` your coin. `mined[j]` is how far
    /// transaction j is in a block (0…1); `lock` how far the timelock's dial is drawn. When a long path
    /// doesn't fit at 26 pt, the gaps between confirmed transactions shrink, like a pile of blocks; the
    /// waiting one and the coin never do. No gap goes below its `minimumGap`: the room for your coin and
    /// the final link comes first, then the rest is shared.
    func levels(_ mined: [Double], lock: Double = 0) -> [Double] {
        let tight = room / Double(count) >= 26 ? 1 : 0.42, side = side, reserve = coinReserve(lock)
        func done(_ level: Int) -> Double { level == 0 ? 1 : mined[safe: level - 1] ?? 0 }
        let weights = (1...count).map { 1 + (tight - 1) * min(done($0 - 1), done($0)) }
        let gaps = Self.share(room, weights: weights, minimums: (1...count).map { minimumGap($0, side: side, reserve: reserve) })
        var ys = [roundY]
        for gap in gaps { ys.append(ys[ys.count - 1] - gap) }
        ys[count] = coinY
        return ys
    }

    /// `total` shared by `weights`, each share at least its minimum: shares that fall short take their
    /// minimum and the rest is shared again. When the minimums don't fit, in proportion to them.
    static func share(_ total: Double, weights: [Double], minimums: [Double]) -> [Double] {
        let need = minimums.reduce(0, +)
        guard need < total else { return minimums.map { $0 * total / max(need, 1) } }
        var fixed = Set<Int>()
        while true {
            let free = weights.indices.filter { !fixed.contains($0) }
            let left = total - fixed.reduce(0) { $0 + minimums[$1] }, sum = free.reduce(0) { $0 + weights[$1] }
            let short = free.filter { left * weights[$0] / sum < minimums[$0] }
            guard short.isEmpty else { fixed.formUnion(short); continue }
            return weights.indices.map { fixed.contains($0) ? minimums[$0] : left * weights[$0] / sum }
        }
    }

    /// The other people's stubs at transaction `level` (1…count-1): three short faint branches,
    /// each ending in a small ring, or none where the levels are packed tighter than 20 pt. Each
    /// of your other exits branching here takes one stub's place.
    func stubs(_ ys: [Double], level: Int, others: Int = 0) -> [CGPoint] {
        guard level >= 1, level < count, let y = ys[safe: level], let up = ys[safe: level + 1], y - up >= 20 else { return [] }
        let length = min(max(10, y - up) * 0.5, 22)
        let tips: [(dx: Double, length: Double)] = [(-0.75, 1), (-1.35, 0.8), (0.7, 0.85)]
        return tips.indices.filter { $0 >= min(2, others) }.map { CGPoint(x: trunk + tips[$0].dx * spread, y: y - length * tips[$0].length - 3) }
    }

    /// Where your `index`th other exit branching at `level` sits (up to two per level). The second keeps a
    /// line clear of the first, for the first's amount; on a short path a branch would reach above the
    /// picture, so it stops at `otherTop`.
    func other(_ ys: [Double], level: Int, index: Int) -> CGPoint {
        let y = ys[safe: level] ?? roundY, up = ys[safe: level + 1] ?? coinY, gap = max(12, y - up)
        let first = y - gap * 0.95
        let height = index == 0 ? first : min(y - gap * 1.5, first - 14)
        return CGPoint(x: trunk + (index == 0 ? -1.15 : -2.2) * spread, y: max(otherTop, height))
    }

    /// The highest another exit's coin goes: room above for its 6 pt radius and its amount beside it.
    var otherTop: Double { 6 + 6 * scale }

    // swiftlint:disable discouraged_optional_collection - nil: the caption doesn't fit
    /// The caption under the lowest stubs, left of the trunk: its lines, or nil when it doesn't fit.
    func caption(_ words: String, _ ys: [Double], width measure: (String) -> Double) -> [String]? {
        // swiftlint:enable discouraged_optional_collection
        guard count > 1, let lowest = ys[safe: 1] else { return nil }
        let right = trunk - roundSide / 2 - 9, maxWidth = right - 14, lineHeight = 12.5 * scale
        var lines: [String] = [], current = ""
        for word in words.split(separator: " ").map(String.init) {
            let next = current.isEmpty ? word : current + " " + word
            if measure(next) <= maxWidth || current.isEmpty { current = next } else { lines.append(current); current = word }
        }
        if !current.isEmpty { lines.append(current) }
        let top = lowest + 9, room = (ground - roundSide - 5) - top
        guard Double(lines.count) * lineHeight <= room, lines.allSatisfy({ measure($0) <= maxWidth }) else { return nil }
        return lines
    }

    /// The claim's stem, from your coin to the destination badge (a cubic, sampled for the coin's travel).
    func stem(_ u: Double) -> CGPoint {
        let p0 = CGPoint(x: trunk + coinRadius + 3, y: coinY), p3 = CGPoint(x: walletX, y: walletY - 19)
        let p1 = CGPoint(x: walletX - 6, y: coinY), p2 = CGPoint(x: walletX, y: coinY + (p3.y - coinY) * 0.3)
        let a = 1 - u
        return CGPoint(x: a * a * a * p0.x + 3 * a * a * u * p1.x + 3 * a * u * u * p2.x + u * u * u * p3.x,
                       y: a * a * a * p0.y + 3 * a * a * u * p1.y + 3 * a * u * u * p2.y + u * u * u * p3.y)
    }

    /// The claim transaction's node on the stem.
    var claimNode: CGPoint { stem(0.42) }

    /// How wide a label in the right-hand column can be between `top` and `bottom`: to the canvas's
    /// edge, or short of the phone's badge (and, while the Ark line shows, the server's) where it would
    /// run into it, leaving a pill's 8 pt and the clearance.
    func column(top: Double, bottom: Double, server: Bool = false) -> Double {
        var right = width - 12
        if bottom > walletY - 20 && top < walletY + 18 { right = min(right, walletX - 15 - Self.clearance - 9) }
        if server && bottom > coinY - 16 && top < coinY + 16 { right = min(right, width - 44 - Self.clearance - 9) }
        return right - labelX
    }

    /// How wide the claim's label can be, right-aligned at `x`: it stays right of your coin.
    func claimColumn(_ x: Double) -> Double { x - (trunk + coinRadius + 8) }

    /// Where labels can go: below the top and above the ground line.
    var labelBounds: ClosedRange<Double> { 2...(ground - 3) }
}

/// A drawn element's clearance outline (a box, or a circle), so the picture can keep its elements apart.
struct ExitStageShape: Equatable {
    enum Kind: Equatable { case round, node, coin, claimNode, badge, server, text, label, brace, stub, sibling, amount, caption }
    var kind: Kind
    /// Parts of one element (a badge and its alarm dot, a label and its bracket) may touch each other.
    var group: String
    var frame: CGRect
    var circle = false

    static func box(_ kind: Kind, _ group: String, _ frame: CGRect) -> Self { .init(kind: kind, group: group, frame: frame) }
    static func circle(_ kind: Kind, _ group: String, _ center: CGPoint, _ radius: Double) -> Self {
        .init(kind: kind, group: group, frame: CGRect(x: center.x - radius, y: center.y - radius, width: 2 * radius, height: 2 * radius), circle: true)
    }

    /// Whether the two come closer than `margin`.
    func overlaps(_ other: Self, margin: Double = 0) -> Bool {
        switch (circle, other.circle) {
        case (true, true):
            return hypot(frame.midX - other.frame.midX, frame.midY - other.frame.midY) < frame.width / 2 + other.frame.width / 2 + margin
        case (true, false): return Self.near(self, other.frame, margin)
        case (false, true): return Self.near(other, frame, margin)
        case (false, false): return frame.insetBy(dx: -margin / 2, dy: -margin / 2).intersects(other.frame.insetBy(dx: -margin / 2, dy: -margin / 2))
        }
    }

    private static func near(_ circle: Self, _ box: CGRect, _ margin: Double) -> Bool {
        let x = min(max(circle.frame.midX, box.minX), box.maxX), y = min(max(circle.frame.midY, box.minY), box.maxY)
        return hypot(circle.frame.midX - x, circle.frame.midY - y) < circle.frame.width / 2 + margin
    }
}

/// Where the picture's optional elements go once everything required has its room: the other people's
/// stubs, your other exits' coins and amounts, and the caption. Anything that would collide moves or is
/// left out, amounts first, then your other exits' coins, then stubs.
struct ExitStageArrangement {
    struct Stub: Equatable {
        var level: Int
        var tip: CGPoint
    }

    struct Sibling: Equatable {
        var other: ExitStageScene.Other
        var index: Int
        var coin: CGPoint
        /// Its amount's text size; nil when there's no room for it.
        var amount: Double?
    }

    var stubs: [Stub] = []
    var siblings: [Sibling] = []
    // swiftlint:disable:next discouraged_optional_collection - nil: the caption doesn't fit
    var caption: [String]?
    /// The caption's first line.
    var captionY = 0.0
    /// Every drawn element's outline; no two of different groups overlap.
    var shapes: [ExitStageShape] = []
}

/// One label in the picture's right-hand column (or, for the claim, beside its node).
struct ExitStageLabel: Equatable {
    enum Tone: Equatable { case strong, ink, muted, failure, failureStrong }
    enum Pill: Equatable { case orange, grey, failure }
    struct Line: Equatable {
        var text: String
        var tone = Tone.muted
    }

    var y: Double
    var lines: [Line]
    var priority: Double
    var pill: Pill?
    /// The first line's share of `lines` once wrapped: drawn larger, inside the pill.
    var head = 1
    /// One line that shrinks to fit (to 75 % at most) rather than wrapping; `size` is how much.
    var shrinks = false
    var size = 1.0
    /// Where this label can go instead when it doesn't fit here, in order.
    var fallbacks: [Self] = []
    /// A confirmed transaction's block: labels that don't fit fold into one range.
    var block: UInt32?
    /// A stack of blocks' oldest block, folding with the rest.
    var first: UInt32?
    /// Right-aligned at this x; nil for the label column.
    var trailing: Double?
    /// The explainer's bracket, from y to y.
    var brace: ClosedRange<Double>?
    /// Set by `place`.
    var top = 0.0
    var bottom = 0.0
}

enum ExitStageLabels {
    /// `text` in lines no wider than `width`, broken between words, and inside a word only when the
    /// word alone is too wide.
    static func wrap(_ text: String, width: Double, measure: (String) -> Double) -> [String] {
        var lines: [String] = [], current = ""
        for word in text.split(separator: " ").map(String.init) {
            let next = current.isEmpty ? word : current + " " + word
            if measure(next) <= width { current = next; continue }
            if !current.isEmpty { lines.append(current) }
            current = ""
            for character in word {
                if !current.isEmpty, measure(current + String(character)) > width { lines.append(current); current = "" }
                current.append(character)
            }
        }
        if !current.isEmpty || lines.isEmpty { lines.append(current) }
        return lines
    }

    /// Lays the labels out by priority so none overlap: the waiting transaction and the claim first,
    /// then your coin, the round and the latest block. Each stays within `bounds`, at its wrapped
    /// height. Confirmed blocks that don't fit fold into one range ("Blocks 921,346–921,349") where it
    /// fits; `fit` wraps that range like the rest.
    static func place(_ items: [ExitStageLabel], lineHeight: Double, scale: Double, bounds: ClosedRange<Double> = -.infinity ... .infinity,
                      fit: (ExitStageLabel) -> ExitStageLabel = \.self, range: (UInt32, UInt32) -> String) -> [ExitStageLabel] {
        let order = items.indices.sorted { items[$0].priority != items[$1].priority ? items[$0].priority > items[$1].priority : $0 < $1 }
        var placed: [ExitStageLabel] = [], skipped: [ExitStageLabel] = []
        func clash(_ top: Double, _ bottom: Double, trailing: Bool, margin: Double) -> Bool {
            placed.contains { ($0.trailing != nil) == trailing && !(bottom + margin < $0.top || top - margin > $0.bottom) }
        }
        func span(_ item: inout ExitStageLabel) {
            let height = Double(item.lines.count) * lineHeight + (item.pill == nil ? 0 : 5 * scale)
            item.y = min(max(item.y, bounds.lowerBound + lineHeight / 2), bounds.upperBound - height + lineHeight / 2)
            item.top = item.y - lineHeight / 2
            item.bottom = item.top + height
        }
        for index in order {
            var item = items[index]
            span(&item)
            if clash(item.top, item.bottom, trailing: item.trailing != nil, margin: 3) {
                let fallback = item.fallbacks.map { var label = $0; span(&label); return label }
                    .first { !clash($0.top, $0.bottom, trailing: $0.trailing != nil, margin: 3) }
                if let fallback { placed.append(fallback) } else if item.block != nil { skipped.append(item) }
                continue
            }
            placed.append(item)
        }
        // A lone block that didn't fit gets a second chance with a tighter margin, at its whole wrapped height.
        if skipped.count == 1, let single = skipped.first, !clash(single.top, single.bottom, trailing: false, margin: 1) {
            placed.append(single)
            skipped = []
        }
        if !skipped.isEmpty {
            // Blocks without a label of their own fold into one range with the older ones that kept theirs and with a
            // stack's range, so a range always covers every block not labelled on its own; the latest keeps its label.
            // Where no such range fits, no range shows (a stack's alone would undercount).
            let before = placed
            let stack = placed.first { $0.first != nil }
            let alone = placed.filter { $0.block != nil && $0.first == nil && $0.priority < 2 }
            placed.removeAll { $0.first != nil || ($0.block != nil && $0.priority < 2) }
            let folded = skipped + alone + (stack.map { [$0] } ?? [])
            let heights = (folded.compactMap(\.block) + folded.compactMap(\.first)).sorted()
            let middle = folded.map(\.y).reduce(0, +) / Double(folded.count)
            let candidates = (stack.map { [$0.y] } ?? []) + [middle] + folded.map(\.y).sorted { abs($0 - middle) < abs($1 - middle) }
            var found: ExitStageLabel?
            if heights.count > 1, heights[0] != heights[heights.count - 1] {
                let text = range(heights[0], heights[heights.count - 1])
                for y in candidates {
                    var fold = fit(ExitStageLabel(
                        y: y,
                        lines: [.init(text: text, tone: .ink)],
                        priority: 0,
                        block: heights[heights.count - 1],
                        first: heights[0]))
                    span(&fold)
                    if !clash(fold.top, fold.bottom, trailing: false, margin: 3) { found = fold; break }
                }
            }
            if let found { placed.append(found) } else { placed = before.filter { $0.first == nil } }
        }
        return placed
    }
}

/// What keeps moving while a transaction (or the claim) waits for the next block (Approved item 8):
/// a breathing glow, two expanding rings, a marching dashed outline and the "Next block" pill's
/// pulse. Drawn in small overlays at the node, never by the tree's Canvas at rest.
struct ExitPulse: Equatable, Identifiable {
    enum Kind: Equatable {
        /// A transaction in the mempool: rounded-square rings and a dashed square of `side`.
        case square(Double)
        /// Your coin's transaction in the mempool: round rings and a dashed circle around radius r.
        case coin(Double)
        /// The unlocked coin's glow, radius r.
        case glow(Double)
        /// The "Next block" pill's tint and outline.
        case pill(CGSize)
        /// The timelock's tip dot.
        case dot
        /// Needs funds: a red dashed ring of radius r.
        case alarm(Double)
    }

    var id: String
    var kind: Kind
    var center: CGPoint

    /// The overlay's side, room for the rings at their widest.
    var extent: CGSize {
        switch kind {
        case .square(let side): CGSize(width: side * 3.6, height: side * 3.6)
        case .coin(let r): CGSize(width: (r + 5) * 6.8, height: (r + 5) * 6.8)
        case .glow(let r): CGSize(width: r * 5, height: r * 5)
        case .pill(let size): CGSize(width: size.width + 6, height: size.height + 6)
        case .dot: CGSize(width: 12, height: 12)
        case .alarm(let r): CGSize(width: r * 2 + 6, height: r * 2 + 6)
        }
    }

    /// The breath: 0…1 over 1.6 s. Reduce Motion and paused pulses hold 0.6.
    static func breathe(_ now: Double, animating: Bool) -> Double {
        animating ? 0.5 + 0.5 * sin(now * 2 * .pi / 1.6) : 0.6
    }

    /// Ring `index`'s progress: two rings 0.8 s apart, each 1.6 s.
    static func ring(_ now: Double, _ index: Int) -> Double {
        (now / 1.6 + Double(index) * 0.5).truncatingRemainder(dividingBy: 1)
    }
}

/// The picture's text: measured with the font the Canvas draws, so pills and the caption fit.
enum ExitStageText {
    static func font(_ size: Double, strong: Bool) -> Font { .system(size: size, weight: strong ? .semibold : .medium) }

    // swiftlint:disable legacy_objc_type - NSCache keeps object keys and values
    static func width(_ text: String, size: Double, strong: Bool = false) -> Double {
        let key = "\(strong ? 1 : 0)|\(size)|\(text)" as NSString
        if let known = widths.object(forKey: key) { return known.doubleValue }
        let font = UIFont.systemFont(ofSize: size, weight: strong ? .semibold : .medium)
        let width = ceil((text as NSString).size(withAttributes: [.font: font]).width)
        widths.setObject(width as NSNumber, forKey: key)
        return width
    }

    /// Measured widths: a moving picture lays its labels out every frame. NSCache is thread-safe.
    private nonisolated(unsafe) static let widths = NSCache<NSString, NSNumber>()
    // swiftlint:enable legacy_objc_type
}

extension Double {
    func clamped(_ low: Double, _ high: Double) -> Double { Swift.min(high, Swift.max(low, self)) }
}
