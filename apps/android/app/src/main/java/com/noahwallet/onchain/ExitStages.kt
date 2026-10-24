package com.noahwallet.onchain

/**
 * One exit transaction as Details and the picture read it: bark's package (its raw parent and CPFP child) reduced to
 * the parent's weight, how many outputs it has, which output of its own parent it spends, and its child.
 */
data class ExitPackage(val txid: String, val weight: Long? = null, val outputs: Int = 0, val spends: Long? = null,
    val childTxid: String? = null, val childWeight: Long? = null, val childOrigin: String? = null)

/**
 * What the exit page shows for one exit (docs/native-rewrite/design/exit-v1-notes.md, States): the phase bark's state
 * maps to, what needs attention, and the numbers its words use. The picture moves only when this changes.
 */
data class ExitStage(
    val phase: Phase, val attention: Attention? = null, val total: Int = 0, val inBlocks: Int = 0,
    /** The transaction being sent, 0-based; [total] once all are in blocks. */
    val current: Int = 0,
    /** bark's status of [current]: verify-inputs, awaiting-input-confirmation, awaiting-cpfp-broadcast or awaiting-confirmation. */
    val currentKind: String? = null,
    /** The block of the last transaction mined so far. */
    val lastBlock: Long? = null,
    /** Blocks [current] has waited in the mempool, by bark's own synced tip. */
    val waited: Long = 0,
    /** [current]'s fee child's fee (this wallet paid it) and its package rate, sat/vB, once known. */
    val fee: Long? = null, val rate: Long? = null,
    val lockedFrom: Long? = null, val claimableHeight: Long? = null, val unlockedAt: Long? = null,
    val claimTxid: String? = null, val claimedBlock: Long? = null,
    /** Fees this wallet paid for the exit's transactions (adopted ones cost nothing). */
    val feesPaid: Long = 0,
    /** bark last checked this exit below the chain's tip: Noah hasn't progressed it since. */
    val stale: Boolean = false,
) {
    enum class Phase { lift, publish, mempool, bump, confirm, onchain, wait, claimable, claiming, claimed, canceled, spent, swept }
    enum class Attention { needsFunds, feesRose, stuck, shared }
    val live get() = phase !in setOf(Phase.claimed, Phase.canceled, Phase.spent, Phase.swept)
    /** Blocks left in the timelock at [tip]. */
    fun left(tip: Long) = claimableHeight?.let { (it - tip).coerceAtLeast(0) }
}

internal object ExitStages {
    /** Statuses of a transaction that's not yet in a block, as ExitDetails names them. */
    private val waitingKinds = setOf("verify-inputs", "awaiting-input-confirmation")

    /**
     * [tip] is the wallet's chain tip; [needsFunds] is [ExitFunding]'s inference for the parked transaction. Fees rising
     * and a transaction stuck in the mempool are read from bark's history, so they survive closing the page.
     */
    fun of(exit: ExitModel, tip: Long, needsFunds: Boolean = false): ExitStage {
        val details = exit.details
        val txs = details.transactions.orEmpty()
        // Later states don't list transactions: the last state that did, or bark's packages, say how many there are.
        val known = (exit.historyDetails + details).lastOrNull { !it.transactions.isNullOrEmpty() }?.transactions.orEmpty()
        val total = listOf(txs.size, exit.packages.size, known.size).firstOrNull { it > 0 } ?: 0
        val feesPaid = exit.packages.filter { it.childOrigin == "wallet" }.sumOf { exit.childFees[it.childTxid] ?: 0 }
        val synced = details.tip_height
        val stale = synced != null && synced < tip
        val base = ExitStage(ExitStage.Phase.lift, total = total, feesPaid = feesPaid, stale = stale)
        return when (exit.state) {
            "Start" -> base
            "Processing" -> processing(exit, base, txs)
            "AwaitingDelta" -> base.copy(phase = ExitStage.Phase.wait, inBlocks = total, current = total,
                lastBlock = details.confirmed_block?.height, lockedFrom = details.confirmed_block?.height, claimableHeight = details.claimable_height)
            "Claimable" -> base.copy(phase = ExitStage.Phase.claimable, inBlocks = total, current = total,
                unlockedAt = details.claimable_since?.height, claimableHeight = details.claimable_since?.height)
            "ClaimInProgress" -> base.copy(phase = ExitStage.Phase.claiming, inBlocks = total, current = total,
                unlockedAt = details.claimable_since?.height, claimTxid = details.claim_txid)
            "Claimed" -> base.copy(phase = ExitStage.Phase.claimed, inBlocks = total, current = total, claimTxid = details.txid,
                claimedBlock = details.block?.height, stale = false)
            "Canceled" -> base.copy(phase = ExitStage.Phase.canceled, inBlocks = known.count { it.status.kind == "confirmed" }, stale = false)
            "VtxoAlreadySpent" -> base.copy(phase = ExitStage.Phase.spent, stale = false)
            "VtxoSwept" -> base.copy(phase = ExitStage.Phase.swept, stale = false)
            else -> base
        }.let { if (needsFunds && it.currentKind == "awaiting-cpfp-broadcast") it.copy(attention = ExitStage.Attention.needsFunds) else it }
    }

