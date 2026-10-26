import BarkBoltFFI
import Foundation
import Testing

@testable import Noah

/// The redesigned exit's rules (docs/native-rewrite/design/exit.md, Testing): bark's states to
/// phases and pictures, the inferred needs-funds state, the per-VTXO fee rule, the Settings row
/// and which exits share a path.
struct ExitProgressTests {
    typealias Tx = ExitDetails.Transaction

    static func tx(_ id: String, _ kind: String, height: UInt32? = nil, child: String? = nil, origin: String? = nil) -> Tx {
        .init(txid: id, status: .init(kind: kind, child_txid: child, block: height.map { .init(height: $0, hash: "h\($0)") }, origin: origin))
    }

    static func exit(_ state: String, _ details: ExitDetails, history: [ExitDetails] = [], amount: UInt64 = 31_000, id: String = "v") -> ExitModel {
        ExitModel(id: id, amount: amount, state: state, details: details, history: history.map { _ in "Processing" },
                  historyDetails: history, claimable: state == "Claimable")
    }

    static func processing(_ txs: [Tx], tip: UInt32 = 100) -> ExitDetails {
        .init(kind: "processing", tip_height: tip, transactions: txs)
    }

    static let path = ["root", "l2", "l1", "leaf"]

    /// A signet exit (VTXO 4bd98032…) that shares ancestors with two others: each path transaction is paid by its CPFP
    /// child, and steps 1–7 were sent once for all of them. Its page shows the whole path, and how much of it is shared.
    @Test func feesCoverTheWholePathAndSayWhatIsShared() {
        let fees: [UInt64] = [32, 407, 364, 28, 37, 28, 37, 28]
        let steps = (1...8).map { Self.tx("t\($0)", "confirmed", height: 100 + UInt32($0), child: "c\($0)", origin: $0 == 8 ? "wallet" : "block") }
        let this = Self.exit("Processing", Self.processing(steps), amount: 7_125, id: "4bd98032")
        let twin = Self.exit("Processing", Self.processing(Array(steps.prefix(7)) + [Self.tx("b8", "confirmed", height: 108, child: "cb8", origin: "wallet")]),
                             amount: 7_125, id: "twin")
        let other = Self.exit("Processing", Self.processing(Array(steps.prefix(5)) + [Self.tx("o6", "confirmed", height: 106, child: "co6", origin: "wallet")]),
                              id: "other")
        var childFees = Dictionary(uniqueKeysWithValues: fees.enumerated().map { ("c\($0.offset + 1)", $0.element) })
        childFees["cb8"] = 31
        childFees["co6"] = 29
        let paid = ExitFees.paid(this, among: [this, twin, other], childFees: childFees)
        #expect(paid.total == 961 && paid.shared == 933)
        // Alone, nothing is shared; a child that isn't this wallet's (no known fee) costs nothing.
        #expect(ExitFees.paid(this, among: [this], childFees: childFees) == (961, 0))
        childFees["c1"] = nil
        #expect(ExitFees.paid(this, among: [this, twin, other], childFees: childFees) == (929, 901))
        childFees["c1"] = 32
        // The list counts each child once, as Activity's "Exit fee" rows: 961 + 31 + 29, not each exit's path again.
        #expect(ExitFees.total([this, twin, other], childFees: childFees) == 1_021)
    }

    /// The explorer link opens the claim once there is one, else the furthest step that's been sent; nothing sent, none.
    @Test func theExplorerOpensTheClaimElseTheFurthestSentStep() {
        let path = [Self.tx("root", "confirmed", height: 101, child: "c1"), Self.tx("l1", "awaiting-confirmation", child: "c2"), Self.tx("leaf", "awaiting-cpfp-broadcast")]
        #expect(Self.exit("Processing", Self.processing(path)).explorerTxid == "l1")
        #expect(Self.exit("Processing", Self.processing([Self.tx("root", "awaiting-cpfp-broadcast")])).explorerTxid == nil)
        #expect(Self.exit("Claimed", .init(kind: "claimed", txid: "claim"), history: [Self.processing(path)]).explorerTxid == "claim")
    }

    @Test func everyBarkStateMapsToItsPhaseAndPicture() {
        let start = ExitProgress(Self.exit("Start", .init(kind: "start", tip_height: 100)), tip: 100)
        #expect(start.phase == .starting && start.picture == .lift && start.cancelable && start.live)

        let fresh = ExitProgress(Self.exit("Processing", Self.processing(Self.path.map { Self.tx($0, "verify-inputs") })), tip: 100)
        #expect(fresh.phase == .publishing && fresh.picture == .path && fresh.total == 4 && fresh.confirmed == 0)

        var txs = Self.path.map { Self.tx($0, "verify-inputs") }
        txs[0] = Self.tx("root", "awaiting-cpfp-broadcast")
        let parked = Self.exit("Processing", Self.processing(txs))
        #expect(ExitProgress(parked, tip: 100).phase == .sending)
        #expect(ExitProgress(parked, tip: 100).step == 1)
        #expect(ExitProgress(parked, tip: 100, needsFunds: true).phase == .needsFunds)

        txs[0] = Self.tx("root", "awaiting-confirmation", child: "c0", origin: "wallet")
        let paid = ExitProgress(Self.exit("Processing", Self.processing(txs)), tip: 100)
        #expect(paid.phase == .mempool && paid.step == 1 && paid.stuck == 0 && !paid.bumped)
        txs[0] = Self.tx("root", "awaiting-confirmation", child: "theirs", origin: "mempool")
        #expect(ExitProgress(Self.exit("Processing", Self.processing(txs)), tip: 100).phase == .shared)

        txs[0] = Self.tx("root", "confirmed", height: 101, child: "c0", origin: "wallet")
        let one = ExitProgress(Self.exit("Processing", Self.processing(txs, tip: 101)), tip: 101)
        #expect(one.phase == .confirmed && one.confirmed == 1 && one.heights.first == 101)
        // The last transaction is still unsent, so cancel stays possible.
        #expect(one.cancelable)

        let mined = Self.path.enumerated().map { Self.tx($1, "confirmed", height: 101 + UInt32($0), child: "c\($0)", origin: "wallet") }
        let onchain = ExitProgress(Self.exit("Processing", Self.processing(mined, tip: 104)), tip: 104)
        #expect(onchain.phase == .onchain && onchain.confirmed == 4 && !onchain.cancelable)

        let history = [Self.processing(mined, tip: 104)]
        let waiting = ExitProgress(Self.exit("AwaitingDelta", .init(kind: "awaiting-delta", tip_height: 150,
            confirmed_block: .init(height: 104, hash: "h"), claimable_height: 248), history: history), tip: 150)
        #expect(waiting.phase == .waiting && waiting.picture == .lock && waiting.left(tip: 150) == 98)
        // Later states keep the path bark last listed.
        #expect(waiting.total == 4 && waiting.confirmed == 4)
        // bark's last list can still show the leaf in the mempool: past Processing it's in a block.
        var leafPending = mined
        leafPending[3] = Self.tx("leaf", "awaiting-confirmation", child: "c3", origin: "wallet")
        let jumped = ExitProgress(Self.exit("AwaitingDelta", .init(kind: "awaiting-delta", tip_height: 150,
            confirmed_block: .init(height: 104, hash: "h"), claimable_height: 248), history: [Self.processing(leafPending, tip: 103)]), tip: 150)
        #expect(jumped.confirmed == 4 && jumped.heights.last == 104)

        let claimable = ExitProgress(Self.exit("Claimable", .init(kind: "claimable", tip_height: 248,
            claimable_since: .init(height: 248, hash: "h"))), tip: 248)
        #expect(claimable.phase == .claimable && claimable.picture == .unlocked && claimable.claimableHeight == 248)

        let claiming = ExitProgress(Self.exit("ClaimInProgress", .init(kind: "claim-in-progress", tip_height: 248,
            claimable_since: .init(height: 248, hash: "h"), claim_txid: "claim")), tip: 248)
        #expect(claiming.phase == .claiming && claiming.picture == .claim && claiming.live)

        let claimed = ExitProgress(Self.exit("Claimed", .init(kind: "claimed", tip_height: 249, block: .init(height: 249, hash: "h"),
            txid: "claim")), tip: 260)
        #expect(claimed.phase == .claimed && claimed.picture == .claimed && claimed.claimedHeight == 249 && !claimed.live)
        // Finished exits are never stale.
        #expect(!claimed.stale)

        for (state, phase) in [("Canceled", ExitProgress.Phase.canceled), ("VtxoAlreadySpent", .alreadySpent), ("VtxoSwept", .swept)] {
            let progress = ExitProgress(Self.exit(state, .init(kind: state, tip_height: 100)), tip: 100)
            #expect(progress.phase == phase && !progress.live)
            #expect(progress.picture == (state == "Canceled" ? .canceled : .none))
        }
    }

