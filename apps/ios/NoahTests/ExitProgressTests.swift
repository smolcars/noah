import BarkBoltFFI
import Foundation
import Testing

@testable import Noah

/// The redesigned exit's rules (docs/native-rewrite/design/exit.md, Testing): bark's states to
/// phases and pictures, the inferred needs-funds state, the per-VTXO fee rule, the Settings row
/// and the shared-path picture.
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
        let seen = [ExitSightings.key("root", "awaiting-confirmation"): UInt32(100)]
        let stuck = ExitProgress(Self.exit("Processing", Self.processing(now, tip: 100), history: [Self.processing(first, tip: 100)]), tip: 103, seen: seen)
        #expect(stuck.phase == .mempool && stuck.stuck == 3 && !stuck.bumped)

        now[0] = Self.tx("root", "awaiting-confirmation", child: "c0-rbf", origin: "wallet")
        let bumped = ExitProgress(Self.exit("Processing", Self.processing(now, tip: 101), history: [Self.processing(first, tip: 100)]), tip: 101, seen: seen)
        #expect(bumped.bumped && bumped.stuck == 1)
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
        #expect(exit.parkedTxid == "l2")
        let t0 = Date(timeIntervalSince1970: 1_000)
        func needs(_ confirmed: UInt64 = 310, fee: UInt64? = 1_215, proven: Set<String> = [], seen: Date? = t0, daemon: Date? = .distantPast,
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
        #expect(!needs(daemon: nil, at: 10_000) && needs(proven: ["l2"], daemon: nil, at: 0))
        // Funds arrive, or nothing to compare against: cleared.
        #expect(!needs(1_215, at: 10_000) && !needs(fee: nil, proven: ["l2"], at: 10_000))
        // Proof for another transaction doesn't carry over; once it has its child it isn't parked.
        #expect(!needs(proven: ["root"], daemon: nil, at: 0))
        var moved = txs
        moved[1] = Self.tx("l2", "awaiting-confirmation", child: "c1", origin: "wallet")
        #expect(!needs(proven: ["l2"], at: 10_000, Self.exit("Processing", Self.processing(moved, tip: 101))))
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
        #expect(sightings.tips[ExitSightings.key("root", "awaiting-confirmation")] == 100)
        // A new status is a new sighting; the old one is forgotten.
        txs[0] = Self.tx("root", "confirmed", height: 105)
        txs[1] = Self.tx("l2", "awaiting-cpfp-broadcast")
        let moved = sightings.observe([Self.exit("Processing", Self.processing(txs, tip: 105))], tip: 105, now: Date(timeIntervalSince1970: 600))
        #expect(moved)
        #expect(sightings.seen.keys.contains(ExitSightings.key("l2", "awaiting-cpfp-broadcast")))
        #expect(sightings.tips[ExitSightings.key("root", "awaiting-confirmation")] == nil)
        // Persisted per wallet, across launches.
        let disk = Disk()
        let store = ExitSightings.Store(read: { disk.value }, write: { disk.value = $0 })
        try store.save(sightings, walletID: "a")
        #expect(ExitSightings.Store(read: { disk.value }, write: { _ in }).load(walletID: "a") == sightings)
        #expect(store.load(walletID: "b") == ExitSightings())
    }

    final class Disk: @unchecked Sendable { var value: String? }

    @Test func barkHistoryProvesAProgressCycleAlready() {
        var txs = Self.path.map { Self.tx($0, "verify-inputs") }
        txs[0] = Self.tx("root", "awaiting-cpfp-broadcast")
        let earlier = Self.processing(txs, tip: 100)
        let exit = Self.exit("Processing", Self.processing(txs, tip: 102), history: [earlier])
        #expect(exit.parkedAcrossProgress)
        #expect(ExitFunds.needsFunds(exit, confirmed: 100, packageFee: 1_215, proven: [], parkedSince: nil, daemonSince: nil, now: Date()))
        // Same tip: one progress only.
        let same = Self.exit("Processing", Self.processing(txs, tip: 100), history: [earlier])
        #expect(!same.parkedAcrossProgress)
    }

    @Test func mempoolWaitCountsFromNoahsFirstSighting() {
        var txs = Self.path.map { Self.tx($0, "verify-inputs") }
        txs[0] = Self.tx("root", "awaiting-confirmation", child: "c0", origin: "wallet")
        // bark keeps the state, height included, while nothing changes: Noah's sighting counts the blocks.
        let frozen = Self.exit("Processing", Self.processing(txs, tip: 100))
        let seen = [ExitSightings.key("root", "awaiting-confirmation"): UInt32(101)]
        #expect(ExitProgress(frozen, tip: 104, seen: seen).stuck == 3)
        #expect(ExitProgress(frozen, tip: 104).stuck == 0)
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
        async let first: Void = model.confirm()
        async let second: Void = model.confirm()
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

/// Auto-board after an exit claim to this phone: the claimed coins stay on-chain. Only a real
/// deposit is offered, prepared by amount; with only the claim on-chain there's no prompt; once the
/// claimed coin is spent it no longer counts.
struct ExitClaimAutoBoardTests {
    static let claim = "\(ExitClaimExecutionTests.claimTxid):0"

    @Test func aClaimsTxidIsKnownBeforeItsSent() {
        #expect(BitcoinTransaction.txid(hex: ExitClaimExecutionTests.claimTransaction) == ExitClaimExecutionTests.claimTxid)
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
        /// The coins bark's coin selection picks for the sized board.
        let inputs: [String]
        /// Above this amount, coin selection picks the claimed coin too.
        let claimedAbove: UInt64?
        var amounts: [UInt64] = []
        var discarded: [UUID] = []
        init(balance: UInt64, inputs: [String] = ["deposit:0"], claimedAbove: UInt64? = nil) {
            self.balance = balance; self.inputs = inputs; self.claimedAbove = claimedAbove
        }
        func prepare(_ amount: UInt64) -> OnchainQuote {
            amounts.append(amount)
            let inputs = amount > 0 && claimedAbove.map { amount > $0 } == true ? self.inputs + [ExitClaimAutoBoardTests.claim] : self.inputs
            var quote = BoardPreparedTests.quote
            quote.id = UUID(); quote.automatic = true; quote.confirmed = balance
            quote.amount = amount == 0 ? balance - 150 : amount
            quote.chainFee = 150; quote.fee = 330; quote.feeRate = 1
            quote.prepared = .init(PreparedBoard(id: quote.id.uuidString, txid: "board", grossSats: quote.amount, netSats: quote.net,
                arkFeeSats: 330, onchainFeeSats: 150, vsize: 150, inputs: inputs.map {
                    .init(txid: String($0.prefix { $0 != ":" }), vout: UInt32($0.split(separator: ":")[1])!) }))
            return quote
        }
        func discard(_ quote: OnchainQuote) { discarded.append(quote.id) }
        func review(held: UInt64, claimed: Set<String> = [ExitClaimAutoBoardTests.claim]) async throws -> OnchainQuote? {
            try await WalletSession.reserveBoardReview(minimum: 20_000, feeRate: 1, claims: .init(outpoints: claimed, held: held),
                                                       discard: { await self.discard($0) }, prepare: { await self.prepare($0) })
        }
    }

    @Test func aLaterDepositIsOfferedWithoutTheClaimedCoins() async throws {
        // The ₿58,705 claim plus a ₿40,000 deposit: only the deposit, less fees and the reserve, is boarded.
        let board = Board(balance: 98_705)
        let quote = try #require(try await board.review(held: 58_705))
        #expect(await board.amounts == [0, 98_555 - (5_000 + 43) - 58_705])
        #expect(quote.confirmed - quote.amount - quote.chainFee! >= 58_705 + BoardingRules.reserve)
        // Without the claim held back, the whole balance would have gone.
        #expect(try await Board(balance: 98_705).review(held: 0, claimed: [])?.amount == 98_555 - 5_043)
    }

    @Test func withOnlyTheClaimOnChainThereIsNoPrompt() async throws {
        #expect(try await Board(balance: 58_705).review(held: 58_705) == nil)
    }

    @Test func aSmallDepositBesideTheClaimGivesNoPrompt() async throws {
        // ₿15,000 beside the claim: what auto-board may board stays under ₿20,000.
        #expect(try await Board(balance: 73_705).review(held: 58_705) == nil)
    }

    @Test func aPreparationThatAlwaysSpendsTheClaimedCoinIsDiscarded() async throws {
        let board = Board(balance: 98_705, inputs: ["deposit:0", ExitClaimAutoBoardTests.claim])
        #expect(try await board.review(held: 58_705) == nil)
        // The probe and three sized boards, each a little smaller, are all discarded: no prompt.
        let first = UInt64(98_555 - 5_043 - 58_705)
        #expect(await board.amounts == [0, first, first - 1_000, first - 2_000])
        #expect(await board.discarded.count == 4)
    }

    @Test func aSmallerBoardThatAvoidsTheClaimedCoinIsOffered() async throws {
        // Coin selection picks the claimed coin for the full amount and the deposit for a little less.
        let first = UInt64(98_555 - 5_043 - 58_705)
        let board = Board(balance: 98_705, inputs: ["deposit:0"], claimedAbove: first - 1)
        let quote = try #require(try await board.review(held: 58_705))
        #expect(await board.amounts == [0, first, first - 1_000])
        #expect(quote.amount == first - 1_000 && !quote.spends([ExitClaimAutoBoardTests.claim]))
        #expect(await board.discarded.count == 2)
    }

    @Test func submittingRechecksTheClaimedCoins() async throws {
        for (claimed, held, submits) in [(Set<String>(), UInt64(0), true), ([ExitClaimAutoBoardTests.claim], 0, false), ([], 58_705, false)] {
            let file = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)
            defer { try? FileManager.default.removeItem(at: file) }
            let journal = OnchainOperationJournal(file: file)
            let session = WalletSession()
            var quote = await Board(balance: 45_000, inputs: ["deposit:0", ExitClaimAutoBoardTests.claim]).prepare(39_000)
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

/// Hold to start and hold to claim across a restart: the durable journal is what stops a repeat.
/// One model is held twice (a size-class change rebuilds the hold), then a fresh model and journal
/// on the same file (a process restart) reviews and holds again. Exactly one wallet mutation each.
@MainActor
struct ExitRestartTests {
    nonisolated static let overview = OnchainOverview(walletID: "wallet", vtxos: [], exits: [], confirmed: 100_000, tip: 100, pendingTotal: 0, claimableHeight: nil)

    @Test func aClaimHeldAgainAfterARestartIsSentOnce() async throws {
        let file = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: file) }
        let wallet = ExitClaimExecutionTests.Wallet()
        func launch() -> OnchainModel {
            let journal = OnchainOperationJournal(file: file), access = wallet.access()
            return OnchainModel(dependencies: .init(ready: { true }, overview: { Self.overview },
                quote: { _, ids, _, destination in try await WalletSession.claimQuote(ids: ids, destination: destination, access: access) },
                execute: { try await WalletSession.executeClaim($0, journal: journal, access: access) },
                attempt: { try await journal.loadAll() }, acknowledge: { try await journal.acknowledge($0) }))
        }
        let first = launch()
        await first.review(.claim, ids: ["a"], destination: ExitClaimExecutionTests.ownAddress)
        async let held: Void = first.confirm()
        async let rebuilt: Void = first.confirm()
        _ = await (held, rebuilt)
        #expect(await wallet.broadcasts.count == 1)
        let restarted = launch()
        await restarted.review(.claim, ids: ["a"], destination: ExitClaimExecutionTests.ownAddress)
        #expect(restarted.quote == nil && restarted.error != nil)
        await restarted.confirm()
        #expect(await wallet.broadcasts.count == 1)
    }

    actor Starts {
        var count = 0
        func start() { count += 1 }
    }

    @Test func aStartHeldAgainAfterARestartStartsOnce() async throws {
        let file = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: file) }
        let starts = Starts()
        // executeOnchain's start: the journal's one operation around bark's start.
        func launch() -> OnchainModel {
            let journal = OnchainOperationJournal(file: file)
            return OnchainModel(dependencies: .init(ready: { true }, overview: { Self.overview },
                quote: { action, ids, _, _ in .init(action: action, walletID: "wallet", ids: ids, amount: 31_000, fee: 262, chainFee: 5_533, confirmed: 18_400) },
                execute: { quote in try await journal.perform(quote) { await starts.start(); return "Exit started." } },
                attempt: { try await journal.loadAll() }, acknowledge: { try await journal.acknowledge($0) }))
        }
        let first = launch()
        await first.review(.startExit, ids: ["v"])
        async let held: Void = first.confirm()
        async let rebuilt: Void = first.confirm()
        _ = await (held, rebuilt)
        #expect(await starts.count == 1)
        let restarted = launch()
        await restarted.review(.startExit, ids: ["v"])
        #expect(restarted.quote == nil && restarted.error != nil)
        await restarted.confirm()
        #expect(await starts.count == 1)
    }
}
