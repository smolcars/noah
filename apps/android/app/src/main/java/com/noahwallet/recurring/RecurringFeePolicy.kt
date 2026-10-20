package com.noahwallet.recurring

import kotlinx.serialization.Serializable

/** Captured at quote time; the final guard uses these same schedule values. */
@Serializable data class RecurringFeeSchedule(val minFeeSats: Long, val baseFeeSats: Long, val maxPpm: Long)
object RecurringFeePolicy {
    data class Decision(val worstCaseFeeSat: Long?, val capSat: Long?, val autoPay: Boolean, val reason: String)
    private fun add(a: Long, b: Long) = if (a > Long.MAX_VALUE - b) Long.MAX_VALUE else a + b
    private fun multiply(a: Long, b: Long) = if (b != 0L && a > Long.MAX_VALUE / b) Long.MAX_VALUE else a * b
    fun evaluate(amount: Long, method: String, quoteFee: Long, schedule: RecurringFeeSchedule?): Decision {
        require(amount > 0 && quoteFee >= 0)
        if (method == "ark") return Decision(null, null, quoteFee == 0L, if (quoteFee == 0L) "within_cap" else "ark_fee")
        val s = schedule ?: RecurringFeeSchedule(20, 0, 5000)
        require(s.minFeeSats >= 0 && s.baseFeeSats >= 0 && s.maxPpm >= 0)
        // Divide first without losing the remainder, avoiding a saturated intermediate product.
        val whole = multiply(amount / 1_000_000, s.maxPpm)
        val rest = multiply(amount % 1_000_000, s.maxPpm)
        val ppm = if (rest == Long.MAX_VALUE) Long.MAX_VALUE else add(whole, rest / 1_000_000 + if (rest % 1_000_000 == 0L) 0 else 1)
        val worst = maxOf(s.minFeeSats, add(s.baseFeeSats, ppm))
        val cap = minOf(multiply(worst, 2), maxOf(200, amount / 50))
        val reason = when { quoteFee > cap || quoteFee == Long.MAX_VALUE -> "quote_exceeds_cap"; worst > cap || worst == Long.MAX_VALUE -> "schedule_exceeds_cap"; else -> "within_cap" }
        return Decision(worst, cap, reason == "within_cap", reason)
    }
    fun approvalIssue(amount: Long, method: String, fee: Long, schedule: RecurringFeeSchedule?): RecurringIssue? {
        val d = evaluate(amount, method, fee, schedule)
        if (d.autoPay) return null
        return when (d.reason) {
            "quote_exceeds_cap" -> RecurringIssue(RecurringIssue.Code.quoteExceedsCap, listOf(fee.toString(), d.capSat.toString()))
            "schedule_exceeds_cap" -> RecurringIssue(RecurringIssue.Code.scheduleExceedsCap, listOf(d.worstCaseFeeSat.toString(), d.capSat.toString()))
            else -> RecurringIssue(RecurringIssue.Code.arkFee)
        }
    }
    fun approvalReason(amount: Long, method: String, fee: Long, schedule: RecurringFeeSchedule?): String? {
        val d = evaluate(amount, method, fee, schedule)
        if (d.autoPay) return null
        return when (d.reason) {
            "quote_exceeds_cap" -> "The fee for this payment ($fee sats) is above your automatic limit of ${d.capSat} sats. Review it to pay manually."
            "schedule_exceeds_cap" -> "Ark's published Lightning fees for this amount can reach ${d.worstCaseFeeSat} sats, above your automatic limit of ${d.capSat} sats. Review it to pay manually."
            else -> "The fee for this Ark payment must be 0 sats to pay automatically. Review it to pay manually."
        }
    }
}
