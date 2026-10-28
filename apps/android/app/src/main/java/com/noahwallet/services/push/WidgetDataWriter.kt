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
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.ZoneId

/**
 * The widget's snapshot (docs/native-rewrite/design/widgets.md, Data the app writes): each writer reads the wallet and
 * writes the whole snapshot; display changes republish the stored one. The values are never logged.
 */
object WidgetDataWriter {
    /**
     * The wave's settled changes, Activity's rows (for Home's Arriving rule) and Home's Renew (blocks to the soonest due
     * expiry); each null when unreadable or none.
     */
    data class Extras(val changes: List<WidgetWave.Change>?, val renewBlocks: Long?,
        val rows: List<com.noahwallet.history.HistoryTransaction>? = null)

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

    private val writes = Mutex()
    private fun prefs(context: Context, variant: String) = context.getSharedPreferences("com.noahwallet.$variant", Context.MODE_PRIVATE)

    private suspend fun <T> optional(read: suspend () -> T): T? = try { read() }
    catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }

    /** Reads [Extras] inside the caller's counted session call. */
    internal suspend fun extras(handles: WalletSession.Handles, variant: String, now: Long = System.currentTimeMillis()): Extras {
        // Activity's own rows, merged as it shows them, so a board's two halves land on one day.
        val rows = optional {
            HistoryRules.unified(handles.history().filter(HistoryMapping::include).map { HistoryMapping.transaction(it) } +
                handles.transactions().filter { it.balanceChangeSats != 0L }.map { HistoryMapping.transaction(it) })
        }
        val changes = rows?.let { optional { WidgetWave.changes(it, handles.m6.tip(), now) } }
        val renew = optional {
            // Home's Renew rule: refreshable VTXOs in bark's expiring set for the refresh threshold.
            val threshold = WalletConfiguration.base(variant).vtxoRefreshExpiryThreshold.toUInt()
            val tip = handles.m6.tip()
            val expiring = handles.m6.expiring(threshold)
            HomeRules.renew(handles.m6.vtxos().map { com.noahwallet.onchain.vtxoModel(it, tip, expiring) })?.blocks
        }
        return Extras(changes, renew, rows)
    }

    @Suppress("LongParameterList") // now and zone are test seams
    internal fun snapshot(balance: WalletBalance, extras: Extras?, display: Display, quote: Quote?,
        now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): WidgetSnapshot {
        val total = balance.totalSats.toLong()
        val price = quote?.takeIf { it.currency == display.currency && it.price.isFinite() && it.price > 0 }?.price
        return WidgetSnapshot(balance = total, arriving = HomeRules.arriving(balance, extras?.rows.orEmpty()).toLong(),
            daily = extras?.changes?.let { WidgetWave.dailyBalances(total, it, now, zone) }, writtenAt = now,
            amountUnit = display.unit.name, hidden = display.hidden,
            fiatCurrency = price?.let { display.currency }, fiatPrice = price,
            renewDue = extras?.renewBlocks?.let { now + it * WidgetWave.BLOCK_MILLIS })
    }

    suspend fun clear(context: Context, variant: String): Unit = withContext(NonCancellable + Dispatchers.IO) {
        writes.withLock { check(prefs(context, variant).commitEdit { clear() }) }
        broadcast(context, variant)
    }

    /** Redraws the variant's widget, which also picks up a newly chosen language. */
    fun broadcast(context: Context, variant: String) {
        val name = variant.replaceFirstChar { it.uppercase() }
        context.sendBroadcast(Intent(NoahWidgetProvider.ACTION_WIDGET_DATA_CHANGED).setComponent(ComponentName(context.packageName,
            "com.noahwallet.widgets.NoahWidget${name}Provider")))
    }

    /** The whole snapshot replaces the file, old keys included; the last quote carries over while its currency holds. */
    @Suppress("LongParameterList") // checkStage is optional per caller
    suspend fun write(context: Context, balance: WalletBalance, extras: Extras?, display: Display, variant: String,
        checkStage: () -> Unit = {}): Unit = withContext(NonCancellable + Dispatchers.IO) {
        writes.withLock {
            val prefs = prefs(context, variant)
            val stored = WidgetSnapshot.decode(prefs.getString(WidgetSnapshot.KEY, null))
            val quote = stored?.let { s -> s.fiatPrice?.let { price -> s.fiatCurrency?.let { Quote(it, price) } } }
            val committed = prefs.commitEdit {
                clear().putString(WidgetSnapshot.KEY, snapshot(balance, extras, display, quote).encode())
                checkStage()
            }
            check(committed) { "Widget preferences could not be saved" }
        }
        broadcast(context, variant)
    }

    /**
     * The hide setting, the unit, the fiat currency or Home's [quote] changed: the stored snapshot takes them without
     * reading the wallet. With no snapshot yet (no wallet), there's nothing to change.
     */
    suspend fun republish(context: Context, display: Display, variant: String, quote: Quote? = null): Unit = withContext(NonCancellable + Dispatchers.IO) {
        val changed = writes.withLock {
            val prefs = prefs(context, variant)
            val stored = WidgetSnapshot.decode(prefs.getString(WidgetSnapshot.KEY, null)) ?: return@withLock false
            val price = (quote ?: stored.fiatPrice?.let { price -> stored.fiatCurrency?.let { Quote(it, price) } })
                ?.takeIf { it.currency == display.currency && it.price.isFinite() && it.price > 0 }?.price
            val next = stored.copy(amountUnit = display.unit.name, hidden = display.hidden,
                fiatCurrency = price?.let { display.currency }, fiatPrice = price)
            if (next == stored) return@withLock false
            check(prefs.edit().putString(WidgetSnapshot.KEY, next.encode()).commit()) { "Widget preferences could not be saved" }
            true
        }
        if (changed) broadcast(context, variant)
    }
}
