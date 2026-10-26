import SwiftUI
import UIKit

/// Where the exit's picture puts things (exit-v2-notes.md, "The picture"; prototype `heroGeo`,
/// `levelYs`, `heroLabels`): your branch of the round's tree standing on a "Bitcoin" ground line,
/// the round's block at its foot and your coin at the top. Pure, so the tests read the numbers the
/// painter draws.
struct ExitStageGeometry: Equatable {
    var width: Double
    var height: Double
    /// Dynamic Type for the picture's labels, capped at 1.25.
    var scale = 1.0
    var wide = false
    /// Transactions on the path; the last one's output is your coin.
    var count = 4

    init(size: CGSize, scale: Double = 1, wide: Bool = false, count: Int = 4) {
        width = size.width
        height = size.height
        self.scale = min(1.25, max(1, scale))
        self.wide = wide
        self.count = max(1, count)
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
    /// A transaction's square; smaller when the levels are packed.
    var side: Double { room / Double(count) < 22 ? 11 : 14 }
    var fontSize: Double { 11.5 * scale }
    var subSize: Double { 10.5 * scale }
    var lineHeight: Double { 14 * scale }

    /// Each level's y, root first: 0 is the round's block, `count` your coin. `mined[j]` is how far
    /// transaction j is in a block (0…1). When a long path doesn't fit at 26 pt, the gaps between
    /// confirmed transactions shrink, like a pile of blocks; the waiting one and the coin never do.
    func levels(_ mined: [Double]) -> [Double] {
        let tight = room / Double(count) >= 26 ? 1 : 0.42
        func done(_ level: Int) -> Double { level == 0 ? 1 : mined[safe: level - 1] ?? 0 }
        var weights = [0.0]
        for level in 1...count { weights.append(1 + (tight - 1) * min(done(level - 1), done(level))) }
        let sum = weights.reduce(0, +)
        var ys = [roundY], acc = 0.0
        for level in 1...count {
            acc += weights[level]
            ys.append(roundY - room * acc / sum)
        }
        return ys
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

    /// The caption under the lowest stubs, left of the trunk: its lines, or nil when it doesn't fit.
    func caption(_ words: String, _ ys: [Double], width measure: (String) -> Double) -> [String]? {
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
    /// edge, or to the phone's badge where it would run into it.
    func column(top: Double, bottom: Double) -> Double {
        (bottom > walletY - 18 && top < walletY + 18 ? walletX - 22 : width - 12) - labelX
    }

    /// How wide the claim's label can be, right-aligned at `x`: it stays right of your coin.
    func claimColumn(_ x: Double) -> Double { x - (trunk + coinRadius + 8) }

    /// Where labels can go: below the top and above the ground line.
    var labelBounds: ClosedRange<Double> { 2...(ground - 3) }
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
    var fallbacks: [ExitStageLabel] = []
    /// A confirmed transaction's block: labels that don't fit fold into one range.
    var block: UInt32?
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
                      fit: (ExitStageLabel) -> ExitStageLabel = { $0 }, range: (UInt32, UInt32) -> String) -> [ExitStageLabel] {
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
        if skipped.count > 1 {
            // The older blocks fold together, those placed on their own too; the latest keeps its own label.
            let alone = placed.filter { $0.block != nil && $0.priority < 2 }
            placed.removeAll { $0.block != nil && $0.priority < 2 }
            let folded = skipped + alone, heights = folded.compactMap(\.block).sorted()
            let middle = folded.map(\.y).reduce(0, +) / Double(folded.count)
            let candidates = [middle] + folded.map(\.y).sorted { abs($0 - middle) < abs($1 - middle) }
            let folds = candidates.map { y in
                var fold = fit(ExitStageLabel(y: y, lines: [.init(text: range(heights[0], heights[heights.count - 1]), tone: .ink)], priority: 0))
                span(&fold)
                return fold
            }
            if let fold = folds.first(where: { !clash($0.top, $0.bottom, trailing: false, margin: 3) }) {
                placed.append(fold)
            } else {
                placed += alone
            }
        } else if let single = skipped.first, !clash(single.top, single.bottom, trailing: false, margin: 1) {
            // A lone block gets a second chance with a tighter margin, at its whole wrapped height.
            placed.append(single)
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

    static func width(_ text: String, size: Double, strong: Bool = false) -> Double {
        let font = UIFont.systemFont(ofSize: size, weight: strong ? .semibold : .medium)
        return ceil((text as NSString).size(withAttributes: [.font: font]).width)
    }
}

extension Double {
    func clamped(_ low: Double, _ high: Double) -> Double { Swift.min(high, Swift.max(low, self)) }
}