    @Test func mempoolWaitCountsBlocksAndSeesRebumps() {
        var first = Self.path.map { Self.tx($0, "verify-inputs") }
        first[0] = Self.tx("root", "awaiting-confirmation", child: "c0", origin: "wallet")
        var now = first
        // One episode across bark's records: the first sighting counts, re-bumps included.
        let seen = ["root|awaiting-confirmation|0": UInt32(100)]
        let stuck = ExitProgress(Self.exit("Processing", Self.processing(now, tip: 100), history: [Self.processing(first, tip: 100)]), tip: 103, seen: seen)
        #expect(stuck.phase == .mempool && stuck.stuck == 3 && !stuck.bumped)

        now[0] = Self.tx("root", "awaiting-confirmation", child: "c0-rbf", origin: "wallet")
        let bumped = ExitProgress(Self.exit("Processing", Self.processing(now, tip: 101), history: [Self.processing(first, tip: 100)]), tip: 101, seen: seen)
        #expect(bumped.bumped && bumped.stuck == 1)

        // Evicted, parked, then a fresh child at the same rate: a new episode. The old child isn't a rise,
        // and the old sighting doesn't count blocks for this one.
        var parked = first
        parked[0] = Self.tx("root", "awaiting-cpfp-broadcast")
        now[0] = Self.tx("root", "awaiting-confirmation", child: "c1", origin: "wallet")
        let history = [Self.processing(first, tip: 100), Self.processing(parked, tip: 102)]
        let fresh = Self.exit("Processing", Self.processing(now, tip: 103), history: history)
        #expect(fresh.episode(now[0]) == "root|awaiting-confirmation|2")
        let again = ExitProgress(fresh, tip: 105, seen: seen.merging(["root|awaiting-confirmation|2": 103]) { $1 })
        #expect(!again.bumped && again.stuck == 2)
        #expect(ExitProgress(fresh, tip: 105, seen: seen).stuck == 0)
        // Bumped within the new episode: a rise again.
        var rebumped = now
        rebumped[0] = Self.tx("root", "awaiting-confirmation", child: "c1-rbf", origin: "wallet")
        #expect(ExitProgress(Self.exit("Processing", Self.processing(rebumped, tip: 104), history: history + [Self.processing(now, tip: 103)]), tip: 104).bumped)
    }

    @Test func staleOnlyWithEvidenceThatNothingProgresses() {
        let behind = Self.exit("Processing", Self.processing(Self.path.map { Self.tx($0, "verify-inputs") }, tip: 90))
        // bark's daemon keeps exits current while it runs: never stale, however old bark's record.
        #expect(!ExitProgress(behind, tip: 100).stale)
        // No daemon, or the last Check the chain failed.
        #expect(ExitProgress(behind, tip: 100, progressing: false).stale)
        // "Last checked" is Noah's own check, never bark's frozen height.
        #expect(ExitProgress(behind, tip: 100).checked == nil)
        #expect(ExitProgress(behind, tip: 100, checkedTip: 98).checked == 98)
        // Waiting exits are never stale.
        let waiting = Self.exit("AwaitingDelta", .init(kind: "awaiting-delta", tip_height: 90, claimable_height: 234))
        #expect(!ExitProgress(waiting, tip: 150, progressing: false).stale)
    }

    @Test func aSentClaimReadsClaimingUntilBarkSeesIt() {
        let ready = Self.exit("Claimable", .init(kind: "claimable", tip_height: 248, claimable_since: .init(height: 248, hash: "h")))
        #expect(ExitProgress(ready, tip: 248).phase == .claimable)
        #expect(ExitProgress(ready, tip: 248, claimSent: true).phase == .claiming)
        // Before bark records the claim, the journal's broadcast covers the gap.
        #expect(ready.awaitsClaim("claim"))
        // bark saw it, then it fell out of the mempool: bark's Claimable counts again.
        let evicted = ExitModel(id: "v", amount: 31_000, state: "Claimable",
            details: .init(kind: "claimable", tip_height: 250, claimable_since: .init(height: 248, hash: "h")),
            history: ["Claimable", "ClaimInProgress"],
            historyDetails: [.init(kind: "claimable", tip_height: 248, claimable_since: .init(height: 248, hash: "h")),
                             .init(kind: "claim-in-progress", tip_height: 249, claimable_since: .init(height: 248, hash: "h"), claim_txid: "claim")],
            claimable: true)
        #expect(!evicted.awaitsClaim("claim"))
        // A later claim, not yet seen, is covered again.
        #expect(evicted.awaitsClaim("claim-2"))
    }

    // MARK: Decision 3

