package com.noahwallet.onchain

import io.github.niteshbalusu11.bark.*
import kotlinx.serialization.json.*

/** Only a foreground review that reaches presentation consumes its confirmed funding. */
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

/** Async reads may finish after pause/navigation; only a still-active caller may evaluate or claim funding. */
class AutoBoardReview(private val store: AutoBoardFunding, private val now: () -> Long = System::currentTimeMillis) {
    var retryAfter = 0L; private set
    suspend fun prepare(allowed: () -> Boolean, funding: suspend () -> AutoBoardFunding.Snapshot?,
        quote: suspend () -> OnchainQuote?, discard: suspend (OnchainQuote) -> Unit,
        present: (OnchainQuote) -> Unit = {}): OnchainQuote? {
        if (!allowed() || now() < retryAfter) return null
        try {
            val current = funding() ?: return null
            if (!allowed() || !store.hasUnhandled(current)) return null
            val prepared = quote() ?: return null
            var presented = false
            try {
                if (!allowed() || prepared.walletID != current.walletID || !store.markHandled(current)) return null
                present(prepared)
                presented = true
                return prepared
            } finally { if (!presented) discard(prepared) }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (failure: Exception) { retryAfter = now() + 30_000; throw failure }
    }
}
