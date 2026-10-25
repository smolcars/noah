package com.noahwallet.onchain

import org.junit.Assert.*
import org.junit.Test
import com.noahwallet.onchain.ExitActions.Action as a
import com.noahwallet.onchain.ExitList.Step as s

/** exit.md, Testing: bark's states map to phases, the inferred rules, and what the list and Settings show. */
class ExitStagesTest {
    private fun tx(id: String, kind: String, child: String? = null, block: Long? = null, origin: String? = null) =
        ExitDetails.Transaction(id, ExitDetails.Status(kind, child, block?.let { ExitDetails.Block(it) }, origin))
    private fun processing(tip: Long, vararg txs: ExitDetails.Transaction) = ExitDetails(kind = "processing", tip_height = tip, transactions = txs.toList())
    private fun exit(state: String, details: ExitDetails, history: List<ExitDetails> = emptyList(), packages: List<ExitPackage> = emptyList(),
                     fees: Map<String, Long> = emptyMap(), next: Long? = null, id: String = "v1") =
        ExitModel(id, 31_000, state, details, history.map { it.kind ?: "" }, history, packages, fees, next)
    private val four = listOf("root", "l2", "l1", "leaf")
    private fun path(vararg kinds: String) = four.zip(kinds).map { (id, kind) -> tx(id, kind) }.toTypedArray()

    @Test fun everyBarkStateMapsToItsPhase() {
        assertEquals(ExitStage.Phase.lift, ExitStages.of(exit("Start", ExitDetails(kind = "start", tip_height = 100),
            packages = four.map { ExitPackage(it) }), 100).phase)
        assertEquals(4, ExitStages.of(exit("Start", ExitDetails(tip_height = 100), packages = four.map { ExitPackage(it) }), 100).total)
        val publish = ExitStages.of(exit("Processing", processing(100, *path("verify-inputs", "awaiting-input-confirmation",
            "awaiting-input-confirmation", "awaiting-input-confirmation"))), 100)
        assertEquals(ExitStage.Phase.publish, publish.phase); assertEquals(0, publish.current); assertEquals(4, publish.total)
        val sending = ExitStages.of(exit("Processing", processing(100, *path("awaiting-cpfp-broadcast", "verify-inputs", "verify-inputs", "verify-inputs"))), 100)
        assertEquals(ExitStage.Phase.mempool, sending.phase); assertNull(sending.attention)
        val bump = ExitStages.of(exit("Processing", processing(100, tx("root", "awaiting-confirmation", "c1", origin = "wallet"),
            tx("l2", "verify-inputs"), tx("l1", "verify-inputs"), tx("leaf", "verify-inputs")),
            packages = listOf(ExitPackage("root", weight = 1_012, childTxid = "c1", childWeight = 608, childOrigin = "wallet")), fees = mapOf("c1" to 1_215)), 100)
        assertEquals(ExitStage.Phase.bump, bump.phase); assertNull(bump.attention)
        assertEquals(1_215L, bump.fee); assertEquals(3L, bump.rate); assertEquals(1_215L, bump.feesPaid)
        val confirmed = ExitStages.of(exit("Processing", processing(101, tx("root", "confirmed", "c1", 101, "wallet"),
            tx("l2", "awaiting-input-confirmation"), tx("l1", "verify-inputs"), tx("leaf", "verify-inputs"))), 101)
        assertEquals(ExitStage.Phase.confirm, confirmed.phase); assertEquals(1, confirmed.inBlocks); assertEquals(101L, confirmed.lastBlock); assertEquals(1, confirmed.current)
        val onchain = ExitStages.of(exit("Processing", processing(104, *four.mapIndexed { i, id -> tx(id, "confirmed", "c$i", 101L + i, "wallet") }.toTypedArray())), 104)
        assertEquals(ExitStage.Phase.onchain, onchain.phase); assertEquals(104L, onchain.lastBlock); assertEquals(4, onchain.inBlocks)
        val wait = ExitStages.of(exit("AwaitingDelta", ExitDetails(kind = "awaiting-delta", tip_height = 150, confirmed_block = ExitDetails.Block(104), claimable_height = 248),
            history = listOf(processing(104, *path("confirmed", "confirmed", "confirmed", "confirmed")))), 150)
        assertEquals(ExitStage.Phase.wait, wait.phase); assertEquals(4, wait.total); assertEquals(248L, wait.claimableHeight); assertEquals(98L, wait.left(150))
        assertEquals(ExitStage.Phase.claimable, ExitStages.of(exit("Claimable", ExitDetails(kind = "claimable", tip_height = 248, claimable_since = ExitDetails.Block(248))), 248).phase)
        val claiming = ExitStages.of(exit("ClaimInProgress", ExitDetails(kind = "claim-in-progress", tip_height = 248, claimable_since = ExitDetails.Block(248), claim_txid = "claim")), 248)
        assertEquals(ExitStage.Phase.claiming, claiming.phase); assertEquals("claim", claiming.claimTxid)
        val claimed = ExitStages.of(exit("Claimed", ExitDetails(kind = "claimed", tip_height = 249, block = ExitDetails.Block(249), txid = "claim")), 260)
        assertEquals(ExitStage.Phase.claimed, claimed.phase); assertEquals(249L, claimed.claimedBlock); assertFalse(claimed.stale); assertFalse(claimed.live)
        val canceled = ExitStages.of(exit("Canceled", ExitDetails(kind = "canceled", tip_height = 102),
            history = listOf(processing(101, tx("root", "confirmed", block = 101), tx("l2", "awaiting-cpfp-broadcast"), tx("l1", "verify-inputs"), tx("leaf", "verify-inputs")))), 102)
        assertEquals(ExitStage.Phase.canceled, canceled.phase); assertEquals(1, canceled.inBlocks); assertEquals(4, canceled.total)
        assertEquals(ExitStage.Phase.spent, ExitStages.of(exit("VtxoAlreadySpent", ExitDetails(tip_height = 100)), 100).phase)
        assertEquals(ExitStage.Phase.swept, ExitStages.of(exit("VtxoSwept", ExitDetails(tip_height = 100)), 100).phase)
    }

