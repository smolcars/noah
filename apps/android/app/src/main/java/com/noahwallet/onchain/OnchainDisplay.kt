package com.noahwallet.onchain

/** Read-only presentation; never used for exit eligibility or submission. */
internal object OnchainDisplay {
    fun currentSelection(vtxos: List<VtxoModel>, ids: Set<String>) = vtxos.filter { it.id in ids }

    fun explorerURL(anchor: String, variant: String): String? {
        val txid = anchor.substringBefore(':')
        if (variant == "regtest" || txid.isEmpty()) return null
        return "https://mempool.space/" + (if (variant == "signet") "signet/" else "") + "tx/$txid"
    }
}
