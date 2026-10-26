package com.noahwallet.onchain

import androidx.compose.runtime.*
import com.noahwallet.formatting.BitcoinAmount
import com.noahwallet.formatting.BitcoinAmountUnit
import com.noahwallet.ui.home.HomeToasts
import com.noahwallet.ui.onboarding.OnboardingFrame
import com.noahwallet.ui.settings.LocalSettingsToasts
import java.util.Locale

/**
 * Public, synthetic exits for the gallery and device tests (exit-v1-notes.md, The numbers): tip 921,345, the ₿31,000
 * VTXO from the round in block 921,201 and its 4 transactions, and Settings v1's VTXOs. No wallet is involved.
 */
internal object ExitFixtures {
    const val tip = 921_345L
    val money: (Long) -> String = { BitcoinAmount.format(it, BitcoinAmountUnit.bip177, Locale.US) }
    // A real regtest key-spend, so packages parse as they do live.
    /** A signed claim transaction, as bark's drain PSBT holds it. */
    const val signedClaim = "020000000001013e16e76ee2b6a9dc798b81be3a4903286e4e454dc019c48062d5396b4e21fdca0000000000fdffffff01d09b0000000000002251208751b9234a38be714a8011a02bad9f346f7b80e4a76b13fcacda7b0e2890c5e601408982addc732de240dd1dfb0bd4a66e0ab807073c73d92b26256827a2643735827b1eb0cb92812c4969544c107489479dc54ec9bc27409a15bef8486972bf03297f030000"
    private const val hex = "020000000001013e16e76ee2b6a9dc798b81be3a4903286e4e454dc019c48062d5396b4e21fdca0000000000fdffffff01d09b0000000000002251208751b9234a38be714a8011a02bad9f346f7b80e4a76b13fcacda7b0e2890c5e601408982addc732de240dd1dfb0bd4a66e0ab807073c73d92b26256827a2643735827b1eb0cb92812c4969544c107489479dc54ec9bc27409a15bef8486972bf03297f030000"
    private fun txid(n: String) = n.padEnd(64, 'a').take(64)
    val ids = listOf("root", "branch2", "branch1", "leaf").map(::txid)
    val packages = ids.mapIndexed { i, id -> ExitPackage(id, if (i == 3) 1_288 else 1_620 - 608, if (i == 3) 2 else 5, listOf(2L, 2, 1, 0)[i], null, 608, null) }

    fun vtxo(id: String, amount: Long, state: String = "Spendable", expiry: Long = tip + 4_176) =
        VtxoModel(id.padEnd(64, '0') + ":0", amount, expiry, state, "round", 4, 1_620, true, "server", 144, "anchor:0", "point:0", false, tip)
    val vtxos = listOf(vtxo("c01", 31_000), vtxo("c02", 24_500), vtxo("c03", 21_000), vtxo("c04", 15_000), vtxo("c05", 12_000),
        vtxo("c06", 9_800), vtxo("c07", 8_400), vtxo("c08", 7_500), vtxo("c09", 5_000), vtxo("c10", 4_200), vtxo("c11", 3_342),
        vtxo("c12", 710, "Locked"), vtxo("c13", 290))
    val costs = vtxos.filter { it.eligible }.associate { it.id to ExitCosts.Cost(5_533, 262, 4).let { c -> if (it.amount <= 7_500) c.copy(broadcast = it.amount) else c } }

    private fun tx(i: Int, kind: String, child: String? = null, block: Long? = null, origin: String? = "wallet") =
        ExitDetails.Transaction(ids[i], ExitDetails.Status(kind, child, block?.let { ExitDetails.Block(it) }, origin.takeIf { child != null }))
    private fun processing(at: Long, vararg txs: ExitDetails.Transaction) = ExitDetails(kind = "processing", tip_height = at, transactions = txs.toList())
    private fun exit(state: String, details: ExitDetails, history: List<ExitDetails> = emptyList(), fees: Map<String, Long> = emptyMap(),
                     next: Long? = null, id: String = vtxos[0].id, amount: Long = 31_000, children: Map<Int, Pair<String, String>> = emptyMap()) =
        ExitModel(id, amount, state, details, history.map { it.kind.orEmpty() }, history,
            packages.mapIndexed { i, p -> children[i]?.let { (child, origin) -> p.copy(childTxid = child, childOrigin = origin) } ?: p }, fees, next)