    @Test fun cancelAppearsOnlyWhileBarkAllowsIt() {
        val sending = ExitStages.of(exit("Processing", processing(100, *path("awaiting-cpfp-broadcast", "verify-inputs"))), 100)
        assertEquals(listOf(a.cancel, a.check), ExitActions.of(sending, cancelable = true))
        assertEquals("the last transaction is out", listOf(a.check), ExitActions.of(sending, cancelable = false))
        // Needing funds, "Add on-chain funds" leads, but Cancel (while bark allows it) and Check the chain stay.
        assertEquals(listOf(a.cancel, a.addFunds, a.check), ExitActions.of(sending.copy(attention = ExitStage.Attention.needsFunds), cancelable = true))
        assertEquals(listOf(a.addFunds, a.check), ExitActions.of(sending.copy(attention = ExitStage.Attention.needsFunds), cancelable = false))
        // Check the chain stays beside Claim and while claiming: only bark's progress notices a claim confirm or fall out.
        assertEquals(listOf(a.claim, a.check), ExitActions.of(ExitStage(ExitStage.Phase.claimable), cancelable = false))
        assertEquals(listOf(a.check), ExitActions.of(ExitStage(ExitStage.Phase.claiming), cancelable = false))
        for (phase in listOf(ExitStage.Phase.claimed, ExitStage.Phase.canceled, ExitStage.Phase.spent, ExitStage.Phase.swept))
            assertEquals(phase.name, listOf(a.done), ExitActions.of(ExitStage(phase), cancelable = true))
        // bark's own rule decides cancelable: Start, or Processing until the last transaction is broadcast.
        assertTrue(exit("Start", ExitDetails()).cancelable)
        assertTrue(exit("Processing", processing(100, *path("confirmed", "confirmed", "confirmed", "awaiting-cpfp-broadcast"))).cancelable)
        assertFalse(exit("Processing", processing(100, *path("confirmed", "confirmed", "confirmed", "awaiting-confirmation"))).cancelable)
        assertFalse(exit("AwaitingDelta", ExitDetails()).cancelable)
    }

    @Test fun thePictureFollowsEachState() {
        fun pose(e: ExitModel, tip: Long = 100, needsFunds: Boolean = false) = ExitPose.of(e, ExitStages.of(e, tip, needsFunds), tip)
        val packages = listOf(ExitPackage("root", outputs = 5), ExitPackage("l2", outputs = 5, spends = 2), ExitPackage("l1", outputs = 5, spends = 2),
            ExitPackage("leaf", outputs = 2, spends = 1))
        val lift = pose(exit("Start", ExitDetails(tip_height = 100), packages = packages))
        assertEquals(4, lift.n); assertEquals(listOf(3, 3, 3, 0), lift.branches); assertEquals(listOf(2, 2, 1, 0), lift.ours)
        assertEquals(0f, PictureMotion.rest(lift).unroll)
        val parked = exit("Processing", processing(100, tx("root", "confirmed", block = 99), tx("l2", "awaiting-cpfp-broadcast"), tx("l1", "verify-inputs"), tx("leaf", "verify-inputs")), packages = packages)
        pose(parked).let { assertTrue(it.dropped); assertFalse(it.fee); assertEquals(listOf(99L, null, null, null), it.mined); assertEquals(1, it.current) }
        pose(parked, needsFunds = true).let { assertFalse("it waits in the branch, ringed", it.dropped); assertEquals(ExitStage.Attention.needsFunds, it.attention) }
        val paying = exit("Processing", processing(100, tx("root", "awaiting-confirmation", "c", origin = "mempool"), tx("leaf", "verify-inputs")))
        pose(paying).let { assertTrue(it.dropped && it.fee); assertEquals(listOf(true, false), it.theirs); assertEquals(listOf(true, false), it.paid) }
        val wait = exit("AwaitingDelta", ExitDetails(tip_height = 150, confirmed_block = ExitDetails.Block(104), claimable_height = 248),
            history = listOf(processing(104, *four.mapIndexed { i, id -> tx(id, "confirmed", block = 101L + i) }.toTypedArray())))
        pose(wait, 150).let { assertTrue(it.zoomed); assertFalse(it.claiming); assertEquals(104L, it.lockedFrom); assertEquals(248L, it.claimableHeight)
            assertEquals(listOf(101L, 102, 103, 104), it.mined); assertEquals(1f, PictureMotion.rest(it).zoom); assertEquals(0f, PictureMotion.rest(it).open) }
        val claimable = wait.copy(state = "Claimable", details = ExitDetails(tip_height = 248, claimable_since = ExitDetails.Block(248)))
        assertEquals(1f, PictureMotion.rest(pose(claimable, 248)).open)
        val claimed = wait.copy(state = "Claimed", details = ExitDetails(tip_height = 249, block = ExitDetails.Block(249), txid = "claim"))
        PictureMotion.rest(pose(claimed, 249)).let { assertEquals(1f, it.claim); assertEquals(1f, it.fin) }
        val canceled = exit("Canceled", ExitDetails(tip_height = 101), history = listOf(processing(101, tx("root", "confirmed", block = 101), tx("leaf", "awaiting-cpfp-broadcast"))))
        pose(canceled, 101).let { assertEquals(listOf(101L, null), it.mined); assertEquals(1f, PictureMotion.rest(it).cancel) }
    }

