import Foundation
@testable import Noah
import SwiftUI
import Testing
import UIKit

/// The v2 picture's rules (docs/native-rewrite/design/exit-v2-notes.md): the tree's geometry and its
/// label layout, the other people's stubs, what breathes and when, the Exits list's option B and the
/// Exits row's subtitle.
struct ExitStageTests {
    static let english = Locale(identifier: "en")
    static let iphone = ExitStageGeometry(size: CGSize(width: 393, height: 300), count: 4)
    static let se = ExitStageGeometry(size: CGSize(width: 375, height: 236), count: 9)

    static func painter(_ geometry: ExitStageGeometry) -> ExitStagePainter {
        ExitStagePainter(geometry: geometry, colors: .theme(.light), reduceMotion: false, locale: english)
    }

    /// Labels in the same column never overlap.
    static func overlapping(_ labels: [ExitStageLabel]) -> Bool {
        labels.indices.contains { i in
            labels.indices.contains { j in
                i < j && (labels[i].trailing != nil) == (labels[j].trailing != nil) && labels[i].top < labels[j].bottom && labels[j].top < labels[i].bottom
            }
        }
    }

    static func texts(_ labels: [ExitStageLabel]) -> [String] { labels.flatMap { $0.lines.map(\.text) } }

    static func mempool(heights: [UInt32], pending: Int, count: Int, fee: String? = "₿1,215") -> ExitStageScene {
        var scene = ExitStageScene(picture: .path, levels: (0..<count).map { index in
            index < heights.count ? .mined(heights[index]) : index == pending ? .pending(theirs: false, bumped: false) : .unsent
        })
        scene.fee = fee
        scene.balance = "₿18,400"
        return scene
    }

    // MARK: Geometry

    @Test func theTreeStandsOnTheGroundWithTheCoinAtTheTop() {
        let g = Self.iphone, ys = g.levels([0, 0, 0, 0])
        #expect(ys.first == g.roundY && ys.last == g.coinY && ys.count == 5)
        // A round's usual four fit at 26 pt or more, so every gap is the same.
        let gaps = zip(ys, ys.dropFirst()).map { $0 - $1 }
        #expect(gaps.allSatisfy { abs($0 - gaps[0]) < 0.001 } && gaps[0] >= 26)
        // As blocks confirm nothing moves: the pile only tightens when the path doesn't fit.
        #expect(g.levels([1, 1, 0, 0]) == ys)
        // Nine transactions on the SE's 236 pt picture take smaller squares, so every level keeps its spacing.
        #expect(g.side == 14 && Self.se.side == 10)
    }

    @Test func aDeepTreeStacksItsConfirmedBlocksAndNeverSqueezesTheWaitingOneOrTheCoin() {
        let g = Self.se, ys = g.levels([1, 1, 1, 1, 1, 0, 0, 0, 0])
        #expect(ys.first == g.roundY && ys.last == g.coinY)
        let gaps = zip(ys, ys.dropFirst()).map { $0 - $1 }, clearance = ExitStageGeometry.clearance
        // Between confirmed transactions: a pile of blocks, as tight as their spacing allows.
        #expect(abs(gaps[0] - (g.roundSide / 2 + g.side / 2 + clearance)) < 0.001)
        #expect(gaps[1..<5].allSatisfy { abs($0 - (g.side + clearance)) < 0.001 })
        // From the last confirmed one up, more room; the coin and its final link keep theirs.
        #expect(gaps[5...].allSatisfy { $0 > g.side + clearance + 3 })
        #expect(gaps[8] >= g.side / 2 + g.coinReserve() + clearance - 0.001)
    }

