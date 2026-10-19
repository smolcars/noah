package com.noahwallet.formatting

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor

object FiatCurrency {
    val supported = setOf("USD", "EUR", "GBP", "CAD", "CHF", "AUD", "JPY", "BRL", "KRW", "INR", "MXN", "SGD")
    fun decimals(currency: String) = if (currency in setOf("JPY", "KRW")) 0 else 2
    fun fiatToSats(amount: Double, btcPrice: Double): Long {
        if (!amount.isFinite() || !btcPrice.isFinite() || btcPrice <= 0) return 0
        val sats = floor(amount / btcPrice * 100_000_000 + 0.5)
        return if (sats.isFinite() && abs(sats) <= 9_007_199_254_740_991.0) sats.toLong() else 0
    }
    fun satsToFiat(sats: Long, btcPrice: Double, currency: String): String {
        // Construct from the binary double to match JS toFixed, including 1.005/2.675.
        return BigDecimal(sats.toDouble() * btcPrice / 100_000_000)
            .setScale(decimals(currency), RoundingMode.HALF_UP).toPlainString()
    }
    fun format(amount: Double, currency: String, locale: Locale = Locale.getDefault()): String {
        val formatter = NumberFormat.getCurrencyInstance(locale).apply {
            this.currency = Currency.getInstance(currency)
            minimumFractionDigits = decimals(currency); maximumFractionDigits = decimals(currency)
            roundingMode = RoundingMode.HALF_UP
        }
        // java.text does not apply CLDR currency spacing for ISO-code symbols.
        return formatter.format(amount).replace(Regex("([A-Z]{3})(?=[0-9])"), "$1\u00a0")
    }
    fun display(sats: Long, btcPrice: Double, currency: String, locale: Locale = Locale.getDefault()) =
        format(satsToFiat(sats, btcPrice, currency).toDouble(), currency, locale)
}