    /**
     * A signet exit (VTXO 4bd98032…) that shares ancestors with two others: each path transaction is paid by its CPFP child,
     * and steps 1–7 were sent once for all of them. Its page shows the whole path, and how much of it is shared.
     */
    @Test fun feesCoverTheWholePathAndSayWhatIsShared() {
        val fees = listOf(32L, 407, 364, 28, 37, 28, 37, 28)
        // This wallet's own fee children, as the overview prices them; each exit carries those on its path (OnchainOperations.enrich).
        val wallet = (1..8).associate { "c$it" to fees[it - 1] } + mapOf("cb8" to 31L, "co6" to 29L)
        fun step(n: Int) = ExitPackage("t$n", childTxid = "c$n", childOrigin = if (n == 8) "wallet" else "block")
        fun known(packages: List<ExitPackage>) = packages.mapNotNull { p -> p.childTxid?.let { c -> wallet[c]?.let { c to it } } }.toMap()
        val mine = (1..8).map(::step)
        val twinPath = (1..7).map(::step) + ExitPackage("b8", childTxid = "cb8", childOrigin = "wallet")
        val otherPath = (1..5).map(::step) + ExitPackage("o6", childTxid = "co6", childOrigin = "wallet")
        val details = processing(108, tx("t8", "confirmed", "c8", 108, "wallet"))
        val self = exit("Processing", details, packages = mine, fees = known(mine), id = "4bd98032")
        val twin = exit("Processing", details, packages = twinPath, fees = known(twinPath), id = "twin")
        val other = exit("Processing", details, packages = otherPath, fees = known(otherPath), id = "other")
        assertEquals(961L to 933L, ExitFees.paid(self, listOf(self, twin, other)))
        assertEquals(961L, ExitStages.of(self, 108).feesPaid)
        // Alone, nothing is shared; a child that isn't this wallet's (no known fee) costs nothing.
        assertEquals(961L to 0L, ExitFees.paid(self, listOf(self)))
        assertEquals(929L to 901L, ExitFees.paid(self.copy(childFees = self.childFees - "c1"), listOf(self, twin, other)))
        // The list counts each child once, as Activity's "Exit fee" rows: 961 + 31 + 29, not each exit's path again.
        assertEquals(1_021L, ExitFees.total(listOf(self, twin, other)))
    }
    /** The explorer link opens the claim once there is one, else the furthest step that's been sent; nothing sent, none. */
    @Test fun theExplorerOpensTheClaimElseTheFurthestSentStep() {
        val sent = exit("Processing", processing(101, tx("root", "confirmed", "c1", 101), tx("l1", "awaiting-confirmation", "c2"), tx("leaf", "awaiting-cpfp-broadcast")))
        assertEquals("l1", sent.explorerTxid(null))
        assertNull(exit("Processing", processing(100, tx("root", "awaiting-cpfp-broadcast"))).explorerTxid(null))
        assertEquals("claim", sent.explorerTxid("claim"))
    }
    @Test fun anotherWalletsChildIsSharedAndCostsNothing() {
        // Its child isn't one of this wallet's transactions, so the overview has no fee for it.
        val stage = ExitStages.of(exit("Processing", processing(100, tx("root", "awaiting-confirmation", "theirs", origin = "mempool"), tx("leaf", "verify-inputs")),
            packages = listOf(ExitPackage("root", childTxid = "theirs", childOrigin = "mempool"))), 100)
        assertEquals(ExitStage.Attention.shared, stage.attention); assertNull(stage.fee); assertEquals(0L, stage.feesPaid)
    }

    @Test fun aReplacedChildMeansFeesRoseAndBlocksWithoutItMeanStuck() {
        val sent = processing(100, tx("root", "awaiting-confirmation", "c1", origin = "wallet"), tx("leaf", "verify-inputs"))
        val firstSeen = mapOf("v1/root/awaiting-confirmation/100:0" to 100L)
        // bark keeps the state, tip and all, while nothing changes: two blocks on the wallet's tip, it's stuck.
        val stuck = ExitStages.of(exit("Processing", sent), 102, seen = firstSeen)
        assertEquals(ExitStage.Attention.stuck, stuck.attention); assertEquals(2L, stuck.waited)
        assertNull("no block has come without it yet", ExitStages.of(exit("Processing", sent), 100, seen = firstSeen).attention)
        assertNull("not seen in the mempool yet: nothing to count from", ExitStages.of(exit("Processing", sent), 102).attention)
        val bumped = processing(101, tx("root", "awaiting-confirmation", "c2", origin = "wallet"), tx("leaf", "verify-inputs"))
        assertEquals(ExitStage.Attention.feesRose, ExitStages.of(exit("Processing", bumped, history = listOf(sent)), 101).attention)
        // Evicted (or reorged out), then sent again with a fresh child: the earlier stay's child isn't a replacement, so it's
        // stuck, not "fees rose", once a block comes without it.
        val evicted = processing(102, tx("root", "awaiting-cpfp-broadcast"), tx("leaf", "verify-inputs"))
        val again = exit("Processing", processing(103, tx("root", "awaiting-confirmation", "c3", origin = "wallet"), tx("leaf", "verify-inputs")),
            history = listOf(sent, evicted))
        val stage = ExitStages.of(again, 105, seen = mapOf("v1/root/awaiting-confirmation/100:2" to 103L))
        assertEquals(ExitStage.Attention.stuck, stage.attention); assertEquals(2L, stage.waited)
        val rebumped = exit("Processing", processing(104, tx("root", "awaiting-confirmation", "c4", origin = "wallet"), tx("leaf", "verify-inputs")),
            history = listOf(sent, evicted, again.details))
        assertEquals("replaced within this stay", ExitStage.Attention.feesRose, ExitStages.of(rebumped, 104).attention)
    }

