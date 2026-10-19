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
}