    @Test func needsFundsNeedsFundsShortAndACycleThatTried() {
        var txs = Self.path.map { Self.tx($0, "verify-inputs") }
        txs[0] = Self.tx("root", "confirmed", height: 101)
        txs[1] = Self.tx("l2", "awaiting-cpfp-broadcast")
        let exit = Self.exit("Processing", Self.processing(txs, tip: 101))
        #expect(exit.parkedTxid == "l2" && exit.parkedEpisode == "l2|awaiting-cpfp-broadcast|0")
        let t0 = Date(timeIntervalSince1970: 1_000)
        func needs(_ confirmed: UInt64 = 310, fee: UInt64? = 1_215, proven: [String: String] = [:], seen: Date? = t0, daemon: Date? = .distantPast,
                   at seconds: TimeInterval, _ subject: ExitModel = exit) -> Bool {
            ExitFunds.needsFunds(subject, confirmed: confirmed, packageFee: fee, proven: proven, parkedSince: seen, daemonSince: daemon,
                                 now: t0.addingTimeInterval(seconds))
        }
        // Funds short, but bark hasn't had a full daemon cycle to try yet: no label.
        #expect(!needs(at: 0) && !needs(at: 74))
        // One daemon interval and its margin since Noah first saw it parked.
        #expect(needs(at: 75))
        // The daemon started after it parked: the cycle counts from then.
        #expect(!needs(daemon: t0.addingTimeInterval(30), at: 75) && needs(daemon: t0.addingTimeInterval(30), at: 105))
        // Without the daemon, elapsed time proves nothing; a completed Check the chain does.
        let proof = ["v": "l2|awaiting-cpfp-broadcast|0"]
        #expect(!needs(daemon: nil, at: 10_000) && needs(proven: proof, daemon: nil, at: 0))
        // Funds arrive, or nothing to compare against: cleared.
        #expect(!needs(1_215, at: 10_000) && !needs(fee: nil, proven: proof, at: 10_000))
        // Proof for another transaction doesn't carry over; once it has its child it isn't parked.
        #expect(!needs(proven: ["v": "root|awaiting-cpfp-broadcast|0"], daemon: nil, at: 0))
        // Proof for another exit doesn't either.
        #expect(!needs(proven: ["w": "l2|awaiting-cpfp-broadcast|0"], daemon: nil, at: 0))
        var moved = txs
        moved[1] = Self.tx("l2", "awaiting-confirmation", child: "c1", origin: "wallet")
        let paid = Self.processing(moved, tip: 101)
        #expect(!needs(proven: proof, at: 10_000, Self.exit("Processing", paid)))
        // Its child fell out of the mempool and it parked again: a new episode. The old proof and the old
        // sighting are gone with the status change; it waits for a check or a daemon cycle again.
        let first = Self.processing(txs, tip: 101)
        let again = Self.exit("Processing", Self.processing(txs, tip: 103), history: [first, paid])
        #expect(again.parkedEpisode == "l2|awaiting-cpfp-broadcast|2")
        #expect(!needs(proven: proof, daemon: nil, at: 10_000, again))
        var sightings = ExitSightings()
        _ = sightings.observe([Self.exit("Processing", first)], tip: 101, now: t0)
        _ = sightings.observe([Self.exit("Processing", paid, history: [first])], tip: 102, now: t0.addingTimeInterval(30))
        _ = sightings.observe([again], tip: 103, now: t0.addingTimeInterval(600))
        let since = sightings.seen[again.parkedEpisode!]?.at
        #expect(since == t0.addingTimeInterval(600))
        #expect(!needs(seen: since, at: 674, again) && needs(seen: since, at: 675, again))
        #expect(needs(proven: ["v": again.parkedEpisode!], daemon: nil, at: 0, again))
    }

    @Test func aRestartedExitIsANewRun() {
        // bark restarts a cancelled exit with an empty history: the same transactions, the same record index.
        var txs = Self.path.map { Self.tx($0, "verify-inputs") }
        txs[0] = Self.tx("root", "awaiting-cpfp-broadcast")
        var first = Self.exit("Processing", Self.processing(txs)), again = first
        first.run = "r1"; again.run = "r2"
        #expect(first.parkedEpisode == "root|awaiting-cpfp-broadcast|0|r1" && again.parkedEpisode == "root|awaiting-cpfp-broadcast|0|r2")
        #expect(ExitFunds.needsFunds(first, confirmed: 100, packageFee: 1_215, proven: ["v": first.parkedEpisode!], parkedSince: nil, daemonSince: nil, now: Date()))
        #expect(!ExitFunds.needsFunds(again, confirmed: 100, packageFee: 1_215, proven: ["v": first.parkedEpisode!], parkedSince: nil, daemonSince: nil, now: Date()))
        var sightings = ExitSightings()
        _ = sightings.observe([first], tip: 100, now: Date(timeIntervalSince1970: 1))
        #expect(sightings.seen[again.parkedEpisode!] == nil)
        // Each start writes a new run per VTXO.
        let disk = Disk(), store = ExitRuns.Store(read: { disk.value }, write: { disk.value = $0 })
        try? ExitRuns.begin(["v"], walletID: "a", store: store)
        let one = store.load(walletID: "a")?["v"]
        try? ExitRuns.begin(["v"], walletID: "a", store: store)
        #expect(one != nil && store.load(walletID: "a")?["v"] != one)
    }

