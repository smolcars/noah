package com.noahwallet.services.push

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.noahwallet.formatting.BitcoinAmountUnit
import com.noahwallet.history.HistoryMapping
import com.noahwallet.history.HistoryRules
import com.noahwallet.services.storage.SettingsPreferences
import com.noahwallet.services.storage.SettingsStorage
import com.noahwallet.services.storage.commitEdit
import com.noahwallet.services.wallet.WalletBalance
import com.noahwallet.services.wallet.WalletConfiguration
import com.noahwallet.services.wallet.WalletSession
import com.noahwallet.ui.home.HomeRules
import com.noahwallet.widgets.NoahWidgetProvider
import com.noahwallet.widgets.WidgetSnapshot
import com.noahwallet.widgets.WidgetWave
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.TimeZone

/**
 * The widget's snapshot (docs/native-rewrite/design/widgets.md, Data the app writes): each writer reads the wallet and
 * writes the whole snapshot; display changes republish the stored one. The values are never logged.
 */
object WidgetDataWriter {
    /**
     * The rows Activity reads, before it merges them (the wave's changes; merged, Home's Arriving rule), the chain tip
     * they're dated against and Home's Renew (blocks to the soonest due expiry); each null when unreadable or none.
     * [pending]: what bark reports pending, null when unread or not consistent with the balance shown (the wave then
     * keeps the earlier days).
     */
    data class Extras(val rows: List<com.noahwallet.history.HistoryTransaction>? = null, val tip: Long? = null,
        val renewBlocks: Long? = null, val pending: WidgetWave.Pending? = WidgetWave.Pending())

    /** Home's persisted keys the widget follows. */
    data class Display(val unit: BitcoinAmountUnit, val hidden: Boolean, val currency: String) {
        companion object {
            fun read(settings: SettingsStorage): Display {
                val profile = settings.string("profile-storage")
                val hidden = SettingsPreferences.state(settings.string("privacy-storage"))["isHomeBalanceHidden"]
                    ?.let { runCatching { it.jsonPrimitive.booleanOrNull }.getOrNull() } ?: false
                return Display(SettingsPreferences.amountUnit(profile), hidden, SettingsPreferences.preferredCurrency(profile))
            }
        }
    }

    /** Home's latest price for [currency]. */
    data class Quote(val currency: String, val price: Double)

    /** Every snapshot update (write, republish, clear) holds this, so none overwrites a newer one. */
    private val writes = Mutex()
    private val reads = java.util.concurrent.atomic.AtomicLong()
    /** The newest read published, or the clear that followed it; guarded by [writes]. */
    private var published = 0L
    /** Home's latest quote in this process, kept whether or not a snapshot exists yet; guarded by [writes]. */
    private var latestQuote: Quote? = null

    /** Tests start without a quote from an earlier one. */
    internal fun forgetQuote() { latestQuote = null }

    /** The newest valid quote in [currency]: Home's latest, else the one a snapshot carried over. */
    private fun quote(currency: String, stored: WidgetSnapshot?): Quote? =
        listOfNotNull(latestQuote, stored?.let { s -> s.fiatPrice?.let { price -> s.fiatCurrency?.let { Quote(it, price) } } })
            .firstOrNull { it.currency == currency && it.price.isFinite() && it.price > 0 }

    /**
     * Take before reading anything from the wallet, and pass to [write]: a write whose reads started before those of one
     * already published (a push that stalled while the app published a newer balance), or before a clear (a deleted
     * wallet), is dropped. Every writer takes it the same way.
     */
    fun ticket(): Long = reads.incrementAndGet()
    /** Wallet transactions' dates by txid ([WidgetWave.ChainDate]), kept beside the snapshot and cleared with it. */
    internal const val CHAIN_DATES = "widgetChainDates"
    private val dateMap = MapSerializer(String.serializer(), WidgetWave.ChainDate.serializer())
    private fun dates(raw: String?): Map<String, WidgetWave.ChainDate> =
        raw?.let { runCatching { kotlinx.serialization.json.Json.decodeFromString(dateMap, it) }.getOrNull() }.orEmpty()
    private fun prefs(context: Context, variant: String) = context.getSharedPreferences("com.noahwallet.$variant", Context.MODE_PRIVATE)

