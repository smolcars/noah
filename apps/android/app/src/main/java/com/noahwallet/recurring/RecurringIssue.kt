package com.noahwallet.recurring

import android.content.Context
import com.noahwallet.localization.L10n
import kotlinx.serialization.Serializable

/** Persist identifiers and quote arguments; resolve copy only at the display boundary. */
@Serializable data class RecurringIssue(val code: Code, val arguments: List<String> = emptyList()) {
    @Serializable enum class Code {
        policyEvidence, automationStopped, legacyHistory, invalidJournal, interrupted, tooManyMissed,
        quoteExceedsCap, scheduleExceedsCap, arkFee, uncertain, retryableFailure, alreadyPaid,
        unsupported, amountLimit, unknown
    }
    val valid get() = arguments.size == (if (code in listOf(Code.quoteExceedsCap, Code.scheduleExceedsCap)) 2 else 0) &&
        arguments.all { it.toLongOrNull()?.let { value -> value >= 0 } == true }
    fun message(context: Context): String = when (code) {
        Code.quoteExceedsCap -> L10n.text(context, "The fee for this payment ({fee} sats) is above your automatic limit of {cap} sats. Review it to pay manually.", arguments[0], arguments[1])
        Code.scheduleExceedsCap -> L10n.text(context, "Ark's published Lightning fees for this amount can reach {fee} sats, above your automatic limit of {cap} sats. Review it to pay manually.", arguments[0], arguments[1])
        Code.policyEvidence -> L10n.text(context, RecurringPolicyLedger.message)
        Code.automationStopped -> L10n.text(context, RecurringAutomationAuthority.message)
        Code.legacyHistory -> L10n.text(context, RecurringJournal.legacyMessage)
        Code.invalidJournal -> L10n.text(context, RecurringJournal.invalidMessage)
        Code.interrupted -> L10n.text(context, "Noah was interrupted while sending this payment. Check your transaction history before resuming.")
        Code.tooManyMissed -> L10n.text(context, "This schedule has more than 5,000 missed occurrences. Cancel it and create a schedule with a future first payment.")
        Code.arkFee -> L10n.text(context, "The fee for this Ark payment must be 0 sats to pay automatically. Review it to pay manually.")
        Code.uncertain -> L10n.text(context, "This payment may have been sent. Check its status before making another payment.")
        Code.alreadyPaid -> L10n.text(context, "This invoice was already paid. This occurrence was not sent again. Check the recipient before resuming.")
        Code.retryableFailure -> L10n.text(context, "This payment could not be prepared. No payment was submitted.")
        Code.amountLimit -> L10n.text(context, RecurringPayment.amountLimitMessage)
        Code.unsupported, Code.unknown -> L10n.text(context, RecurringPayment.unsupportedMessage)
    }
    companion object {
        // Only legacy decoding may recognize English copy. Unknown text never establishes safety.
        fun legacy(text: String): RecurringIssue {
            val fixed = mapOf(
                RecurringPolicyLedger.message to Code.policyEvidence,
                RecurringAutomationAuthority.message to Code.automationStopped,
                RecurringJournal.legacyMessage to Code.legacyHistory,
                RecurringJournal.invalidMessage to Code.invalidJournal,
                "Noah was interrupted while sending this payment. Check your transaction history before resuming." to Code.interrupted,
                "This schedule has more than 5,000 missed occurrences. Cancel it and create a schedule with a future first payment." to Code.tooManyMissed,
                "The fee for this Ark payment must be 0 sats to pay automatically. Review it to pay manually." to Code.arkFee,
                "This payment may have been sent. Check its status before making another payment." to Code.uncertain,
                "This invoice was already paid. This occurrence was not sent again. Check the recipient before resuming." to Code.alreadyPaid,
                "This payment could not be prepared. No payment was submitted." to Code.retryableFailure,
                "No payment was submitted." to Code.retryableFailure,
                RecurringPayment.unsupportedMessage to Code.unsupported,
                RecurringPayment.amountLimitMessage to Code.amountLimit
            )
            fixed[text]?.let { return RecurringIssue(it) }
            for ((pattern, code) in listOf(
                """^The fee for this payment \(([0-9]+) sats\) is above your automatic limit of ([0-9]+) sats\. Review it to pay manually\.$""" to Code.quoteExceedsCap,
                """^Ark's published Lightning fees for this amount can reach ([0-9]+) sats, above your automatic limit of ([0-9]+) sats\. Review it to pay manually\.$""" to Code.scheduleExceedsCap
            )) {
                Regex(pattern).matchEntire(text)?.let { match ->
                    val issue = RecurringIssue(code, match.groupValues.drop(1))
                    if (issue.valid) return issue
                }
            }
            return RecurringIssue(Code.unknown)
        }
    }
}