    val start = exit("Start", ExitDetails(kind = "start", tip_height = tip))
    val publish = exit("Processing", processing(tip, tx(0, "verify-inputs"), tx(1, "awaiting-input-confirmation"), tx(2, "awaiting-input-confirmation"), tx(3, "awaiting-input-confirmation")))
    val mempool = exit("Processing", processing(tip, tx(0, "awaiting-cpfp-broadcast"), tx(1, "verify-inputs"), tx(2, "verify-inputs"), tx(3, "verify-inputs")))
    val bump = exit("Processing", processing(tip, tx(0, "awaiting-confirmation", "fee1"), tx(1, "verify-inputs"), tx(2, "verify-inputs"), tx(3, "verify-inputs")),
        fees = mapOf("fee1" to 1_215), children = mapOf(0 to ("fee1" to "wallet")))
    val confirm = exit("Processing", processing(tip + 2, tx(0, "confirmed", "fee1", tip + 1), tx(1, "confirmed", "fee2", tip + 2), tx(2, "awaiting-input-confirmation"), tx(3, "verify-inputs")),
        fees = mapOf("fee1" to 1_215, "fee2" to 1_215), children = mapOf(0 to ("fee1" to "wallet"), 1 to ("fee2" to "wallet")))
    val onchain = exit("Processing", processing(tip + 4, *(0..3).map { tx(it, "confirmed", "fee$it", tip + 1 + it) }.toTypedArray()),
        fees = (0..3).associate { "fee$it" to 1_150L }, children = (0..3).associate { it to ("fee$it" to "wallet") })
    val wait = exit("AwaitingDelta", ExitDetails(kind = "awaiting-delta", tip_height = tip + 49, confirmed_block = ExitDetails.Block(tip + 4), claimable_height = tip + 148),
        history = listOf(onchain.details), fees = onchain.childFees, children = onchain.packages.mapIndexedNotNull { i, p -> p.childTxid?.let { i to (it to "wallet") } }.toMap())
    // bark's history keeps the wait, which holds the timelock's start and end.
    val claimable = wait.copy(state = "Claimable", details = ExitDetails(kind = "claimable", tip_height = tip + 148, claimable_since = ExitDetails.Block(tip + 148)),
        history = listOf("Start", "Processing", "AwaitingDelta"), historyDetails = listOf(start.details, onchain.details, wait.details))
    val claiming = claimable.copy(state = "ClaimInProgress", details = ExitDetails(kind = "claim-in-progress", tip_height = tip + 148, claimable_since = ExitDetails.Block(tip + 148), claim_txid = txid("claim")))
    val claimed = wait.copy(state = "Claimed", details = ExitDetails(kind = "claimed", tip_height = tip + 149, block = ExitDetails.Block(tip + 149), txid = txid("claim")),
        history = listOf("Start", "Processing", "AwaitingDelta"), historyDetails = listOf(start.details, onchain.details, wait.details))
    val nofunds = exit("Processing", processing(tip + 1, tx(0, "confirmed", "fee1", tip + 1), tx(1, "awaiting-cpfp-broadcast"), tx(2, "verify-inputs"), tx(3, "verify-inputs")),
        history = listOf(processing(tip, tx(0, "awaiting-confirmation", "fee1"), tx(1, "verify-inputs"), tx(2, "verify-inputs"), tx(3, "verify-inputs"))),
        fees = mapOf("fee1" to 1_215), next = 1_216, children = mapOf(0 to ("fee1" to "wallet")))
    val feesrose = exit("Processing", processing(tip + 2, tx(0, "confirmed", "fee1", tip + 1), tx(1, "awaiting-confirmation", "fee2b"), tx(2, "verify-inputs"), tx(3, "verify-inputs")),
        history = listOf(processing(tip + 1, tx(0, "confirmed", "fee1", tip + 1), tx(1, "awaiting-confirmation", "fee2a"), tx(2, "verify-inputs"), tx(3, "verify-inputs"))),
        fees = mapOf("fee1" to 1_215, "fee2b" to 3_645), children = mapOf(0 to ("fee1" to "wallet"), 1 to ("fee2b" to "wallet")))
    val stuck = exit("Processing", processing(tip + 5, tx(0, "confirmed", "fee1", tip + 1), tx(1, "confirmed", "fee2", tip + 2), tx(2, "awaiting-confirmation", "fee3"), tx(3, "verify-inputs")),
        history = listOf(processing(tip + 2, tx(0, "confirmed", "fee1", tip + 1), tx(1, "confirmed", "fee2", tip + 2), tx(2, "awaiting-confirmation", "fee3"), tx(3, "verify-inputs"))),
        fees = mapOf("fee1" to 1_215, "fee2" to 1_215, "fee3" to 1_215), children = (0..2).associate { it to ("fee${it + 1}" to "wallet") })
    val shared = exit("Processing", processing(tip, tx(0, "awaiting-confirmation", "theirs", origin = "mempool"), tx(1, "verify-inputs"), tx(2, "verify-inputs"), tx(3, "verify-inputs")),
        children = mapOf(0 to ("theirs" to "mempool")))
    val stale = exit("Processing", processing(tip - 52, tx(0, "confirmed", "fee1", tip - 54), tx(1, "confirmed", "fee2", tip - 53), tx(2, "awaiting-confirmation", "fee3"), tx(3, "verify-inputs")),
        history = listOf(processing(tip - 52, tx(0, "confirmed", "fee1", tip - 54), tx(1, "confirmed", "fee2", tip - 53), tx(2, "awaiting-confirmation", "fee3"), tx(3, "verify-inputs"))),
        fees = mapOf("fee1" to 1_215, "fee2" to 1_215, "fee3" to 1_215), children = (0..2).associate { it to ("fee${it + 1}" to "wallet") })
    /** What Noah noted for the stuck fixture: its transaction first seen in the mempool three blocks before its tip. */
    val seen = mapOf("${vtxos[0].id}/${ids[2]}/awaiting-confirmation/${tip + 2}:0" to tip + 2)
    /** A fixture's stage as its page computes it; [stale] with Noah's evidence that nothing progresses exits. */
    fun stage(exit: ExitModel, tip: Long = exit.details.tip_height ?: ExitFixtures.tip, needsFunds: Boolean = exit == nofunds) =
        ExitStages.of(exit, tip, needsFunds, seen, stalled = exit == stale)
    val canceled = exit("Canceled", ExitDetails(kind = "canceled", tip_height = tip + 1),
        history = listOf(processing(tip + 1, tx(0, "confirmed", "fee1", tip + 1), tx(1, "awaiting-cpfp-broadcast"), tx(2, "verify-inputs"), tx(3, "verify-inputs"))))