    private suspend fun <T> optional(read: suspend () -> T): T? = try { read() }
    catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }

    /** How long the balance read after the records may take; bark's balance fetches the chain tip. */
    private const val SETTLED_READ_MILLIS = 2_000L

    /**
     * Reads [Extras] inside the caller's counted session call, after it read [shown] for the snapshot. A payment that
     * settles during the records' reads would be counted by neither, so the balance is read once more after them:
     * unless it agrees with [shown] in time, [Extras.pending] is null.
     */
    internal suspend fun extras(handles: WalletSession.Handles, variant: String, shown: WalletBalance?,
        settledRead: Long = SETTLED_READ_MILLIS): Extras {
        // Every movement, so one that only names a transaction (a free renewal) still claims it, as on iOS.
        val rows = optional {
            handles.history().map { HistoryMapping.transaction(it) } +
                handles.transactions().filter { it.balanceChangeSats != 0L }.map { HistoryMapping.transaction(it) }
        }
        val pending = rows?.let {
            optional {
                WidgetWave.Pending(handles.pendingLightningReceives().filter { it.state == "preimage-revealed" }.mapNotNull { it.movementId }.toSet(),
                    handles.pendingBoardVtxos().map { it.id }.toSet())
            }
        }
        val after = if (pending == null || shown == null) null else optional { withTimeoutOrNull(settledRead) { handles.balance() } }
        val tip = optional { handles.m6.tip() }
        val renew = optional {
            // Home's Renew rule: refreshable VTXOs in bark's expiring set for the refresh threshold.
            val threshold = WalletConfiguration.base(variant).vtxoRefreshExpiryThreshold.toUInt()
            val height = tip ?: handles.m6.tip()
            val expiring = handles.m6.expiring(threshold)
            HomeRules.renew(handles.m6.vtxos().map { com.noahwallet.onchain.vtxoModel(it, height, expiring) })?.blocks
        }
        return Extras(rows, tip, renew, pending?.takeIf { shown != null && after != null && settled(shown, after) })
    }

    /** The buckets the wave's pending rules read, and the total, are the same in both reads. */
    private fun settled(before: WalletBalance, after: WalletBalance) = before.totalSats == after.totalSats &&
        before.ark.claimableLightningReceive == after.ark.claimableLightningReceive && before.ark.pendingBoard == after.ark.pendingBoard &&
        before.ark.pendingOffboard == after.ark.pendingOffboard

    /**
     * The snapshot for [balance]. Without the wave's records the earlier days stay as [previous] wrote them and today
     * takes the balance; with neither, calm water.
     */
    @Suppress("LongParameterList") // now and zone are test seams
    internal fun snapshot(balance: WalletBalance, extras: Extras?, display: Display, quote: Quote?, chainDates: Map<String, Long> = emptyMap(),
        now: Long = System.currentTimeMillis(), zone: TimeZone = TimeZone.getDefault(), previous: WidgetSnapshot? = null): WidgetSnapshot {
        val total = balance.totalSats.toLong()
        val changes = extras?.rows?.let { rows ->
            extras.pending?.let {
                WidgetWave.changes(rows, chainDates, it) + WidgetWave.offboarded(WidgetWave.offboards(rows), balance.ark.pendingOffboard.toLong())
            }
        }
        val kept = previous?.daily?.let { days ->
            (previous.writtenAt?.let { WidgetWave.shifted(days, WidgetWave.day(it, zone), WidgetWave.day(now, zone)) } ?: days).dropLast(1) + total
        }
        val price = quote?.takeIf { it.currency == display.currency && it.price.isFinite() && it.price > 0 }?.price
        // Home counts what's arriving in Activity's merged rows.
        return WidgetSnapshot(balance = total, arriving = HomeRules.arriving(balance, HistoryRules.unified(extras?.rows.orEmpty())).toLong(),
            daily = changes?.let { WidgetWave.dailyBalances(total, it, now, zone) } ?: kept, writtenAt = now,
            amountUnit = display.unit.name, hidden = display.hidden,
            fiatCurrency = price?.let { display.currency }, fiatPrice = price,
            renewDue = extras?.renewBlocks?.let { now + it * WidgetWave.BLOCK_MILLIS })
    }

    suspend fun clear(context: Context, variant: String): Unit = withContext(NonCancellable + Dispatchers.IO) {
        writes.withLock {
            published = reads.incrementAndGet()
            check(prefs(context, variant).commitEdit { clear() })
        }
        broadcast(context, variant)
    }

    /** Redraws the variant's widget, which also picks up a newly chosen language. */
    fun broadcast(context: Context, variant: String) {
        val name = variant.replaceFirstChar { it.uppercase() }
        context.sendBroadcast(Intent(NoahWidgetProvider.ACTION_WIDGET_DATA_CHANGED).setComponent(ComponentName(context.packageName,
            "com.noahwallet.widgets.NoahWidget${name}Provider")))
    }

    /**
     * The whole snapshot replaces the file, old keys included. [display] is read under the lock from its live source, so
     * a slow writer never brings back a hide, unit or currency the user has changed since; the last quote carries over
     * while its currency holds. [ticket]: taken before [balance] was read. [known]: block times Activity has read
     * (txid → epoch ms), which date transactions exactly.
     */
    @Suppress("LongParameterList") // known and checkStage are optional per caller
    suspend fun write(context: Context, balance: WalletBalance, extras: Extras?, display: () -> Display, variant: String, ticket: Long,
        known: Map<String, Long> = emptyMap(), checkStage: () -> Unit = {}): Unit = withContext(NonCancellable + Dispatchers.IO) {
        val wrote = writes.withLock {
            if (ticket <= published) return@withLock false
            val prefs = prefs(context, variant)
            val stored = WidgetSnapshot.decode(prefs.getString(WidgetSnapshot.KEY, null))
            // Settings and the quote as they are now, not as they were when the wallet was read.
            val display = display()
            val now = System.currentTimeMillis()
            // Unreadable history keeps the dates already known.
            val storedDates = dates(prefs.getString(CHAIN_DATES, null))
            val chainDates = extras?.rows?.let { WidgetWave.chainDates(it, storedDates, known, extras.tip, now) } ?: storedDates
            val committed = prefs.commitEdit {
                clear()
                    .putString(WidgetSnapshot.KEY,
                        snapshot(balance, extras, display, quote(display.currency, stored), chainDates.mapValues { it.value.at }, now,
                            previous = stored).encode())
                    .putString(CHAIN_DATES, kotlinx.serialization.json.Json.encodeToString(dateMap, chainDates))
                checkStage()
            }
            check(committed) { "Widget preferences could not be saved" }
            published = ticket
            true
        }
        if (wrote) broadcast(context, variant)
    }

    /**
     * The hide setting, the unit, the fiat currency or Home's [quote] changed: the stored snapshot takes them without
     * reading the wallet. A quote is kept even before there's a snapshot, for the first write.
     */
    suspend fun republish(context: Context, display: () -> Display, variant: String,
        quote: Quote? = null): Unit = withContext(NonCancellable + Dispatchers.IO) {
        val changed = writes.withLock {
            if (quote != null) latestQuote = quote
            val prefs = prefs(context, variant)
            val display = display()
            val stored = WidgetSnapshot.decode(prefs.getString(WidgetSnapshot.KEY, null)) ?: return@withLock false
            val price = quote(display.currency, stored)?.price
            val next = stored.copy(amountUnit = display.unit.name, hidden = display.hidden,
                fiatCurrency = price?.let { display.currency }, fiatPrice = price)
            if (next == stored) return@withLock false
            check(prefs.commitEdit { putString(WidgetSnapshot.KEY, next.encode()) }) { "Widget preferences could not be saved" }
            true
        }
        if (changed) broadcast(context, variant)
    }
}
