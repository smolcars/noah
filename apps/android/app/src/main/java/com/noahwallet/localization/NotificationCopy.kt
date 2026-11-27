package com.noahwallet.localization

import android.content.Context
import com.noahwallet.formatting.BitcoinAmount
import com.noahwallet.formatting.BitcoinAmountUnit
import com.noahwallet.services.storage.LegacyLocale
import java.util.Locale

data class NotificationCopy(val title: String, val body: String) {
    companion object {
        fun received(context: Context, sats: Long, usd: String?, unit: BitcoinAmountUnit): NotificationCopy {
            val bitcoin = BitcoinAmount.format(sats, unit, Locale.forLanguageTag(LegacyLocale.resolved(context)))
            val amount = bitcoin + (usd?.let { " (≈ US$$it)" } ?: "")
            return NotificationCopy(L10n.text(context, "Lightning Payment Received! ⚡"), L10n.text(context, "You received {amount}", amount))
        }

        /** A Lightning address payment: from [payer] when the payer named themselves, with their [comment]. */
        fun lnurlPayReceived(context: Context, sats: Long, unit: BitcoinAmountUnit, payer: String?, comment: String?): NotificationCopy {
            val amount = BitcoinAmount.format(sats, unit, Locale.forLanguageTag(LegacyLocale.resolved(context)))
            return if (payer == null) NotificationCopy(L10n.text(context, "Lightning Payment Received! ⚡"),
                listOfNotNull(L10n.text(context, "You received {amount}", amount), comment).joinToString("\n"))
            else NotificationCopy(payer, listOfNotNull(L10n.text(context, "Sent you {amount}", amount), comment).joinToString("\n"))
        }

        fun reminder(context: Context, amount: String, recipient: String, upcoming: Boolean): NotificationCopy =
            NotificationCopy(L10n.text(context, if (upcoming) "Upcoming recurring payment" else "Recurring payment waiting"),
                if (upcoming) L10n.text(context, "{amount} to {recipient} will be sent tomorrow.", amount, recipient)
                else L10n.text(context, "Open Noah to send {amount} to {recipient}.", amount, recipient))
    }
}
