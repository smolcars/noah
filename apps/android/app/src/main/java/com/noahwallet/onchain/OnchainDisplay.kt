package com.noahwallet.onchain

/** Read-only presentation; never used for exit eligibility or submission. */
internal object OnchainDisplay {
    fun remaining(current: Long?, target: Long?): String = when {
        current == null || target == null -> "Unknown"
        target <= current -> "Now"
        target - current == 1L -> "{count} block"
        else -> "{count} blocks"
    }
    fun block(block: ExitDetails.Block) = "${block.height} (${block.hash})"
    fun explorerURL(anchor: String, variant: String): String? {
        val txid = anchor.substringBefore(':')
        if (variant == "regtest" || txid.isEmpty()) return null
        return "https://mempool.space/" + (if (variant == "signet") "signet/" else "") + "tx/$txid"
    }
}

internal val ExitDetails.displayClaimableHeight get() = claimable_height ?: claimable_since?.height