    @Test fun noahCountsWaitingFromTheTipItFirstSawEachStatusAt() {
        var stored: String? = null
        val seen = ExitSeen({ stored }, { stored = it })
        val sent = exit("Processing", processing(100, tx("root", "awaiting-confirmation", "c1"), tx("leaf", "verify-inputs")))
        assertEquals(mapOf("v1/root/awaiting-confirmation/100:0" to 100L, "v1/leaf/verify-inputs/100:0" to 100L), seen.note(listOf(sent), 100))
        // A later read of the same state keeps the first tip, also after a restart (a new store over the same text).
        assertEquals(100L, ExitSeen({ stored }, { stored = it }).note(listOf(sent), 103)["v1/root/awaiting-confirmation/100:0"])
        assertEquals(3L, ExitStages.of(sent, 103, seen = seen.note(listOf(sent), 103)).waited)
        // A new status starts over; anything no longer waiting is forgotten.
        val mined = exit("Processing", processing(104, tx("root", "confirmed", "c1", 104), tx("leaf", "awaiting-confirmation", "c2")))
        assertEquals(mapOf("v1/leaf/awaiting-confirmation/104:0" to 104L), seen.note(listOf(mined), 104))
        assertEquals(emptyMap<String, Long>(), seen.note(emptyList(), 105))
        assertEquals("unreadable text starts afresh", mapOf("v1/leaf/awaiting-confirmation/104:0" to 106L), ExitSeen({ "{" }, {}).note(listOf(mined), 106))
        // Evicted and sent again while Noah wasn't looking: bark's history shows a new episode, counted afresh.
        val evicted = exit("Processing", processing(110, tx("root", "confirmed", "c1", 104), tx("leaf", "awaiting-confirmation", "c3")),
            history = listOf(mined.details, processing(108, tx("root", "confirmed", "c1", 104), tx("leaf", "awaiting-cpfp-broadcast"))))
        assertEquals(mapOf("v1/leaf/awaiting-confirmation/104:2" to 110L), seen.note(listOf(evicted), 110))
    }

    @Test fun aCanceledAndRestartedExitStartsItsEvidenceOver() {
        fun run(began: Long, vararg later: ExitDetails, now: ExitDetails) =
            exit("Processing", now, history = listOf(ExitDetails(kind = "start", tip_height = began)) + later, next = 1_215)
        val parked = processing(102, tx("root", "awaiting-cpfp-broadcast"), tx("leaf", "verify-inputs"))
        val first = run(100, processing(101, tx("root", "verify-inputs"), tx("leaf", "verify-inputs")), now = parked)
        val funding = ExitFunding(75_000) { 0 }
        funding.checked(listOf(first), completed = true)
        assertEquals(setOf("v1"), funding.needsFunds(listOf(first), 0, 1))
        // bark restarts a canceled exit with empty history: the same transaction parks at the same place in a later run.
        val restarted = run(108, processing(109, tx("root", "verify-inputs"), tx("leaf", "verify-inputs")), now = parked.copy(tip_height = 110))
        assertEquals("the same place in its history", "100:2" to "108:2", ExitStages.episode(first, "root") to ExitStages.episode(restarted, "root"))
        assertTrue("a new run needs its own proof", funding.needsFunds(listOf(restarted), 0, 1).isEmpty())
        // Restarted within the same block, the keys would match: starting or canceling forgets the exit's evidence.
        val again = ExitFunding(75_000) { 0 }
        again.checked(listOf(first), completed = true); again.forget(listOf("v1"))
        assertTrue(again.needsFunds(listOf(first), 0, 1).isEmpty())
        var stored: String? = null
        val seen = ExitSeen({ stored }, { stored = it })
        assertEquals(100L, seen.note(listOf(first), 100).getValue("v1/root/awaiting-cpfp-broadcast/100:2"))
        assertEquals("another run, another tip", 110L, seen.note(listOf(restarted), 110).getValue("v1/root/awaiting-cpfp-broadcast/108:2"))
        seen.note(listOf(first), 100)
        seen.forget(listOf("v2")); assertEquals("only the exits named", 100L, seen.note(listOf(first), 120).getValue("v1/root/awaiting-cpfp-broadcast/100:2"))
        seen.forget(listOf("v1")); assertEquals(120L, seen.note(listOf(first), 120).getValue("v1/root/awaiting-cpfp-broadcast/100:2"))
    }

    @Test fun waitingTimeFromBeforeTheBackgroundDoesNotCount() {
        var now = 0L
        val funding = ExitFunding(75_000) { now }
        funding.needsFunds(listOf(parked), 0, 1)
        // Backgrounded for a while, the run unchanged: bark's daemon may not have run, so the wait starts over on return.
        now = 300_000; funding.backgrounded()
        assertTrue(funding.needsFunds(listOf(parked), 0, 1).isEmpty())
        now = 374_999; assertTrue(funding.needsFunds(listOf(parked), 0, 1).isEmpty())
        now = 375_000; assertEquals(setOf("v1"), funding.needsFunds(listOf(parked), 0, 1))
        // A completed Check the chain is proof whatever happens after.
        val checking = ExitFunding(75_000) { 0 }
        checking.checked(listOf(parked), completed = true); checking.backgrounded()
        assertEquals(setOf("v1"), checking.needsFunds(listOf(parked), 0, 1))
    }

    @Test fun anExitIsStaleOnlyWithEvidenceThatNothingProgressesIt() {
        val sent = processing(100, tx("root", "awaiting-confirmation", "c1", origin = "wallet"), tx("leaf", "verify-inputs"))
        assertFalse("the daemon keeps it current, whatever bark's height says", ExitStages.of(exit("Processing", sent), 150).stale)
        assertTrue(ExitStages.of(exit("Processing", sent), 101, stalled = true).stale)
        assertTrue(ExitStages.of(exit("Start", ExitDetails(kind = "start", tip_height = 100)), 101, stalled = true).stale)
        assertFalse("not behind", ExitStages.of(exit("Processing", sent), 100, stalled = true).stale)
        assertFalse("a waiting exit has nothing to send", ExitStages.of(exit("AwaitingDelta",
            ExitDetails(tip_height = 100, confirmed_block = ExitDetails.Block(100), claimable_height = 244)), 180, stalled = true).stale)
    }

