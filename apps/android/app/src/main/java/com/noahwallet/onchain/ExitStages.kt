package com.noahwallet.onchain

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.json.Json

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
    /** Blocks [current] has waited in the mempool: the wallet's tip less the tip Noah first saw it there at ([ExitSeen]). */
    val waited: Long = 0,
    /** [current]'s fee child's fee (this wallet paid it) and its package rate, sat/vB, once known. */
    val fee: Long? = null, val rate: Long? = null,
    val lockedFrom: Long? = null, val claimableHeight: Long? = null, val unlockedAt: Long? = null,
    val claimTxid: String? = null, val claimedBlock: Long? = null,
    /** Fees this wallet paid for the exit's whole path ([ExitFees.paid]); another wallet's children cost nothing. */
    val feesPaid: Long = 0,
    /** Noah has evidence nothing progresses exits, and bark last recorded this one, while sending it, below the chain's tip. */
    val stale: Boolean = false,
) {
    enum class Phase { lift, publish, mempool, bump, confirm, onchain, wait, claimable, claiming, claimed, canceled, spent, swept }
    enum class Attention { needsFunds, feesRose, stuck, shared }
    val live get() = phase !in setOf(Phase.claimed, Phase.canceled, Phase.spent, Phase.swept)
    /** Blocks left in the timelock at [tip]. */
    fun left(tip: Long) = claimableHeight?.let { (it - tip).coerceAtLeast(0) }
}

internal object ExitStages {
    /**
     * Where [txid]'s current status began in bark's history (append-only: each change pushes the state before it), within
     * this run of the exit (bark restarts a canceled one with empty history, so a run is told apart by the tip it began at).
     * A transaction that leaves a status and comes back starts a new episode, so evidence from the last one doesn't count.
     */
    fun episode(exit: ExitModel, txid: String) = "${(exit.historyDetails + exit.details).first().tip_height}:${episodeStart(exit, txid)}"

    /** The index in bark's history (and current state) where [txid]'s current status began. */
    fun episodeStart(exit: ExitModel, txid: String): Int {
        val states = exit.historyDetails + exit.details
        val kind = exit.details.transactions?.find { it.txid == txid }?.status?.kind
        var start = states.lastIndex
        while (start > 0 && states[start - 1].transactions?.find { it.txid == txid }?.status?.kind == kind) start--
        return start
    }

    /** Statuses of a transaction that's not yet in a block, as ExitDetails names them. */
    private val waitingKinds = setOf("verify-inputs", "awaiting-input-confirmation")

