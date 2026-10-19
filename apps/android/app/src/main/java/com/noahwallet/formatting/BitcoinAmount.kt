package com.noahwallet.formatting

import android.icu.number.NumberFormatter
import android.icu.text.NumberFormat
import android.os.Build
import java.util.Locale

enum class BitcoinAmountUnit { bip177, sats }

/** Integer sats only; use Android ICU so locale grouping agrees with RN and iOS. */
object BitcoinAmount {
    fun format(sats: Long, unit: BitcoinAmountUnit, locale: Locale = Locale.getDefault()): String {
        val number = if (Build.VERSION.SDK_INT >= 30) {
            NumberFormatter.withLocale(locale).format(sats).toString()
        } else legacyNumber(sats, locale)
        return when (unit) {
            BitcoinAmountUnit.bip177 -> "₿\u00a0$number"
            BitcoinAmountUnit.sats -> "$number sats"
        }
    }

    internal fun legacyNumber(sats: Long, locale: Locale): String = NumberFormat.getIntegerInstance(locale).apply {
        // API 24–29's formatter ignores Spanish CLDR minimumGroupingDigits=2.
        if (locale.language == "es" && locale.country in listOf("", "ES") && sats in -9999L..9999L) {
            isGroupingUsed = false
        }
    }.format(sats)
}