    private fun processing(exit: ExitModel, base: ExitStage, txs: List<ExitDetails.Transaction>): ExitStage {
        val inBlocks = txs.count { it.status.kind == "confirmed" }
        val current = txs.indexOfFirst { it.status.kind != "confirmed" }.let { if (it < 0) txs.size else it }
        val lastBlock = txs.mapNotNull { it.status.block?.height }.maxOrNull()
        val tx = txs.getOrNull(current)
        val kind = tx?.status?.kind
        val stage = base.copy(inBlocks = inBlocks, current = current, currentKind = kind, lastBlock = lastBlock)
        return when {
            tx == null -> stage.copy(phase = ExitStage.Phase.onchain)
            kind in waitingKinds -> stage.copy(phase = if (inBlocks == 0) ExitStage.Phase.publish else ExitStage.Phase.confirm)
            kind == "awaiting-cpfp-broadcast" -> stage.copy(phase = ExitStage.Phase.mempool)
            else -> {
                val mine = tx.status.origin == null || tx.status.origin == "wallet"
                // Every state bark recorded while this transaction waited in the mempool, oldest first.
                val seen = (exit.historyDetails + exit.details).mapNotNull { state ->
                    state.transactions?.find { it.txid == tx.txid }?.takeIf { it.status.kind == "awaiting-confirmation" }?.let { state.tip_height to it.status.child_txid }
                }
                val sentAt = seen.mapNotNull { it.first }.minOrNull()
                val waited = if (sentAt != null && exit.details.tip_height != null) (exit.details.tip_height - sentAt).coerceAtLeast(0) else 0
                val children = seen.mapNotNull { it.second }.distinct()
                val fee = tx.status.child_txid?.let { exit.childFees[it] }
                val pkg = exit.packages.find { it.txid == tx.txid }
                val weight = pkg?.let { p -> p.weight?.let { w -> p.childWeight?.let { w + it } } }
                val rate = if (fee != null && weight != null && weight > 0) Math.round(fee * 4.0 / weight) else null
                stage.copy(phase = ExitStage.Phase.bump, waited = waited, fee = if (mine) fee else null, rate = if (mine) rate else null,
                    attention = when {
                        !mine -> ExitStage.Attention.shared
                        children.size > 1 -> ExitStage.Attention.feesRose
                        waited >= 1 -> ExitStage.Attention.stuck
                        else -> null
                    })
            }
        }
    }
}

/** The exit page's bottom actions for a stage (prototype `pFoot`); Cancel exit only while bark allows it. */
internal object ExitActions {
    enum class Action { done, claim, addFunds, cancel, check }
    fun of(stage: ExitStage, cancelable: Boolean): List<Action> = when {
        !stage.live -> listOf(Action.done)
        stage.phase == ExitStage.Phase.claimable -> listOf(Action.claim)
        stage.phase == ExitStage.Phase.claiming -> emptyList()
        stage.attention == ExitStage.Attention.needsFunds -> listOfNotNull(Action.cancel.takeIf { cancelable }, Action.addFunds)
        else -> listOfNotNull(Action.cancel.takeIf { cancelable }, Action.check)
    }
}

/**
 * "Needs on-chain funds" is inferred (exit.md, Decision 3): bark 0.8 leaves a transaction it can't pay for in
 * AwaitingCpfpBroadcast and only logs a warning. It shows once the transaction has waited there past one progress cycle
 * (bark's daemon runs every minute) while the confirmed on-chain balance is below that package's fee, and clears as soon
 * as the balance covers it. Never from error text.
 */
