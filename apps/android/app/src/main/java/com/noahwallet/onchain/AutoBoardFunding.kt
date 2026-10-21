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
    private data class State(val handled: Map<String, Set<String>>, val retryAfter: Map<String, Long> = emptyMap())
    private fun load(): State {
        val root = read()?.let { Json.parseToJsonElement(it).jsonObject } ?: return State(emptyMap())
        val handled = root["handled"]?.jsonObject ?: root // Original outpoint-only ledger.
        return State(handled.mapValues { (_, value) ->
            value.jsonArray.map { require(it.jsonPrimitive.isString); it.jsonPrimitive.content }.toSet()
        }, root["retryAfter"]?.jsonObject?.mapValues { it.value.jsonPrimitive.long }.orEmpty())
    }
    fun retryDeadline(walletID: String): Long = load().retryAfter[walletID] ?: 0
    fun hasUnhandled(funding: Snapshot): Boolean = (funding.outpoints - load().handled[funding.walletID].orEmpty()).isNotEmpty()
    fun markHandled(funding: Snapshot): Boolean = claim(funding) != null
    fun release(funding: Snapshot, retryAfter: Long) {
        val saved = load()
        save(State(saved.handled + (funding.walletID to (saved.handled[funding.walletID].orEmpty() - funding.outpoints)),
            saved.retryAfter + (funding.walletID to retryAfter))) // Release and deadline share one durable write.
    }
    private fun save(saved: State) {
        write(buildJsonObject {
            put("handled", JsonObject(saved.handled.mapValues { (_, ids) -> JsonArray(ids.sorted().map(::JsonPrimitive)) }))
            put("retryAfter", JsonObject(saved.retryAfter.mapValues { JsonPrimitive(it.value) }))
        }.toString())
    }
    fun claim(funding: Snapshot): Snapshot? {
        val saved = load()
        val previous = saved.handled[funding.walletID].orEmpty()
        val newly = funding.outpoints - previous
        if (newly.isEmpty()) return null
        save(saved.copy(handled = saved.handled + (funding.walletID to (previous + funding.outpoints))))
        return Snapshot(funding.walletID, newly)
    }
}

/** Async reads may finish after pause/navigation; only a still-active caller may evaluate or claim funding. */
class AutoBoardReview(private val store: AutoBoardFunding, private val now: () -> Long = System::currentTimeMillis) {
    private var claimed: Triple<String, AutoBoardFunding.Snapshot, AutoBoardFunding.Snapshot>? = null
    var retryAfter = 0L; private set
    fun fundingFor(quote: OnchainQuote): AutoBoardFunding.Snapshot {
        val held = claimed ?: fail(OnchainFailure.changed)
        if (held.first != quote.id) fail(OnchainFailure.changed)
        return held.third
    }
    suspend fun failedConfirm(quote: OnchainQuote, changed: Boolean, proveUnsubmitted: suspend () -> Boolean) {
        val (id, funding) = claimed ?: return
        if (id != quote.id) return
        claimed = null
        if (!proveUnsubmitted()) return
        retryAfter = if (changed) now() else now() + 30_000
        store.release(funding, retryAfter)
    }
    suspend fun prepare(allowed: () -> Boolean, funding: suspend () -> AutoBoardFunding.Snapshot?,
        quote: suspend () -> OnchainQuote?, discard: suspend (OnchainQuote) -> Unit,
        present: (OnchainQuote) -> Unit = {}): OnchainQuote? {
        if (!allowed() || now() < retryAfter) return null
        try {
            val current = funding() ?: return null
            if (!allowed() || now() < store.retryDeadline(current.walletID) || !store.hasUnhandled(current)) return null
            val prepared = quote() ?: return null
            var presented = false
            try {
                if (!allowed() || prepared.walletID != current.walletID) return null
                val newly = store.claim(current) ?: return null
                claimed = Triple(prepared.id, newly, current)
                present(prepared)
                presented = true
                return prepared
            } finally { if (!presented) discard(prepared) }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (failure: Exception) { retryAfter = now() + 30_000; throw failure }
    }
}
