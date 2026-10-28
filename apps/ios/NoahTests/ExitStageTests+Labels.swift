import Foundation
@testable import Noah
import SwiftUI
import Testing
import UIKit

extension ExitStageTests {
    // MARK: Labels

    @Test func labelsSayWhichBlockEachIsInAndWhatsWaiting() {
        let painter = Self.painter(Self.iphone), scene = Self.mempool(heights: [921_346], pending: 1, count: 4)
        let labels = painter.labels(scene, ys: Self.iphone.levels([1, 0, 0, 0]))
        #expect(Self.texts(labels).sorted() == ["2 more to send", "Block 921,346", "Next block", "The round,", "Your VTXO", "already on Bitcoin",
                                                "fee ₿1,215"].sorted())
        // No round height from bark: the round's block says what it is, as the explainer's does.
        #expect(labels.contains { $0.lines.map(\.text) == ["The round,", "already on Bitcoin"] && $0.lines[0].tone == .strong })
        #expect(!Self.overlapping(labels))
        #expect(labels.first { $0.lines[0].text == "Next block" }?.pill == .orange)
    }

    @Test func labelsGoByPriorityAndOlderBlocksFoldIntoARange() {
        let g = Self.se, painter = Self.painter(g)
        let scene = Self.mempool(heights: (921_346...921_350).map(\.self), pending: 5, count: 9)
        let labels = painter.labels(scene, ys: g.levels([1, 1, 1, 1, 1, 0, 0, 0, 0]))
        let texts = Self.texts(labels)
        // The waiting transaction and your coin always get theirs; the pile of blocks becomes one range.
        #expect(texts.contains("Next block") && texts.contains("Your VTXO"))
        #expect(texts.contains { $0.hasPrefix("Blocks 921,346–") })
        #expect(!Self.overlapping(labels))
        // Plain placement: three items, the lowest priority one clashing, dropped rather than overlapped.
        let placed = ExitStageLabels.place([.init(y: 100, lines: [.init(text: "a")], priority: 4),
                                            .init(y: 105, lines: [.init(text: "b")], priority: 1),
                                            .init(y: 200, lines: [.init(text: "c")], priority: 2)], lineHeight: 14, scale: 1) { "\($0)–\($1)" }
        #expect(Self.texts(placed) == ["a", "c"])
        // Two clashing blocks fold into their range.
        let folded = ExitStageLabels.place([.init(y: 100, lines: [.init(text: "next")], priority: 4),
                                            .init(y: 104, lines: [.init(text: "1")], priority: 1, block: 1),
                                            .init(y: 112, lines: [.init(text: "2")], priority: 1, block: 2),
                                            .init(y: 140, lines: [.init(text: "3")], priority: 1, block: 3)], lineHeight: 14, scale: 1) { "\($0)–\($1)" }
        #expect(Self.texts(folded).contains("1–3") && !Self.overlapping(folded))
        // A lone block that wrapped to two lines gets its second chance at its whole height, so its second
        // line never lands on the label below it.
        let lone = ExitStageLabels.place([.init(y: 100, lines: [.init(text: "next")], priority: 4),
                                          .init(y: 150, lines: [.init(text: "The round,"), .init(text: "already on Bitcoin")], priority: 2),
                                          .init(y: 125, lines: [.init(text: "Block 921 346"), .init(text: "· delad")], priority: 1, block: 1)],
                                         lineHeight: 14, scale: 1) { "\($0)–\($1)" }
        #expect(!Self.texts(lone).contains("Block 921 346") && !Self.overlapping(lone))
        // With room, it's placed whole.
        let room = ExitStageLabels.place([.init(y: 100, lines: [.init(text: "next")], priority: 4),
                                          .init(y: 125, lines: [.init(text: "Block 921 346"), .init(text: "· delad")], priority: 1, block: 1)],
                                         lineHeight: 14, scale: 1) { "\($0)–\($1)" }
        #expect(room.first { $0.block == 1 }.map { $0.bottom - $0.top } == 28 && !Self.overlapping(room))
    }

    @Test func eachStateSaysWhatItWaitsFor() {
        let painter = Self.painter(Self.iphone), ys = Self.iphone.levels([0, 0, 0, 0])
        var needs = ExitStageScene(picture: .path, levels: [.mined(921_346), .needsFunds, .unsent, .unsent])
        needs.need = "₿3,765"
        #expect(Self.texts(painter.labels(needs, ys: ys)).contains("add ₿3,765 on-chain"))
        var stale = Self.mempool(heights: [921_346], pending: 1, count: 4)
        stale.stale = true
        stale.checked = 921_347
        let staleLabels = painter.labels(stale, ys: ys)
        #expect(Self.texts(staleLabels).contains("last checked at 921,347") && staleLabels.contains { $0.pill == .grey })
        var stuck = Self.mempool(heights: [921_346], pending: 1, count: 4)
        stuck.stuck = 3
        #expect(Self.texts(painter.labels(stuck, ys: ys)).contains("3 blocks without it"))
        let theirs = ExitStageScene(picture: .path, levels: [.pending(theirs: true, bumped: false), .unsent, .unsent, .unsent])
        #expect(Self.texts(painter.labels(theirs, ys: ys)).contains("sent by someone else"))
        var shared = Self.mempool(heights: [921_346], pending: 1, count: 4)
        shared.shared = [true, true, false, false]
        #expect(Self.texts(painter.labels(shared, ys: ys)).contains("Block 921,346 · shared"))
        var locked = ExitStageScene(picture: .lock, levels: Array(repeating: .mined(921_349), count: 4))
        locked.tip = 921_394
        locked.leaf = 921_349
        locked.unlock = 921_493
        #expect(Self.texts(painter.labels(locked, ys: ys)).contains("Locked · 99 blocks"))
        var claimed = ExitStageScene(picture: .claimed, levels: Array(repeating: .mined(921_349), count: 4))
        claimed.claimHeight = 921_494
        let claimLabels = painter.labels(claimed, ys: ys)
        // While claiming, the tree's labels give way to the claim's, beside its node on the stem.
        #expect(Self.texts(claimLabels) == ["Block 921,494", "claim"] && claimLabels[0].trailing != nil)
        var canceled = ExitStageScene(picture: .canceled, levels: [.mined(921_346), .unsent, .unsent, .unsent])
        canceled.balance = nil
        #expect(Self.texts(painter.labels(canceled, ys: ys)).contains("Back in Ark"))
        let explainer = Self.texts(painter.labels(.explainer(count: 4), ys: ys))
        #expect(explainer == ["Your VTXO", "in Ark", "4 transactions", "one block each,", "root first", "The round,", "already on Bitcoin"])
    }

    @Test func wrappingBreaksBetweenWordsAndOnlyBreaksAWordThatCantFit() {
        let measure = { (text: String) in Double(text.count) * 6 }
        #expect(ExitStageLabels.wrap("add 3 765 sats on-chain", width: 60, measure: measure) == ["add 3 765", "sats", "on-chain"])
        #expect(ExitStageLabels.wrap("Next block", width: 200, measure: measure) == ["Next block"])
        #expect(ExitStageLabels.wrap("bcrt1qqqqqqqqqqq", width: 36, measure: measure) == ["bcrt1q", "qqqqqq", "qqqq"])
        #expect(ExitStageLabels.wrap("", width: 36, measure: measure) == [""])
    }

    /// Translations and large text run long (Swedish, AX1, sats on an iPhone SE): every label wraps to the room it
    /// has, beside the phone's badge too, stays between the top and the ground, and none overlap.
    @Test func longTranslationsWrapInsideTheirColumn() {
        let g = ExitStageGeometry(size: CGSize(width: 375, height: 236), scale: 1.65, count: 4)
        let painter = ExitStagePainter(geometry: g, colors: .theme(.light), reduceMotion: false, locale: Locale(identifier: "sv"))
        var needs = ExitStageScene(picture: .path, levels: [.mined(921_346), .needsFunds, .unsent, .unsent])
        needs.need = "3 765 sats"
        var stuck = Self.mempool(heights: [921_346, 921_347], pending: 2, count: 4, fee: "1 215 sats")
        stuck.stuck = 12
        stuck.shared = [true, true, true, false]
        var lock = ExitStageScene(picture: .lock, levels: Array(repeating: .mined(921_349), count: 4))
        (lock.tip, lock.leaf, lock.unlock) = (921_394, 921_349, 921_493)
        var claim = ExitStageScene(picture: .claim, levels: Array(repeating: .mined(921_349), count: 4))
        claim.claimFee = "262 sats"
        for scene in [needs, stuck, lock, claim, .explainer(count: 4)] {
            let labels = painter.labels(scene, ys: g.levels(ExitStageStill(scene).mined))
            #expect(!Self.overlapping(labels), "\(scene.picture)")
            for label in labels {
                #expect(label.top >= 2 && label.bottom <= g.ground - 3, "\(label.lines)")
                let room = label.trailing.map(g.claimColumn) ?? g.column(top: label.top, bottom: label.bottom)
                for (index, line) in label.lines.enumerated() {
                    let head = index < label.head
                    let width = ExitStageText
                        .width(line.text, size: head ? g.fontSize : g.subSize, strong: [.strong, .failureStrong].contains(line.tone)) * label.size
                    #expect(width + (head && label.pill != nil ? 8 : 0) <= room + 0.5, "\(line.text)")
                }
            }
        }
        // "lägg till 3 765 sats på blockkedjan" wraps under its pill rather than leaving the canvas.
        let alarm = painter.labels(needs, ys: g.levels([1, 0, 0, 0])).first { $0.pill == .failure }
        #expect((alarm?.lines.count ?? 0) > 2 && alarm?.lines.map(\.text).joined(separator: " ") == "Behöver en avgift lägg till 3 765 sats på blockkedjan")
        // The pill wraps around every line of its heading.
        if let alarm { #expect(painter.pill(alarm).height >= Double(alarm.head - 1) * g.lineHeight + g.lineHeight) }
        // One-line texts (other exits' amounts, the destination) shrink to fit, to 60 % at most.
        let size = 11.0, shrunk = ExitStagePainter.shrunk("21 000 sats", size, room: 40)
        #expect(shrunk < size && shrunk >= size * 0.6 && ExitStagePainter.shrunk("₿21", size, room: 40) == size)
        // Your other exits' amounts that don't fit left of their coin even at 60 % are left out (the list carries the
        // numbers): two siblings of 1 BTC or more, in sats, at AX1 on an SE.
        let second = g.other(g.levels([1, 1, 0, 0]), level: 1, index: 1), amountSize = 9.5 * g.scale
        #expect(ExitStagePainter.fitted("100,000,000 sats", amountSize, room: second.x - 14) == nil)
        #expect(ExitStagePainter.fitted("24,500 sats", amountSize, room: second.x - 14).map { $0 <= amountSize && $0 >= amountSize * 0.6 } == true)
        #expect(ExitStagePainter.fitted("₿21", size, room: 40) == size)
    }

    /// The root waiting on the SE's 236 pt picture: "Next block" takes the room above the round's block, so the round's
    /// caption goes on one line rather than disappearing. At AX1, with no room for either, it may give way; nothing overlaps.
    @Test func theRoundsCaptionStaysWhileTheRootWaitsOnASmallPicture() {
        for (scale, locale) in [(1.0, "en"), (1.0, "sv"), (1.0, "ja"), (1.65, "en"), (1.65, "sv")] {
            let g = ExitStageGeometry(size: CGSize(width: 375, height: 236), scale: scale, count: 4)
            let painter = ExitStagePainter(geometry: g, colors: .theme(.light), reduceMotion: false, locale: Locale(identifier: locale))
            let caption = L10n.text("already on Bitcoin", locale: locale)
            for scene in [Self.mempool(heights: [], pending: 0, count: 4), Self.mempool(heights: [921_346], pending: 1, count: 4)] {
                let labels = painter.labels(scene, ys: g.levels(ExitStageStill(scene).mined))
                #expect(!Self.overlapping(labels), "\(locale) \(scale)")
                #expect(labels.contains { $0.lines.first?.text == L10n.text("Next block", locale: locale) })
                if scale == 1 { #expect(labels.contains { $0.lines.contains { $0.text.contains(caption) } }, "\(locale) \(scene.levels)") }
                for label in labels where label.shrinks {
                    let room = g.column(top: label.top, bottom: label.bottom)
                    #expect(label.size >= 0.75 && ExitStageText.width(label.lines[0].text, size: g.fontSize) * label.size <= room + 0.5)
                }
            }
        }
        // The iPhone's 300 pt picture has room for both lines.
        let labels = Self.painter(Self.iphone).labels(Self.mempool(heights: [], pending: 0, count: 4), ys: Self.iphone.levels([0, 0, 0, 0]))
        #expect(labels.contains { $0.lines.map(\.text) == ["The round,", "already on Bitcoin"] })
    }

    /// The exit pages' buttons wrap at accessibility sizes inside a taller pill; the rest of Settings keeps one line.
    @MainActor @Test func exitButtonsWrapAndOtherSettingsButtonsKeepOneLine() {
        func height(wrap: Bool) -> CGFloat {
            let button = Button("Lägg till pengar på blockkedjan") {}.buttonStyle(SettingsActionStyle(wrap: wrap))
                .environment(\.dynamicTypeSize, .accessibility1)
            return UIHostingController(rootView: button).sizeThatFits(in: CGSize(width: 327, height: CGFloat.greatestFiniteMagnitude)).height
        }
        #expect(height(wrap: false) == NoahTokens.Size.button)
        #expect(height(wrap: true) > NoahTokens.Size.button + 20)
    }

    // MARK: Motion

    @Test func onlyWhatWaitsForABlockMoves() {
        let painter = Self.painter(Self.iphone)
        func ids(_ scene: ExitStageScene) -> [String] { painter.pulses(scene).map(\.id) }
        let mempool = Self.mempool(heights: [921_346], pending: 1, count: 4)
        #expect(ids(mempool).contains("node1") && ids(mempool).contains { $0.hasPrefix("pill") })
        #expect(painter.pulses(mempool).first { $0.id == "node1" }?.kind == .square(14))
        // Your coin's transaction waits: round rings.
        #expect(painter.pulses(Self.mempool(heights: [1, 2, 3], pending: 3, count: 4)).first?.kind == .coin(Self.iphone.coinRadius))
        // Stale: nothing vouches it's moving, so nothing breathes.
        var stale = mempool
        stale.stale = true
        #expect(ids(stale).isEmpty)
        #expect(ids(ExitStageScene(picture: .path, levels: [.mined(1), .needsFunds, .unsent, .unsent])) == ["alarm1"])
        var lock = ExitStageScene(picture: .lock, levels: Array(repeating: .mined(1), count: 4))
        lock.leaf = 100
        lock.unlock = 244
        lock.tip = 150
        #expect(ids(lock) == ["dot"])
        #expect(ids(ExitStageScene(picture: .unlocked, levels: Array(repeating: .mined(1), count: 4))) == ["glow"])
        let claim = ids(ExitStageScene(picture: .claim, levels: Array(repeating: .mined(1), count: 4)))
        #expect(claim.contains("claim") && claim.contains { $0.hasPrefix("pill") })
        #expect(ids(ExitStageScene(picture: .claimed, levels: Array(repeating: .mined(1), count: 4))).isEmpty)
        #expect(ids(.explainer(count: 4)).isEmpty)
        #expect(ids(Self.mempool(heights: [], pending: -1, count: 4)).isEmpty)
    }

    @Test func reduceMotionHoldsASteadyGlowAndFades() {
        // Nothing breathes: a steady 60 %.
        #expect(ExitPulse.breathe(0, animating: false) == 0.6 && ExitPulse.breathe(123.4, animating: false) == 0.6)
        #expect(ExitPulse.breathe(0.4, animating: true) != 0.6)
        // Two rings, 0.8 s apart.
        #expect(abs(ExitPulse.ring(0, 1) - ExitPulse.ring(0.8, 0)) < 0.0001)
        let a = Self.mempool(heights: [921_346], pending: 1, count: 4), b = Self.mempool(heights: [921_346, 921_347], pending: 2, count: 4)
        // Mined: one ripple, unless Reduce Motion is on.
        #expect(ExitStageFrame(a, b, elapsed: 0.2, reduceMotion: false).ripple[1] > 0)
        #expect(ExitStageFrame(a, b, elapsed: 0.2, reduceMotion: true).ripple.allSatisfy { $0 == 0 })
        // Every move is a 180 ms fade, so the Canvas stops after 0.3 s.
        #expect(ExitStageFrame.duration(a, b, reduceMotion: true) == 0.3)
        #expect(ExitStageFrame.duration(a, b, reduceMotion: false) > 1)
        #expect(ExitStageFrame.duration(nil, b, reduceMotion: false) == 0)
    }

    @Test func theBranchGrowsFromTheGroundUpOnce() {
        let lift = ExitStageScene(picture: .lift, levels: Array(repeating: .unsent, count: 4))
        let publish = ExitStageScene(picture: .path, levels: Array(repeating: .unsent, count: 4))
        let start = ExitStageFrame(lift, publish, elapsed: 0, reduceMotion: false)
        #expect(start.grow[1...].allSatisfy { $0 == 0 })
        // 0.26 s a link, root first.
        let middle = ExitStageFrame(lift, publish, elapsed: 0.4, reduceMotion: false)
        #expect(middle.grow[1] == 1 && middle.grow[2] > 0 && middle.grow[2] < 1 && middle.grow[4] == 0)
        #expect(ExitStageFrame(lift, publish, elapsed: 2, reduceMotion: false).grow[1...].allSatisfy { $0 == 1 })
        // The explainer grows once when the page opens; its labels follow.
        let explainer = ExitStageFrame(.explainer(count: 4, grown: false), .explainer(count: 4), elapsed: 0.5, reduceMotion: false)
        #expect(explainer.labels == 0 && explainer.grow[4] == 0)
        #expect(ExitStageFrame(.explainer(count: 4, grown: false), .explainer(count: 4), elapsed: 2, reduceMotion: false).labels == 1)
    }

    /// The intro is short whatever the path's length: about a second to grow, then the labels. The explainer draws 8
    /// levels at most and still says how many transactions there are.
    @Test func anyPathGrowsInAboutASecond() {
        #expect(ExitStageFrame.growStep(4) == 0.26 && ExitStageFrame.growStep(100) * 100 <= 1.05 + 1e-9)
        let big = ExitStageScene.explainer(count: 100)
        #expect(big.levels.count == 8 && big.transactions == 100)
        let seed = ExitStageScene.explainer(count: 100, grown: false)
        #expect(ExitStageFrame(seed, big, elapsed: 1.1, reduceMotion: false).grow[1...].allSatisfy { $0 == 1 })
        #expect(ExitStageFrame(seed, big, elapsed: 1.6, reduceMotion: false).labels == 1)
        #expect(ExitStageFrame.duration(seed, big, reduceMotion: false) <= 2.5)
        #expect(Self.texts(Self.painter(Self.iphone).labels(
            big,
            ys: ExitStageGeometry(size: CGSize(width: 393, height: 300), count: 8).levels(Array(repeating: 0, count: 8))))
            .contains("100 transactions"))
        // A deep path on the progress page grows as fast once bark lists it.
        let lift = ExitStageScene(picture: .lift, levels: Array(repeating: .unsent, count: 9))
        let publish = ExitStageScene(picture: .path, levels: Array(repeating: .unsent, count: 9))
        #expect(ExitStageFrame(lift, publish, elapsed: 1.1, reduceMotion: false).grow[1...].allSatisfy { $0 == 1 })
    }

    @Test func theClaimDimsTheTreeAndTheCoinTravelsIntoItsDestination() {
        let claim = ExitStageScene(picture: .claim, levels: Array(repeating: .mined(1), count: 4))
        let claimed = ExitStageScene(picture: .claimed, levels: Array(repeating: .mined(1), count: 4))
        let settled = ExitStageFrame(claim, claim, elapsed: 60, reduceMotion: false)
        #expect(abs(settled.tree - 0.4) < 0.001 && settled.labels < 0.001 && settled.stem > 0.999)
        let landing = ExitStageFrame(claim, claimed, elapsed: 0.4, reduceMotion: false)
        #expect(landing.land == 0 && landing.claimMined > 0)
        let landed = ExitStageFrame(claim, claimed, elapsed: 3, reduceMotion: false)
        #expect(landed.land > 0.99 && landed.destRing > 0.99 && landed.destPop > 0.9)
        // Both destinations land on the bottom-right badge; another wallet swaps it for a wallet.
        var other = claimed
        other.destination = .other("bcrt1q…u202")
        #expect(ExitStageStill(other).toOther == 1 && ExitStageStill(claimed).toOther == 0)
    }

    // MARK: Exits list and the Emergency exit page

    static func exit(_ id: String, _ state: String, _ txs: [ExitDetails.Transaction], details: ExitDetails? = nil) -> ExitModel {
        ExitModel(id: id, amount: 10_000, state: state, details: details ?? .init(kind: state, tip_height: 100, transactions: txs),
                  history: [], historyDetails: [], claimable: state == "Claimable")
    }

    static func tx(_ id: String, _ kind: String, height: UInt32? = nil) -> ExitDetails.Transaction {
        .init(txid: id, status: .init(kind: kind, block: height.map { .init(height: $0, hash: "h") }))
    }

    @Test func theExitsSubtitleLeavesOutZeros() {
        func subtitle(_ states: [String]) -> String? {
            ExitListView.subtitle(states.enumerated().map { Self.exit("\($0.offset)", $0.element, []) }, language: "en")
        }
        #expect(subtitle([]) == nil)
        #expect(subtitle(["Claimed", "Canceled"]) == nil)
        #expect(subtitle(["Processing", "AwaitingDelta"]) == "2 in progress")
        #expect(subtitle(["Claimable"]) == "1 ready to claim")
        #expect(subtitle(["Processing", "Processing", "Start", "AwaitingDelta", "ClaimInProgress", "Processing", "Claimable", "Claimed"])
            == "6 in progress · 1 ready to claim")
    }

    @Test func theExitsCardIsTitledByWhatItHoldsAndTintedWhileAnyIsPending() {
        func entry(_ states: [String]) -> ExitEntry? {
            ExitEntry(states.enumerated().map { Self.exit("\($0.offset)", $0.element, []) })
        }
        #expect(entry([]) == nil)
        for state in ["Start", "Processing", "AwaitingDelta", "Claimable", "ClaimInProgress"] {
            #expect(entry([state]) == .pending, "\(state)")
        }
        for state in ["Claimed", "Canceled", "VtxoAlreadySpent", "VtxoSwept"] {
            #expect(entry([state]) == .past, "\(state)")
        }
        #expect(entry(["Processing", "Claimable"]) == .pending)
        #expect(entry(["Claimed", "Canceled"]) == .past)
        #expect(entry(["Claimable", "Claimed"]) == .both)
        #expect(entry(["Processing", "Canceled", "Claimed"]) == .both)
        #expect(ExitEntry.pending.title == "Pending exits" && ExitEntry.past.title == "Past exits"
            && ExitEntry.both.title == "Pending and past exits")
        // Orange while any exit is still yours to finish; the soft surface once they've all ended.
        #expect(ExitEntry.pending.tinted && ExitEntry.both.tinted && !ExitEntry.past.tinted)
    }

    @Test func onlyExitsThatShareTransactionsGetAPathInTheirGutter() {
        let root = Self.tx("r1", "confirmed", height: 99)
        let a = Self.exit(
            "a",
            "Processing",
            [root, Self.tx("l2", "confirmed", height: 100), Self.tx("a1", "awaiting-confirmation"), Self.tx("a2", "verify-inputs")])
        let b = Self.exit("b", "Processing", [root, Self.tx("l2", "confirmed", height: 100), Self.tx("b1", "verify-inputs")])
        let c = Self.exit("c", "Processing", [Self.tx("r2", "confirmed", height: 90), Self.tx("c1", "verify-inputs")])
        let ready = Self.exit("d", "Claimable", [], details: .init(kind: "claimable", claimable_since: .init(height: 100, hash: "h")))
        let claimed = Self.exit("e", "Claimed", [root])
        let sections = ExitListView.sections([ready, a, c, b, claimed])
        #expect(sections.map(\.title) == ["Ready to claim", "Exiting", "Finished"])
        #expect(sections[0].shared.isEmpty && sections[0].solo.map(\.id) == ["d"])
        #expect(sections[1].shared.map { $0.map(\.id) } == [["a", "b"]] && sections[1].solo.map(\.id) == ["c"])
        // Finished rows never share a gutter.
        #expect(sections[2].shared.isEmpty && sections[2].solo.map(\.id) == ["e"])
        // The shared path is drawn once: each transaction one node, at the middle of the rows it leads to.
        let routes = [a, b].map { ExitGutterTree.Route($0, ExitProgress($0, tip: 100), coin: ExitGutterCoin()) }
        let layout = ExitGutterTree.layout(routes, ys: [32, 96], gutter: ExitListGroup.gutter(12), step: 12)
        #expect(layout.root.y == 64 && layout.coins.map(\.y) == [32, 96])
        // Each path's last transaction is its coin; a step's id names its column and transaction.
        func node(_ txid: String) -> ExitGutterTree.Node? { layout.nodes.first { $0.id.hasSuffix("/" + txid) } }
        #expect(layout.nodes.map { $0.id.components(separatedBy: "/").last } == ["r1", "l2", "a1"])
        #expect(node("l2")?.point.y == 64 && node("a1")?.point.y == 32 && node("a1")?.status == .pending)
        #expect(Set(layout.links.map { "\($0.from)>\($0.to)" }).count == layout.links.count)
        // A long path folds, so its steps stay apart and end before the coin.
        let long = ExitGutterTree.Route(txids: (0..<9).map { "t\($0)" }, statuses: Array(repeating: .unsent, count: 9), coin: ExitGutterCoin())
        let deep = ExitGutterTree.layout([long], ys: [32], gutter: ExitListGroup.gutter(12), step: 12)
        #expect(deep.nodes.count <= ExitGutterTree.capacity(gutter: ExitListGroup.gutter(12)) && deep.nodes.contains { $0.fold == .more })
        #expect(deep.nodes.allSatisfy { $0.point.x + 6 + ExitStageGeometry.clearance <= deep.coins[0].x - 14 })
    }

    /// The Exits list's shared gutters keep their steps apart however long the paths: a long route folds as the picture
    /// does, its current transaction drawn on its own. Samples the narrowest and widest gutters; path lengths from 1 to
    /// 100; three exits sharing a prefix of several lengths; mined counts; waiting and needing funds.
    @Test func listGuttersKeepTheirSpacing() {
        var problems: [String] = [], checked = 0
        for step in [11.0, 13] {
            let gutter = ExitListGroup.gutter(step), capacity = ExitGutterTree.capacity(gutter: gutter)
            for length in [1, 2, 3, 4, 6, 9, 13, 20, 100] {
                for shared in Set([1, length / 2, length - 1]).filter({ $0 >= 1 && $0 < max(2, length) }).sorted() {
                    let lengths = [length, length, max(shared + 1, length / 2)]
                    let mineds = Set([0, 1, shared, length / 2, length - 1, length]).filter { $0 <= length }.sorted()
                    // Shared transactions have one status on every row; past them, rows can be further along or behind.
                    for (mined, spread) in mineds.flatMap({ mined in [(mined, 0), (mined, max(1, (length - shared) / 3))] }) {
                        for alarm in [false, true] {
                            var routes = lengths.enumerated().map { route, count in
                                let txids = (0..<count).map { $0 < shared ? "s\($0)" : "r\(route)-\($0)" }
                                let done = mined < shared ? mined : max(shared, min(count, mined + (route - 1) * spread))
                                let statuses: [ExitGutterTree.Status] = (0..<count).map {
                                    $0 < done ? .mined : $0 == done ? (alarm ? .needsFunds : .pending) : .unsent
                                }
                                return ExitGutterTree.Route(txids: txids, statuses: statuses, coin: ExitGutterCoin())
                            }
                            routes.sort { $0.txids.lexicographicallyPrecedes($1.txids) }
                            let ys = routes.indices.map { 32 + Double($0) * 64 }
                            let layout = ExitGutterTree.layout(routes, ys: ys, gutter: gutter, step: step)
                            checked += 1
                            let note = "gutter \(gutter), \(length) long, \(shared) shared, \(mined) mined ±\(spread)\(alarm ? ", needs funds" : "")"
                            var shapes = [ExitStageShape.box(.round, "root", CGRect(x: layout.root.x - 4.5, y: layout.root.y - 4.5, width: 9, height: 9))]
                            for node in layout.nodes {
                                shapes.append(node.status == .needsFunds ? .circle(.node, node.id, node.point, 6)
                                    : .box(.node, node.id, CGRect(x: node.point.x - 4, y: node.point.y - 4, width: 8, height: 8)))
                            }
                            shapes += layout.coins.enumerated().map { .circle(.coin, "coin\($0.offset)", $0.element, 14) }
                            for (index, a) in shapes.enumerated() {
                                if a.frame.minX < 0 || a.frame.maxX > gutter + 14 { problems.append("\(note): \(a.group) leaves the gutter") }
                                for b in shapes[(index + 1)...] where a.overlaps(b, margin: ExitStageGeometry.clearance) {
                                    problems.append("\(note): \(a.group) overlaps \(b.group)")
                                }
                            }
                            let plan = ExitGutterTree.plan(routes, capacity: capacity)
                            for route in routes {
                                let drawn = plan.map(route.drawn(plan:)) ?? route.drawn(capacity: capacity)
                                if drawn.count - 1 > capacity { problems.append("\(note): \(drawn.count - 1) steps") }
                                // Its current transaction, where it's a step, is drawn on its own.
                                if route.current < route.txids.count - 1,
                                   !drawn.contains(where: { $0.fold == nil && $0.id.hasSuffix("/" + route.txids[route.current]) }) {
                                    problems.append("\(note): current folded away")
                                }
                            }
                            // Shared transactions are drawn once while one plan fits.
                            if plan != nil, shared > 0, layout.nodes.count(where: { $0.id.hasSuffix("/s0") }) > 1 { problems.append("\(note): s0 drawn twice") }
                        }
                    }
                }
            }
        }
        #expect(checked > 500)
        #expect(problems.isEmpty, "\(problems.count) problems, e.g. \(problems.prefix(12).joined(separator: "\n"))")
    }

    /// Review's one-line path keeps its transactions apart: as many as fit 14 pt apart, eight at most, the caption saying
    /// how many there are. Sweeps phone and wide widths and 1–20, 50 and 100 transactions.
    @Test func reviewsRailKeepsItsSpacing() {
        var problems: [String] = []
        for width in [280.0, 300, 327, 345, 360, 393, 420, 500, 700] {
            for transactions in Array(1...20) + [50, 100] {
                let size = CGSize(width: width, height: 58), layout = ExitPathRail.layout(size, transactions: transactions), shapes = layout.shapes
                if layout.nodes.count + 1 > min(8, transactions) { problems.append("\(width) \(transactions): drew \(layout.nodes.count + 1)") }
                for (index, a) in shapes.enumerated() {
                    if !CGRect(origin: .zero, size: size).contains(a.frame) { problems.append("\(width) \(transactions): \(a.group) leaves") }
                    for b in shapes[(index + 1)...] where a.overlaps(b, margin: ExitStageGeometry.clearance) {
                        problems.append("\(width) \(transactions): \(a.group) overlaps \(b.group)")
                    }
                }
            }
        }
        #expect(problems.isEmpty, "\(problems.prefix(12).joined(separator: "\n"))")
        // On an SE (327 pt wide) eight would be 9.2 pt apart: it draws five.
        #expect(ExitPathRail.layout(CGSize(width: 327, height: 58), transactions: 8).nodes.count + 1 == 5)
    }

    @Test func aGutterCoinCarriesItsState() {
        func coin(_ state: String, _ details: ExitDetails, tip: UInt32 = 200, needsFunds: Bool = false) -> ExitGutterCoin {
            let exit = Self.exit("x", state, [], details: details)
            return ExitGutterCoin(ExitProgress(exit, tip: tip, needsFunds: needsFunds), tip: tip, delta: 144)
        }
        #expect(coin("Claimable", .init(kind: "claimable", claimable_since: .init(height: 190, hash: "h"))).ready)
        let waiting = coin("AwaitingDelta", .init(kind: "awaiting-delta", confirmed_block: .init(height: 100, hash: "h"), claimable_height: 244), tip: 172)
        #expect(waiting.lock == 0.5)
        #expect(coin("Claimed", .init(kind: "claimed", block: .init(height: 250, hash: "h"))).claimed)
        #expect(coin("Canceled", .init(kind: "canceled")).canceled)
        let parked = ExitDetails(kind: "processing", transactions: [Self.tx("r", "confirmed", height: 99), Self.tx("l", "awaiting-cpfp-broadcast")])
        #expect(coin("Processing", parked, needsFunds: true).alarm)
        let last = ExitDetails(kind: "processing", transactions: [Self.tx("r", "confirmed", height: 99), Self.tx("l", "awaiting-confirmation")])
        let leaf = coin("Processing", last)
        #expect(leaf.waiting && leaf.pulses(at: .zero, id: "x").first?.kind == .coin(9))
        #expect(coin("Claimable", .init(kind: "claimable", claimable_since: .init(height: 190, hash: "h"))).pulses(at: .zero, id: "x").first?
            .kind == .glow(9.2))
    }

    /// The scene comes from bark's state: each transaction's status, what your other exits share and where they branch off, and the claim's destination.
    @MainActor @Test func theSceneComesFromBarksState() throws {
        let root = FileManager.default.temporaryDirectory.appending(path: "exit-stage-\(UUID())")
        defer { try? FileManager.default.removeItem(at: root) }
        let app = AppModel(settings: try SettingsStorage(rootPath: root.path))
        let money = ExitMoney(app: app, locale: Self.english)
        let shared = [Self.tx("r1", "confirmed", height: 99), Self.tx("l2", "confirmed", height: 100), Self.tx("l1", "awaiting-confirmation")]
        var mine = Self.exit("a", "Processing", shared + [Self.tx("leafA", "verify-inputs")])
        mine.details.transactions?[2].status.child_txid = "child"
        mine.details.transactions?[2].status.origin = "wallet"
        let sibling = Self.exit("b", "Processing", shared + [Self.tx("leafB", "verify-inputs")])
        let cousin = Self.exit("c", "Processing", [Self.tx("r1", "confirmed", height: 99), Self.tx("l2c", "verify-inputs"), Self.tx("leafC", "verify-inputs")])
        var overview = OnchainOverview(
            walletID: "w",
            vtxos: [],
            exits: [mine, sibling, cousin],
            confirmed: 18_400,
            tip: 101,
            pendingTotal: 0,
            claimableHeight: nil)
        overview.childFees = ["child": 1_215]
        let text = ExitText(progress: ExitProgress(mine, tip: 101), exit: mine, tip: 101, overview: overview, attempts: [], money: money, language: "en")
        let scene = text.scene
        #expect(scene.levels == [.mined(99), .mined(100), .pending(theirs: false, bumped: false), .unsent])
        #expect(scene.shared == [true, true, true, false])
        #expect(scene.others.map(\.level).sorted() == [1, 3])
        #expect(scene.others.first { $0.level == 1 }?.sent == false)
        #expect(scene.fee == money(1_215) && scene.balance == money(18_400))
        // Claimed to another wallet: the address from what Noah recorded, once the journal entry is gone.
        let claimed = Self.exit("a", "Claimed", [], details: .init(kind: "claimed", block: .init(height: 300, hash: "h"), txid: "claim"))
        overview.claimRecords = ["claim": .init(ids: ["a"], fee: 262, toPhone: false, destination: "bcrt1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqdku202")]
        overview.exits = [claimed]
        let done = ExitText(progress: ExitProgress(claimed, tip: 301), exit: claimed, tip: 301, overview: overview, attempts: [], money: money, language: "en")
            .scene
        #expect(done.picture == .claimed && done.destination == .other("bcrt1q…u202") && done.claimHeight == 300 && done.claimFee == money(262))
        // VoiceOver reads where it went, address in full: the picture is the only place that says so.
        let label = ExitText(progress: ExitProgress(claimed, tip: 301), exit: claimed, tip: 301, overview: overview, attempts: [], money: money,
                             language: "en").pictureLabel
        #expect(label.hasPrefix("Claimed in block 300") && label.hasSuffix("Another wallet, bcrt1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqdku202"))
        overview.claimRecords["claim"]?.toPhone = true
        overview.claimRecords["claim"]?.destination = nil
        let phone = ExitText(progress: ExitProgress(claimed, tip: 301), exit: claimed, tip: 301, overview: overview, attempts: [], money: money, language: "en")
            .scene
        #expect(phone.destination == .phone && phone.received != nil)
        let phoneLabel = ExitText(progress: ExitProgress(claimed, tip: 301), exit: claimed, tip: 301, overview: overview, attempts: [], money: money,
                                  language: "en").pictureLabel
        #expect(phoneLabel.hasSuffix("This phone") && !phoneLabel.contains("bcrt1q"))
        // Before the claim, the label doesn't name a destination.
        #expect(!text.pictureLabel.contains("This phone") && !text.pictureLabel.contains("Another wallet"))
    }
}
