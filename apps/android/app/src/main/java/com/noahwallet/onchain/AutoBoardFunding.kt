package com.noahwallet.onchain

import io.github.niteshbalusu11.bark.*
import kotlinx.serialization.json.*

/** Only a foreground review that reaches presentation consumes its confirmed funding. */
class AutoBoardFunding(private val read: () -> String?, private val write: (String) -> Unit) {
    data class Snapshot(val walletID: String, val outpoints: Set<String>)
    companion object {
        const val key = "native-auto-board-funding-v1"
        const val RETRY_BACKOFF_MS = 30_000L
        /** Auto-board's prompt and a claim both update the ledger; one at a time, so neither drops the other's write. */
        private val lock = Any()
        fun stored(settings: com.noahwallet.services.storage.SettingsStorage) =
            AutoBoardFunding({ settings.string(key) }, { settings.set(it, key); settings.sync() })
        /** What claimed-to-this-phone coins hold now: those still unspent and confirmed in the wallet (spent ones drop out). */
        fun keptValue(utxos: List<OnchainUtxo>, kept: Set<String>): Long = utxos.filterIsInstance<OnchainUtxo.Local>()
            .filter { it.confirmationHeight != null && "${it.outpoint.txid}:${it.outpoint.vout}" in kept }.sumOf { it.amountSats.toLong() }
        /** What auto-board may move (exit.md, Decision 8): the confirmed balance less the coins claimed to this phone. */
        fun boardable(confirmed: Long, utxos: List<OnchainUtxo>, kept: Set<String>) = confirmed - keptValue(utxos, kept)
        fun confirmed(walletID: String, utxos: List<OnchainUtxo>, transactions: List<WalletTransaction>): Snapshot {
            // A board/send's change is not a new deposit. Unknown transactions are not eligible.
            val incoming = transactions.filter { it.balanceChangeSats > 0 }.map { it.txid }.toSet()
            return Snapshot(walletID, utxos.filterIsInstance<OnchainUtxo.Local>()
                .filter { it.confirmationHeight != null && it.outpoint.txid in incoming }
                .map { "${it.outpoint.txid}:${it.outpoint.vout}" }.toSet())
        }
    }
    /** [kept]: outpoints claimed to this phone from exits, per wallet; auto-board never moves their value. */
    private data class State(val handled: Map<String, Set<String>>, val retryAfter: Map<String, Long> = emptyMap(),
        val kept: Map<String, Set<String>> = emptyMap())
    private fun load(): State {
        val root = read()?.let { Json.parseToJsonElement(it).jsonObject } ?: return State(emptyMap())
        val handled = root["handled"]?.jsonObject ?: root // Original outpoint-only ledger.
        fun sets(node: JsonObject) = node.mapValues { (_, value) ->
            value.jsonArray.map { require(it.jsonPrimitive.isString); it.jsonPrimitive.content }.toSet()
        }
        return State(sets(handled), root["retryAfter"]?.jsonObject?.mapValues { it.value.jsonPrimitive.long }.orEmpty(),
            root["kept"]?.jsonObject?.let(::sets).orEmpty())
    }
    /** A deadline beyond one backoff window means the clock moved back since saving; cap it durably once. */
    fun retryDeadline(walletID: String, now: Long = System.currentTimeMillis()): Long = synchronized(lock) {
        val saved = load()
        val stored = saved.retryAfter[walletID] ?: return 0
        val latest = now + RETRY_BACKOFF_MS
        if (stored <= latest) return stored
        save(saved.copy(retryAfter = saved.retryAfter + (walletID to latest)))
        latest
    }
    fun hasUnhandled(funding: Snapshot): Boolean = (funding.outpoints - load().handled[funding.walletID].orEmpty()).isNotEmpty()
    fun markHandled(funding: Snapshot): Boolean = claim(funding) != null
    fun release(funding: Snapshot, retryAfter: Long) = synchronized(lock) {
        val saved = load()
        save(saved.copy(handled = saved.handled + (funding.walletID to (saved.handled[funding.walletID].orEmpty() - funding.outpoints)),
            retryAfter = saved.retryAfter + (funding.walletID to retryAfter))) // Release and deadline share one durable write.
    }
    private fun save(saved: State) {
        write(buildJsonObject {
            put("handled", JsonObject(saved.handled.mapValues { (_, ids) -> JsonArray(ids.sorted().map(::JsonPrimitive)) }))
            put("retryAfter", JsonObject(saved.retryAfter.mapValues { JsonPrimitive(it.value) }))
            if (saved.kept.isNotEmpty()) put("kept", JsonObject(saved.kept.mapValues { (_, ids) -> JsonArray(ids.sorted().map(::JsonPrimitive)) }))
        }.toString())
    }
    fun claim(funding: Snapshot): Snapshot? = synchronized(lock) {
        val saved = load()
        val previous = saved.handled[funding.walletID].orEmpty()
        val newly = funding.outpoints - previous
        if (newly.isEmpty()) return null
        save(saved.copy(handled = saved.handled + (funding.walletID to (previous + funding.outpoints))))
        Snapshot(funding.walletID, newly)
    }
    /**
     * Coins claimed to this phone stay on-chain (exit.md, Decision 8): handled, so no review offers them, and kept, so no
     * review's amount includes their value. One durable write, before the claim is broadcast.
     */
    fun keep(walletID: String, outpoints: Set<String>) = synchronized(lock) {
        val saved = load()
        save(saved.copy(handled = saved.handled + (walletID to (saved.handled[walletID].orEmpty() + outpoints)),
            kept = saved.kept + (walletID to (saved.kept[walletID].orEmpty() + outpoints))))
    }
    fun kept(walletID: String): Set<String> = load().kept[walletID].orEmpty()
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
        retryAfter = if (changed) now() else now() + AutoBoardFunding.RETRY_BACKOFF_MS
        store.release(funding, retryAfter)
    }
    suspend fun prepare(allowed: () -> Boolean, funding: suspend () -> AutoBoardFunding.Snapshot?,
        quote: suspend () -> OnchainQuote?, discard: suspend (OnchainQuote) -> Unit,
        present: (OnchainQuote) -> Unit = {}): OnchainQuote? {
        if (!allowed() || now() < retryAfter) return null
        try {
            val current = funding() ?: return null
            if (!allowed() || now() < store.retryDeadline(current.walletID, now()) || !store.hasUnhandled(current)) return null
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
        catch (@Suppress("TooGenericExceptionCaught") failure: Exception) { // any failure backs off, then propagates
            retryAfter = now() + 30_000; throw failure
        }
    }
}