    private val parked = exit("Processing", processing(100, tx("root", "confirmed", block = 99), tx("l2", "awaiting-cpfp-broadcast"), tx("leaf", "verify-inputs")),
        history = listOf(processing(99, tx("root", "awaiting-confirmation", "c1"), tx("l2", "verify-inputs"), tx("leaf", "verify-inputs")),
            processing(100, tx("root", "confirmed", block = 99), tx("l2", "awaiting-cpfp-broadcast"), tx("leaf", "verify-inputs"))), next = 1_215)

    @Test fun needsFundsWaitsUntilBarkHasTriedAndClearsWhenFundsArrive() {
        var now = 0L
        val funding = ExitFunding(75_000) { now }
        assertTrue("just parked: bark may still pay it", funding.needsFunds(listOf(parked), 310, run = 1).isEmpty())
        now = 74_999; assertTrue(funding.needsFunds(listOf(parked), 310, 1).isEmpty())
        now = 75_000; assertEquals("a daemon interval passed in one run", setOf("v1"), funding.needsFunds(listOf(parked), 310, 1))
        assertEquals(ExitStage.Attention.needsFunds, ExitStages.of(parked, 100, needsFunds = true).attention)
        assertTrue("funds arrived", funding.needsFunds(listOf(parked), 1_215, 1).isEmpty())
        assertTrue("no package fee known", funding.needsFunds(listOf(parked.copy(nextPackageFee = null)), 0, 1).isEmpty())
        // Time counts only within one daemon run: reopening the wallet starts it over, and without the daemon it never counts.
        val reopened = ExitFunding(75_000) { now }
        now = 0; reopened.needsFunds(listOf(parked), 0, 1)
        now = 60_000; assertTrue(reopened.needsFunds(listOf(parked), 0, 2).isEmpty())
        now = 134_999; assertTrue(reopened.needsFunds(listOf(parked), 0, 2).isEmpty())
        now = 135_000; assertEquals(setOf("v1"), reopened.needsFunds(listOf(parked), 0, 2))
        val noDaemon = ExitFunding(75_000) { now }
        now = 0; noDaemon.needsFunds(listOf(parked), 0, null)
        now = 10_000_000; assertTrue(noDaemon.needsFunds(listOf(parked), 0, null).isEmpty())
        // A completed Check the chain proves bark tried, whether Noah saw the transaction parked before or after it; a failed one doesn't.
        val checking = ExitFunding(75_000) { 0 }
        checking.checked(listOf(parked), completed = false)
        assertTrue(checking.checkFailed); assertTrue(checking.needsFunds(listOf(parked), 0, null).isEmpty())
        checking.checked(listOf(parked), completed = true)
        assertFalse(checking.checkFailed); assertEquals(setOf("v1"), checking.needsFunds(listOf(parked), 0, null))
        // Once it gets its child it's forgotten: parked again, it needs bark to try again.
        val sent = parked.copy(details = processing(100, tx("root", "confirmed", block = 99), tx("l2", "awaiting-confirmation", "c2"), tx("leaf", "verify-inputs")))
        assertTrue(checking.needsFunds(listOf(sent), 0, null).isEmpty())
        assertTrue(checking.needsFunds(listOf(parked), 0, null).isEmpty())
        // The same, unseen by Noah: evicted and parked again between two reads. bark's history starts a new episode, so
        // neither the old check nor the old waiting time counts.
        val episodes = ExitFunding(75_000) { now }
        now = 0; episodes.checked(listOf(parked), completed = true)
        assertEquals(setOf("v1"), episodes.needsFunds(listOf(parked), 0, 1))
        val again = parked.copy(details = parked.details.copy(tip_height = 103),
            historyDetails = parked.historyDetails + parked.details + sent.details)
        assertTrue("a new episode needs its own proof", episodes.needsFunds(listOf(again), 0, 1).isEmpty())
        now = 75_000; assertEquals(setOf("v1"), episodes.needsFunds(listOf(again), 0, 1))
        assertEquals("only the parked transaction shows it", null, ExitStages.of(exit("Processing", processing(100, *path("verify-inputs"))), 100, needsFunds = true).attention)
    }

    @Test fun aCheckThatReportsFundsShortIsDirectEvidenceWithWhatBarkNeeds() {
        val funding = ExitFunding(75_000) { 0 }
        // No waiting time and no package fee known: bark's own report is enough.
        val unpriced = parked.copy(nextPackageFee = null)
        assertTrue(funding.needsFunds(listOf(unpriced), 0, 1).isEmpty())
        funding.reported(mapOf("v1" to 4_000))
        assertEquals(4_000L, funding.needed("v1"))
        assertEquals(setOf("v1"), funding.needsFunds(listOf(unpriced), 3_999, 1))
        assertTrue("funds arrived", funding.needsFunds(listOf(unpriced), 4_000, 1).isEmpty())
        assertTrue("not once it's over", funding.needsFunds(listOf(unpriced.copy(state = "Canceled")), 0, 1).isEmpty())
        // A later check replaces it; a start or cancel forgets it.
        funding.reported(emptyMap()); assertNull(funding.needed("v1"))
        funding.reported(mapOf("v1" to 4_000)); funding.forget(listOf("v1"))
        assertNull(funding.needed("v1")); assertTrue(funding.needsFunds(listOf(unpriced), 0, 1).isEmpty())
    }

    private fun vtxo(id: String, amount: Long, state: String = "Spendable") = VtxoModel(id, amount, 1_000, state, "round", 4, 0, true, "", 144, "a:0", "p:0", false, 100)

