package com.noahwallet.onchain

import org.junit.Assert.*
import org.junit.Test

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

    @Test fun anotherWalletsChildIsSharedAndCostsNothing() {
        val stage = ExitStages.of(exit("Processing", processing(100, tx("root", "awaiting-confirmation", "theirs", origin = "mempool"), tx("leaf", "verify-inputs")),
            packages = listOf(ExitPackage("root", childTxid = "theirs", childOrigin = "mempool")), fees = mapOf("theirs" to 999)), 100)
        assertEquals(ExitStage.Attention.shared, stage.attention); assertNull(stage.fee); assertEquals(0L, stage.feesPaid)
    }

    @Test fun aReplacedChildMeansFeesRoseAndBlocksWithoutItMeanStuck() {
        val sent = processing(100, tx("root", "awaiting-confirmation", "c1", origin = "wallet"), tx("leaf", "verify-inputs"))
        val stillWaiting = processing(102, tx("root", "awaiting-confirmation", "c1", origin = "wallet"), tx("leaf", "verify-inputs"))
        val stuck = ExitStages.of(exit("Processing", stillWaiting, history = listOf(sent)), 102)
        assertEquals(ExitStage.Attention.stuck, stuck.attention); assertEquals(2L, stuck.waited)
        // The same tip as when it was sent: no block has come without it yet.
        assertNull(ExitStages.of(exit("Processing", sent), 100).attention)
        // The wallet's tip is ahead of bark's: stale, but not stuck (bark hasn't looked at the new block).
        val behind = ExitStages.of(exit("Processing", sent), 101)
        assertNull(behind.attention); assertTrue(behind.stale)
        val bumped = processing(101, tx("root", "awaiting-confirmation", "c2", origin = "wallet"), tx("leaf", "verify-inputs"))
        assertEquals(ExitStage.Attention.feesRose, ExitStages.of(exit("Processing", bumped, history = listOf(sent)), 101).attention)
    }

    private val parked = exit("Processing", processing(100, tx("root", "confirmed", block = 99), tx("l2", "awaiting-cpfp-broadcast"), tx("leaf", "verify-inputs")),
        history = listOf(processing(99, tx("root", "awaiting-confirmation", "c1"), tx("l2", "verify-inputs"), tx("leaf", "verify-inputs")),
            processing(100, tx("root", "confirmed", block = 99), tx("l2", "awaiting-cpfp-broadcast"), tx("leaf", "verify-inputs"))), next = 1_215)

    @Test fun needsFundsShowsOnlyAfterAFullProgressCycleAndClearsWhenFundsArrive() {
        var now = 0L
        val funding = ExitFunding(60_000) { now }
        assertTrue("just parked: bark may still pay it", funding.needsFunds(listOf(parked), 310).isEmpty())
        now = 59_999; assertTrue(funding.needsFunds(listOf(parked), 310).isEmpty())
        now = 60_000; assertEquals(setOf("v1"), funding.needsFunds(listOf(parked), 310))
        assertEquals(ExitStage.Attention.needsFunds, ExitStages.of(parked, 100, needsFunds = true).attention)
        assertTrue("funds arrived", funding.needsFunds(listOf(parked), 1_215).isEmpty())
        assertTrue("no package fee known", funding.needsFunds(listOf(parked.copy(nextPackageFee = null)), 0).isEmpty())
        // Once it gets its child, it's forgotten: parking again starts a new cycle.
        val sent = parked.copy(details = processing(100, tx("root", "confirmed", block = 99), tx("l2", "awaiting-confirmation", "c2"), tx("leaf", "verify-inputs")))
        assertTrue(funding.needsFunds(listOf(sent), 0).isEmpty())
        assertTrue(funding.needsFunds(listOf(parked), 0).isEmpty())
        // Check the chain is a full cycle.
        funding.checkedChain(); assertEquals(setOf("v1"), funding.needsFunds(listOf(parked), 0))
        // bark progressed it at a later tip and it still waits: a cycle passed, even on a fresh page.
        val progressed = parked.copy(details = parked.details.copy(tip_height = 101))
        assertEquals(setOf("v1"), ExitFunding(60_000) { 0 }.needsFunds(listOf(progressed), 0))
        assertEquals("only the parked transaction shows it", null, ExitStages.of(exit("Processing", processing(100, *path("verify-inputs"))), 100, needsFunds = true).attention)
    }

    private fun vtxo(id: String, amount: Long, state: String = "Spendable") = VtxoModel(id, amount, 1_000, state, "round", 4, 0, true, "", 144, "a:0", "p:0", false, 100)

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
        assertNull(ExitList.shared(listOf(a, c)))
        assertEquals(ExitList.Shared(listOf(a, b), 6, 8), ExitList.shared(listOf(a, b, c)))
        assertNull("a finished exit isn't drawn", ExitList.shared(listOf(a, paths("b", "root", "l2a", "l1b", "leafB", state = "Claimed"))))
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
