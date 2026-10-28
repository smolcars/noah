package com.noahwallet.widgets

import android.content.SharedPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What the widget shows, written whole by the app (docs/native-rewrite/design/widgets.md, Data the app writes) and only
 * read by the widget. App-private preferences, never logged.
 */
@Serializable
internal data class WidgetSnapshot(
    val version: Int = VERSION,
    /** Sats: the balance Home shows, and what Home's Arriving line counts (incoming only, [com.noahwallet.ui.home.HomeRules.arriving]). */
    val balance: Long,
    val arriving: Long = 0,
    /** 31 daily balances, oldest first, the last one [balance]; absent draws calm water. */
    val daily: List<Long>? = null,
    /** When [daily] was read (epoch ms), so a later day moves the window on. */
    val writtenAt: Long? = null,
    /** Home's persisted unit (`bip177` / `sats`) and hide-balance flag. */
    val amountUnit: String = "bip177",
    val hidden: Boolean = false,
    /** Home's latest quote: the bitcoin price in [fiatCurrency]; the widget applies Home's fiat formula to the balance. */
    val fiatCurrency: String? = null,
    val fiatPrice: Double? = null,
    /** While Home's Renew rule holds: the soonest refreshable VTXO expiry (epoch ms, now + blocks × 10 min). */
    val renewDue: Long? = null,
) {
    val sats get() = amountUnit == "sats"

    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        const val VERSION = 1
        const val KEY = "widgetSnapshot"
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

        fun decode(raw: String?): WidgetSnapshot? = raw?.let { runCatching { json.decodeFromString(serializer(), it) }.getOrNull() }
            ?.takeIf { it.daily == null || it.daily.size == WidgetWave.DAYS + 1 }

        /** One read of the whole file, so a concurrent commit can't mix two writes. Null: no wallet. */
        fun read(prefs: SharedPreferences): WidgetSnapshot? {
            val values = prefs.all
            decode(values[KEY] as? String)?.let { return it }
            // The old keys (an RN or earlier native snapshot, before the updated app runs): ₿, no fiat, calm water, no
            // status line. Their pending total also counts outgoing payments, so it can't be shown as arriving.
            val total = values["totalBalance"] as? Long ?: return null
            return WidgetSnapshot(balance = total)
        }
    }
}