    @Test fun vtxosBarkRefusedShowWhyAndAreLeftOutOfEverySelection() {
        val open = vtxo("open", 31_000); val spent = vtxo("spent", 20_000); val dust = vtxo("dust", 290); val locked = vtxo("locked", 9_000, "Locked")
        val refused = mapOf("spent" to "Already spent", "dust" to "This VTXO can't be exited.", "locked" to "Swept")
        assertEquals(ExitCosts.Barred.refused, ExitCosts.barred(spent, refused))
        assertEquals("today's labels come first", ExitCosts.Barred.dust, ExitCosts.barred(dust, refused))
        assertEquals(ExitCosts.Barred.locked, ExitCosts.barred(locked, refused))
        assertNull(ExitCosts.barred(open, refused))
        val cost = ExitCosts.Cost(5_533, 262, 4)
        assertEquals(listOf(open), ExitCosts.all(listOf(open, spent, dust, locked), mapOf("open" to cost, "spent" to cost), refused))
        assertEquals("without a refusal it's chosen as before", listOf(open, spent), ExitCosts.all(listOf(open, spent), mapOf("open" to cost, "spent" to cost)))
    }

    @Test fun vtxosTheAllEstimateLeavesOutNeedRenewingAndCountNowhere() {
        val open = vtxo("open", 31_000); val nonStandard = vtxo("non-standard", 20_000); val dust = vtxo("dust", 290); val locked = vtxo("locked", 9_000, "Locked")
        val excluded = setOf("non-standard", "dust", "locked")
        assertEquals(ExitCosts.Barred.renew, ExitCosts.barred(nonStandard, excluded = excluded))
        assertEquals("today's dust label stays", ExitCosts.Barred.dust, ExitCosts.barred(dust, excluded = excluded))
        assertEquals(ExitCosts.Barred.locked, ExitCosts.barred(locked, excluded = excluded))
        assertEquals("renewing first is the advice, whatever else bark said", ExitCosts.Barred.renew,
            ExitCosts.barred(nonStandard, mapOf("non-standard" to "This VTXO can't be exited."), excluded))
        val cost = ExitCosts.Cost(5_533, 262, 4)
        val all = ExitCosts.all(listOf(open, nonStandard, dust, locked), mapOf("open" to cost, "non-standard" to cost), excluded = excluded)
        assertEquals("neither in All nor in its total", listOf(open), all); assertEquals(31_000L, all.sumOf { it.amount })
        assertEquals("dust is still counted as today", 290L, ExitCosts.leftOut(listOf(open, nonStandard, dust), emptyMap()).dust)
    }

    @Test fun uneconomicVtxosAreLabelledAndLeftOutOfAll() {
        val big = vtxo("big", 31_000); val small = vtxo("small", 5_000); val dust = vtxo("dust", 290); val locked = vtxo("locked", 710, "Locked"); val unknown = vtxo("unknown", 4_000)
        val costs = mapOf("big" to ExitCosts.Cost(5_533, 262, 4), "small" to ExitCosts.Cost(4_800, 262, 4), "unknown" to null)
        assertFalse(ExitCosts.uneconomic(big, costs["big"]))
        assertTrue(ExitCosts.uneconomic(small, costs["small"]))
        assertTrue("exactly its amount is not worth it", ExitCosts.uneconomic(vtxo("even", 5_062), costs["small"]))
        assertFalse(ExitCosts.uneconomic(unknown, null))
        assertFalse("dust stays today's rule, not this one", ExitCosts.uneconomic(dust, ExitCosts.Cost(1, 1, 1)))
        val vtxos = listOf(big, small, dust, locked, unknown)
        assertEquals(listOf(big, unknown), ExitCosts.all(vtxos, costs))
        assertEquals(ExitCosts.LeftOut(290, 710, 5_000), ExitCosts.leftOut(vtxos, costs))
    }

    @Test fun settingsSaysInProgressOnlyWhileAnExitRuns() {
        assertFalse(ExitList.live(null)); assertFalse(ExitList.live(emptyList()))
        for (state in listOf("Start", "Processing", "AwaitingDelta", "Claimable", "ClaimInProgress")) assertTrue(state, ExitList.live(listOf(exit(state, ExitDetails()))))
        for (state in listOf("Claimed", "Canceled", "VtxoAlreadySpent", "VtxoSwept")) assertFalse(state, ExitList.live(listOf(exit(state, ExitDetails()))))
    }

    @Test fun theListDrawsSharedPathsOnlyWhenExitsShareATransaction() {
        fun paths(id: String, vararg txs: String, state: String = "Processing") = exit(state, ExitDetails(), packages = txs.map { ExitPackage(it) }, id = id)
        val a = paths("a", "root", "l2a", "l1a", "leafA"); val b = paths("b", "root", "l2a", "l1b", "leafB"); val c = paths("c", "root2", "x", "y", "z")
        assertEquals(emptyList<ExitList.Shared>(), ExitList.shared(listOf(a, c)))
        assertEquals(listOf(ExitList.Shared(listOf(a, b), 6, 8)), ExitList.shared(listOf(a, b, c)))
        assertEquals("a finished exit isn't drawn", emptyList<ExitList.Shared>(),
            ExitList.shared(listOf(a, paths("b", "root", "l2a", "l1b", "leafB", state = "Claimed"))))
    }

    @Test fun longSharedPathsStayInsideThePicture() {
        assertEquals("a round's four hops keep today's spacing", 30f, ExitList.rows(250f, 3).step, 0f)
        // Four tree hops and two checkpoint or arkoor hops: 8 transactions, the deepest at depth 7; and longer still.
        for (deepest in 1..12) {
            val rows = ExitList.rows(250f, deepest)
            assertTrue("depth $deepest: the coin's amount is cut off", rows.y(deepest) - ExitList.labelRoom >= 0f)
            assertTrue(rows.step <= 30f)
        }
    }