    @Test func cancellingOrStartingForgetsThatExitsProof() async {
        let session = WalletSession(variant: .regtest, location: WalletDataLocation(variant: .regtest,
            documentsDirectory: FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)))
        await session.recordExitCheck(.init(tip: 100, parked: ["a": "x|awaiting-cpfp-broadcast|0", "b": "y|awaiting-cpfp-broadcast|0"]), walletID: "w")
        await session.forgetExitProof(["a"], walletID: "w")
        #expect(await session.exitChecks["w"]?.parked == ["b": "y|awaiting-cpfp-broadcast|0"])
    }

    @Test func barkHistoryIsNoEvidence() {
        // bark recorded it parked at an earlier tip, but only a completed Check the chain or a daemon cycle
        // Noah saw within this episode counts.
        var txs = Self.path.map { Self.tx($0, "verify-inputs") }
        txs[0] = Self.tx("root", "awaiting-cpfp-broadcast")
        let exit = Self.exit("Processing", Self.processing(txs, tip: 102), history: [Self.processing(txs, tip: 100)])
        #expect(exit.parkedEpisode == "root|awaiting-cpfp-broadcast|0")
        #expect(!ExitFunds.needsFunds(exit, confirmed: 100, packageFee: 1_215, proven: [:], parkedSince: nil, daemonSince: nil, now: Date()))
        #expect(!ExitFunds.needsFunds(exit, confirmed: 100, packageFee: 1_215, proven: [:], parkedSince: nil, daemonSince: .distantPast, now: Date()))
    }

    @Test func sightingsKeepTheFirstTipAndForgetWhatMovedOn() throws {
        var txs = Self.path.map { Self.tx($0, "verify-inputs") }
        txs[0] = Self.tx("root", "awaiting-confirmation", child: "c0", origin: "wallet")
        let exit = Self.exit("Processing", Self.processing(txs, tip: 100))
        var sightings = ExitSightings()
        let added = sightings.observe([exit], tip: 100, now: Date(timeIntervalSince1970: 1))
        #expect(added)
        // Seen again later: the first sighting stays.
        let again = sightings.observe([exit], tip: 104, now: Date(timeIntervalSince1970: 500))
        #expect(!again)
        #expect(sightings.tips["root|awaiting-confirmation|0"] == 100)
        // A new status is a new sighting; the old one is forgotten.
        txs[0] = Self.tx("root", "confirmed", height: 105)
        txs[1] = Self.tx("l2", "awaiting-cpfp-broadcast")
        let moved = sightings.observe([Self.exit("Processing", Self.processing(txs, tip: 105))], tip: 105, now: Date(timeIntervalSince1970: 600))
        #expect(moved)
        #expect(sightings.seen.keys.contains("l2|awaiting-cpfp-broadcast|0"))
        #expect(sightings.tips["root|awaiting-confirmation|0"] == nil)
        // Persisted per wallet, across launches.
        let disk = Disk()
        let store = ExitSightings.Store(read: { disk.value }, write: { disk.value = $0 })
        try store.save(sightings, walletID: "a")
        #expect(ExitSightings.Store(read: { disk.value }, write: { _ in }).load(walletID: "a") == sightings)
        #expect(store.load(walletID: "b") == nil)
    }

    final class Disk: @unchecked Sendable { var value: String? }

    @Test func theParkedPackageIsPricedItselfNotAsAnAverage() {
        // A root of 2,821 WU (705 vB) and bark's 609 WU child at 1 sat/vB: 3,430 WU, 858 sats.
        #expect(ExitFunds.packageFee(parentWeight: 2_821, satPerVb: 1) == 858)
        // Rounded up, as bark's package_fee: 3,432 WU is exactly 858 sats, one more weight unit is 859.
        #expect(ExitFunds.packageFee(parentWeight: 2_823, satPerVb: 1) == 858)
        #expect(ExitFunds.packageFee(parentWeight: 2_824, satPerVb: 1) == 859)
        #expect(ExitFunds.packageFee(parentWeight: 470, satPerVb: 10) == 2_698)
        // 1,180 confirmed against the root's 1,215 (not the path's 1,152 average): it needs funds.
        var txs = Self.path.map { Self.tx($0, "verify-inputs") }
        txs[0] = Self.tx("root", "awaiting-cpfp-broadcast")
        let exit = Self.exit("Processing", Self.processing(txs, tip: 100))
        #expect(ExitFunds.needsFunds(exit, confirmed: 1_180, packageFee: 1_215, proven: ["v": "root|awaiting-cpfp-broadcast|0"],
                                     parkedSince: nil, daemonSince: nil, now: Date()))
    }

    @Test func mempoolWaitCountsFromNoahsFirstSighting() {
        var txs = Self.path.map { Self.tx($0, "verify-inputs") }
        txs[0] = Self.tx("root", "awaiting-confirmation", child: "c0", origin: "wallet")
        // bark keeps the state, height included, while nothing changes: Noah's sighting counts the blocks.
        let frozen = Self.exit("Processing", Self.processing(txs, tip: 100))
        let seen = ["root|awaiting-confirmation|0": UInt32(101)]
        #expect(ExitProgress(frozen, tip: 104, seen: seen).stuck == 3)
        #expect(ExitProgress(frozen, tip: 104).stuck == 0)
    }

    @Test func aLongerRealPathKeepsEveryNodeAfterStart() {
        // Start draws a round's usual four; bark then lists eight (tree hops plus checkpoint or arkoor hops).
        let start = ExitStageScene(picture: .lift, levels: Array(repeating: .unsent, count: 4))
        let path = ExitStageScene(picture: .path, levels: Array(repeating: .unsent, count: 8))
        let first = ExitStageFrame(start, path, elapsed: 0, reduceMotion: false)
        #expect(first.mined.count == 8 && first.pending.count == 8 && first.grow.count == 9 && first.pop.count == 9)
        // The branch grows from the ground, root first; once it has, every node is there.
        let grown = ExitStageFrame(start, path, elapsed: 4, reduceMotion: false)
        #expect(grown.grow[1...].allSatisfy { $0 == 1 } && grown.pop[1..<8].allSatisfy { $0 > 0.9 })
        let ys = ExitStageGeometry(size: CGSize(width: 390, height: 300), count: 8).levels(grown.mined)
        #expect(ys.count == 9 && zip(ys, ys.dropFirst()).allSatisfy { $0 > $1 })
    }

    @Test func aBatchedClaimsFeeIsSplitSoThePartsAddUp() {
        let parts = ExitClaimSplit.fees(["a": 31_000, "b": 24_500], fee: 262)
        #expect(parts.values.reduce(0, +) == 262)
        #expect((31_000 - parts["a"]!) + (24_500 - parts["b"]!) == 55_238)
        #expect(parts == ["a": 146, "b": 116])
        #expect(ExitClaimSplit.fees(["a": 31_000], fee: 262) == ["a": 262])
        let thirds = ExitClaimSplit.fees(["a": 1, "b": 1, "c": 1], fee: 100)
        #expect(thirds.values.reduce(0, +) == 100 && thirds.values.allSatisfy { $0 == 33 || $0 == 34 })
    }

    // MARK: Decision 4

    static func vtxo(_ id: String, _ amount: UInt64, state: String = "Spendable") -> VtxoModel {
        VtxoModel(id: id, amount: amount, expiry: 5_000, state: state, kind: "round", exitDepth: 2, exitWeight: 1_000,
                  registered: true, serverPubkey: "02", exitDelta: 144, anchorPoint: "a:0", point: "\(id):0", expiring: false, tip: 100)
    }

    /// bark's estimator: each distinct transaction once, plus a claim input per VTXO.
    static func estimator(_ paths: [String: [String]]) -> @Sendable ([String]) async throws -> UInt64 {
        { ids in
            #expect(!ids.isEmpty, "An empty list prices the whole wallet")
            return UInt64(Set(ids.flatMap { paths[$0]! }).count) * 1_000 + UInt64(ids.count) * 100
        }
    }

    @Test func feesMoreThanItsWorthPricesEachVtxoOnItsOwn() async throws {
        // A and B share the round's root; C is alone in its round.
        let paths = ["a": ["r1", "a"], "b": ["r1", "b"], "c": ["r2", "c"]]
        let vtxos = [Self.vtxo("a", 2_100), Self.vtxo("b", 1_100), Self.vtxo("c", 2_200)]
        let costs = await ExitEconomics.classify(vtxos, estimate: Self.estimator(paths))
        // Each carries its whole path and a claim input on its own: 2,100. A and B meet it; C is worth more.
        #expect(costs == ["a": .uneconomic, "b": .uneconomic, "c": .worthIt])
        #expect(ExitEconomics.classified(vtxos, costs: costs))
        #expect(await ExitEconomics.classify([], estimate: Self.estimator(paths)).isEmpty)
    }

    @Test func aFailedEstimateKeepsItsOwnStateAndHoldsAll() async {
        let vtxos = [Self.vtxo("a", 31_000), Self.vtxo("b", 24_500), Self.vtxo("c", 1_100)]
        let costs = await ExitEconomics.classify(vtxos) { ids in
            if ids == ["b"] { throw OnchainFailure.unavailable }
            return 2_100
        }
        // One failure doesn't throw the others away.
        #expect(costs == ["a": .worthIt, "b": .failed, "c": .uneconomic])
        // All waits: it can't know yet whether B is worth its fees.
        #expect(!ExitEconomics.classified(vtxos, costs: costs))
        #expect(!ExitEconomics.classified(vtxos, costs: ["a": .worthIt]))
        #expect(ExitEconomics.all(vtxos, costs: costs).map(\.id) == ["a"])
    }

    @Test func allLeavesOutUneconomicDustAndLocked() {
        let vtxos = [Self.vtxo("a", 31_000), Self.vtxo("b", 1_100), Self.vtxo("dust", 290), Self.vtxo("locked", 710, state: "Locked")]
        #expect(ExitEconomics.all(vtxos, costs: ["a": .worthIt, "b": .uneconomic]).map(\.id) == ["a"])
        #expect(ExitEconomics.all(vtxos, costs: ["a": .worthIt, "b": .worthIt]).map(\.id) == ["a", "b"])
        // Dust and locked VTXOs never need a classification.
        #expect(ExitEconomics.classified(vtxos, costs: ["a": .worthIt, "b": .worthIt]))
    }

    // MARK: Decisions 5, 6 and 8

    @Test func settingsRowSaysInProgressOnlyWhileLive() {
        #expect(ExitRules.settingsValue([]) == nil)
        for state in ExitRules.finished {
            #expect(ExitRules.settingsValue([Self.exit(state, .init(kind: state))]) == nil)
        }
        for state in ["Start", "Processing", "AwaitingDelta", "Claimable", "ClaimInProgress"] {
            #expect(ExitRules.settingsValue([Self.exit("Claimed", .init(kind: "claimed")), Self.exit(state, .init(kind: state))]) == "In progress")
        }
    }

    @Test func sharedPictureOnlyWhenExitsShareATransaction() {
        let a = Self.exit("Processing", Self.processing([Self.tx("r1", "confirmed", height: 101), Self.tx("a", "verify-inputs")]), id: "a")
        let b = Self.exit("Processing", Self.processing([Self.tx("r1", "confirmed", height: 101), Self.tx("b", "verify-inputs")]), id: "b")
        let c = Self.exit("Processing", Self.processing([Self.tx("r2", "verify-inputs"), Self.tx("c", "verify-inputs")]), id: "c")
        #expect(ExitSharing.groups([a, c]).isEmpty)
        #expect(ExitSharing.groups([a, b, c]).map { $0.map(\.id) } == [["a", "b"]])
        // Past processing, the path comes from bark's history; finished exits never count.
        let waiting = Self.exit("AwaitingDelta", .init(kind: "awaiting-delta", tip_height: 150, claimable_height: 248),
                                history: [Self.processing([Self.tx("r2", "confirmed", height: 101), Self.tx("w", "confirmed", height: 102)])], id: "w")
        #expect(ExitSharing.groups([c, waiting]).map { $0.map(\.id) } == [["c", "w"]])
        let claimed = Self.exit("Claimed", .init(kind: "claimed"), history: [Self.processing([Self.tx("r2", "confirmed", height: 101)])], id: "x")
        #expect(ExitSharing.groups([c, claimed]).isEmpty)
        // Each connected group is its own picture.
        #expect(ExitSharing.groups([a, b, c, waiting]).map { $0.map(\.id) } == [["a", "b"], ["c", "w"]])
    }

    @Test func claimToAnotherWalletTakesThisNetworksAddressesOnly() {
        #expect(ExitRules.acceptsClaimAddress("bcrt1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqdku202", network: "regtest"))
        #expect(ExitRules.acceptsClaimAddress("mipcBbFg9gMiCh81Kj8tqqdgoZub1ZJRfn", network: "regtest"))
        #expect(ExitRules.acceptsClaimAddress("tb1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq0l98cr", network: "signet"))
        #expect(ExitRules.acceptsClaimAddress("1BoatSLRHtKNngkdXEeobR76b53LETtpyT", network: "mainnet"))
        for wrong in ["1BoatSLRHtKNngkdXEeobR76b53LETtpyT", "tb1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq0l98cr", "invalid", "",
                      "bitcoin:bcrt1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqdku202"] {
            #expect(!ExitRules.acceptsClaimAddress(wrong, network: "regtest"), "\(wrong)")
        }
        #expect(!ExitRules.acceptsClaimAddress("bcrt1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqdku202", network: "mainnet"))
    }
}

