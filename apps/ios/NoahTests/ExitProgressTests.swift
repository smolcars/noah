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
        now[0] = Self.tx("root", "awaiting-confirmation", child: "c0", origin: "wallet")
        let stuck = ExitProgress(Self.exit("Processing", Self.processing(now, tip: 103), history: [Self.processing(first, tip: 100)]), tip: 103)
        #expect(stuck.phase == .mempool && stuck.stuck == 3 && !stuck.bumped)

        now[0] = Self.tx("root", "awaiting-confirmation", child: "c0-rbf", origin: "wallet")
        let bumped = ExitProgress(Self.exit("Processing", Self.processing(now, tip: 101), history: [Self.processing(first, tip: 100)]), tip: 101)
        #expect(bumped.bumped && bumped.stuck == 1)
    }

    @Test func staleOnlyWhileLiveAndBehindTheChain() {
        let behind = Self.exit("Processing", Self.processing(Self.path.map { Self.tx($0, "verify-inputs") }, tip: 90))
        #expect(ExitProgress(behind, tip: 100).stale)
        #expect(!ExitProgress(behind, tip: 90).stale)
    }

    // MARK: Decision 3

    @Test func needsFundsShowsOnlyAfterAFullCycleAndClearsWhenFundsArrive() {
        var txs = Self.path.map { Self.tx($0, "verify-inputs") }
        txs[0] = Self.tx("root", "confirmed", height: 101)
        txs[1] = Self.tx("l2", "awaiting-cpfp-broadcast")
        let exit = Self.exit("Processing", Self.processing(txs, tip: 101))
        #expect(exit.parkedTxid == "l2")
        let t0 = Date(timeIntervalSince1970: 1_000)
        var funds = ExitFunds()
        funds.observe([exit], now: t0)
        #expect(!funds.needsFunds(exit, confirmed: 310, packageFee: 1_215, now: t0))
        #expect(!funds.needsFunds(exit, confirmed: 310, packageFee: 1_215, now: t0.addingTimeInterval(59)))
        // Observing again doesn't restart the clock.
        funds.observe([exit], now: t0.addingTimeInterval(30))
        #expect(funds.needsFunds(exit, confirmed: 310, packageFee: 1_215, now: t0.addingTimeInterval(60)))
        // Funds arrive: enough confirmed for the package.
        #expect(!funds.needsFunds(exit, confirmed: 1_215, packageFee: 1_215, now: t0.addingTimeInterval(60)))
        // No package fee known: nothing is inferred.
        #expect(!funds.needsFunds(exit, confirmed: 0, packageFee: nil, now: t0.addingTimeInterval(600)))

        // The transaction got its child: forgotten, and no longer parked.
        var moved = txs
        moved[1] = Self.tx("l2", "awaiting-confirmation", child: "c1", origin: "wallet")
        let sent = Self.exit("Processing", Self.processing(moved, tip: 101))
        funds.observe([sent], now: t0.addingTimeInterval(90))
        #expect(funds.parkedSince.isEmpty)
        #expect(!funds.needsFunds(sent, confirmed: 0, packageFee: 1_215, now: t0.addingTimeInterval(90)))
    }

    @Test func barkHistoryProvesAProgressCycleAlready() {
        var txs = Self.path.map { Self.tx($0, "verify-inputs") }
        txs[0] = Self.tx("root", "awaiting-cpfp-broadcast")
        let earlier = Self.processing(txs, tip: 100)
        let exit = Self.exit("Processing", Self.processing(txs, tip: 102), history: [earlier])
        #expect(exit.parkedAcrossProgress)
        let now = Date()
        var funds = ExitFunds()
        funds.observe([exit], now: now)
        #expect(funds.needsFunds(exit, confirmed: 100, packageFee: 1_215, now: now))
        // Same tip: one progress only, so it waits for a cycle.
        let same = Self.exit("Processing", Self.processing(txs, tip: 100), history: [earlier])
        #expect(!same.parkedAcrossProgress)
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

    @Test func feesMoreThanItsWorthCountsSharedTransactionsOnce() async throws {
        // A and B share the round's root; C is alone in its round.
        let paths = ["a": ["r1", "a"], "b": ["r1", "b"], "c": ["r2", "c"]]
        let vtxos = [Self.vtxo("a", 1_500), Self.vtxo("b", 1_100), Self.vtxo("c", 2_000)]
        let flagged = try await ExitEconomics.uneconomic(vtxos, estimate: Self.estimator(paths))
        // Standalone, A would cost 2,100. Its share is its own leaf and claim input: 1,100 < 1,500.
        #expect(!flagged.contains("a"))
        // B's share, 1,100, meets its amount.
        #expect(flagged.contains("b"))
        // C pays its whole path, 2,100, more than 2,000.
        #expect(flagged.contains("c"))
        // Alone, a VTXO's share is the whole estimate (never the empty list).
        #expect(try await ExitEconomics.uneconomic([Self.vtxo("a", 2_100)], estimate: Self.estimator(paths)) == ["a"])
        #expect(try await ExitEconomics.uneconomic([], estimate: Self.estimator(paths)).isEmpty)
    }

    @Test func allLeavesOutUneconomicDustAndLocked() {
        let vtxos = [Self.vtxo("a", 31_000), Self.vtxo("b", 1_100), Self.vtxo("dust", 290), Self.vtxo("locked", 710, state: "Locked")]
        #expect(ExitEconomics.all(vtxos, uneconomic: ["b"]).map(\.id) == ["a"])
        #expect(ExitEconomics.all(vtxos, uneconomic: []).map(\.id) == ["a", "b"])
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