    /**
     * [tip] is the wallet's chain tip; [needsFunds] is [ExitFunding]'s inference for the parked transaction; [seen] is
     * [ExitSeen]'s first-seen tips; [stalled] is Noah's evidence that nothing progresses exits (its last Check the chain
     * failed, or the wallet is open without bark's daemon). Fees rising is read from bark's history.
     */
    fun of(exit: ExitModel, tip: Long, needsFunds: Boolean = false, seen: Map<String, Long> = emptyMap(), stalled: Boolean = false): ExitStage {
        val details = exit.details
        val txs = details.transactions.orEmpty()
        // Later states don't list transactions: the last state that did, or bark's packages, say how many there are.
        val known = (exit.historyDetails + details).lastOrNull { !it.transactions.isNullOrEmpty() }?.transactions.orEmpty()
        val total = listOf(txs.size, exit.packages.size, known.size).firstOrNull { it > 0 } ?: 0
        val feesPaid = ExitFees.paid(exit, emptyList()).first
        // bark keeps a state's tip while nothing changes, so its height can't say whether progress runs; while the daemon
        // runs it keeps exits current. Only with Noah's evidence that it doesn't is an exit bark last recorded below the tip behind.
        val synced = details.tip_height
        val stale = stalled && synced != null && exit.state in setOf("Start", "Processing") && tip > synced
        val base = ExitStage(ExitStage.Phase.lift, total = total, feesPaid = feesPaid, stale = stale)
        // Once claimable, the timelock's start and end are what bark recorded while it waited.
        val locked = (exit.historyDetails + details).lastOrNull { it.confirmed_block != null }
        return when (exit.state) {
            "Start" -> base
            "Processing" -> processing(exit, base, txs, tip, seen)
            "AwaitingDelta" -> base.copy(phase = ExitStage.Phase.wait, inBlocks = total, current = total,
                lastBlock = details.confirmed_block?.height, lockedFrom = details.confirmed_block?.height, claimableHeight = details.claimable_height)
            "Claimable" -> base.copy(phase = ExitStage.Phase.claimable, inBlocks = total, current = total, lockedFrom = locked?.confirmed_block?.height,
                unlockedAt = details.claimable_since?.height, claimableHeight = locked?.claimable_height ?: details.claimable_since?.height)
            "ClaimInProgress" -> base.copy(phase = ExitStage.Phase.claiming, inBlocks = total, current = total, lockedFrom = locked?.confirmed_block?.height,
                unlockedAt = details.claimable_since?.height, claimableHeight = locked?.claimable_height, claimTxid = details.claim_txid)
            "Claimed" -> base.copy(phase = ExitStage.Phase.claimed, inBlocks = total, current = total, lockedFrom = locked?.confirmed_block?.height,
                claimableHeight = locked?.claimable_height, claimTxid = details.txid, claimedBlock = details.block?.height, stale = false)
            "Canceled" -> base.copy(phase = ExitStage.Phase.canceled, inBlocks = known.count { it.status.kind == "confirmed" }, stale = false)
            "VtxoAlreadySpent" -> base.copy(phase = ExitStage.Phase.spent, stale = false)
            "VtxoSwept" -> base.copy(phase = ExitStage.Phase.swept, stale = false)
            else -> base
        }.let { if (needsFunds && it.currentKind == "awaiting-cpfp-broadcast") it.copy(attention = ExitStage.Attention.needsFunds) else it }
    }

