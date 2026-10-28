package com.noahwallet.widgets

import android.content.Context
import com.noahwallet.formatting.BitcoinAmount
import com.noahwallet.formatting.BitcoinAmountUnit
import com.noahwallet.formatting.FiatCurrency
import com.noahwallet.localization.L10n
import com.noahwallet.services.storage.LegacyLocale
import com.noahwallet.ui.home.HomeRules
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs

/** What a widget shows, in the app's language, from the snapshot alone (widgets.md, What every widget shows). */
internal data class WidgetContent(
    /** The balance; in sats, "sats" follows at about half size ([satsSuffix]). */
    val balance: String,
    val satsSuffix: Boolean,
    val fiat: String?,
    /** The label: the signed 30-day change, then " · 30 days"; absent when hidden or calm. */
    val change: String?,
    val days: String?,
    /** Home's network chip on signet and regtest. */
    val chip: String?,
    val status: Status?,
    /** The daily balances to draw; null is calm water (no history, every value equal, or hidden). */
    val wave: List<Long>?,
    val receive: String, val scan: String, val send: String,
) {
    sealed interface Status {
        /** Renew, then "Expires in {n} hours"; one-row layouts show the expiry alone. */
        data class Renew(val title: String, val expiry: String) : Status
        data class Arriving(val text: String) : Status
    }

    /** One label per widget for TalkBack: balance, fiat, the change label, then the status line. */
    val description: String get() = listOfNotNull(balance, fiat, change?.let { it + days.orEmpty() },
        when (status) { is Status.Renew -> "${status.title}, ${status.expiry}"; is Status.Arriving -> status.text; null -> null })
        .joinToString(", ")

    companion object {
        private const val HOUR = 3_600_000L

        fun of(context: Context, snapshot: WidgetSnapshot, variant: String, now: Long = System.currentTimeMillis(),
            zone: ZoneId = ZoneId.systemDefault()): WidgetContent {
            val locale = Locale.forLanguageTag(LegacyLocale.resolved(context))
            val unit = if (snapshot.sats) BitcoinAmountUnit.sats else BitcoinAmountUnit.bip177
            val hidden = snapshot.hidden
            fun amount(sats: Long) = BitcoinAmount.format(sats, unit, locale)
            fun masked(dots: String) = BitcoinAmount.format(0, unit, locale).replace("0", dots)
            val wave = wave(snapshot, now, zone)
            val change = wave?.let(WidgetWave::change)
            val renew = renew(context, snapshot.renewDue, now)
            val arriving = snapshot.pending.takeIf { it > 0 }?.let {
                Status.Arriving(L10n.text(context, "{amount} arriving", "+" + if (hidden) masked("••••") else amount(it)))
            }
            return WidgetContent(
                balance = if (hidden) masked("••••••") else amount(snapshot.balance),
                satsSuffix = unit == BitcoinAmountUnit.sats,
                fiat = fiat(snapshot, locale),
                change = change?.let { (if (it < 0) "−" else "+") + amount(abs(it)) },
                days = change?.let { " · " + L10n.plural(context, "{n} days", WidgetWave.DAYS) },
                chip = chip(context, variant),
                // Renew wins when both apply.
                status = renew ?: arriving,
                wave = wave,
                receive = L10n.text(context, "Receive"), scan = L10n.text(context, "Scan"), send = L10n.text(context, "Send"),
            )
        }

        private fun wave(snapshot: WidgetSnapshot, now: Long, zone: ZoneId): List<Long>? {
            val daily = snapshot.daily?.let { values ->
                snapshot.writtenAt?.let { WidgetWave.shifted(values, WidgetWave.day(it, zone), WidgetWave.day(now, zone)) } ?: values
            }
            // The wave would reveal your history, so it never draws while hidden.
            return daily?.takeIf { !snapshot.hidden && !WidgetWave.calm(it) }
        }

        private fun renew(context: Context, due: Long?, now: Long): Status.Renew? = due?.takeIf { it > now }?.let {
            val hours = (it - now) / HOUR
            Status.Renew(L10n.text(context, "Renew"), if (hours < 1) L10n.text(context, "Expires in under an hour")
            else L10n.plural(context, "Expires in {n} hours", hours.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()))
        }

        private fun fiat(snapshot: WidgetSnapshot, locale: Locale): String? =
            snapshot.fiatCurrency?.takeIf { it in FiatCurrency.supported }?.let { currency ->
                if (snapshot.hidden) HomeRules.fiat(null, null, currency, locale, true)
                else snapshot.fiatValue?.takeIf { it.isFinite() }?.let { FiatCurrency.format(it, currency, locale) }
            }

        private fun chip(context: Context, variant: String): String? =
            when (variant) { "signet" -> L10n.text(context, "Signet"); "regtest" -> L10n.text(context, "Regtest"); else -> null }
    }
}
