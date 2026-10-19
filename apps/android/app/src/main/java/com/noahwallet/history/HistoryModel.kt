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
    ) {
        companion object {
            fun live(session: WalletSession, client: HttpClient, variant: String,
                suspended: () -> Boolean, warn: (String) -> Unit): Dependencies {
                val explorer = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()
                fun available() { if (suspended()) throw AppServices.WalletSuspended() }
                return Dependencies(
                    load = { available(); session.transactionHistory() }, sync = { available(); session.sync() },
                    changes = { flow { available(); emitAll(session.stateChanges().map { it.reason }) } },
                    blockTime = { txid ->
                        WalletConfiguration.base(variant).esplora?.let { base ->
                            withContext(Dispatchers.IO) {
                                // Esplora has no wallet authentication; never reuse the authenticated server client.
                                val request = Request.Builder().url(base.trimEnd('/') + "/tx/" + java.net.URLEncoder.encode(txid, "UTF-8")).build()
                                explorer.newCall(request).execute().use { response ->
                                    val status = if (response.isSuccessful) Json.parseToJsonElement(response.body.string()).jsonObject["status"] as? JsonObject else null
                                    if ((status?.get("confirmed") as? JsonPrimitive)?.booleanOrNull == true)
                                        (status["block_time"] as? JsonPrimitive)?.longOrNull?.let { Date(it * 1000) } else null
                                }
                            }
                        }
                    },
                    price = { timestamp, currency -> V0HistoricalPriceClient(client)
                        .historicalFiatPrice(HistoricalFiatPricePayload(currency, timestamp)).value().rate },
                    currentPrice = { currency -> V0PricesClient(client).fiatPrices(JsonObject(emptyMap())).value().rates[currency] },
                    warn = warn,
                )
            }
        }
    }
    var transactions by mutableStateOf<List<HistoryTransaction>>(emptyList()); private set
    var isLoading by mutableStateOf(true); private set
    var isRefreshing by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    private val blockTimes = mutableMapOf<String, Date>()
    private val prices = linkedMapOf<String, Double>()
    private var generation = 0L

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
                    } finally { collector.cancelAndJoin(); events.cancel() }
                }
            } while (resubscribe && currentCoroutineContext().isActive)
            // A closed session ends its stream; clear stale history through the normal read path.
            refresh(currency)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { refresh(currency) }
    }

    private suspend fun <T> optional(body: suspend () -> T): T? = try { body() }
        catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
    private fun Double?.validRate() = this?.takeIf { it.isFinite() && it > 0 }

    suspend fun refresh(currency: String, sync: Boolean = false) {
        val request = ++generation
        isRefreshing = true
        try {
            if (sync) dependencies.sync()
            var latest = dependencies.load()
            currentCoroutineContext().ensureActive()
            if (request != generation) return
            transactions = HistoryRules.unified(latest); isLoading = false; error = null
            latest = latest.map { original ->
                currentCoroutineContext().ensureActive()
                if (request != generation) return
                var row = original
                if (row.source == "onchain-wallet" && row.hasConfirmation == true && row.txid != null) {
                    val txid = row.txid
                    val time = blockTimes[txid] ?: optional { dependencies.blockTime(txid) }
                    if (time != null) {
                        blockTimes[txid] = time
                        row = row.copy(date = HistoryRules.isoDate(time), dateLabel = null, sortTimestamp = time.time.toDouble())
                    }
                }
                val date = row.timestamp
                if (date != null && date.time > 0) {
                    val key = "$currency:${row.date.take(10)}"
                    val price = prices[key] ?: optional { dependencies.price(date.time / 1000, currency) }.validRate()
                    if (price != null) {
                        prices[key] = price
                        if (prices.size > 200) prices.remove(prices.keys.first())
                        row = row.copy(btcPrice = price)
                    } else row = row.copy(btcPrice = optional { dependencies.currentPrice(currency) }.validRate())
                }
                row
            }
            currentCoroutineContext().ensureActive()
            if (request == generation) transactions = HistoryRules.unified(latest)
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
}