    @Test func stubsHideWherePackedAndYieldToYourOtherExits() {
        let g = Self.iphone, ys = g.levels([0, 0, 0, 0])
        for level in 1...3 { #expect(g.stubs(ys, level: level).count == 3) }
        // The coin's own transaction has no siblings.
        #expect(g.stubs(ys, level: 4).isEmpty && g.stubs(ys, level: 0).isEmpty)
        // Each of your other exits branching here takes a stub's place.
        #expect(g.stubs(ys, level: 1, others: 1).count == 2 && g.stubs(ys, level: 1, others: 2).count == 1)
        // A deep tree packs its confirmed levels tighter than 20 pt: no stubs there.
        let deep = Self.se.levels([1, 1, 1, 1, 1, 0, 0, 0, 0])
        for level in 1...4 { #expect(Self.se.stubs(deep, level: level).isEmpty, "level \(level)") }
        for level in 5...8 { #expect(Self.se.stubs(deep, level: level).isEmpty == (deep[level] - deep[level + 1] < 20), "level \(level)") }
    }

    @Test func theCaptionShowsOnlyWhereItFits() {
        let words = "Other people's coins in this round"
        let lines = Self.iphone.caption(words, Self.iphone.levels([0, 0, 0, 0])) { Double($0.count) * 4.6 }
        #expect(lines?.joined(separator: " ") == words && (lines?.count ?? 0) <= 2)
        // Too wide for the room left of the trunk, or no room under the lowest stubs (a deep tree on the SE): hidden.
        #expect(Self.iphone.caption(words, Self.iphone.levels([0, 0, 0, 0])) { Double($0.count) * 12 } == nil)
        #expect(Self.se.caption(words, Self.se.levels(Array(repeating: 0, count: 9))) { Double($0.count) * 4.6 } == nil)
        #expect(ExitStageGeometry(size: CGSize(width: 393, height: 300), count: 1).caption(words, [252, 41]) { _ in 1 } == nil)
    }

    @Test func theDialTicksOncePerSixBlocks() {
        #expect(ExitStagePainter.ticks(144) == (24, true))
        #expect(ExitStagePainter.ticks(96) == (16, true))
        #expect(ExitStagePainter.ticks(600) == (48, true))
        // Below 72 blocks: one tick per 6 blocks, no hour reading.
        #expect(ExitStagePainter.ticks(36) == (6, false))
        #expect(ExitStagePainter.ticks(3) == (1, false))
    }

    /// The picture's one rule: no two drawn elements (the round's block, your path's nodes, your coin, the destination,
    /// the server, texts and labels, stubs, your other exits' coins and amounts, the caption) overlap, none leaves the
    /// canvas, and every level keeps its full spacing. Samples path lengths from 1 to 100 (folded where they don't fit)
    /// and the capped explainer; every mined count up to 4 and a sample beyond; every picture; your other exits at every
    /// pair of levels up to 3 transactions (a sample beyond, and for the variants that lay out like another picture); on
    /// the iPhone 17 at its default size and an SE at AX1, in English and Swedish. Named cases keep their own tests.
    @Test func noTwoDrawnElementsOverlap() {
        var problems: [String] = [], checked = 0
        for (size, scale, device) in [(CGSize(width: 393, height: 300), 1.0, "iPhone 17"), (CGSize(width: 375, height: 236), 1.65, "SE AX1")] {
            for locale in ["en", "sv"] {
                func check(_ real: ExitStageScene, _ note: @autoclosure () -> String) {
                    let (g, scene) = ExitStageGeometry.fitting(real, size: size, scale: scale)
                    let painter = ExitStagePainter(geometry: g, colors: .theme(.light), reduceMotion: false, locale: Locale(identifier: locale))
                    let still = ExitStageStill(scene), ys = g.levels(still.mined, lock: still.lock)
                    let labels = painter.labels(scene, ys: ys), shapes = painter.arrange(scene, ys: ys, lock: still.lock, labels: labels).shapes
                    checked += 1
                    if scene.realCount != real.levels.count || scene.levels.count - 1 > max(g.foldCapacity, real.explain ? 7 : 0) {
                        problems.append("\(device) \(locale) \(note()): drew \(scene.levels.count) of \(scene.realCount)")
                    }
                    for level in 1..<ys.count where ys[level - 1] - ys[level] < g.minimumGap(level, side: g.side, reserve: g.coinReserve(still.lock)) - 0.001 {
                        problems.append("\(device) \(locale) \(note()): gap \(level) below its minimum")
                    }
                    if !Self.coversEveryBlock(labels, real) { problems.append("\(device) \(locale) \(note()): a range undercounts") }
                    for (index, a) in shapes.enumerated() {
                        if !CGRect(origin: .zero, size: size)
                            .contains(a.frame) { problems.append("\(device) \(locale) \(note()): \(a.kind) \(a.group) leaves the canvas") }
                        for b in shapes[(index + 1)...] where a.group != b.group && a.overlaps(b) {
                            problems.append("\(device) \(locale) \(note()): \(a.kind) \(a.group) overlaps \(b.kind) \(b.group)")
                        }
                    }
                }
                for count in [1, 2, 3, 4, 5, 6, 7, 8, 9, 100] { check(.explainer(count: count), "explainer \(count)") }
                for count in [1, 2, 3, 4, 6, 9, 12, 20, 100] {
                    // Your other exits: none, or two at every pair of levels (a sample on longer paths).
                    let pairs: [(Int, Int)?] = count <= 3 ? [nil] + (1..<max(1, count)).flatMap { i in (i..<count).map { Optional((i, $0)) } }
                        : [nil, (1, 1), (1, count - 1), (count / 2, count / 2), (count - 2, count - 1)]
                    let mineds = count <= 4 ? Array(0...count) : Array(Set([0, 1, count / 2, count - 1, count])).sorted()
                    for mined in mineds {
                        var scenes: [(ExitStageScene, String)] = []
                        func scene(_ picture: ExitProgress.Picture, waiting: ExitStageScene.Level? = .pending(theirs: false, bumped: false)) -> ExitStageScene {
                            var scene = ExitStageScene(picture: picture, levels: (0..<count).map { index in
                                index < mined ? .mined(921_346 + UInt32(index)) : index == mined ? waiting ?? .unsent : .unsent
                            })
                            (scene.fee, scene.need, scene.balance, scene.tip) = ("12 345 sats", "3 765 sats", "18 400 sats", 921_394)
                            (scene.leaf, scene.unlock, scene.claimFee, scene.claimHeight) = (921_349, 921_493, "262 sats", 921_494)
                            return scene
                        }
                        scenes.append((scene(.path), "waiting"))
                        if mined < count {
                            scenes.append((scene(.path, waiting: .needsFunds), "needs funds"))
                            // Sent by someone else lays out as waiting; on short paths it's checked too.
                            if count <= 9 { scenes.append((scene(.path, waiting: .pending(theirs: true, bumped: true)), "theirs")) }
                        }
                        scenes.append((scene(.canceled, waiting: nil), "canceled"))
                        if mined == 0 { scenes.append((scene(.lift, waiting: nil), "lift")) }
                        if mined == count {
                            for picture in [ExitProgress.Picture.lock, .unlocked, .claim, .claimed] { scenes.append((scene(picture), "\(picture)")) }
                            var other = scene(.claimed)
                            other.destination = .other("bcrt1q…m4k2")
                            scenes.append((other, "claimed to another wallet"))
                        }
                        for (base, name) in scenes {
                            // Every pair for the pictures that lay out differently; the variants that lay out like them, a sample.
                            let all = ["waiting", "needs funds", "lock", "claimed"].contains(name)
                            for pair in all ? pairs : pairs.filter({ pair in
                                pair.map { $0 == (1, count - 1) || $0 == (count / 2, count / 2) } ?? true
                            }) {
                                var scene = base
                                if let (low, high) = pair {
                                    scene.others = [
                                        .init(level: low, amount: "100 000 000 sats", sent: true),
                                        .init(level: high, amount: "₿24,500", sent: false),
                                    ]
                                    scene.shared = (0..<count).map { $0 < high }
                                }
                                check(scene, "\(count) transactions, \(mined) mined, \(name), others at \(pair.map { "\($0.0)/\($0.1)" } ?? "none")")
                            }
                        }
                    }
                }
            }
        }
        #expect(checked > 2_000)
        let kinds = Dictionary(grouping: problems) { $0.components(separatedBy: ": ").last ?? $0 }
        #expect(
            problems.isEmpty,
            "\(problems.count) problems:\n\(kinds.map { "\($0.value.count)× \($0.key), e.g. \($0.value[0])" }.sorted().prefix(40).joined(separator: "\n"))")
    }

    /// A path too long for the picture is folded so every level keeps its full spacing: the oldest blocks stack into one
    /// at the base, the one being sent and the next two stay on their own, the rest of the unsent fold into one "+N" node.
    /// Labels count the real transactions. (It drew 20 transactions on an SE at AX1 as 7 pt nodes 5.96 pt apart.)
    @Test func aLongPathFoldsRatherThanSqueezing() {
        let size = CGSize(width: 375, height: 236), english = Locale(identifier: "en")
        var scene = Self.mempool(heights: (921_346...921_350).map(\.self), pending: 5, count: 20)
        scene.others = [.init(level: 2, amount: "₿21,000", sent: true)]
        scene.shared = (0..<20).map { $0 < 2 }
        let (g, drawn) = ExitStageGeometry.fitting(scene, size: size, scale: 1.65)
        #expect(drawn.realCount == 20 && drawn.levels.count - 1 == 8 && g.side >= 7)
        // The oldest two blocks stacked, the latest three on their own.
        #expect(drawn.folds.first == .init(kind: .stack, index: 0, count: 2, heights: 921_346...921_347))
        let current = drawn.levels.firstIndex { $0.isPending }
        #expect(current.map { drawn.real($0) } == 5)
        // At least the next two unsent after it are drawn on their own, then one "+N" node, then your coin.
        let more = drawn.folds.first { $0.kind == .more }
        if let current, let more {
            #expect(more.index >= current + 3 && more.index == drawn.levels.count - 2)
            #expect(drawn.levels[(current + 1)..<more.index].allSatisfy { $0 == .unsent } && drawn.real(more.index) + more.count == 19)
        } else {
            Issue.record("no current or no +N node")
        }
        // Your other exit branching at the second transaction branches from where it's drawn.
        if let level = drawn.others.first?.level {
            let first = drawn.real(level - 1), count = drawn.fold(at: level - 1)?.count ?? 1
            #expect(first <= 1 && first + count > 1)
        }
        let ys = g.levels(ExitStageStill(drawn).mined)
        for level in 1..<ys.count { #expect(ys[level - 1] - ys[level] >= g.minimumGap(level, side: g.side, reserve: g.coinReserve()) - 0.001) }
        #expect(Self.texts(ExitStagePainter(geometry: g, colors: .theme(.light), reduceMotion: false, locale: english).labels(drawn, ys: ys))
            .contains("Next block"))
        // The labels count the real transactions: on the iPhone 17's picture, folded too, all 14 still to send.
        let (p, phone) = ExitStageGeometry.fitting(scene, size: CGSize(width: 393, height: 300), scale: 1)
        let phoneTexts = Self.texts(ExitStagePainter(geometry: p, colors: .theme(.light), reduceMotion: false, locale: english)
            .labels(phone, ys: p.levels(ExitStageStill(phone).mined)))
        #expect(!phone.folds.isEmpty && phoneTexts.contains("14 more to send") && phoneTexts
            .contains { $0.hasPrefix("Blocks 921,346–") || $0.hasPrefix("Block ") })
        // A hundred, all in blocks and locked: the oldest stacked with their range, the coin's dial clear.
        var locked = ExitStageScene(picture: .lock, levels: (0..<100).map { .mined(921_000 + UInt32($0)) })
        (locked.tip, locked.leaf, locked.unlock) = (921_150, 921_099, 921_243)
        let (h, hundred) = ExitStageGeometry.fitting(locked, size: size, scale: 1.65)
        #expect(hundred.realCount == 100 && hundred.folds.map(\.kind) == [.stack] && hundred.folds[0].heights?.lowerBound == 921_000)
        let hys = h.levels(ExitStageStill(hundred).mined, lock: 1)
        #expect(hys[hys.count - 2] - hys[hys.count - 1] >= h.side / 2 + h.coinReserve(1) + ExitStageGeometry.clearance - 0.001)
        let lockLabels = ExitStagePainter(geometry: h, colors: .theme(.light), reduceMotion: false, locale: english).labels(hundred, ys: hys)
        #expect(lockLabels.contains { $0.lines.map(\.text).joined(separator: " ").hasPrefix("Blocks 921,000–") })
        // Paths that fit are drawn as they are.
        #expect(ExitStageGeometry.fitting(Self.mempool(heights: [1, 2, 3, 4], pending: 4, count: 9), size: size, scale: 1.65).scene.folds.isEmpty)
    }

    /// When a range shows, every block without its own label is in it: the heights shown account for every mined
    /// transaction (your coin's own, while locked, is the dial's).
    static func coversEveryBlock(_ labels: [ExitStageLabel], _ real: ExitStageScene) -> Bool {
        let ranges = labels.compactMap { label in label.first.flatMap { first in label.block.map { first...$0 } } }
        guard !ranges.isEmpty else { return true }
        let own = Set(labels.filter { $0.first == nil }.compactMap(\.block))
        let mined = real.levels.enumerated().compactMap { index, level -> UInt32? in
            guard case .mined(let height?) = level, !(index == real.levels.count - 1 && (real.locked || real.claiming)) else { return nil }
            return height
        }
        return mined.allSatisfy { height in own.contains(height) || ranges.contains { $0.contains(height) } }
    }

    /// The folded stack's range covers every block without its own label: on the SE, 20 transactions with 5 in blocks
    /// read "Blocks 921,346–921,350" (it read 921,346–921,347, the stack alone).
    @Test func theFoldedRangeCountsEveryBlock() {
        for scale in [1.0, 1.65] {
            let scene = Self.mempool(heights: (921_346...921_350).map(\.self), pending: 5, count: 20)
            let (g, drawn) = ExitStageGeometry.fitting(scene, size: CGSize(width: 375, height: 236), scale: scale)
            let labels = Self.painter(g).labels(drawn, ys: g.levels(ExitStageStill(drawn).mined))
            #expect(Self.coversEveryBlock(labels, scene), "\(scale)")
            if scale == 1 { #expect(Self.texts(labels).contains("Blocks 921,346–921,350")) }
        }
        // Placement on its own: a stack's range takes in the blocks that lost their labels, or no range shows.
        let placed = ExitStageLabels.place([.init(y: 100, lines: [.init(text: "next")], priority: 4),
                                            .init(y: 106, lines: [.init(text: "4")], priority: 1, block: 4),
                                            .init(y: 112, lines: [.init(text: "3")], priority: 1, block: 3),
                                            .init(y: 150, lines: [.init(text: "1–2")], priority: 2.1, block: 2, first: 1)],
                                           lineHeight: 14, scale: 1) { "\($0)–\($1)" }
        #expect(Self.texts(placed).contains("1–4") && !Self.texts(placed).contains("1–2"))
    }

    /// On a folded path a move matches transactions, not drawn places: 20 transactions going from 5 to 6 in blocks on an
    /// SE at AX1 grows the stack, so every transaction shifts a place.
    @Test func aFoldedPathMovesEachTransaction() throws {
        let size = CGSize(width: 375, height: 236)
        var five = Self.mempool(heights: (921_346...921_350).map(\.self), pending: 5, count: 20)
        var six = Self.mempool(heights: (921_346...921_351).map(\.self), pending: 6, count: 20)
        five.others = [.init(level: 8, amount: "₿21,000", sent: false)]
        six.others = five.others
        let (g, b) = ExitStageGeometry.fitting(six, size: size, scale: 1.65)
        let a = five.folded(nodes: g.foldCapacity)
        #expect(a.folds != b.folds && a.levels.count == b.levels.count)
        let f = ExitStageFrame(a, b, elapsed: 0.05, reduceMotion: false)
        for j in b.levels.indices.dropLast() { #expect(f.origin[j] == a.level(holding: b.real(j))) }
        // Transaction 6 just got into a block: it fills and settles. Transaction 7 starts waiting. Nothing else changes.
        let mined = try #require(b.level(holding: 5)), sending = try #require(b.level(holding: 6))
        #expect(f.stamp[mined] < 1 && f.mined[mined] < 1 && f.pending[mined] > 0)
        #expect(f.pending[sending] < 0.5 && f.mined[sending] == 0)
        #expect(f.stamp.indices.allSatisfy { $0 == mined || f.stamp[$0] == 1 })
        // The block joining the stack sinks into it; the unsent one leaving the "+N" node splits out of it.
        #expect(try f.merging == [.init(from: #require(a.level(holding: 2)), into: 0, mined: true)])
        #expect(try f.origin[#require(b.level(holding: 8))] == a.folds.first { $0.kind == .more }?.index)
        // Each transaction starts where it was drawn and ends where it's drawn now; your other exit's branch follows it.
        let painter = ExitStagePainter(geometry: g, colors: .theme(.light), reduceMotion: false, locale: Self.english)
        let start = painter.positions(ExitStageFrame(a, b, elapsed: 0, reduceMotion: false), from: a, to: b)
        for j in b.levels.indices.dropLast() { if let o = f.origin[j] { #expect(abs(start.ys[j + 1] - start.before[o + 1]) < 0.001) } }
        #expect(abs(start.ys[b.others[0].level] - start.before[a.others[0].level]) < 0.001)
        let end = painter.positions(ExitStageFrame(a, b, elapsed: 60, reduceMotion: false), from: a, to: b)
        #expect(zip(end.ys, g.levels(ExitStageStill(b).mined)).allSatisfy { abs($0 - $1) < 0.01 })
    }

    /// Optional elements give way to everything required, in order: amounts first, then your other exits' coins (moved
    /// down their branch before they're left out), then stubs. Nine transactions, five mined, others at levels 1 and 2 on
    /// an SE at AX1: their coins no longer share a spot 9.98 pt apart.
    @Test func optionalElementsGiveWayInOrder() {
        let g = ExitStageGeometry(size: CGSize(width: 375, height: 236), scale: 1.65, count: 9), painter = Self.painter(g)
        var scene = Self.mempool(heights: (921_346...921_350).map(\.self), pending: 5, count: 9)
        scene.others = [.init(level: 1, amount: "₿21,000", sent: true), .init(level: 2, amount: "₿24,500", sent: true)]
        let ys = g.levels(ExitStageStill(scene).mined)
        let arrangement = painter.arrange(scene, ys: ys, lock: 0, labels: painter.labels(scene, ys: ys))
        let coins = arrangement.siblings.map(\.coin)
        for (index, a) in coins.enumerated() { for b in coins[(index + 1)...] { #expect(hypot(a.x - b.x, a.y - b.y) >= 12 + ExitStageGeometry.clearance) } }
        // A sibling that moved stays on its own side of its branch, below where it would have been.
        for sibling in arrangement.siblings {
            let base = g.other(ys, level: sibling.other.level, index: sibling.index)
            #expect(sibling.coin.x <= base.x && sibling.coin.y >= base.y && sibling.coin.y <= ys[sibling.other.level])
        }
        // The amount of a sibling goes before the sibling itself, and a sibling before a stub.
        #expect(arrangement.siblings.allSatisfy { $0.amount == nil } || !arrangement.siblings.isEmpty)
        let stubsWithout = painter.arrange(ExitStageScene(picture: .path, levels: scene.levels), ys: ys, lock: 0, labels: []).stubs
        let stubsWith = painter.arrange(scene, ys: ys, lock: 0, labels: []).stubs
        // Siblings take their own level's stub slots, and never push out another level's stub.
        #expect(stubsWith.filter { ![1, 2].contains($0.level) } == stubsWithout.filter { ![1, 2].contains($0.level) })
    }
}
