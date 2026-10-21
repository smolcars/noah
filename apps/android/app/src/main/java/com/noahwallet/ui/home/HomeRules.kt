package com.noahwallet.ui.home

import com.noahwallet.services.storage.PersistedBackupState
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import com.noahwallet.formatting.FiatCurrency

object HomeRules {
    private val symbols = mapOf("USD" to "$", "EUR" to "€", "GBP" to "£", "CAD" to "CA$", "CHF" to "CHF", "AUD" to "A$",
        "JPY" to "¥", "BRL" to "R$", "KRW" to "₩", "INR" to "₹", "MXN" to "MX$", "SGD" to "S$")
    fun fiat(sats: Long?, price: Double?, currency: String, locale: Locale, hidden: Boolean): String? =
        if (hidden) "${symbols.getValue(currency)} ••••" else sats?.let { balance -> price?.takeIf { it.isFinite() && it > 0 }?.let { FiatCurrency.display(balance, it, currency, locale) } }
    const val successMillis = 5_000L
    data class Banner(val title: String, val message: String, val failed: Boolean = false, val retry: Boolean = false)
    fun backup(state: PersistedBackupState, now: Long, locale: Locale): Banner? = when {
        state.lastBackupStatus == "in_progress" -> Banner("Backing up wallet", "Running in background")
        state.lastBackupStatus == "failed" -> Banner("Backup failed", "An unknown error occurred while backing up.", failed = true, retry = true)
        !state.backupPending && state.lastBackupStatus == "success" && state.lastBackupAt != null && now - state.lastBackupAt!! < successMillis ->
            Banner("Backup completed", "Last backup ${DateFormat.getTimeInstance(DateFormat.SHORT, locale).format(Date(state.lastBackupAt!!.toLong()))}")
        else -> null
    }
    fun boarding(amount: Long, createdAt: Long, now: Long, locale: Locale): Banner? =
        if (now - createdAt < successMillis) Banner("Boarding completed", "${java.text.NumberFormat.getIntegerInstance(locale).format(amount)} sats available in Ark") else null
    fun activeRound(final: Boolean, status: String) = !final || status in setOf("pending", "unconfirmed")
    fun updateURL(variant: String) = "https://play.google.com/store/apps/details?id=com.noahwallet.${if (variant == "mainnet") "mainnet" else "signet"}"
}