/// Hold to start and hold to claim confirm through the model, which submits a review at most once.
@MainActor
struct ExitHoldTests {
    actor Count {
        var value = 0
        func hit() { value += 1 }
    }

    static func model(_ count: Count) -> OnchainModel {
        let overview = OnchainOverview(walletID: "w", vtxos: [], exits: [], confirmed: 18_400, tip: 100, pendingTotal: 0, claimableHeight: nil)
        return OnchainModel(dependencies: .init(ready: { true }, overview: { overview },
            quote: { action, ids, _, destination in
                .init(action: action, walletID: "w", ids: ids, destination: destination, amount: 31_000, fee: 262,
                      chainFee: action == .claim ? 0 : 5_533, confirmed: 18_400)
            },
            execute: { _ in
                await count.hit()
                try await Task.sleep(for: .milliseconds(100))
                return "Submitted."
            }, attempt: { [] }, acknowledge: { _ in }))
    }

    @Test(arguments: [OnchainAction.startExit, .claim]) func aHeldReviewSubmitsOnce(action: OnchainAction) async {
        let count = Count()
        let model = Self.model(count)
        await model.review(action, ids: ["v"], destination: action == .claim ? "bcrt1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqdku202" : nil)
        #expect(model.quote != nil)
        // Two holds (a pose change rebuilt the button, or VoiceOver's action raced a finger).
        async let first = model.confirm()
        async let second = model.confirm()
        _ = await (first, second)
        #expect(await count.value == 1)
        // The review is spent: holding again submits nothing.
        await model.confirm()
        #expect(await count.value == 1)
    }
}

/// Decision 8: claiming to this phone's on-chain wallet puts the claimed output in auto-board's
/// handled ledger, so auto-board never offers it; claims to another wallet don't touch the ledger.
struct ExitClaimLedgerTests {
    actor Marks {
        var txids: [String] = []
        func add(_ txid: String) { txids.append(txid) }
    }

    @Test func aClaimToThisPhoneMarksItsOutputOnceSent() async throws {
        for mine in [true, false] {
            let file = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)
            defer { try? FileManager.default.removeItem(at: file) }
            let wallet = ExitClaimExecutionTests.Wallet()
            if !mine { await wallet.change("own-external") }
            let marks = Marks()
            var access = wallet.access()
            access.claimedToPhone = { await marks.add($0) }
            let destination = mine ? ExitClaimExecutionTests.ownAddress : ExitClaimExecutionTests.externalAddress
            let reviewed = try await WalletSession.claimQuote(ids: ["a"], destination: destination, access: access)
            _ = try await WalletSession.executeClaim(reviewed, journal: OnchainOperationJournal(file: file), access: access)
            // Recorded before the broadcast, from the signed transaction itself.
            #expect(await marks.txids == (mine ? [ExitClaimExecutionTests.claimTxid] : []))
        }
    }

    @MainActor @Test func claimedOutputsAreNeverOfferedAndTheRestIsUnchanged() async throws {
        var disk: String?
        var store = AutoBoardFunding()
        store.read = { disk }; store.write = { disk = $0 }
        try AutoBoardFunding.markClaimed(["claim:0"], walletID: "wallet", store: store)
        let claimed = AutoBoardFunding.Snapshot(walletID: "wallet", outpoints: ["claim:0"])
        #expect(try !store.hasUnhandled(claimed))
        // Auto-board never offers them: it doesn't even ask for a quote.
        let review = AutoBoardReview(store: store)
        var quoted = false
        #expect(try await review.prepare(allowed: { true }, funding: { claimed }, quote: { quoted = true; return nil }, discard: { _ in }) == nil)
        #expect(!quoted)
        // A real deposit beside it still counts, as before.
        #expect(try store.hasUnhandled(.init(walletID: "wallet", outpoints: ["claim:0", "deposit:0"])))
        // Nothing to mark writes nothing.
        let before = disk
        try AutoBoardFunding.markClaimed([], walletID: "wallet", store: store)
        #expect(disk == before)
    }
}

/// Sol I: the claim's display record. Written before broadcast, so an uncertain broadcast keeps the
/// fee; never blocks the claim; and Done reads it after the journal entry is acknowledged.
@MainActor
struct ExitClaimRecordTests {
    actor Records {
        var all: [String: ExitClaimRecord] = [:]
        func add(_ txid: String, _ record: ExitClaimRecord) { all[txid] = record }
    }

