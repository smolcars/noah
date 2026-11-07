package com.noahwallet.assistant

import android.content.Context
import android.icu.text.DisplayContext
import android.icu.text.RelativeDateTimeFormatter
import android.icu.util.ULocale
import androidx.annotation.RequiresApi
import com.noahwallet.localization.L10n
import java.util.Locale
import android.icu.text.RelativeDateTimeFormatter.RelativeDateTimeUnit as Span

/**
 * What Siri and Gemini say back (docs/native-rewrite/design/voice-assistants.md, Replies), in the app's language.
 * Amounts are always whole sats, whatever unit Home shows; apps/fixtures/assistant-replies.json pins the English.
 */
internal object AssistantReplies {
    /** The newest payment as a reply reads it: [time] is already relative ("2 hours ago"). */
    data class Payment(val incoming: Boolean, val sats: Long, val counterparty: String?, val time: String)

    /** [fiat]: the balance in the user's currency, already formatted; null when the app has no price or hides it. */
    fun balance(context: Context, sats: Long, fiat: String?): String =
        if (fiat == null) L10n.plural(context, "You have {sats} sats.", sats)
        else L10n.plural(context, "You have {sats} sats, about {fiat}.", sats, fiat)

    fun request(context: Context, sats: Long): String = L10n.plural(context, "Here's your Lightning invoice for {sats} sats.", sats)

    fun arkAddress(context: Context): String = L10n.text(context, "Here's a new Ark address.")

    fun lightningAddress(context: Context, address: String?): String =
        if (address == null) L10n.text(context, "You don't have a Lightning address yet. Open Noah to pick one.")
        else L10n.text(context, "Your Lightning address is {address}.", address)

    fun lastPayment(context: Context, payment: Payment?): String = when {
        payment == null -> L10n.text(context, "You don't have any payments yet.")
        payment.incoming -> L10n.plural(context, "You received {sats} sats {when}.", payment.sats, payment.time)
        payment.counterparty != null ->
            L10n.plural(context, "You sent {sats} sats to {counterparty} {when}.", payment.sats, payment.counterparty, payment.time)
        else -> L10n.plural(context, "You sent {sats} sats {when}.", payment.sats, payment.time)
    }

    fun pay(context: Context, recipient: String?): String =
        if (recipient == null) L10n.text(context, "Opening Send.") else L10n.text(context, "Opening Send to {recipient}.", recipient)

    fun unreachable(context: Context): String = L10n.text(context, "Noah couldn't reach the Ark server. Check your connection and try again.")

    /** Why a request was refused, and what to do next. */
    enum class Refusal { locked, notReady, tooLittle, tooMuch, longNote, recipient }

    fun refusal(context: Context, refusal: Refusal): String = when (refusal) {
        Refusal.locked -> L10n.text(context, "Unlock your device and try again.")
        Refusal.notReady -> L10n.text(context, "Noah's wallet isn't ready. Open Noah and try again.")
        Refusal.tooLittle -> L10n.text(context, "The amount must be at least one sat.")
        Refusal.tooMuch -> L10n.text(context, "Enter a whole amount in sats, up to 21 million BTC.")
        Refusal.longNote -> L10n.text(context, "Note must be 639 characters or fewer.")
        Refusal.recipient -> L10n.text(context, "Unable to resolve this recipient. Please check the address.")
    }

    /**
     * [time] relative to [now] the way it reads mid-sentence ("2 hours ago", "yesterday", "last week"): the largest
     * whole unit that has passed, as iOS's RelativeDateTimeFormatter picks it.
     */
    @RequiresApi(28)
    fun relative(time: Long, now: Long, locale: Locale): String {
        val seconds = ((now - time) / 1000).coerceAtLeast(0)
        val units = listOf(365 * DAY to Span.YEAR, 30 * DAY to Span.MONTH, 7 * DAY to Span.WEEK, DAY to Span.DAY,
            3_600L to Span.HOUR, 60L to Span.MINUTE, 1L to Span.SECOND)
        val (size, unit) = units.firstOrNull { seconds >= it.first } ?: units.last()
        return RelativeDateTimeFormatter.getInstance(ULocale.forLocale(locale), null, RelativeDateTimeFormatter.Style.LONG,
            DisplayContext.CAPITALIZATION_FOR_MIDDLE_OF_SENTENCE).format((-(seconds / size)).toDouble(), unit)
    }

    private const val DAY = 86_400L
}
