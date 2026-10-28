package com.noahwallet.recurring

import kotlinx.serialization.Serializable
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone

@Serializable data class RecurringInterval(val unit: String, val every: Int) {
    val valid get() = unit in listOf("day", "week", "month") && every in 1..(if (unit == "day") 365 else 12)
    val description get() = if (every == 1) when (unit) { "day" -> "Daily"; "week" -> "Weekly"; else -> "Monthly" } else "Every $every ${unit}s"
}
@Serializable data class RecurringPayment(
    val id: String, val label: String, val destination: String, val destinationType: String, val amountSat: Long,
    val comment: String = "", val interval: RecurringInterval, val startAt: Long,
    val endAt: Long? = null, val maxOccurrences: Long? = null, val nativeRevision: String? = null,
    val status: String = "active", val nextOccurrenceIndex: Int = 0, val nextRunAt: Long? = startAt,
    val occurrencesPaid: Int = 0, val consecutiveFailures: Int = 0, val lastError: String? = null, val issue: RecurringIssue? = null,
    val retryNotBefore: Long? = null, val inFlight: InFlight? = null, val runs: List<Run> = emptyList(),
    val createdAt: Long, val updatedAt: Long,
    @kotlinx.serialization.Transient val unsupportedStorage: Boolean = false,
    @kotlinx.serialization.Transient val stopReason: StopReason? = null,
) {
    enum class StopReason { policyEvidence, automationStopped }
    val requiresPolicyApproval get() = stopReason == StopReason.policyEvidence || issue?.code == RecurringIssue.Code.policyEvidence
    fun decodeLegacyIssue(): RecurringPayment {
        val decoded = (issue ?: lastError?.let(RecurringIssue::legacy))?.let { if (it.valid) it else RecurringIssue(RecurringIssue.Code.unknown) }
        val approval = decoded?.code in listOf(RecurringIssue.Code.quoteExceedsCap, RecurringIssue.Code.scheduleExceedsCap, RecurringIssue.Code.arkFee)
        return when {
            decoded == null -> this
            decoded.code == RecurringIssue.Code.retryableFailure -> copy(issue = decoded)
            approval -> copy(issue = decoded, status = if (status == "active") "needs_approval" else status, retryNotBefore = null)
            else -> copy(issue = decoded, status = if (status in listOf("active", "needs_approval")) "needs_attention" else status,
                retryNotBefore = if (status in listOf("active", "needs_approval")) null else retryNotBefore)
        }
    }
    companion object {
        const val unsupportedMessage = "Noah can't safely read this schedule. Cancel it and explicitly approve a new recurring payment."
        const val maximumAmount = 2_100_000_000_000_000L
        const val amountLimitMessage = "This amount exceeds 21 million BTC. Cancel this schedule and approve a new payment within the limit."
    }
    @Serializable data class InFlight(val occurrenceIndex: Int, val startedAt: Long)
    @Serializable data class Run(val occurrenceIndex: Int, val scheduledFor: Long, val attemptedAt: Long,
        val status: String, val amountSat: Long, val error: String? = null)
    @Serializable data class Plan(val kind: String, val occurrenceIndex: Int? = null, val scheduledFor: Long? = null,
        val skippedIndices: List<Int>? = null)
    val validPolicy get() = label.isNotBlank() && label.length <= 64 && amountSat > 0 &&
        destinationType in listOf("ark", "lnurl", "offer") && destination.isNotEmpty() && interval.valid &&
        status in listOf("active", "paused", "needs_attention", "needs_approval", "completed") &&
        startAt in -8_639_999_999_999_999L..8_639_999_999_999_999L && (endAt == null || endAt >= startAt) &&
        (maxOccurrences == null || maxOccurrences >= 1) && nextOccurrenceIndex in 0..1_000_000 &&
        occurrencesPaid >= 0 && consecutiveFailures >= 0
    fun validateInput(now: Long) {
        require(!unsupportedStorage) { unsupportedMessage }
        require(label.isNotBlank() && label.length <= 64) { "Give this payment a short name (max 64 characters)." }
        require(amountSat in 1..maximumAmount) { "Enter a whole amount in sats, up to 21 million BTC." }
        require(validPolicy) { "Enter an Ark address, Lightning address or BOLT12 offer and a valid interval." }
        require(startAt >= now - 300_000) { "The first payment can't be in the past." }
    }
    fun occurrence(index: Int, zone: TimeZone = TimeZone.getDefault()): Long {
        require(index >= 0 && interval.valid)
        val start = GregorianCalendar(zone).apply { timeInMillis = startAt }
        val wall = GregorianCalendar(TimeZone.getTimeZone("""UTC""")).apply {
            clear(); set(start.get(Calendar.YEAR), start.get(Calendar.MONTH), start.get(Calendar.DAY_OF_MONTH),
                start.get(Calendar.HOUR_OF_DAY), start.get(Calendar.MINUTE), start.get(Calendar.SECOND))
            set(Calendar.MILLISECOND, start.get(Calendar.MILLISECOND))
        }
        val steps = Math.multiplyExact(index, interval.every)
        if (interval.unit == "month") {
            val day = wall.get(Calendar.DAY_OF_MONTH)
            wall.set(Calendar.DAY_OF_MONTH, 1); wall.add(Calendar.MONTH, steps)
            wall.set(Calendar.DAY_OF_MONTH, minOf(day, wall.getActualMaximum(Calendar.DAY_OF_MONTH)))
        } else wall.add(Calendar.DAY_OF_MONTH, Math.multiplyExact(steps, if (interval.unit == "week") 7 else 1))
        val local = wall.timeInMillis
        // JS compatible: first fold, forward through a gap (including non-hour transitions).
        val candidates = (-2..2).map { zone.getOffset(local + it * 86_400_000L) }.distinct().map { local - it }
        return candidates.filter { it + zone.getOffset(it) == local }.minOrNull()
            ?: candidates.filter { it + zone.getOffset(it) > local }.minBy { it + zone.getOffset(it) - local }
    }
    fun next(index: Int, zone: TimeZone = TimeZone.getDefault()): Long? {
        if (maxOccurrences != null && index >= maxOccurrences) return null
        return occurrence(index, zone).takeIf { endAt == null || it <= endAt }
    }
    fun plan(now: Long, zone: TimeZone = TimeZone.getDefault(), ignoreBackoff: Boolean = false): Plan {
        if (status != "active") return Plan("idle")
        inFlight?.let { return Plan(if (now - it.startedAt > 600_000) "stale_in_flight" else "idle") }
        val first = next(nextOccurrenceIndex, zone) ?: return Plan("complete")
        if (first > now || (!ignoreBackoff && (retryNotBefore ?: 0) > now)) return Plan("idle")
        return duePlan(now, zone)
    }
    /** The latest due occurrence, skipping at most 5,000 missed ones. */
    private fun duePlan(now: Long, zone: TimeZone): Plan {
        var latest = nextOccurrenceIndex
        for (ignored in 0 until 5000) { if (next(latest + 1, zone)?.let { it <= now } == true) latest++ else break }
        if (next(latest + 1, zone)?.let { it <= now } == true) return Plan("too_many_missed")
        return Plan("pay", latest, occurrence(latest, zone), (nextOccurrenceIndex until latest).toList())
    }
    fun advance(index: Int, zone: TimeZone): RecurringPayment {
        val next = next(index, zone)
        return copy(nextOccurrenceIndex = index, nextRunAt = next, status = if (next == null) "completed" else status)
    }
    fun resume(now: Long, zone: TimeZone): RecurringPayment {
        val active = copy(status = "active", inFlight = null, retryNotBefore = null, lastError = null, issue = null, consecutiveFailures = 0, updatedAt = now)
        return active.plan(now, zone, true).occurrenceIndex?.let { active.advance(it + 1, zone) } ?: active
    }
    fun success(plan: Plan, now: Long, zone: TimeZone): RecurringPayment {
        val added = plan.skippedIndices.orEmpty().map { Run(it, occurrence(it, zone), now, "skipped", amountSat) } +
            Run(plan.occurrenceIndex!!, plan.scheduledFor!!, now, "success", amountSat)
        return copy(runs = (added.reversed() + runs).take(24), occurrencesPaid = occurrencesPaid + 1,
            consecutiveFailures = 0, lastError = null, issue = null, retryNotBefore = null, inFlight = null, updatedAt = now).advance(plan.occurrenceIndex + 1,
            zone)
    }
    fun failure(plan: Plan, message: String, retryable: Boolean, now: Long, issue: RecurringIssue? = null): RecurringPayment {
        val failures = consecutiveFailures + 1
        return copy(inFlight = null, consecutiveFailures = failures, lastError = message,
            issue = issue ?: RecurringIssue(if (retryable) RecurringIssue.Code.retryableFailure else RecurringIssue.Code.uncertain), updatedAt = now,
            status = if (retryable) status else "needs_attention",
            retryNotBefore = if (retryable) now + minOf(900_000L * (1L shl (failures.coerceIn(1, 16) - 1)), 21_600_000) else null,
            runs = (listOf(Run(plan.occurrenceIndex!!, plan.scheduledFor!!, now, "failed", amountSat, message)) + runs).take(24))
    }
}

internal fun RecurringPayment.formattedAmount(unit: com.noahwallet.formatting.BitcoinAmountUnit, locale: java.util.Locale): String =
    com.noahwallet.formatting.BitcoinAmount.format(amountSat, unit, locale)