    @Test func aClaimsFeeIsRecordedBeforeItsSentAndNeverBlocksIt() async throws {
        let file = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: file) }
        let wallet = ExitClaimExecutionTests.Wallet(), records = Records()
        var access = wallet.access()
        access.recorded = { await records.add($0, $1) }
        access.broadcast = { _ in
            #expect(await records.all[ExitClaimExecutionTests.claimTxid] != nil, "Recorded before it's sent")
            throw URLError(.timedOut)
        }
        let reviewed = try await WalletSession.claimQuote(ids: ["a"], destination: ExitClaimExecutionTests.ownAddress, access: access)
        await #expect(throws: OnchainFailure.uncertain) {
            try await WalletSession.executeClaim(reviewed, journal: OnchainOperationJournal(file: file), access: access)
        }
        #expect(await records.all == [ExitClaimExecutionTests.claimTxid: .init(ids: ["a"], fee: 1295, toPhone: true)])

        // A record that can't be written doesn't stop the claim.
        let other = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: other) }
        var failing = wallet.access()
        failing.recorded = { _, _ in throw OnchainFailure.unavailable }
        _ = try await WalletSession.executeClaim(reviewed, journal: OnchainOperationJournal(file: other), access: failing)
        #expect(await wallet.broadcasts.count == 1)
    }

    @Test func doneKeepsTheNetAndFeeAfterTheJournalEntryIsAcknowledged() throws {
        let root = FileManager.default.temporaryDirectory.appending(path: "exit-claim-record-\(UUID())")
        defer { try? FileManager.default.removeItem(at: root) }
        let money = ExitMoney(app: AppModel(settings: try SettingsStorage(rootPath: root.path)), locale: Locale(identifier: "en"))
        func claimed(_ id: String, _ amount: UInt64) -> ExitModel {
            ExitModel(id: id, amount: amount, state: "Claimed", details: .init(kind: "claimed", tip_height: 250, block: .init(height: 250, hash: "h"), txid: "claim"),
                      history: [], historyDetails: [], claimable: false)
        }
        let a = claimed("a", 31_000), b = claimed("b", 24_500)
        var overview = OnchainOverview(walletID: "wallet", vtxos: [], exits: [a], confirmed: 0, tip: 250, pendingTotal: 0, claimableHeight: nil)
        func text(_ exit: ExitModel) -> ExitText {
            ExitText(progress: ExitProgress(exit, tip: 250), exit: exit, tip: 250, overview: overview, attempts: [], money: money, language: "en")
        }
        // No journal entry and nothing received here: only the record knows the fee.
        #expect(text(a).claimFee == nil && text(a).amount == 31_000)
        overview.claimRecords = ["claim": .init(ids: ["a"], fee: 262, toPhone: false)]
        #expect(text(a).claimFee == 262 && text(a).amount == 30_738)
        // One claim for two exits: the record's fee, split so the parts add up.
        overview.exits = [a, b]
        overview.claimRecords = ["claim": .init(ids: ["a", "b"], fee: 262, toPhone: false)]
        #expect(text(a).batch.map(\.id) == ["a", "b"] && text(a).claimFee! + text(b).claimFee! == 262)
        #expect(text(a).batchLine != nil)
    }
}

/// Auto-board after an exit claim to this phone: the claimed coins stay on-chain. Only a real
/// deposit is offered, prepared by amount; with only the claim on-chain there's no prompt; once the
/// claimed coin is spent it no longer counts.
struct ExitClaimAutoBoardTests {
    static let claim = "\(ExitClaimExecutionTests.claimTxid):0"

    @Test func aClaimsTxidIsKnownBeforeItsSent() {
        #expect(BitcoinTransaction.txid(hex: ExitClaimExecutionTests.claimTransaction) == ExitClaimExecutionTests.claimTxid)
        // bitcoind's weight for the same transaction.
        #expect(BitcoinTransaction.weight(hex: ExitClaimExecutionTests.claimTransaction) == 470)
        for broken in ["", "zz", String(ExitClaimExecutionTests.claimTransaction.dropLast(2)), ExitClaimExecutionTests.claimTransaction + "00"] {
            #expect(BitcoinTransaction.txid(hex: broken) == nil)
        }
    }

    @Test func heldValueIsTheUnspentConfirmedClaimOnly() {
        let utxos: [OnchainUtxo] = [
            .local(outpoint: .init(txid: ExitClaimExecutionTests.claimTxid, vout: 0), amountSats: 58_705, confirmationHeight: 1_278),
            .local(outpoint: .init(txid: "deposit", vout: 0), amountSats: 40_000, confirmationHeight: 1_280),
            .local(outpoint: .init(txid: "pending-claim", vout: 0), amountSats: 9_000, confirmationHeight: nil),
        ]
        #expect(AutoBoardFunding.held([Self.claim, "pending-claim:0"], utxos: utxos) == 58_705)
        // Spent: gone from the wallet's coins, so nothing is held back any more.
        #expect(AutoBoardFunding.held([Self.claim], utxos: Array(utxos.dropFirst())) == 0)
    }

    actor Board {
        let balance: UInt64
        /// The claimed coin's value; coin selection leaves it out when it's excluded.
        let claimed: UInt64
        /// A coin selection that spends the claimed coin anyway.
        let ignoresExclusions: Bool
        let chainFee: UInt64
        var amounts: [UInt64] = []
        var exclusions: [Set<String>] = []
        var discarded: [UUID] = []
        init(balance: UInt64, claimed: UInt64 = 0, ignoresExclusions: Bool = false, chainFee: UInt64 = 150) {
            self.balance = balance; self.claimed = claimed; self.ignoresExclusions = ignoresExclusions; self.chainFee = chainFee
        }
        func prepare(_ amount: UInt64, excluding: Set<String> = []) throws -> OnchainQuote {
            amounts.append(amount)
            exclusions.append(excluding)
            let spendsClaim = claimed > 0 && (ignoresExclusions || !excluding.contains(ExitClaimAutoBoardTests.claim))
            // bark drains only what isn't excluded; with nothing else it's the usual InsufficientFunds.
            guard amount > 0 || balance - (spendsClaim ? 0 : claimed) > chainFee else {
                throw BarkError(message: "insufficient", code: .insufficientFunds)
            }
            var quote = BoardPreparedTests.quote
            quote.id = UUID(); quote.automatic = true; quote.confirmed = balance
            quote.amount = amount == 0 ? balance - (spendsClaim ? 0 : claimed) - chainFee : amount
            quote.chainFee = chainFee; quote.fee = 330; quote.feeRate = 1
            quote.prepared = .init(PreparedBoard(id: quote.id.uuidString, txid: "board", grossSats: quote.amount, netSats: quote.net,
                arkFeeSats: 330, onchainFeeSats: chainFee, vsize: chainFee, inputs: (spendsClaim ? ["deposit:0", ExitClaimAutoBoardTests.claim] : ["deposit:0"]).map {
                    .init(txid: String($0.prefix { $0 != ":" }), vout: UInt32($0.split(separator: ":")[1])!) }))
            return quote
        }
        func discard(_ quote: OnchainQuote) { discarded.append(quote.id) }
        func review(held: UInt64, claimed: Set<String> = [ExitClaimAutoBoardTests.claim]) async throws -> OnchainQuote? {
            try await WalletSession.reserveBoardReview(minimum: 20_000, feeRate: 1, claims: .init(outpoints: claimed, held: held),
                                                       discard: { await self.discard($0) }, prepare: { try await self.prepare($0, excluding: $1) })
        }
    }

    @Test func aLaterDepositIsOfferedWithoutTheClaimedCoins() async throws {
        // The ₿58,705 claim plus a ₿40,000 deposit: the claim is excluded, so only the deposit, less fees and
        // the reserve, is boarded, prepared once after the probe.
        let board = Board(balance: 98_705, claimed: 58_705)
        let quote = try #require(try await board.review(held: 58_705))
        #expect(await board.amounts == [0, 40_000 - 150 - (5_000 + 43)])
        #expect(await board.exclusions == [[Self.claim], [Self.claim]])
        #expect(!quote.spends([Self.claim]))
        #expect(await board.discarded.count == 1)
        #expect(quote.confirmed - quote.amount - quote.chainFee! >= 58_705 + BoardingRules.reserve)
        // Without the claim nothing is excluded and the whole balance would have gone.
        let unclaimed = Board(balance: 98_705)
        #expect(try await unclaimed.review(held: 0, claimed: [])?.amount == 98_555 - 5_043)
        #expect(await unclaimed.exclusions == [[], []])
    }