    /** Your exit with two others from its round: its third transaction waits for the next block, its first three are shared. */
    val sharing = exit("Processing", processing(tip + 2, tx(0, "confirmed", "fee1", tip + 1), tx(1, "confirmed", "fee2", tip + 2), tx(2, "awaiting-confirmation", "fee3"),
        tx(3, "verify-inputs")), fees = mapOf("fee1" to 1_215, "fee2" to 1_215, "fee3" to 1_215), children = (0..2).associate { it to ("fee${it + 1}" to "wallet") })
    /** A 4,096-leaf round and an arkoor hop: 9 transactions, six mined, the seventh waiting for the next block. */
    val deepHero: ExitModel = run {
        val names = listOf("root", "n5", "n4", "n3", "n2", "n1", "leaf", "ckpt", "arkoor").map(::txid)
        val statuses = names.mapIndexed { i, id -> when {
            i < 6 -> ExitDetails.Transaction(id, ExitDetails.Status("confirmed", "fee$i", ExitDetails.Block(tip - 5 + i), "wallet"))
            i == 6 -> ExitDetails.Transaction(id, ExitDetails.Status("awaiting-confirmation", "fee$i", null, "wallet"))
            else -> ExitDetails.Transaction(id, ExitDetails.Status("verify-inputs", null, null, null))
        } }
        ExitModel(vtxos[0].id, 31_000, "Processing", processing(tip, *statuses.toTypedArray()), listOf("processing"), emptyList(),
            names.mapIndexed { i, id -> ExitPackage(id, 1_000, if (i < 6) 5 else 2, 1, if (i <= 6) "fee$i" else null, 608, if (i <= 6) "wallet" else null) },
            (0..6).associate { "fee$it" to 1_215L })
    }