internal class ExitFunding(private val cycle: Long = 60_000, private val now: () -> Long = { android.os.SystemClock.elapsedRealtime() }) {
    private val parked = mutableMapOf<String, Long>()
    private val checked = mutableSetOf<String>()

    /**
     * Notes each parked transaction (exit and txid) when first seen, and forgets ones no longer parked. A cycle has passed
     * once bark progressed the exit since it parked (its synced tip moved), a minute went by, or "Check the chain" ran.
     */
    @Synchronized
    fun needsFunds(exits: List<ExitModel>, confirmed: Long): Set<String> {
        val current = mutableSetOf<String>()
        val result = mutableSetOf<String>()
        for (exit in exits) {
            val tx = exit.details.transactions?.firstOrNull { it.status.kind != "confirmed" } ?: continue
            if (exit.state != "Processing" || tx.status.kind != "awaiting-cpfp-broadcast") continue
            val key = "${exit.id}/${tx.txid}"
            current += key
            val since = parked.getOrPut(key) { now() }
            val parkedAt = (exit.historyDetails + exit.details).firstOrNull { state ->
                state.transactions?.find { it.txid == tx.txid }?.status?.kind == "awaiting-cpfp-broadcast"
            }?.tip_height
            val progressed = parkedAt != null && (exit.details.tip_height ?: parkedAt) > parkedAt
            val cycled = progressed || now() - since >= cycle || key in checked
            val fee = exit.nextPackageFee
            if (cycled && fee != null && confirmed < fee) result += exit.id
        }
        parked.keys.retainAll(current); checked.retainAll(current)
        return result
    }

    /** "Check the chain" just ran a full progress: whatever is still parked has had its cycle. */
    @Synchronized
    fun checkedChain() { checked += parked.keys }

    companion object { val shared = ExitFunding() }
}

/** Decision 4: a VTXO whose own exit costs at least its amount says "Fees more than it's worth" and stays out of "All". */
internal object ExitCosts {
    /** A VTXO's own estimate: bark's for it alone, which counts each of its transactions once and skips any on-chain. */
    data class Cost(val broadcast: Long, val claim: Long, val transactions: Long) { val total get() = broadcast + claim }

    fun uneconomic(vtxo: VtxoModel, cost: Cost?) = vtxo.eligible && cost != null && cost.total >= vtxo.amount

    /** "All": every VTXO that can exit and is worth exiting. An unknown cost isn't evidence against it. */
    fun all(vtxos: List<VtxoModel>, costs: Map<String, Cost?>) = vtxos.filter { it.eligible && !uneconomic(it, costs[it.id]) }

    /** What "All" leaves out: too small to exit (today's dust rule), locked, and costing more than they're worth. */
    data class LeftOut(val dust: Long, val locked: Long, val uneconomic: Long)
    fun leftOut(vtxos: List<VtxoModel>, costs: Map<String, Cost?>) = LeftOut(
        vtxos.filter { it.state == "Spendable" && it.amount < ExitRules.dust }.sumOf { it.amount },
        vtxos.filter { it.state == "Locked" }.sumOf { it.amount },
        vtxos.filter { uneconomic(it, costs[it.id]) }.sumOf { it.amount })
}

internal object ExitList {
    /** Decision 5: Settings says "In progress" only while an exit runs, from its start until it's claimed or canceled. */
    fun live(exits: List<ExitModel>?) = exits.orEmpty().any { it.state !in ExitTimeline.terminal }

    /** Decision 6: live exits sharing a transaction, which the list draws once; empty unless two or more share one. */
    data class Shared(val exits: List<ExitModel>, val transactions: Int, val separately: Int)
    fun shared(exits: List<ExitModel>): Shared? {
        val live = exits.filter { it.state !in ExitTimeline.terminal && it.packages.isNotEmpty() }
        val counts = live.flatMap { exit -> exit.packages.map { it.txid }.distinct() }.groupingBy { it }.eachCount()
        val sharing = live.filter { exit -> exit.packages.any { (counts[it.txid] ?: 0) > 1 } }
        if (sharing.size < 2) return null
        return Shared(sharing, sharing.flatMap { exit -> exit.packages.map { it.txid } }.distinct().size, sharing.sumOf { it.packages.size })
    }

