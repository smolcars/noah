package com.noahwallet.services.wallet

import io.github.niteshbalusu11.bark.TransactionStatus
import kotlinx.serialization.Serializable

/** Evidence from the chain source; absence is never evidence of non-delivery. */
@Serializable
data class ChainStatus(val kind: Kind, val height: Long? = null) {
    @Serializable enum class Kind { notFound, inMempool, confirmed }
    val text get() = when (kind) {
        Kind.notFound -> "Not yet seen by the network. This chain source does not currently know the transaction. Do not submit it again."
        Kind.inMempool -> "In mempool. Waiting for confirmation."
        Kind.confirmed -> "Confirmed at block $height."
    }
    companion object {
        const val errorText = "Couldn't check network status. Showing the last known network status. Try again."
        fun from(value: TransactionStatus): ChainStatus = when (value) {
            TransactionStatus.NotFound -> ChainStatus(Kind.notFound)
            TransactionStatus.InMempool -> ChainStatus(Kind.inMempool)
            is TransactionStatus.Confirmed -> ChainStatus(Kind.confirmed, value.height.toLong())
        }
    }
}