    /// Android parity: the claimed value is excluded by the SDK only, never subtracted again.
    @Test func theOfferIsTheDepositLessFeesAndTheReserveOnly() async throws {
        let board = Board(balance: 90_000, claimed: 30_000, chainFee: 1_543)
        let quote = try #require(try await board.review(held: 30_000))
        #expect(quote.amount == 60_000 - 1_543 - 43 - 5_000)
        #expect(await board.amounts == [0, 60_000 - 1_543 - 43 - 5_000])
    }

    @Test func withOnlyTheClaimOnChainThereIsNoBoard() async throws {
        // autoBoardQuote doesn't even ask; if asked, bark refuses rather than spend the claim.
        let board = Board(balance: 58_705, claimed: 58_705)
        await #expect(throws: OnchainFailure.insufficientFunds) { try await board.review(held: 58_705) }
        #expect(await board.amounts == [0])
        #expect(await board.exclusions == [[Self.claim]])
    }

    @Test func aSmallDepositBesideTheClaimGivesNoPrompt() async throws {
        // ₿15,000 beside the claim: what auto-board may board stays under ₿20,000, so nothing is prepared after the probe.
        let board = Board(balance: 73_705, claimed: 58_705)
        #expect(try await board.review(held: 58_705) == nil)
        #expect(await board.amounts == [0])
    }

    @Test func aPreparationThatSpendsTheClaimedCoinAnywayIsDiscarded() async throws {
        let board = Board(balance: 98_705, claimed: 58_705, ignoresExclusions: true)
        #expect(try await board.review(held: 58_705) == nil)
        // The probe and the one sized board, both discarded: no prompt, and no retry at a lower amount.
        #expect(await board.amounts.count == 2)
        #expect(await board.discarded.count == 2)
    }

    /// Claimed outpoints as `prepareBoard` exclusions; malformed ones are dropped.
    @Test func claimOutpointsBecomeSdkExclusions() {
        let points = WalletSession.outpoints([Self.claim, "deposit:1", "malformed", "a:b", "x:1:2"])
        #expect(Set(points.map { "\($0.txid):\($0.vout)" }) == [Self.claim, "deposit:1"])
    }

    @Test func submittingRechecksTheClaimedCoins() async throws {
        for (claimed, held, submits) in [(Set<String>(), UInt64(0), true), ([ExitClaimAutoBoardTests.claim], 0, false), ([], 58_705, false)] {
            let file = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)
            defer { try? FileManager.default.removeItem(at: file) }
            let journal = OnchainOperationJournal(file: file)
            let session = WalletSession()
            var quote = try await Board(balance: 45_000, claimed: 5_000, ignoresExclusions: true).prepare(39_000)
            quote.walletID = "wallet"
            let reviewed = quote
            let sent = BoardSubmissions()
            await session.retainBoardReview(reviewed, discard: {}, submit: {
                await sent.add()
                return .init(txid: "board", broadcast: .accepted, vtxoIds: [])
            })
            let submit = {
                try await session.submitBoard(reviewed, walletID: "wallet", journal: journal, confirmed: { 45_000 },
                                              claims: { .init(outpoints: claimed, held: held) })
            }
            if submits { _ = try await submit() } else { await #expect(throws: OnchainFailure.changed) { _ = try await submit() } }
            #expect(await sent.count == (submits ? 1 : 0))
        }
    }

    actor BoardSubmissions {
        var count = 0
        func add() { count += 1 }
    }

    @MainActor @Test func claimsAreRecordedBesideHandledInOneWrite() throws {
        var disk: String? = #"{"wallet":["deposit:0"]}"# // An original outpoint-only ledger.
        var writes = 0
        var store = AutoBoardFunding()
        store.read = { disk }; store.write = { disk = $0; writes += 1 }
        try AutoBoardFunding.markClaimed([Self.claim], walletID: "wallet", store: store)
        #expect(writes == 1)
        #expect(try store.claims(walletID: "wallet") == [Self.claim])
        #expect(try !store.hasUnhandled(.init(walletID: "wallet", outpoints: ["deposit:0", Self.claim])))
        // Marking it again changes nothing.
        try AutoBoardFunding.markClaimed([Self.claim], walletID: "wallet", store: store)
        #expect(writes == 1)
        #expect(try store.claims(walletID: "other").isEmpty)
    }

    @Test func aClaimToThisPhoneThatCantBeRecordedIsNotSent() async throws {
        let file = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: file) }
        let wallet = ExitClaimExecutionTests.Wallet()
        var access = wallet.access()
        access.claimedToPhone = { _ in throw OnchainFailure.unavailable }
        let journal = OnchainOperationJournal(file: file)
        let reviewed = try await WalletSession.claimQuote(ids: ["a"], destination: ExitClaimExecutionTests.ownAddress, access: access)
        await #expect(throws: OnchainFailure.constructionFailed) {
            _ = try await WalletSession.executeClaim(reviewed, journal: journal, access: access)
        }
        #expect(await wallet.broadcasts.isEmpty)
        // Nothing was sent, so nothing blocks a new claim.
        #expect(try await journal.loadAll().isEmpty)
    }
}

/// An open page keeps the daemon clock of its last read; coming back restarts it there too, so needs-funds
/// waits for a daemon cycle after the app returns instead of showing at once.
@MainActor
struct ExitSceneTests {
    @Test func comingBackRestartsTheDaemonEvidence() async {
        var txs = ExitProgressTests.path.map { ExitProgressTests.tx($0, "verify-inputs") }
        txs[0] = ExitProgressTests.tx("root", "awaiting-cpfp-broadcast")
        let exit = ExitProgressTests.exit("Processing", ExitProgressTests.processing(txs))
        var read = OnchainOverview(walletID: "wallet", vtxos: [], exits: [exit], confirmed: 100, tip: 100, pendingTotal: 0, claimableHeight: nil)
        read.packageFees = [exit.id: 1_215]
        read.daemonSince = .distantPast
        let overview = read
        let seen = ["wallet": ExitSightings(seen: [exit.parkedEpisode!: .init(tip: 100, at: .distantPast)])]
        let model = OnchainModel(dependencies: .init(ready: { true }, overview: { overview },
            quote: { _, _, _, _ in throw OnchainFailure.unavailable }, execute: { _ in throw OnchainFailure.unavailable },
            attempt: { [] }, acknowledge: { _ in },
            sightings: .init(read: { String(decoding: try! JSONEncoder().encode(seen), as: UTF8.self) }, write: { _ in })))
        await model.load()
        let now = Date()
        #expect(model.progress(exit, now: now).phase == .needsFunds)
        model.sceneChanged(at: now)
        #expect(model.progress(exit, now: now.addingTimeInterval(74)).phase == .sending)
        #expect(model.progress(exit, now: now.addingTimeInterval(75)).phase == .needsFunds)
    }
}

/// Hold to start and hold to claim through the real start and claim paths (`startQuote`/`executeStart`,
/// `claimQuote`/`executeClaim`) and the durable journal. The app leaves and comes back before the hold
/// (the screen drops the review and fetches it again), the hold fires twice (a size-class change
/// rebuilds it), the app comes back after the hold, then a fresh model and journal on the same file (a
/// process restart) reviews and holds again. Exactly one wallet mutation each.
@MainActor
struct ExitRestartTests {
    nonisolated static let overview = OnchainOverview(walletID: "wallet", vtxos: [], exits: [], confirmed: 100_000, tip: 100, pendingTotal: 0, claimableHeight: nil)

