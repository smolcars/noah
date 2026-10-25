package com.noahwallet.onchain

import kotlinx.serialization.Serializable

object ExitRules {
    const val dust = 330L
    fun eligible(state: String, amount: Long) = state == "Spendable" && amount >= dust
    fun ready(initialized: Boolean, loaded: Boolean, suspended: Boolean, background: Boolean) =
        initialized && loaded && !suspended && !background
    /** Not ready only for a moment: a sync or background job holds the wallet, or it's still opening. Suspended or not set up isn't a moment. */
    fun settling(initialized: Boolean, loaded: Boolean, suspended: Boolean, background: Boolean) =
        initialized && !suspended && (background || !loaded)
    fun current(reviewedAt: Long, now: Long = System.currentTimeMillis()) = now >= reviewedAt && now - reviewedAt < 30_000
    fun received(amount: Long, claimFee: Long) = (amount - claimFee).coerceAtLeast(0)
    fun warning(amount: Long, claimFee: Long, totalFee: Long, claimOnly: Boolean): String? = when {
        claimFee >= amount -> "The estimated claim fee consumes the entire recovered value. A claim may not be possible."
        !claimOnly && totalFee >= amount -> "Estimated total fees meet or exceed the value being recovered."
        else -> null
    }
    fun cancelable(state: String, finalTransactionKind: String?) = state == "Start" ||
        (state == "Processing" && (finalTransactionKind == null || finalTransactionKind in
            listOf("verify-inputs", "awaiting-input-confirmation", "awaiting-cpfp-broadcast")))
}

object BoardingRules {
    const val reserve = 5_000L
    fun threshold(minimum: Long) = maxOf(minimum, 20_000)
    /** RN's 208-vbyte estimate; SDK rates are sats/kwu. */
    fun onchainFee(satsPerKwu: Long) = (satsPerKwu * 208 + 249) / 250
    fun autoAmount(balance: Long, minimum: Long, onchainFee: Long, arkFee: Long): Long? {
        if (balance < onchainFee + reserve) return null
        val gross = balance - onchainFee - reserve
        return gross.takeIf { it >= arkFee && it - arkFee >= threshold(minimum) }
    }
}

@Serializable
data class ExitReviewInputs(
    val walletId: String? = null, val vtxoIds: List<String>, val amountSat: Long, val scope: String,
    val destinationAddress: String? = null, val revision: String,
) {
    fun matches(other: ExitReviewInputs) = copy(vtxoIds = vtxoIds.sorted()) == other.copy(vtxoIds = other.vtxoIds.sorted())
}

@Serializable
data class ExitDetails(
    val kind: String? = null, val tip_height: Long? = null, val transactions: List<Transaction>? = null,
    val confirmed_block: Block? = null, val claimable_height: Long? = null, val claimable_since: Block? = null,
    val last_scanned_block: Block? = null, val claim_txid: String? = null, val block: Block? = null, val txid: String? = null,
) {
    @Serializable data class Block(val height: Long, val hash: String = "")
    @Serializable data class Status(val kind: String, val child_txid: String? = null, val block: Block? = null, val origin: String? = null)
    @Serializable data class Transaction(val txid: String, val status: Status)
    val height get() = claimable_height ?: claimable_since?.height ?: block?.height ?: tip_height
}

data class ExitTimelineItem(
    val state: String, val count: Int, val startHeight: Long?, val endHeight: Long?,
    val details: ExitDetails?, val isCurrent: Boolean,
) {
    val label get() = ExitTimeline.labels[state] ?: state
    val description get() = when (state) {
        "Start" -> "Exit tracking was registered for this VTXO."
        "Canceled" -> "Exit processing was canceled before completion."
        "Processing" -> "Exit transactions were prepared and monitored."
        "AwaitingDelta" -> details?.claimable_height?.let { "Exit transaction confirmed; funds become claimable at block $it." }
            ?: "Exit transaction confirmed; waiting for the timelock."
        "Claimable" -> "Funds are sweepable to an on-chain address."
        "ClaimInProgress" -> "Final claim transaction was broadcast and is waiting for confirmation."
        "Claimed" -> "Funds were recovered on-chain."
        "VtxoAlreadySpent" -> "Exit tracking found that this VTXO was already spent."
        "VtxoSwept" -> "An ancestor exit input was swept."
        else -> state
    }
}

object ExitTimeline {
    val labels = linkedMapOf("Start" to "Started", "Processing" to "Processing", "AwaitingDelta" to "Waiting",
        "Claimable" to "Claimable", "ClaimInProgress" to "Claiming", "Claimed" to "Claimed",
        "VtxoAlreadySpent" to "Already spent", "Canceled" to "Canceled", "VtxoSwept" to "Swept")
    val terminal = setOf("Canceled", "Claimed", "VtxoAlreadySpent", "VtxoSwept")
    fun build(history: List<String>, historyDetails: List<ExitDetails>, state: String, current: ExitDetails?): List<ExitTimelineItem> {
        val states = history.ifEmpty { listOf(state) }.toMutableList()
        if (states.last() != state) states.add(state)
        val details = historyDetails.map<ExitDetails, ExitDetails?> { it }.toMutableList()
        if (details.size < states.size) details.add(current)
        else if (details.size == states.size) details[details.lastIndex] = current ?: details.last()
        val items = mutableListOf<ExitTimelineItem>()
        states.forEachIndexed { index, value ->
            val detail = details.getOrNull(index)
            val height = detail?.height
            val previous = items.lastOrNull()
            val isCurrent = index == states.lastIndex
            if (previous?.state == value) items[items.lastIndex] = previous.copy(count = previous.count + 1,
                endHeight = height ?: previous.endHeight, details = detail, isCurrent = isCurrent)
            else items.add(ExitTimelineItem(value, 1, height, height, detail, isCurrent))
        }
        return items
    }
}
