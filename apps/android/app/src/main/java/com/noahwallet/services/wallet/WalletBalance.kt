package com.noahwallet.services.wallet

import kotlinx.serialization.Serializable

/** Bark accounting: every VTXO belongs to exactly one bucket. */
@Serializable
data class WalletBalance(val ark: Ark, val onchain: Onchain) {
    @Serializable
    data class Ark(
        val spendable: ULong,
        val needsRefresh: ULong,
        val pendingLightningSend: ULong,
        val claimableLightningReceive: ULong,
        val pendingInRound: ULong,
        val pendingExit: ULong,
        val pendingBoard: ULong,
        val pendingArkoorSend: ULong,
        val pendingOffboard: ULong,
        /** Bark's `pending()` as the SDK reports it; bark's formula only when absent (fixtures). */
        val pending: ULong = pendingInRound + pendingBoard + pendingArkoorSend +
            pendingLightningSend + claimableLightningReceive + pendingOffboard + pendingExit,
        /** Bark's `total()`: spendable + needs refresh + pending. */
        val total: ULong = spendable + needsRefresh + pending,
    )

    @Serializable
    data class Onchain(val confirmed: ULong, val pending: ULong, val total: ULong)

    val offchainSats: ULong get() = ark.total
    val onchainSats: ULong get() = onchain.total
    val totalSats: ULong get() = offchainSats + onchainSats
    val pendingSats: ULong get() = onchain.total - onchain.confirmed + ark.pending

    fun pendingRoundMessage(amount: String): String? = if (ark.pendingInRound == 0uL) null else
        "$amount pending in a round. These funds become available after the round confirms."

    @Serializable data class Detail(val label: String, val sats: ULong)
    val details get() = listOf(
        Detail("Onchain", onchain.total), Detail("Confirmed", onchain.confirmed),
        Detail("Pending", onchain.pending), Detail("Immature", onchain.total - onchain.confirmed - onchain.pending),
        Detail("Offchain", ark.total), Detail("Spendable", ark.spendable), Detail("Needs Refresh", ark.needsRefresh),
        Detail("Pending Send", ark.pendingLightningSend), Detail("Claimable Lightning Receive", ark.claimableLightningReceive),
        Detail("Pending In Round", ark.pendingInRound), Detail("Pending Exit", ark.pendingExit),
        Detail("Pending Board", ark.pendingBoard), Detail("Pending Arkoor Send", ark.pendingArkoorSend),
        Detail("Pending Offboard", ark.pendingOffboard),
    )
    companion object {
        fun display(sats: ULong, hidden: Boolean, unit: com.noahwallet.formatting.BitcoinAmountUnit, locale: java.util.Locale): String =
            if (hidden) "••••" else com.noahwallet.formatting.BitcoinAmount.format(sats.toLong(), unit, locale)
    }
}