    /** A 60-transaction path (a deep round and arkoor hops): 25 mined, the 26th waiting for the next block. */
    val long: ExitModel get() = long(25)
    /** The same path with [mined] in blocks and the next waiting. */
    fun long(mined: Int): ExitModel {
        val names = List(60) { txid("long$it") }
        val statuses = names.mapIndexed { i, id -> when {
            i < mined -> ExitDetails.Transaction(id, ExitDetails.Status("confirmed", "lfee$i", ExitDetails.Block(tip + i), "wallet"))
            i == mined -> ExitDetails.Transaction(id, ExitDetails.Status("awaiting-confirmation", "lfee$i", null, "wallet"))
            else -> ExitDetails.Transaction(id, ExitDetails.Status("verify-inputs", null, null, null))
        } }
        return ExitModel(vtxos[0].id, 31_000, "Processing", processing(tip + mined, *statuses.toTypedArray()), listOf("processing"), emptyList(),
            names.mapIndexed { i, id -> ExitPackage(id, 1_000, if (i < 40) 5 else 2, 1, if (i <= mined) "lfee$i" else null, 608, if (i <= mined) "wallet" else null) },
            (0..mined).associate { "lfee$it" to 1_215L })
    }
    /** Another exit sharing all of the long path but its coin, a block behind. */
    val longTwin: ExitModel get() = long(24).let { it.copy(id = vtxos[2].id, amount = 21_000, packages = it.packages.dropLast(1) + ExitPackage(txid("longTwinCoin"))) }
    /** Another exit from the long path's round, leaving it after its 22nd transaction. */
    val longSibling: ExitModel get() = long(25).let { it.copy(id = vtxos[1].id, amount = 24_500, packages = it.packages.take(22) + List(38) { i -> ExitPackage(txid("longB$i")) }) }

    /** The list: exits in every group, three of them from one round sharing its root. */
    val list: List<ExitModel> = run {
        fun from(id: String, amount: Long, base: ExitModel, path: List<String>) = base.copy(id = vtxos.first { it.id.startsWith(id) }.id, amount = amount,
            packages = path.map { ExitPackage(txid(it)) })
        listOf(from("c04", 15_000, claimable, listOf("r2", "a", "b", "c04")),
            from("c07", 8_400, wait, listOf("board")),
            from("c01", 31_000, confirm, listOf("root", "branch2", "branch1", "leaf")),
            from("c02", 24_500, confirm, listOf("root", "branch2", "branch1", "leaf43")),
            from("c03", 21_000, mempool, listOf("root", "branch2x", "branch1x", "leaf58", "ckpt", "arkoor")),
            from("c05", 12_000, nofunds, listOf("r3", "d", "e", "c05")),
            from("c06", 9_800, claimed, listOf("r4", "f", "g", "c06")),
            from("c11", 3_342, canceled, listOf("r8", "h", "i", "c11")))
    }
}

/** Two exits from one round whose paths run 8 transactions (four tree hops, then checkpoint and arkoor hops). */
internal val ExitFixtures.deep: List<ExitModel> get() = listOf(
    confirm.copy(id = vtxos[0].id, packages = listOf("root", "b1", "b2", "b3", "leaf", "ckpt", "ark1", "ark2").map { ExitPackage(it.padEnd(64, 'd')) }),
    confirm.copy(id = vtxos[1].id, amount = 24_500, packages = listOf("root", "b1", "c2", "c3", "leaf2", "ckpt2", "ark3", "ark4").map { ExitPackage(it.padEnd(64, 'd')) }))

/** What Settings gives an exit page: the pose, the theme's palette and the toasts. */
@Composable
internal fun ExitPreviewHost(content: @Composable () -> Unit) {
    OnboardingFrame(null, splitAtWindowCentre = true) {
        CompositionLocalProvider(LocalSettingsToasts provides remember { HomeToasts() }) { content() }
    }
}
