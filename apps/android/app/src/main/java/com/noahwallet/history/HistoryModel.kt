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
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
                                    override fun onFailure(call: okhttp3.Call, failure: java.io.IOException) {
                                        continuation.resumeWith(Result.failure(failure))
                                    }
                                    override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                                        continuation.resumeWith(runCatching {
                                            response.use {
                                                val status = if (it.isSuccessful) Json.parseToJsonElement(it.body.string()).jsonObject["status"] as? JsonObject else null
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
        catch (failure: Exception) {
            if (request != generation) return
            if (failure is WalletSession.SessionError || failure is AppServices.WalletSuspended) {
                transactions = emptyList()
                error = if (failure is AppServices.WalletSuspended) "Your wallet is suspended. Resume it to view transactions."
                    else "Your wallet isn't ready. Please try again."
            } else {
                error = "Couldn't load transactions. Please try again."
                dependencies.warn("History read failed")
            }
        } finally { if (request == generation) { isRefreshing = false; isLoading = false } }
    }

    private suspend fun enrich(rows: List<HistoryTransaction>, currency: String, request: Long) {
        val latest = rows.toMutableList()
        var dirty = false
        suspend fun publish() {
            currentCoroutineContext().ensureActive()
            if (request != generation || !dirty) return
            transactions = HistoryRules.unified(latest)
            dirty = false
            yield()
        }
        for (index in latest.indices) {
            currentCoroutineContext().ensureActive()
            if (request != generation) return
            var row = latest[index]
            if (row.source == "onchain-wallet" && row.hasConfirmation == true && row.txid != null) {
                val txid = row.txid
                val time = blockTimes[txid] ?: run { publish(); optional { dependencies.blockTime(txid) } }
                currentCoroutineContext().ensureActive()
                if (request != generation) return
                if (time != null) {
                    blockTimes[txid] = time
                    row = row.copy(date = HistoryRules.isoDate(time), dateLabel = null, sortTimestamp = time.time.toDouble())
                    latest[index] = row
                    dirty = true
                }
            }
            val date = row.timestamp
            if (date != null && date.time > 0) {
                val key = "$currency:${row.date.take(10)}"
                // Publish completed rows before a cache miss can stall the single worker.
                val price = prices[key] ?: run { publish(); optional { dependencies.price(date.time / 1000, currency) }.validRate() }
                currentCoroutineContext().ensureActive()
                if (request != generation) return
                if (price != null) {
                    prices[key] = price
                    if (prices.size > 200) prices.remove(prices.keys.first())
                    row = row.copy(btcPrice = price)
                } else { publish(); row = row.copy(btcPrice = optional { dependencies.currentPrice(currency) }.validRate()) }
            }
            currentCoroutineContext().ensureActive()
            if (request != generation) return
            latest[index] = row
            dirty = true
            // Cached quotes never suspend: bound merge/sort work and let wallet events run.
            if ((index + 1) % 32 == 0 || index == latest.lastIndex) publish()
        }
    }
}