    /// What `OnchainScreen` and the review pages do when the scene becomes inactive, then active again.
    static func reactivate(_ model: OnchainModel, _ action: OnchainAction, ids: [String], destination: String? = nil) async {
        model.invalidate()
        #expect(model.quote == nil)
        await model.review(action, ids: ids, destination: destination)
    }

    static func hold(_ model: OnchainModel) async {
        async let held = model.confirm()
        async let rebuilt = model.confirm()
        _ = await (held, rebuilt)
    }

    @Test func aClaimHeldAgainAfterARestartIsSentOnce() async throws {
        let file = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: file) }
        let wallet = ExitClaimExecutionTests.Wallet(), to = ExitClaimExecutionTests.ownAddress
        func launch() -> OnchainModel {
            let journal = OnchainOperationJournal(file: file), access = wallet.access()
            return OnchainModel(dependencies: .init(ready: { true }, overview: { Self.overview },
                quote: { _, ids, _, destination in try await WalletSession.claimQuote(ids: ids, destination: destination, access: access) },
                execute: { try await WalletSession.executeClaim($0, journal: journal, access: access) },
                attempt: { try await journal.loadAll() }, acknowledge: { try await journal.acknowledge($0) }))
        }
        let first = launch()
        await first.review(.claim, ids: ["a"], destination: to)
        await Self.reactivate(first, .claim, ids: ["a"], destination: to)
        #expect(first.quote != nil)
        await Self.hold(first)
        #expect(await wallet.broadcasts.count == 1)
        await Self.reactivate(first, .claim, ids: ["a"], destination: to)
        #expect(first.quote == nil && first.error != nil)
        let restarted = launch()
        await restarted.review(.claim, ids: ["a"], destination: to)
        #expect(restarted.quote == nil && restarted.error != nil)
        await restarted.confirm()
        #expect(await wallet.broadcasts.count == 1)
    }

    actor StartWallet {
        var starts: [[String]] = []
        /// What happened, in order: Noah's new run, then bark's start.
        var events: [String] = []
        var estimatorFails = false
        var runFails = false
        func start(_ ids: [String]) { starts.append(ids); events.append("start") }
        func began() throws {
            if runFails { throw OnchainFailure.unavailable }
            events.append("run")
        }
        func failRuns(_ fails: Bool) { runFails = fails }
        func failEstimates() { estimatorFails = true }
        func estimate() throws -> EmergencyExitFeeEstimate {
            if estimatorFails { throw OnchainFailure.unavailable }
            return .init(exitBroadcastFeeSats: 5_533, claimFeeSats: 262, totalFeeSats: 5_795, feeRateSatPerVb: 3, txsToBroadcast: 4, excludedVtxos: [])
        }
        /// bark still lists the VTXO as spendable: only the journal stops a second start.
        nonisolated func access() -> WalletSession.OnchainStartAccess {
            .init(walletID: "wallet", confirmed: { 18_400 }, spendable: { ["v": 31_000] },
                  estimate: { _ in try await self.estimate() }, start: { await self.start($0) }, began: { _ in try await self.began() })
        }
    }

    /// The new run couldn't be recorded: bark isn't asked, and the start can be reviewed and held again.
    @Test func aStartWhoseRunCantBeRecordedIsNotSent() async throws {
        let file = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: file) }
        let wallet = StartWallet(), journal = OnchainOperationJournal(file: file), access = wallet.access()
        let model = OnchainModel(dependencies: .init(ready: { true }, overview: { Self.overview },
            quote: { _, ids, _, _ in try await WalletSession.startQuote(ids: ids, access: access) },
            execute: { try await WalletSession.executeStart($0, journal: journal, access: access) },
            attempt: { try await journal.loadAll() }, acknowledge: { try await journal.acknowledge($0) }))
        await wallet.failRuns(true)
        await model.review(.startExit, ids: ["v"])
        #expect(await model.confirm() == false)
        #expect(model.error == OnchainFailure.constructionFailed.errorDescription && model.quote == nil && model.estimateFallback == nil)
        #expect(await wallet.starts.isEmpty)
        // Nothing was sent, so the journal holds nothing back: Review again gets a fresh quote.
        #expect(try await journal.loadAll().isEmpty)
        await wallet.failRuns(false)
        await model.review(.startExit, ids: ["v"])
        #expect(model.quote != nil)
        #expect(await model.confirm() == true)
        #expect(await wallet.starts == [["v"]])
    }

    /// The first estimate worked, the one at submit didn't: nothing was sent, so the hold isn't a success
    /// (the page stays, offering "Continue without estimate"). With consent it is.
    @Test func aHoldWhoseSubmitEstimateFailsIsNotASuccess() async throws {
        let file = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: file) }
        let starter = StartWallet(), claimer = ExitClaimExecutionTests.Wallet()
        let journal = OnchainOperationJournal(file: file), start = starter.access(), claim = claimer.access()
        let model = OnchainModel(dependencies: .init(ready: { true }, overview: { Self.overview },
            quote: { action, ids, _, destination in
                action == .claim ? try await WalletSession.claimQuote(ids: ids, destination: destination, access: claim)
                    : try await WalletSession.startQuote(ids: ids, access: start)
            },
            execute: { $0.action == .claim ? try await WalletSession.executeClaim($0, journal: journal, access: claim)
                : try await WalletSession.executeStart($0, journal: journal, access: start) },
            attempt: { try await journal.loadAll() }, acknowledge: { try await journal.acknowledge($0) }))

        await model.review(.startExit, ids: ["v"])
        await starter.failEstimates()
        #expect(await model.confirm() == false)
        #expect(model.error == nil && model.estimateFallback != nil && model.quote == nil)
        #expect(await starter.starts.isEmpty)
        await model.continueWithoutEstimate()
        #expect(await model.confirm() == true)
        #expect(await starter.starts == [["v"]])

        await model.review(.claim, ids: ["a"], destination: ExitClaimExecutionTests.ownAddress)
        await claimer.change("estimator")
        #expect(await model.confirm() == false)
        #expect(model.error == nil && model.estimateFallback != nil && model.quote == nil)
        #expect(await claimer.broadcasts.isEmpty)
        await model.continueWithoutEstimate()
        #expect(await model.confirm() == true)
        #expect(await claimer.broadcasts.count == 1)
    }

    @Test func aStartHeldAgainAfterARestartStartsOnce() async throws {
        let file = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: file) }
        let wallet = StartWallet()
        func launch() -> OnchainModel {
            let journal = OnchainOperationJournal(file: file), access = wallet.access()
            return OnchainModel(dependencies: .init(ready: { true }, overview: { Self.overview },
                quote: { _, ids, _, _ in try await WalletSession.startQuote(ids: ids, access: access) },
                execute: { try await WalletSession.executeStart($0, journal: journal, access: access) },
                attempt: { try await journal.loadAll() }, acknowledge: { try await journal.acknowledge($0) }))
        }
        let first = launch()
        await first.review(.startExit, ids: ["v"])
        await Self.reactivate(first, .startExit, ids: ["v"])
        #expect(first.quote?.fee == 262 && first.quote?.chainFee == 5_533)
        await Self.hold(first)
        #expect(await wallet.starts == [["v"]])
        #expect(await wallet.events == ["run", "start"])
        await Self.reactivate(first, .startExit, ids: ["v"])
        #expect(first.quote == nil && first.error != nil)
        let restarted = launch()
        await restarted.review(.startExit, ids: ["v"])
        #expect(restarted.quote == nil && restarted.error != nil)
        await restarted.confirm()
        #expect(await wallet.starts == [["v"]])
    }
}
