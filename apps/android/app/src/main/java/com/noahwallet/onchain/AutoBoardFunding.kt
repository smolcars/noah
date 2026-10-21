package com.noahwallet.onchain

import io.github.niteshbalusu11.bark.*
import kotlinx.serialization.json.*

/** Persist before presenting a review: dismissal, expiry and process death consume the same funding. */
class AutoBoardFunding(private val read: () -> String?, private val write: (String) -> Unit) {
    data class Snapshot(val walletID: String, val outpoints: Set<String>)
    companion object {
        const val key = "native-auto-board-funding-v1"
        fun confirmed(walletID: String, utxos: List<OnchainUtxo>, transactions: List<WalletTransaction>): Snapshot {
            // A board/send's change is not a new deposit. Unknown transactions are not eligible.
            val incoming = transactions.filter { it.balanceChangeSats > 0 }.map { it.txid }.toSet()
            return Snapshot(walletID, utxos.filterIsInstance<OnchainUtxo.Local>()
                .filter { it.confirmationHeight != null && it.outpoint.txid in incoming }
                .map { "${it.outpoint.txid}:${it.outpoint.vout}" }.toSet())
        }
    }
    private fun load(): Map<String, Set<String>> = read()?.let { raw ->
        Json.parseToJsonElement(raw).jsonObject.mapValues { (_, value) ->
            value.jsonArray.map { require(it.jsonPrimitive.isString); it.jsonPrimitive.content }.toSet()
        }
    }.orEmpty()
    fun hasUnhandled(funding: Snapshot): Boolean = (funding.outpoints - load()[funding.walletID].orEmpty()).isNotEmpty()
    fun markHandled(funding: Snapshot): Boolean {
        val saved = load()
        val previous = saved[funding.walletID].orEmpty()
        if ((funding.outpoints - previous).isEmpty()) return false
        write(JsonObject((saved + (funding.walletID to (previous + funding.outpoints))).mapValues { (_, ids) ->
            JsonArray(ids.sorted().map(::JsonPrimitive))
        }).toString())
        return true
    }
}
