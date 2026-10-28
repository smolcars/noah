package com.noahwallet.onchain

/**
 * What the exit page needs beyond bark's exit: the wallet's tip and balance, the server's delta, and its last claim: the
 * claim transaction's whole fee and every exit it claimed.
 */
internal data class ExitContext(val tip: Long, val confirmed: Long?, val delta: Long?, val claimFee: Long? = null, val claimIds: List<String> = emptyList(),
    val claimedTo: String? = null, val claimEstimate: Long? = null, val cancelable: Boolean = false,
    /** The part of the exit's network fees ([ExitStage.feesPaid]) for steps its other exits share ([ExitFees.paid]). */
    val sharedFees: Long = 0) {
    /** This exit's part of [claimFee]; see [ExitClaim.share]. */
    fun claimShare(id: String) = claimFee?.let { ExitClaim.share(it, claimIds, id) }

    /** The claim to show: Noah's record of it, kept past acknowledgement, else its journal attempt (a claim from before records). */
    fun claimed(record: ExitClaimRecords.Record?, attempt: OnchainOperationJournal.Attempt?) = when {
        record != null -> copy(claimFee = record.fee, claimIds = record.ids, claimedTo = record.destination)
        attempt != null -> copy(claimFee = attempt.quote.fee, claimIds = attempt.quote.ids,
            claimedTo = attempt.quote.destination?.takeIf { attempt.quote.destinationIsMine == false })
        else -> this
    }
}
