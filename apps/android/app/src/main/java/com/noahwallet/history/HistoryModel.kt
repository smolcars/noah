package com.noahwallet.history

import androidx.compose.runtime.*
import com.noahwallet.api.generated.client.V0HistoricalPriceClient
import com.noahwallet.api.generated.client.V0PricesClient
import com.noahwallet.api.generated.models.HistoricalFiatPricePayload
import com.noahwallet.services.AppServices
import com.noahwallet.services.server.value
import com.noahwallet.services.wallet.WalletConfiguration
import com.noahwallet.services.wallet.WalletSession
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Date
import java.util.concurrent.TimeUnit

/** UI-scoped observation; native reads are finite counted calls owned by WalletSession. */
class HistoryModel(private val dependencies: Dependencies) {
    data class Dependencies(
        val load: suspend () -> List<HistoryTransaction>, val sync: suspend () -> Unit,
        val changes: () -> Flow<String>, val blockTime: suspend (String) -> Date?,
        val price: suspend (Long, String) -> Double?, val currentPrice: suspend (String) -> Double? = { null },
        val warn: (String) -> Unit = {},
        /** Recurring payments the journal recorded as paid, to name the rows they made. */
        val recurring: suspend () -> List<HistoryMapping.RecurringLink> = { emptyList() },
    ) {
        companion object {
            @Suppress("LongParameterList") // the app's wiring: each of the history's sources and hooks, from AppModel
            fun live(session: WalletSession, client: HttpClient, variant: String,
                suspended: () -> Boolean, warn: (String) -> Unit,
                esploraEndpoint: () -> String? = { WalletConfiguration.base(variant).esplora },
                recurring: suspend () -> List<HistoryMapping.RecurringLink> = { emptyList() }): Dependencies {
                val explorer = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()
                fun available() { if (suspended()) throw AppServices.WalletSuspended() }
                return Dependencies(
                    load = { available(); session.transactionHistory() }, sync = { available(); session.sync() },
                    changes = { flow { available(); emitAll(session.stateChanges().map { it.reason }) } },
                    blockTime = { txid ->
                        esploraEndpoint()?.let { base ->
                            suspendCancellableCoroutine { continuation ->
                                // Esplora has no wallet authentication; cancellation aborts the network request.
                                val request = Request.Builder().url(base.trimEnd('/') + "/tx/" + java.net.URLEncoder.encode(txid, "UTF-8")).build()
                                val call = explorer.newCall(request)
                                continuation.invokeOnCancellation { call.cancel() }
                                call.enqueue(object : okhttp3.Callback {
                                    override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                                        continuation.resumeWith(Result.failure(e))
                                    }
                                    override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                                        continuation.resumeWith(runCatching {
                                            response.use {
                                                val body = if (it.isSuccessful) Json.parseToJsonElement(it.body.string()).jsonObject else null
                                                val status = body?.get("status") as? JsonObject
                                                if ((status?.get("confirmed") as? JsonPrimitive)?.booleanOrNull == true)
                                                    (status["block_time"] as? JsonPrimitive)?.longOrNull?.let { time -> Date(time * 1000) } else null
                                            }
                                        })
                                    }
                                })
                            }
                        }
                    },
                    price = { timestamp, currency -> V0HistoricalPriceClient(client)
                        .historicalFiatPrice(HistoricalFiatPricePayload(currency, timestamp)).value().rate },
                    currentPrice = { currency -> V0PricesClient(client).fiatPrices(JsonObject(emptyMap())).value().rates[currency] },
                    warn = warn, recurring = recurring,
                )
            }
        }
    }
    var transactions by mutableStateOf<List<HistoryTransaction>>(emptyList()); private set
    var isLoading by mutableStateOf(true); private set
    var isRefreshing by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    /** Counts completed reads of the wallet's history (wallet events, pull to refresh, resume), even when nothing changed. */
    var revision by mutableLongStateOf(0L); private set
    private val blockTimes = mutableMapOf<String, Date>()
    private val prices = linkedMapOf<String, Double>()
    private var generation = 0L
    private var enrichment: Job? = null
    private val enrichmentLock = Mutex()

    fun clear() {
        generation++
        enrichment?.cancel()
        transactions = emptyList(); error = null; isLoading = true; isRefreshing = false
        blockTimes.clear(); prices.clear()
    }

    internal suspend fun awaitEnrichment() { enrichment?.join() }

    suspend fun observe(currency: String) {
        try {
            do {
                var resubscribe = false
                coroutineScope {
                    val events = Channel<String>(Channel.CONFLATED)
                    val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                        try { dependencies.changes().collect { events.send(it) } }
                        finally { events.close() }
                    }
                    try {
                        refresh(currency)
                        for (reason in events) {
                            refresh(currency)
                            if (reason == "resyncRequired") { resubscribe = true; break }
                        }
                    } finally { enrichment?.cancel(); collector.cancelAndJoin(); events.cancel() }
                }
            } while (resubscribe && currentCoroutineContext().isActive)
            // A closed session ends its stream; clear stale history through the normal read path.
            refresh(currency)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { refresh(currency) }
        finally { enrichment?.cancel() }
    }

    private suspend fun <T> optional(body: suspend () -> T): T? = try { body() }
    catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
    private fun Double?.validRate() = this?.takeIf { it.isFinite() && it > 0 }

    suspend fun refresh(currency: String, sync: Boolean = false) {
        val request = ++generation
        enrichment?.cancel()
        isRefreshing = true
        try {
            if (sync) dependencies.sync()
            // A recurring journal that can't be read just leaves its payments as ordinary sends.
            val latest = HistoryMapping.joinRecurring(dependencies.load(), optional { dependencies.recurring() } ?: emptyList())
            currentCoroutineContext().ensureActive()
            if (request != generation) return
            transactions = HistoryRules.unified(latest); isLoading = false; error = null; revision++
            // One worker bounds optional requests; it never blocks the wallet event consumer.
            enrichment = CoroutineScope(currentCoroutineContext()).launch {
                enrichmentLock.withLock { enrich(latest, currency, request) }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: AppServices.WalletSuspended) { unavailable(request, "Your wallet is suspended. Resume it to view transactions.") }
        catch (_: WalletSession.SessionError) { unavailable(request, "Your wallet isn't ready. Please try again.") }
        catch (_: Exception) {
            if (request != generation) return
            error = "Couldn't load transactions. Please try again."
            dependencies.warn("History read failed")
        } finally { if (request == generation) { isRefreshing = false; isLoading = false } }
    }

    /** No wallet to read: the list empties and says why, unless a newer request took over. */
    private fun unavailable(request: Long, message: String) {
        if (request != generation) return
        transactions = emptyList(); error = message
    }

    private suspend fun enrich(rows: List<HistoryTransaction>, currency: String, request: Long) {
        val work = Enrichment(rows, currency, request)
        for (index in work.latest.indices) {
            currentCoroutineContext().ensureActive()
            if (request != generation) return
            val dated = work.dated(index, work.latest[index]) ?: return
            val row = work.priced(dated) ?: return
            currentCoroutineContext().ensureActive()
            if (request != generation) return
            work.store(index, row)
            // Cached quotes never suspend: bound merge/sort work and let wallet events run.
            if ((index + 1) % 32 == 0 || index == work.latest.lastIndex) work.publish()
        }
    }

    /** One enrichment pass over [request]'s rows: block times for confirmed on-chain rows, then each row's price. */
    private inner class Enrichment(rows: List<HistoryTransaction>, private val currency: String, private val request: Long) {
        val latest = rows.toMutableList()
        private var dirty = false

        fun store(index: Int, row: HistoryTransaction) { latest[index] = row; dirty = true }

        suspend fun publish() {
            currentCoroutineContext().ensureActive()
            if (request != generation || !dirty) return
            transactions = HistoryRules.unified(latest)
            dirty = false
            yield()
        }

        /** A confirmed on-chain [row] with its block's time; null once a newer request has taken over. */
        suspend fun dated(index: Int, row: HistoryTransaction): HistoryTransaction? {
            val txid = row.txid
            if (row.source != "onchain-wallet" || row.hasConfirmation != true || txid == null) return row
            val time = blockTimes[txid] ?: run { publish(); optional { dependencies.blockTime(txid) } }
            currentCoroutineContext().ensureActive()
            if (request != generation) return null
            if (time == null) return row
            blockTimes[txid] = time
            return row.copy(date = HistoryRules.isoDate(time), dateLabel = null, sortTimestamp = time.time.toDouble()).also { store(index, it) }
        }

        /** [row] with its price on its day (cached), else today's; null once a newer request has taken over. */
        suspend fun priced(row: HistoryTransaction): HistoryTransaction? {
            val date = row.timestamp
            if (date == null || date.time <= 0) return row
            val key = "$currency:${row.date.take(10)}"
            // Publish completed rows before a cache miss can stall the single worker.
            val price = prices[key] ?: run { publish(); optional { dependencies.price(date.time / 1000, currency) }.validRate() }
            currentCoroutineContext().ensureActive()
            if (request != generation) return null
            if (price == null) { publish(); return row.copy(btcPrice = optional { dependencies.currentPrice(currency) }.validRate()) }
            prices[key] = price
            if (prices.size > 200) prices.remove(prices.keys.first())
            return row.copy(btcPrice = price)
        }
    }
}