    /** The list's five-step rail (prototype `.mini`): started, sending, waiting, ready, claimed. */
    enum class Step { done, now, failed, todo }
    fun rail(stage: ExitStage): List<Step> {
        if (stage.phase in setOf(ExitStage.Phase.canceled, ExitStage.Phase.spent, ExitStage.Phase.swept)) return listOf(Step.done) + List(4) { Step.todo }
        val at = when (stage.phase) {
            ExitStage.Phase.lift, ExitStage.Phase.publish -> 0
            ExitStage.Phase.onchain, ExitStage.Phase.wait -> 2
            ExitStage.Phase.claimable -> 3
            ExitStage.Phase.claiming -> 4
            ExitStage.Phase.claimed -> 5
            else -> 1
        }
        return List(5) { i -> when { i < at -> Step.done; i > at -> Step.todo; stage.attention == ExitStage.Attention.needsFunds -> Step.failed; else -> Step.now } }
    }

    /** The shared picture's transactions: each drawn once, at its depth from the round, between the exits' coins it leads to. */
    enum class TxState { mined, sent, waiting }
    data class Placed(val txid: String, val parent: String?, val depth: Int, val x: Float, val state: TxState, val coin: Int?)
    fun layout(group: Shared): List<Placed> {
        val parents = linkedMapOf<String, String?>(); val depths = mutableMapOf<String, Int>(); val coins = mutableMapOf<String, Int>()
        val states = mutableMapOf<String, TxState>()
        group.exits.forEachIndexed { e, exit ->
            val txs = exit.details.transactions
            exit.packages.forEachIndexed { i, p ->
                parents.putIfAbsent(p.txid, exit.packages.getOrNull(i - 1)?.txid); depths[p.txid] = i
                val kind = txs?.find { it.txid == p.txid }?.status?.kind
                states[p.txid] = when {
                    txs == null && exit.state != "Start" -> TxState.mined
                    kind == "confirmed" -> TxState.mined
                    kind == "awaiting-confirmation" -> TxState.sent
                    else -> states[p.txid] ?: TxState.waiting
                }
            }
            exit.packages.lastOrNull()?.let { coins[it.txid] = e }
        }
        val x = mutableMapOf<String, Float>()
        coins.entries.forEachIndexed { i, (txid, _) -> x[txid] = if (coins.size == 1) 0f else i / (coins.size - 1f) - .5f }
        for (txid in parents.keys.sortedByDescending { depths.getValue(it) }) if (txid !in x) {
            x[txid] = parents.filterValues { it == txid }.keys.mapNotNull { x[it] }.average().toFloat().takeIf { !it.isNaN() } ?: 0f
        }
        return parents.map { (txid, parent) -> Placed(txid, parent, depths.getValue(txid), x.getValue(txid), states.getValue(txid), coins[txid]) }
    }
}

/** Where a claim goes (exit.md, Decision 8): another wallet by default, or this phone's on-chain wallet. */
internal enum class ClaimTo { other, phone }

internal object ExitClaim {
    /** The claim's destination: the typed address once it's valid for [network], or this phone's fresh address. */
    fun destination(to: ClaimTo, typed: String, phone: String?, network: String) = if (to == ClaimTo.phone) phone else address(typed, network)

    /** Hold to claim works only with a valid destination and today's review of exactly it, still fresh. */
    fun ready(destination: String?, quote: OnchainQuote?, now: Long = System.currentTimeMillis()) =
        destination != null && quote != null && quote.action == OnchainAction.claim && quote.destination == destination && quote.current(now)

    /**
     * "Another wallet" (Decision 8): the address to claim to, or null. Only an on-chain Bitcoin address for this network,
     * checked by today's claim parser; a scanned `bitcoin:` URI gives its address through Send's parser.
     */
    fun address(input: String, network: String): String? {
        val trimmed = input.trim()
        val address = if (trimmed.startsWith("bitcoin:", true))
            com.noahwallet.send.SendDestination.parse(trimmed, network).bip321?.get("onchainAddress") ?: return null else trimmed
        return address.takeIf { runCatching { validateClaimDestination(it, network) }.isSuccess }
    }
}