    @Test fun exitsFromTwoRoundsAreDrawnAsTwoGroups() {
        fun paths(id: String, vararg txs: String) = exit("Processing", ExitDetails(), packages = txs.map { ExitPackage(it) }, id = id)
        val a = paths("a", "r1", "a1"); val b = paths("b", "r1", "b1"); val c = paths("c", "r2", "c1"); val d = paths("d", "r2", "d1")
        assertEquals(listOf(ExitList.Shared(listOf(a, b), 3, 4), ExitList.Shared(listOf(c, d), 3, 4)), ExitList.shared(listOf(a, c, b, d)))
        // Two groups that a later exit joins become one, in the list's order.
        val e = paths("e", "r1", "c1")
        assertEquals(listOf(ExitList.Shared(listOf(a, c, b, d, e), 6, 10)), ExitList.shared(listOf(a, c, b, d, e)))
    }

    @Test fun aBatchedClaimsExitsAddUpToWhatArrived() {
        val ids = listOf("b", "a")
        assertEquals(listOf(131L, 131L), ids.map { ExitClaim.share(262, ids, it) })
        assertEquals("the spare sat goes to the first id", listOf(131L, 132L), ids.map { ExitClaim.share(263, ids, it) })
        assertEquals(262L, ExitClaim.share(262, listOf("a"), "a"))
        assertEquals("unknown inputs: this exit carries the fee", 262L, ExitClaim.share(262, emptyList(), "a"))
        val claim = ExitContext(0, null, null, claimFee = 262, claimIds = listOf("a", "b"))
        assertEquals(55_238L, ExitRules.received(31_000, claim.claimShare("a")!!) + ExitRules.received(24_500, claim.claimShare("b")!!))
        assertNull(ExitContext(0, null, null).claimShare("a"))
    }

    @Test fun theListsRailAndSharedTreeFollowEachExit() {
        assertEquals(listOf(s.now, s.todo, s.todo, s.todo, s.todo), ExitList.rail(ExitStage(ExitStage.Phase.lift)))
        assertEquals(listOf(s.done, s.now, s.todo, s.todo, s.todo), ExitList.rail(ExitStage(ExitStage.Phase.bump)))
        assertEquals(listOf(s.done, s.failed, s.todo, s.todo, s.todo), ExitList.rail(ExitStage(ExitStage.Phase.mempool, ExitStage.Attention.needsFunds)))
        assertEquals(listOf(s.done, s.done, s.now, s.todo, s.todo), ExitList.rail(ExitStage(ExitStage.Phase.wait)))
        assertEquals(listOf(s.done, s.done, s.done, s.now, s.todo), ExitList.rail(ExitStage(ExitStage.Phase.claimable)))
        assertEquals(List(5) { s.done }, ExitList.rail(ExitStage(ExitStage.Phase.claimed)))
        assertEquals(listOf(s.done, s.todo, s.todo, s.todo, s.todo), ExitList.rail(ExitStage(ExitStage.Phase.canceled)))
        fun paths(id: String, vararg txs: String) = exit("Processing", processing(100, tx(txs[0], "confirmed", block = 99)), packages = txs.map { ExitPackage(it) }, id = id)
        val group = ExitList.shared(listOf(paths("a", "root", "x", "leafA"), paths("b", "root", "x", "leafB"))).single()
        val placed = ExitList.layout(group).associateBy { it.txid }
        assertEquals(setOf("root", "x", "leafA", "leafB"), placed.keys)
        assertEquals(null, placed.getValue("root").parent); assertEquals("x", placed.getValue("leafB").parent)
        assertEquals(0, placed.getValue("leafA").coin); assertEquals(1, placed.getValue("leafB").coin); assertNull(placed.getValue("x").coin)
        assertEquals("shared nodes sit between the coins they lead to", 0f, placed.getValue("x").x, 0.001f)
        assertEquals(ExitList.TxState.mined, placed.getValue("root").state); assertEquals(ExitList.TxState.waiting, placed.getValue("x").state)
    }

    @Test fun anotherWalletTakesOnlyAnOnchainAddressForThisNetwork() {
        val regtest = "bcrt1psagmjg628zl8zj5qzxszhtvlx3hhhq8y5a438l9vmfasu2yschnqwyxvxg"
        assertEquals(regtest, ExitClaim.address(regtest, "regtest"))
        assertEquals(regtest, ExitClaim.address("  $regtest\n", "regtest"))
        assertEquals(regtest, ExitClaim.address("bitcoin:$regtest?amount=0.001", "regtest"))
        assertNull(ExitClaim.address(regtest, "mainnet"))
        assertNull(ExitClaim.address("bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq", "regtest"))
        assertEquals("bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq", ExitClaim.address("bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq", "mainnet"))
        assertNull("a signet wallet isn't regtest", ExitClaim.address("tb1qw508d6qejxtdg4y5r3zarvary0c5xw7kxpjzsx", "regtest"))
        assertEquals("tb1qw508d6qejxtdg4y5r3zarvary0c5xw7kxpjzsx", ExitClaim.address("tb1qw508d6qejxtdg4y5r3zarvary0c5xw7kxpjzsx", "signet"))
        for (bad in listOf("", "bcrt1q", "ark1qq", "lnbcrt1", "bitcoin:?lightning=lnbc1", "user@example.com")) assertNull(bad, ExitClaim.address(bad, "regtest"))
    }