    private fun processing(exit: ExitModel, base: ExitStage, txs: List<ExitDetails.Transaction>, tip: Long, seen: Map<String, Long>): ExitStage {
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
                // Every child bark gave it in this stay in the mempool, oldest first: a replaced one means fees rose. An earlier
                // stay (before an eviction or reorg) doesn't count.
                val children = (exit.historyDetails + exit.details).drop(episodeStart(exit, tx.txid)).mapNotNull { state ->
                    state.transactions?.find { it.txid == tx.txid }?.status?.child_txid
                }.distinct()
                // bark's own tip stays put while nothing changes: count from the tip Noah first saw it in the mempool at.
                val waited = seen[ExitSeen.key(exit, tx)]?.let { (tip - it).coerceAtLeast(0) } ?: 0
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

/**
 * bark marks a claim only at its next progress, up to a minute after Noah broadcasts it. Until then the journal's submitted
 * claim (unless the chain source has lost it) shows the exit as claiming, so the page never offers Claim again meanwhile.
 * Once bark has seen that claim, bark's state wins: claimable again if it fell out, recovered through the journal's controls.
 */
internal object ExitClaims {
    fun submitted(attempts: List<OnchainOperationJournal.Attempt>): Map<String, String> = attempts
        .filter { it.quote.action == OnchainAction.claim && it.receipt?.chainStatus?.kind != com.noahwallet.services.wallet.ChainStatus.Kind.notFound }
        .mapNotNull { attempt -> (attempt.receipt?.txid ?: attempt.txid)?.let { txid -> attempt.quote.ids.map { it to txid } } }.flatten().toMap()
    fun apply(exit: ExitModel, stage: ExitStage, txid: String?) =
        if (stage.phase == ExitStage.Phase.claimable && txid != null && exit.historyDetails.none { it.claim_txid == txid })
            stage.copy(phase = ExitStage.Phase.claiming, claimTxid = txid) else stage
}

/**
 * The exit page's actions for a stage (prototype `pFoot`); Cancel exit only while bark allows it. Check the chain stays while
 * the exit is live, also beside a primary Claim or "Add on-chain funds" and while claiming, so a stopped daemon never strands
 * it: bark notices a claim confirming, or falling out, only when it progresses.
 */
internal object ExitActions {
    enum class Action { done, claim, addFunds, cancel, check }
    fun of(stage: ExitStage, cancelable: Boolean): List<Action> = when {
        !stage.live -> listOf(Action.done)
        stage.phase == ExitStage.Phase.claimable -> listOf(Action.claim, Action.check)
        stage.phase == ExitStage.Phase.claiming -> listOf(Action.check)
        stage.attention == ExitStage.Attention.needsFunds -> listOfNotNull(Action.cancel.takeIf { cancelable }, Action.addFunds, Action.check)
        else -> listOfNotNull(Action.cancel.takeIf { cancelable }, Action.check)
    }
}

/**
 * The wallet tip at which Noah first saw each exit transaction in its current status, kept across restarts. bark returns
 * its old state, tip and all, while no status changes, so how long a transaction has waited counts from here.
 */
internal class ExitSeen(private val read: () -> String?, private val write: (String) -> Unit) {
    /** Notes [tip] for each unconfirmed transaction's status seen for the first time, forgets the rest, and returns them all. */
    @Synchronized
    fun note(exits: List<ExitModel>, tip: Long): Map<String, Long> {
        val saved = read()?.let { runCatching { Json.decodeFromString<Map<String, Long>>(it) }.getOrNull() }.orEmpty()
        val seen = exits.filter { it.state == "Processing" }.flatMap { exit ->
            exit.details.transactions.orEmpty().filter { it.status.kind != "confirmed" }.map { key(exit, it) } }.associateWith { saved[it] ?: tip }
        if (seen != saved) write(Json.encodeToString(seen))
        return seen
    }

    /** Started or canceled: the exits' tips are from a run that's over. */
    @Synchronized
    fun forget(ids: Collection<String>) {
        val saved = read()?.let { runCatching { Json.decodeFromString<Map<String, Long>>(it) }.getOrNull() }.orEmpty()
        val kept = saved.filterKeys { key -> ids.none { key.startsWith("$it/") } }
        if (kept != saved) write(Json.encodeToString(kept))
    }

    companion object {
        private const val storageKey = "native-exit-seen-v1"
        /** A transaction's status in its exit's current episode ([ExitStages.episode]). */
        fun key(exit: ExitModel, tx: ExitDetails.Transaction) = "${exit.id}/${tx.txid}/${tx.status.kind}/${ExitStages.episode(exit, tx.txid)}"
        fun stored(settings: com.noahwallet.services.storage.SettingsStorage) = ExitSeen({ settings.string(storageKey) }, { settings.set(it, storageKey) })
    }
}

/**
 * Each claim Noah broadcast, apart from the journal (acknowledging clears that) so a finished exit still shows what it
 * received: the fee the drain actually paid, the exits it claimed and where it went. Display only: it blocks nothing.
 */
class ExitClaimRecords(private val read: () -> String?, private val write: (String) -> Unit) {
    /** [destination] is the other wallet's address; null for this phone's own wallet. */
    @kotlinx.serialization.Serializable data class Record(val txid: String, val fee: Long, val ids: List<String>, val toPhone: Boolean, val destination: String? = null)

    fun all(): List<Record> = read()?.let { runCatching { Json.decodeFromString<List<Record>>(it) }.getOrNull() }.orEmpty()
    @Synchronized fun add(record: Record) = write(Json.encodeToString(all().filter { it.txid != record.txid } + record))
    /** The claim that took exit [id]: the one bark names ([txid]) once it does, else the latest. */
    fun of(id: String, txid: String?) = all().lastOrNull { id in it.ids && (txid == null || it.txid == txid) }

    companion object {
        private const val storageKey = "native-exit-claims-v1"
        fun stored(settings: com.noahwallet.services.storage.SettingsStorage) =
            ExitClaimRecords({ settings.string(storageKey) }, { settings.set(it, storageKey); settings.sync() })
    }
}

/**
 * "Needs on-chain funds" is inferred (exit.md, Decision 3): bark 0.8 leaves a transaction it can't pay for in
 * AwaitingCpfpBroadcast and only logs a warning. It shows while the confirmed on-chain balance is below that package's
 * fee, once bark has had a chance to pay, and clears as soon as the balance covers it. Never from error text. When a
 * Check the chain reports it by code (SDK 0.7.0), that's direct evidence, with what bark needs.
 */
internal class ExitFunding(private val interval: Long, private val now: () -> Long = { android.os.SystemClock.uptimeMillis() }) {
    private class Parked(val run: Long?, val since: Long, var tried: Boolean = false)
    private val parked = mutableMapOf<String, Parked>()
    /** What bark said each exit needs, from the last Check the chain that reported confirmed funds short. */
    private var reported = emptyMap<String, Long>()
    /** The last Check the chain didn't complete: Noah's evidence that nothing progresses exits. */
    var checkFailed by mutableStateOf(false); private set

    /** [run] is the wallet's current daemon run ([com.noahwallet.services.wallet.WalletSession.daemonRun]), timed from when Noah first saw it parked. */
    @Synchronized
    fun needsFunds(exits: List<ExitModel>, confirmed: Long, run: Long?): Set<String> {
        val current = parkedIn(exits)
        parked.keys.retainAll(current.keys)
        return current.filter { (key, exit) ->
            val entry = parked[key]?.takeIf { it.tried || it.run == run } ?: Parked(run, now()).also { parked[key] = it }
            // The short balance is the proof; a completed check or one daemon interval only stops it flashing before bark has tried.
            val tried = entry.tried || (run != null && now() - entry.since >= interval)
            val fee = exit.nextPackageFee
            tried && fee != null && confirmed < fee
        }.values.map { it.id }.toSet() + exits.filter { exit -> reported[exit.id]?.let { exit.state !in ExitTimeline.terminal && confirmed < it } == true }.map { it.id }
    }

    /** A Check the chain's statuses short of confirmed funds: what bark needs for each exit ([needed]). */
    @Synchronized
    fun reported(needed: Map<String, Long>) { reported = needed }

    /** What bark last said exit [id] needs, if it said so. */
    @Synchronized
    fun needed(id: String): Long? = reported[id]

    /** A Check the chain ended: if it [completed], bark tried to pay every transaction parked in [exits], read before or after it. */
    @Synchronized
    fun checked(exits: List<ExitModel>, completed: Boolean) {
        checkFailed = !completed
        if (completed) for (key in parkedIn(exits).keys) parked.getOrPut(key) { Parked(null, now()) }.tried = true
    }

    /** Started or canceled: the exits' proof is from a run that's over. */
    @Synchronized
    fun forget(ids: Collection<String>) { parked.keys.removeAll { key -> ids.any { key.startsWith("$it/") } }; reported = reported - ids.toSet() }

    /** Noah went to the background, where bark's daemon may not run: waiting time starts over; a completed check still counts. */
    @Synchronized
    fun backgrounded() { parked.values.removeAll { !it.tried } }

    private fun parkedIn(exits: List<ExitModel>) = exits.mapNotNull { exit ->
        exit.details.transactions?.firstOrNull { it.status.kind != "confirmed" }
            ?.takeIf { exit.state == "Processing" && it.status.kind == "awaiting-cpfp-broadcast" }?.let { "${exit.id}/${it.txid}/${ExitStages.episode(exit, it.txid)}" to exit }
    }.toMap()

    companion object {
        /** bark's daemon interval as Noah configures it, with a margin for the progress itself. */
        val shared by lazy {
            ExitFunding(com.noahwallet.services.wallet.WalletConfiguration.base(com.noahwallet.BuildConfig.APP_VARIANT).daemonSyncIntervalSecs.toLong() * 1_000 + 15_000)
        }
    }
}

/** Decision 4: a VTXO whose own exit costs at least its amount says "Fees more than it's worth" and stays out of "All". */
internal object ExitCosts {
    /** A VTXO's own estimate: bark's for it alone, which counts each of its transactions once and skips any on-chain. */
    data class Cost(val broadcast: Long, val claim: Long, val transactions: Long) { val total get() = broadcast + claim }

    fun uneconomic(vtxo: VtxoModel, cost: Cost?) = vtxo.eligible && cost != null && cost.total >= vtxo.amount

    /** Why Choose VTXOs can't select a VTXO, or null: today's rules (in use, too small, not spendable) come first. */
    enum class Barred { locked, dust, state, renew, refused }
    /** [refused]: VTXOs bark refused to exit, with their reason; [excluded]: ones bark's "All" estimate leaves out. */
    fun barred(vtxo: VtxoModel, refused: Map<String, String> = emptyMap(), excluded: Set<String> = emptySet()): Barred? = when {
        vtxo.state == "Locked" -> Barred.locked
        vtxo.amount < ExitRules.dust -> Barred.dust
        !vtxo.eligible -> Barred.state
        vtxo.id in excluded -> Barred.renew
        vtxo.id in refused -> Barred.refused
        else -> null
    }

    /** "All": every VTXO that can exit and is worth exiting. An unknown cost isn't evidence against it. */
    fun all(vtxos: List<VtxoModel>, costs: Map<String, Cost?>, refused: Map<String, String> = emptyMap(), excluded: Set<String> = emptySet()) =
        vtxos.filter { barred(it, refused, excluded) == null && !uneconomic(it, costs[it.id]) }

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

    /** Decision 6: live exits sharing transactions, which the list draws once; one group per set of exits that are connected. */
    data class Shared(val exits: List<ExitModel>, val transactions: Int, val separately: Int)
    fun shared(exits: List<ExitModel>): List<Shared> {
        val live = exits.filter { it.state !in ExitTimeline.terminal && it.packages.isNotEmpty() }
        // Each exit joins every earlier group it shares a transaction with, so exits linked only through a third one meet too.
        val groups = mutableListOf<List<Int>>()
        live.forEachIndexed { i, exit ->
            val txids = exit.packages.map { it.txid }.toSet()
            val touching = groups.filter { group -> group.any { j -> live[j].packages.any { it.txid in txids } } }
            groups.removeAll(touching); groups += (touching.flatten() + i).sorted()
        }
        return groups.filter { it.size > 1 }.sortedBy { it.first() }.map { group -> group.map(live::get) }
            .map { group -> Shared(group, group.flatMap { exit -> exit.packages.map { it.txid } }.distinct().size, group.sumOf { it.packages.size }) }
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

    /**
     * The shared picture's rows in a canvas [height] dp tall: the round's block at [y0], each depth [step] above it, close
     * enough that the deepest coin and its amount ([labelRoom] above its centre) stay inside.
     */
    data class Rows(val y0: Float, val step: Float) { fun y(depth: Int) = y0 - 26 - depth * step }
    const val labelRoom = 34f
    fun rows(height: Float, deepest: Int): Rows {
        val y0 = minOf(height - 44, (height + 190) / 2)
        return Rows(y0, if (deepest <= 0) 30f else minOf(30f, (y0 - 26 - labelRoom) / deepest))
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

    /**
     * An exit's part of its claim's fee. Every input weighs the same, so a batched claim's fee splits evenly, spare sats to
     * the first of [ids] in order: the exits it claimed then add up to what arrived. An exit outside [ids] carries it all.
     */
    fun share(fee: Long, ids: List<String>, id: String): Long {
        val i = ids.sorted().indexOf(id)
        return if (i < 0) fee else fee / ids.size + if (i < fee % ids.size) 1 else 0
    }

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

/**
 * What exits cost in fee children this wallet paid ([ExitModel.childFees]: its own transactions, whichever exit bark filed
 * them under), each child once. A step several exits share is paid once, by whichever sent it first.
 */
internal object ExitFees {
    /** [exit]'s whole path, and the part of it for steps another of [exits] also has. */
    fun paid(exit: ExitModel, exits: List<ExitModel>): Pair<Long, Long> {
        val others = exits.filter { it.id != exit.id }.flatMap { other -> other.packages.map { it.txid } }.toSet()
        val children = exit.packages.filter { it.childTxid != null }.distinctBy { it.childTxid }
        val fee = { p: ExitPackage -> exit.childFees[p.childTxid] ?: 0L }
        return children.sumOf(fee) to children.filter { it.txid in others }.sumOf(fee)
    }

    /** Every exit's children, each once: what Activity's "Exit fee" rows add up to. */
    fun total(exits: List<ExitModel>): Long = exits.flatMap { it.childFees.entries }.associate { it.key to it.value }.values.sum()
}
