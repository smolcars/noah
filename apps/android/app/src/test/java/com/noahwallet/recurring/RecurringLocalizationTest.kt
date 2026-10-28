package com.noahwallet.recurring

import com.noahwallet.localization.L10n
import com.noahwallet.services.storage.LegacyLocale
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecurringLocalizationTest {
    private val languages = listOf("en", "es", "ja", "ko", "sv")
    private val context get() = RuntimeEnvironment.getApplication()
    private fun language(value: String) { context.getSharedPreferences("gt_store", 0).edit().putString(LegacyLocale.key, value).commit() }
    private fun display() = L10n.text(context, "Needs attention")
    private fun cleanup(w: RecurringWorld) { w.policyDirectory.parentFile!!.deleteRecursively() }

    @Test fun policyStopSurvivesBothRecoveryLoopsAndClock() = runBlocking {
        for (locale in languages) for (corrupt in listOf(false, true)) {
            language(locale); val w = RecurringWorld(this)
            try {
                val s = w.add().copy(status = "needs_attention", issue = RecurringIssue(RecurringIssue.Code.policyEvidence),
                    lastError = display(), nextRunAt = 1123)
                w.store.upsert(s)
                if (corrupt) w.journalRaw = "{broken"
                else w.journal.save(RecurringJournal.Entry(w.binding, s.id, s.copy(status = "active").plan(w.now, w.zone), w.now))
                w.executor.timeChanged(); w.executor.execute(); w.executor.execute(manual = true)
                val stopped = w.schedule!!
                assertTrue(stopped.requiresPolicyApproval); assertEquals("needs_attention", stopped.status)
                assertEquals(s.lastError, stopped.lastError); assertEquals(s.nextRunAt, stopped.nextRunAt)
                assertEquals(0, w.wallet.calls)
            } finally { cleanup(w) }
        }
    }
    @Test fun explicitResumeAndEditUseIdentifier() = runBlocking {
        for (locale in languages) for (edit in listOf(false, true)) {
            language(locale); val w = RecurringWorld(this)
            try {
                val s = w.add().copy(status = "needs_attention", issue = RecurringIssue(RecurringIssue.Code.policyEvidence), lastError = display())
                w.store.upsert(s)
                if (edit) w.executor.save(s, true) else w.executor.resume(s.id)
                assertEquals("active", w.schedule!!.status); assertFalse(w.schedule!!.requiresPolicyApproval)
                assertNull(w.schedule!!.issue); assertNotEquals(s.nativeRevision, w.schedule!!.nativeRevision)
                w.executor.execute(); assertEquals(0, w.wallet.calls)
            } finally { cleanup(w) }
        }
    }
    @Test fun legacyHistoryStaysLatchedWithTranslatedCopy() = runBlocking {
        for (locale in languages) {
            language(locale); val w = RecurringWorld(this)
            try {
                val s = w.add()
                w.journal.save(RecurringJournal.Entry(w.binding, s.id, s.plan(w.now, w.zone), w.now, resolved = true))
                val root = RecurringStore.json.parseToJsonElement(w.journalRaw!!).jsonObject
                w.journalRaw = JsonObject(root + ("version" to JsonPrimitive(2))).toString()
                w.executor.execute(); assertEquals(RecurringJournal.legacyReason, w.schedule!!.issue)
                w.store.upsert(w.schedule!!.copy(lastError = display()))
                val notified = w.notifications.size
                w.executor.execute(); w.executor.execute(manual = true)
                assertEquals(notified, w.notifications.size); assertEquals(0, w.wallet.calls)
                assertEquals(display(), w.schedule!!.lastError)
                assertTrue(runCatching { w.executor.resume(s.id) }.isFailure)
            } finally { cleanup(w) }
        }
    }
    @Test fun allFeeApprovalReasonsStoreArgumentsAndNeverAutoRetry() = runBlocking {
        for (locale in languages) for (kind in listOf("ark", "quote", "schedule")) {
            language(locale); val w = RecurringWorld(this)
            try {
                w.add(); w.method = if (kind == "ark") "ark" else "lightning"; w.fee = if (kind == "schedule") 0 else 500
                if (kind == "schedule") w.feeSchedule = RecurringFeeSchedule(500, 0, 0)
                w.executor.execute(); w.executor.execute(manual = true)
                val s = w.schedule!!
                assertEquals("needs_approval", s.status); assertNull(s.retryNotBefore); assertEquals(0, w.wallet.calls)
                assertEquals(when (kind) { "ark" ->
                    RecurringIssue.Code.arkFee
                    "quote" -> RecurringIssue.Code.quoteExceedsCap
                    else -> RecurringIssue.Code.scheduleExceedsCap
                }, s.issue!!.code)
                assertTrue(s.issue.valid); assertTrue(w.journal.load().isEmpty())
                if (kind != "ark") assertEquals(listOf("500", if (kind == "quote") "40" else "200"), s.issue.arguments)
                assertTrue(s.issue.message(context).isNotEmpty())
            } finally { cleanup(w) }
        }
    }
    @Test fun retryGateAndAmbiguityLatchIgnoreCopy() = runBlocking {
        for (locale in languages) {
            language(locale); val w = RecurringWorld(this)
            try {
                val s = w.add(); w.store.upsert(s.failure(s.plan(w.now, w.zone), display(), true, w.now))
                w.executor.execute(); assertEquals(0, w.wallet.calls)
                assertEquals(RecurringIssue.Code.retryableFailure, w.schedule!!.issue!!.code)
                w.now += 900_000; w.wallet.payBlock = { throw com.noahwallet.send.SendFailure(com.noahwallet.send.SendFailure.Reason.unresolved) }
                w.executor.execute(); w.executor.execute(manual = true)
                assertEquals(1, w.wallet.calls); assertEquals("needs_attention", w.schedule!!.status)
                assertEquals(RecurringIssue.Code.uncertain, w.schedule!!.issue!!.code)
                assertNull(w.schedule!!.retryNotBefore); assertTrue(w.controller.locked)
                assertTrue(runCatching { w.executor.resume(s.id) }.isFailure)
            } finally { cleanup(w) }
        }
    }
    @Test fun legacyEnglishDecodeAndUnknownTextFailClosed() = runBlocking {
        for (locale in languages) {
            language(locale); val w = RecurringWorld(this)
            try {
                val original = w.add(); val raw = w.raw!!
                val cases = listOf(
                    RecurringPolicyLedger.message to RecurringIssue.Code.policyEvidence,
                    RecurringJournal.legacyMessage to RecurringIssue.Code.legacyHistory,
                    RecurringJournal.invalidMessage to RecurringIssue.Code.invalidJournal,
                    RecurringAutomationAuthority.message to RecurringIssue.Code.automationStopped,
                    RecurringPayment.unsupportedMessage to RecurringIssue.Code.unsupported,
                    RecurringPayment.amountLimitMessage to RecurringIssue.Code.amountLimit,
                    "Noah was interrupted while sending this payment. Check your transaction history before resuming." to RecurringIssue.Code.interrupted,
                    "This schedule has more than 5,000 missed occurrences. " +
                        "Cancel it and create a schedule with a future first payment." to RecurringIssue.Code.tooManyMissed,
                    "This payment may have been sent. Check its status before making another payment." to RecurringIssue.Code.uncertain,
                    "This invoice was already paid. This occurrence was not sent again. " +
                        "Check the recipient before resuming." to RecurringIssue.Code.alreadyPaid,
                    "The fee for this Ark payment must be 0 sats to pay automatically. Review it to pay manually." to RecurringIssue.Code.arkFee,
                    "The fee for this payment (41 sats) is above your automatic limit of 40 sats. " +
                        "Review it to pay manually." to RecurringIssue.Code.quoteExceedsCap,
                    "Ark's published Lightning fees for this amount can reach 500 sats, above your automatic limit of 200 sats. " +
                        "Review it to pay manually." to RecurringIssue.Code.scheduleExceedsCap,
                    "No payment was submitted." to RecurringIssue.Code.retryableFailure,
                    "This payment could not be prepared. No payment was submitted." to RecurringIssue.Code.retryableFailure,
                    (display() + " unknown") to RecurringIssue.Code.unknown)
                for ((message, code) in cases) {
                    val root = RecurringStore.json.parseToJsonElement(raw).jsonObject
                    val state = root.getValue("state").jsonObject; val rows = state.getValue("schedules").jsonObject
                    val row = JsonObject(rows.getValue(original.id).jsonObject - "issue" + mapOf("lastError" to JsonPrimitive(message),
                        "retryNotBefore" to JsonPrimitive(1123)))
                    val legacy = JsonObject(root + ("state" to JsonObject(state + ("schedules" to JsonObject(rows + (original.id to row)))))).toString()
                    var stored = legacy
                    val decoded = RecurringStore({ stored }, { stored = it }).load().schedules.getValue(original.id)
                    assertEquals(code, decoded.issue!!.code); assertTrue(decoded.issue.valid)
                    if (code == RecurringIssue.Code.retryableFailure) { assertEquals("active", decoded.status); assertNotNull(decoded.retryNotBefore) }
                    else {
                        assertNotEquals("active", decoded.status)
                        assertNull(decoded.retryNotBefore)
                        assertEquals("idle", decoded.plan(w.now, w.zone).kind)
                    }
                    assertEquals(legacy, stored)
                }
            } finally { cleanup(w) }
        }
    }
    @Test fun unknownCodesAndMalformedArgumentsStop() = runBlocking {
        for (locale in languages) {
            language(locale); val w = RecurringWorld(this)
            try {
                val s = w.add().copy(issue = RecurringIssue(RecurringIssue.Code.quoteExceedsCap, listOf("bogus")), lastError = display())
                w.store.upsert(s); assertEquals(RecurringIssue.Code.unknown, w.schedule!!.issue!!.code)
                assertEquals("needs_attention", w.schedule!!.status)
                w.executor.execute(); assertEquals(0, w.wallet.calls)
                w.raw = w.raw!!.replace("quoteExceedsCap", "futureReason")
                assertEquals("needs_attention", w.schedule!!.status)
            } finally { cleanup(w) }
        }
    }
    @Test fun unknownJournalOutcomeQuarantinesAndStorageFailureLatches() = runBlocking {
        for (locale in languages) {
            language(locale); val w = RecurringWorld(this)
            try {
                w.add(); w.executor.execute(); assertEquals(1, w.wallet.calls)
                w.journalRaw = w.journalRaw!!.replace("succeeded", "futureOutcome")
                w.executor.execute(); w.executor.execute(manual = true)
                assertEquals(1, w.wallet.calls); assertEquals("needs_attention", w.schedule!!.status)
                assertNotNull(w.quarantineRaw)
                assertTrue(runCatching { w.executor.resume("rent") }.isFailure)
            } finally { cleanup(w) }
            val failed = RecurringWorld(this)
            try {
                failed.add(); failed.mmkvFails = true; failed.fee = 1
                failed.executor.execute(); failed.mmkvFails = false; failed.fee = 0
                failed.executor.execute(manual = true); assertEquals(0, failed.wallet.calls)
            } finally { cleanup(failed) }
        }
    }
}