    @Test fun aSubmittedClaimShowsAsClaimingUntilBarkCatchesUp() {
        fun attempt(txid: String?, kind: com.noahwallet.services.wallet.ChainStatus.Kind?, action: OnchainAction = OnchainAction.claim) =
            OnchainOperationJournal.Attempt(OnchainQuote(action, "wallet", listOf("a", "b"), "address"),
                OnchainReceipt("Claim submitted.", txid, chainStatus = kind?.let { com.noahwallet.services.wallet.ChainStatus(it) }))
        assertEquals(mapOf("a" to "claim", "b" to "claim"), ExitClaims.submitted(listOf(attempt("claim", com.noahwallet.services.wallet.ChainStatus.Kind.inMempool))))
        assertEquals("not checked yet", mapOf("a" to "claim", "b" to "claim"), ExitClaims.submitted(listOf(attempt("claim", null))))
        assertTrue("the chain source lost it: claimable again", ExitClaims.submitted(listOf(attempt("claim", com.noahwallet.services.wallet.ChainStatus.Kind.notFound))).isEmpty())
        assertTrue(ExitClaims.submitted(listOf(attempt(null, null), attempt("x", null, OnchainAction.startExit))).isEmpty())
        val claimable = ExitStage(ExitStage.Phase.claimable)
        val waiting = exit("Claimable", ExitDetails(kind = "claimable"))
        assertEquals(ExitStage(ExitStage.Phase.claiming, claimTxid = "claim"), ExitClaims.apply(waiting, claimable, "claim"))
        assertEquals(listOf(a.check), ExitActions.of(ExitClaims.apply(waiting, claimable, "claim"), cancelable = false))
        assertEquals(claimable, ExitClaims.apply(waiting, claimable, null))
        assertEquals("bark's own later states win", ExitStage(ExitStage.Phase.claimed), ExitClaims.apply(waiting, ExitStage(ExitStage.Phase.claimed), "claim"))
        // bark saw that claim, then it fell out and the exit is claimable again: show that, not "Claiming" forever.
        val evicted = exit("Claimable", ExitDetails(kind = "claimable"), history = listOf(ExitDetails(kind = "claim-in-progress", claim_txid = "claim")))
        assertEquals(claimable, ExitClaims.apply(evicted, claimable, "claim"))
        assertEquals("a new claim, not yet seen by bark, still shows", ExitStage.Phase.claiming, ExitClaims.apply(evicted, claimable, "claim2").phase)
    }

    @Test fun claimStaysOffUntilItsDestinationIsValidAndReviewed() {
        val regtest = "bcrt1psagmjg628zl8zj5qzxszhtvlx3hhhq8y5a438l9vmfasu2yschnqwyxvxg"
        val mainnet = "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq"
        assertEquals("Another wallet is the default", ClaimTo.other, ClaimTo.entries.first())
        assertNull(ExitClaim.destination(ClaimTo.other, "", "phone-address", "regtest"))
        assertNull(ExitClaim.destination(ClaimTo.other, mainnet, null, "regtest"))
        assertEquals(regtest, ExitClaim.destination(ClaimTo.other, regtest, null, "regtest"))
        assertEquals("phone-address", ExitClaim.destination(ClaimTo.phone, mainnet, "phone-address", "regtest"))
        assertNull("this phone's address is still loading", ExitClaim.destination(ClaimTo.phone, regtest, null, "regtest"))
        val now = System.currentTimeMillis()
        val review = OnchainQuote(OnchainAction.claim, "wallet", listOf("a"), regtest, 31_000, fee = 262, chainFee = 0, createdAt = now)
        assertTrue(ExitClaim.ready(regtest, review, now))
        assertFalse("no valid address", ExitClaim.ready(null, review, now))
        assertFalse("not reviewed yet", ExitClaim.ready(regtest, null, now))
        assertFalse("reviewed for another address", ExitClaim.ready("phone-address", review, now))
        assertFalse("stale review", ExitClaim.ready(regtest, review, now + 30_000))
        assertFalse(ExitClaim.ready(regtest, review.copy(action = OnchainAction.startExit), now))
    }

    @Test fun transactionsGiveTheirTxidWeightAndShape() {
        // Genesis coinbase (legacy) and a regtest key-spend (segwit), checked against bitcoind's decoderawtransaction.
        val genesis = ExitTransactions.parse("01000000010000000000000000000000000000000000000000000000000000000000000000ffffffff4d04ffff001d0104455468652054696d65732030332f4a616e2f32303039204368616e63656c6c6f72206f6e206272696e6b206f66207365636f6e64206261696c6f757420666f722062616e6b73ffffffff0100f2052a01000000434104678afdb0fe5548271967f1a67130b7105cd6a828e03909a67962e0ea1f61deb649f6bc3f4cef38c4f35504e51ec112de5c384df7ba0b8d578a4c702b6bf11d5fac00000000")!!
        assertEquals("4a5e1e4baab89f3a32518a88c31bc87f618f76673e2cc77ab2127b7afdeda33b", genesis.txid); assertEquals(816L, genesis.weight); assertEquals(1, genesis.outputs)
        val segwit = ExitTransactions.parse("020000000001013e16e76ee2b6a9dc798b81be3a4903286e4e454dc019c48062d5396b4e21fdca0000000000fdffffff01d09b0000000000002251208751b9234a38be714a8011a02bad9f346f7b80e4a76b13fcacda7b0e2890c5e601408982addc732de240dd1dfb0bd4a66e0ab807073c73d92b26256827a2643735827b1eb0cb92812c4969544c107489479dc54ec9bc27409a15bef8486972bf03297f030000")!!
        assertEquals("75ebf7553b394d32e07c7e4297c86f2e861101cf5cae510a1c4f28f490cf20c6", segwit.txid); assertEquals(444L, segwit.weight)
        assertEquals(listOf("cafd214e6b39d56280c419c04d454e6e2803493abe818b79dca9b6e26ee7163e" to 0L), segwit.inputs)
        for (bad in listOf("", "zz", "0100000001")) assertNull(ExitTransactions.parse(bad))
        // bark's package fee: (parent + canonical child) × the rate, rounded up. A 1,012 wu node at 3 sat/vB (750 sat/kwu).
        assertEquals(1_216L, ExitTransactions.packageFee(1_012, 750))
    }
}
