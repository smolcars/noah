package com.noahwallet.recurring

import com.noahwallet.send.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.TimeZone
import java.util.UUID

internal class RecurringWorld(val scope: CoroutineScope, val wallet: FakeSendWallet = FakeSendWallet(),
    reservation: (suspend (suspend (SendWallet) -> Unit) -> Unit)? = null) {
    var raw: String? = null
    val store = RecurringStore({ raw }, { if (mmkvFails) error("MMKV"); raw = it })
    var journalRaw: String? = null
    var journalFails = false; var mmkvFails = false; var auditFails = false
    val journal = RecurringJournal(File("unused"), { journalRaw }, { if (journalFails) error("journal"); journalRaw = it })
    init { wallet.owner = SendIdentity("wallet-a", "regtest"); wallet.snapshot = SendSnapshot(walletIdentity = wallet.owner) }
    val sendJournal = TestJournal()
    val controller = if (reservation == null) controller(wallet, sendJournal, scope) else SendController(SendController.Dependencies(
        { sendJournal.attempt }, sendJournal::write, recurringReservation = reservation, reservation = reservation), scope)
    var binding = RecurringStore.Binding(wallet.owner, UUID.randomUUID().toString())
    var allowed = true; var generation: String? = null; var now = 1000L; var zone = TimeZone.getTimeZone("UTC")
    var quotes = 0; var fee = 0L; var method = "ark"; var feeSchedule: RecurringFeeSchedule? = null
    var beforeQuote: suspend () -> Unit = {}; var sync: suspend () -> Unit = {}; var syncCalls = 0
    val notifications = mutableListOf<RecurringPayment>()
    var audits = 0
    val executor = RecurringExecutor(RecurringExecutor.Dependencies(store, journal, controller,
        ensureOpen = {}, binding = { binding }, allowed = { check(allowed) },
        quote = { s -> quotes++; beforeQuote(); SendPayment(s.destination, method, s.amountSat) to SendQuote(s.amountSat, fee, s.amountSat + fee, walletIdentity = binding.wallet, lightningFeeSchedule = feeSchedule) },
        automationGeneration = { generation }, now = { now }, zone = { zone },
        sync = { syncCalls++; sync() }, notify = { _, result -> result?.let { notifications.add(it) } },
        audit = { _, _, _, _ -> if (auditFails) error("audit"); audits++ }), scope)
    val schedule get() = store.load().schedules["rent"]
    fun add(start: Long = 1000): RecurringPayment {
        val schedule = RecurringPayment("rent", "Rent", "recipient", "ark", 1000, interval = RecurringInterval("day", 1), startAt = start, createdAt = 0, updatedAt = 0)
        store.bind(binding); store.upsert(schedule); return schedule
    }
    suspend fun change(action: String) {
        when (action) {
            "pause" -> executor.pause("rent")
            "cancel" -> executor.cancel("rent")
            "edit" -> store.upsert(schedule!!.copy(amountSat = 2000, nativeRevision = UUID.randomUUID().toString()))
            "suspend" -> { generation = UUID.randomUUID().toString(); allowed = false }
            "replace" -> { binding = binding.copy(wallet = SendIdentity("other", "regtest")) }
            "restore" -> { binding = binding.copy(instance = UUID.randomUUID().toString()) }
        }
    }
}

class RecurringExecutorTest {
    @Test fun pushForegroundAndRepeatedTriggersPayLatestDueOnlyOnce() = runBlocking {
        val w = RecurringWorld(this); w.add(1000 - 2 * 86_400_000)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        w.beforeQuote = { entered.complete(Unit); release.await() }
        val foreground = async { w.executor.execute() }; entered.await()
        val push = async { w.executor.execute() }; yield(); release.complete(Unit); foreground.await(); push.await()
        assertEquals(1, w.wallet.calls); assertEquals(1, w.schedule!!.occurrencesPaid)
        assertEquals(listOf("success", "skipped", "skipped"), w.schedule!!.runs.map { it.status })
        w.executor.execute(); assertEquals(1, w.wallet.calls)
        w.executor.pause("rent"); w.executor.execute(); w.executor.cancel("rent"); w.executor.execute()
        assertEquals(1, w.wallet.calls); assertNull(w.schedule)
    }
    @Test fun markersAtBothCrashBoundariesNeverAutomaticallyRetry() = runBlocking {
        for (mmkv in listOf(false, true)) {
            val w = RecurringWorld(this); val s = w.add()
            w.journal.save(RecurringJournal.Entry(w.binding, s.id, s.plan(w.now), w.now))
            if (mmkv) w.store.upsert(s.copy(inFlight = RecurringPayment.InFlight(0, w.now)))
            w.executor.execute(); w.executor.execute()
            assertEquals(0, w.wallet.calls); assertEquals("needs_attention", w.schedule!!.status)
        }
    }
    @Test fun ambiguousPaymentAndCancelledPolicyKeepSendLocked() = runBlocking {
        val w = RecurringWorld(this); w.add(); w.wallet.payBlock = { error("connection lost") }
        w.executor.execute(); w.executor.execute(); assertEquals(1, w.wallet.calls)
        assertEquals("needs_attention", w.schedule!!.status)
        w.executor.cancel("rent"); assertTrue(w.controller.locked); assertFalse(w.journal.load().getValue("rent").resolved)
    }
    @Test fun consumedWatermarkBlocksMMKVRollbackAcrossRevisions() = runBlocking {
        val w = RecurringWorld(this); val a = w.add(); val rollback = w.raw
        w.executor.execute(); assertEquals(1, w.wallet.calls)
        w.store.upsert(a.copy(nativeRevision = "revision-b", startAt = 2000, nextRunAt = 2000)); w.now = 2000
        w.executor.execute(); assertEquals(2, w.wallet.calls)
        w.raw = rollback; w.executor.execute(); assertEquals(2, w.wallet.calls); assertEquals("needs_attention", w.schedule!!.status)
    }
    @Test fun consumedIndexCannotReplayAfterTimeZoneShiftAndMMKVRollback() = runBlocking {
        val w = RecurringWorld(this)
        w.zone = TimeZone.getTimeZone("America/New_York"); w.now = 1775088000000
        val s = w.add(1772337600000).copy(interval = RecurringInterval("month", 1), nextOccurrenceIndex = 1, nextRunAt = 1774753200000)
        w.store.upsert(s); val rollback = w.raw
        w.executor.execute(); assertEquals(1, w.wallet.calls)
        w.raw = rollback; w.zone = TimeZone.getTimeZone("UTC")
        w.executor.execute(); assertEquals(1, w.wallet.calls); assertEquals("needs_attention", w.schedule!!.status)
    }
    @Test fun legacyTimestampWithOverwrittenRevisionCannotReplayAfterZoneRollback() = runBlocking {
        val w = RecurringWorld(this)
        w.zone = TimeZone.getTimeZone("America/New_York"); w.now = 1774753200000
        val a = w.add(1772337600000).copy(interval = RecurringInterval("month", 1), nextOccurrenceIndex = 1,
            nextRunAt = w.now, nativeRevision = UUID.randomUUID().toString())
        w.store.upsert(a); val rollback = w.raw
        w.executor.execute(); assertEquals(1, w.wallet.calls)
        // v1 retained A only as a timestamp after B's proven pre-submission failure.
        val b = RecurringJournal.Entry(w.binding, a.id, a.plan(w.now, w.zone), w.now,
            UUID.randomUUID().toString(), resolved = true)
        w.journalRaw = """{"version":1,"attempts":${RecurringStore.json.encodeToString(mapOf(a.id to b))},"consumed":${RecurringStore.json.encodeToString(listOf(RecurringJournal.Watermark(w.binding, a.id, w.now)))}}"""
        w.raw = rollback; w.zone = TimeZone.getTimeZone("UTC"); w.now = 1775016000000
        assertEquals(w.now, a.plan(w.now, w.zone).scheduledFor)
        w.executor.execute()
        assertEquals("Paid monthly index must never be submitted again", 1, w.wallet.calls)
        assertEquals("needs_attention", w.schedule!!.status)
        assertEquals(RecurringJournal.legacyMessage, w.schedule!!.lastError)
        assertTrue(runCatching { w.executor.overrideAndResume(a.id, true) }.isFailure)
        assertTrue(runCatching { w.executor.resume(a.id) }.isFailure)
        w.store.upsert(a.copy(nativeRevision = UUID.randomUUID().toString()))
        w.executor.execute(); assertEquals(1, w.wallet.calls); assertEquals("needs_attention", w.schedule!!.status)
        w.executor.cancel(a.id)
        val fresh = a.copy(id = UUID.randomUUID().toString(), startAt = w.now + 1000, nextOccurrenceIndex = 0, nextRunAt = w.now + 1000)
        w.executor.save(fresh, editing = false); w.now += 1000
        w.executor.execute(); assertEquals(2, w.wallet.calls)
    }
    @Test fun consumedDailyIndexSurvivesSpringAndFallDSTZoneRollback() = runBlocking {
        for (fall in listOf(false, true)) {
            val w = RecurringWorld(this)
            val start = java.time.Instant.parse(if (fall) "2026-10-31T16:00:00Z" else "2026-03-07T17:00:00Z").toEpochMilli()
            val from = TimeZone.getTimeZone(if (fall) "UTC" else "America/New_York")
            val to = TimeZone.getTimeZone(if (fall) "America/New_York" else "UTC")
            val a = w.add(start).copy(nextOccurrenceIndex = 1)
            val paidAt = a.occurrence(1, from); val shifted = a.occurrence(1, to)
            assertEquals(3_600_000L, shifted - paidAt)
            w.zone = from; w.now = paidAt; w.store.upsert(a); val rollback = w.raw
            w.executor.execute(); assertEquals(1, w.wallet.calls)
            w.raw = rollback; w.zone = to; w.now = shifted
            w.executor.execute(); assertEquals(1, w.wallet.calls); assertEquals("needs_attention", w.schedule!!.status)
        }
    }
    @Test fun legacyConsumptionMigratesBeforeLaterAttemptReplacesIt() {
        var raw: String? = null
        val binding = RecurringStore.Binding(SendIdentity("wallet", "regtest"), UUID.randomUUID().toString())
        val a = RecurringJournal.Entry(binding, "rent", RecurringPayment.Plan("pay", 0, 1000, emptyList()), 1000, resolved = true, consumed = true)
        raw = RecurringStore.json.encodeToString(mapOf("rent" to a))
        val j = RecurringJournal(File("unused"), { raw }, { raw = it })
        j.save(a.copy(plan = a.plan.copy(scheduledFor = 2000), consumed = false, resolved = false, policyRevision = "b"))
        assertTrue(raw!!.contains("\"version\":2")); assertTrue(j.consumed(binding, "rent", 1000)); assertTrue(j.consumed(binding, "rent", 2000))
    }
    @Test fun failedLegacyMigrationCannotOverwriteConsumption() {
        val binding = RecurringStore.Binding(SendIdentity("wallet", "regtest"), UUID.randomUUID().toString())
        val e = RecurringJournal.Entry(binding, "rent", RecurringPayment.Plan("pay", 0, 1000), 1000, resolved = true, consumed = true)
        val raw = RecurringStore.json.encodeToString(mapOf("rent" to e))
        val journal = RecurringJournal(File("unused"), { raw }, { error("disk") })
        assertTrue(runCatching { journal.save(e.copy(consumed = false)) }.isFailure)
        assertTrue(runCatching { journal.load() }.isFailure)
    }
    @Test fun corruptJournalAndFailedMarkerWritesNeverSubmit() = runBlocking {
        for (corrupt in listOf(false, true)) {
            val w = RecurringWorld(this); w.add()
            if (corrupt) w.journalRaw = "{broken" else w.journalFails = true
            w.executor.execute(); assertEquals(0, w.wallet.calls)
            if (corrupt) assertEquals("{broken", w.journalRaw)
        }
    }
    @Test fun mmkvMarkerFailureStopsBeforeSend() = runBlocking {
        val w = RecurringWorld(this); w.add(); w.mmkvFails = true
        w.executor.execute(); assertEquals(0, w.wallet.calls)
    }
    @Test fun failedReceiptStorageStillConsumesAndNeverRetries() = runBlocking {
        val w = RecurringWorld(this); w.add(); w.sendJournal.failReceipt = true
        w.executor.execute(); assertEquals(1, w.wallet.calls)
        assertTrue(w.journal.consumed(w.binding, "rent", 1000)); w.executor.execute(); assertEquals(1, w.wallet.calls)
    }
    @Test fun overCapNeedsApprovalWithoutAnyJournalOrConsumption() = runBlocking {
        val w = RecurringWorld(this); w.add(); w.method = "offer"; w.fee = 41
        w.executor.execute(); w.executor.execute()
        assertEquals("needs_approval", w.schedule!!.status); assertEquals(0, w.wallet.calls)
        assertNull(w.sendJournal.attempt); assertNull(w.journalRaw); assertEquals(1, w.notifications.size)
        assertEquals(0, w.schedule!!.consecutiveFailures)
    }
    @Test fun lowQuoteStillNeedsApprovalWhenScheduleExceedsCap() = runBlocking {
        val w = RecurringWorld(this); w.add(); w.method = "offer"; w.fee = 1; w.feeSchedule = RecurringFeeSchedule(300, 0, 0)
        w.executor.execute(); assertEquals("needs_approval", w.schedule!!.status); assertEquals(0, w.wallet.calls)
    }
    @Test fun lightningPaysAutomaticallyUnderCapturedRegtestCap() = runBlocking {
        val w = RecurringWorld(this); w.add(); w.method = "offer"; w.fee = 79; w.feeSchedule = RecurringFeeSchedule(10, 75, 8000)
        w.wallet.payBlock = { SendReceipt(1000, paymentHash = "hash", movementID = 1u, fee = 79, proof = SendReceipt.Proof.actualMovement) }
        w.executor.execute(); w.executor.execute(); assertEquals(1, w.wallet.calls); assertEquals(1, w.schedule!!.occurrencesPaid)
    }
    @Test fun explicitApprovalUsesExactQuoteAndSettlesOnce() = runBlocking {
        val w = RecurringWorld(this); w.add(); w.fee = 1
        w.executor.execute(); assertEquals("needs_approval", w.schedule!!.status)
        val review = w.executor.review("rent"); val quotes = w.quotes
        w.executor.execute(manual = true, approval = review); w.executor.execute(manual = true, approval = review)
        assertEquals(1, w.wallet.calls); assertEquals(quotes, w.quotes); assertEquals(1, w.schedule!!.occurrencesPaid)
    }
    @Test fun manualReviewCannotOutlivePause() = runBlocking {
        val w = RecurringWorld(this); w.add(); w.fee = 1; w.executor.execute()
        val review = w.executor.review("rent"); w.executor.pause("rent"); w.executor.execute(true, review)
        assertEquals(0, w.wallet.calls)
    }
    @Test fun manualReviewCannotApproveAnEditedOrResumedPolicy() = runBlocking {
        val w = RecurringWorld(this); val original = w.add(); w.fee = 1; w.executor.execute()
        val review = w.executor.review("rent")
        w.store.upsert(original.copy(destination = "different-recipient", nativeRevision = "new"))
        w.executor.execute(true, review); assertEquals(0, w.wallet.calls)
    }
    @Test fun pauseOrCancelDuringSubmittedPaymentWinsOverSettlement() = runBlocking {
        for (cancel in listOf(false, true)) {
            val w = RecurringWorld(this); w.add()
            w.wallet.payBlock = {
                if (cancel) w.executor.cancel("rent") else w.executor.pause("rent")
                SendReceipt(1000, proof = SendReceipt.Proof.submitReturnedArkSuccess)
            }
            w.executor.execute(); assertEquals(1, w.wallet.calls)
            if (cancel) assertNull(w.schedule) else { assertEquals("paused", w.schedule!!.status); assertEquals(1, w.schedule!!.occurrencesPaid) }
            assertTrue(w.journal.consumed(w.binding, "rent", 1000)); w.executor.execute(); assertEquals(1, w.wallet.calls)
        }
    }
    @Test fun finalGuardRejectsClockAndBackoffJumpsWithoutConsumptionOrUncertainty() = runBlocking {
        for (jump in listOf(-1L, 86_400_000L, -600L)) {
            val w = RecurringWorld(this); val s = w.add(); w.store.upsert(s.copy(retryNotBefore = 500))
            w.wallet.prepareBlock = { w.now += jump; w.wallet.snapshot }
            w.executor.execute(); assertEquals(0, w.wallet.calls); assertNull(w.schedule!!.inFlight)
            assertEquals("active", w.schedule!!.status); assertFalse(w.journal.consumed(w.binding, "rent", 1000)); assertFalse(w.controller.locked)
        }
    }
    @Test fun expiredBudgetDuringQuoteOrPreparationNeverSubmits() = runBlocking {
        for (prepare in listOf(false, true)) {
            val w = RecurringWorld(this); w.add(); var clock = 0L; val budget = RecurringBudget(100, { clock })
            if (prepare) w.wallet.prepareBlock = { clock = 100; w.wallet.snapshot } else w.beforeQuote = { clock = 100 }
            w.executor.execute(budget = budget); assertEquals(0, w.wallet.calls); assertFalse(w.journal.consumed(w.binding, "rent", 1000))
        }
    }
    @Test fun alreadySubmittedPaymentSettlesAfterPushDeadline() = runBlocking {
        val w = RecurringWorld(this); w.add(); var clock = 0L; val budget = RecurringBudget(100, { clock })
        w.wallet.payBlock = { clock = 200; SendReceipt(1000, proof = SendReceipt.Proof.submitReturnedArkSuccess) }
        w.executor.execute(budget = budget); assertEquals(1, w.wallet.calls); assertEquals(1, w.schedule!!.occurrencesPaid)
    }
    @Test fun cancelledCallerDoesNotAbandonSettlement() = runBlocking {
        val w = RecurringWorld(this); w.add(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        w.wallet.payBlock = { entered.complete(Unit); release.await(); SendReceipt(1000, proof = SendReceipt.Proof.submitReturnedArkSuccess) }
        val caller = launch { w.executor.execute() }; entered.await(); caller.cancelAndJoin(); release.complete(Unit)
        withTimeout(2000) { while (w.executor.running) yield() }
        assertEquals(1, w.schedule!!.occurrencesPaid); assertTrue(w.journal.consumed(w.binding, "rent", 1000))
    }
    @Test fun overrideRequiresConfirmationAndDurableAuditAndSkipsDue() = runBlocking {
        val w = RecurringWorld(this); val s = w.add(); w.store.upsert(s.copy(status = "needs_attention", inFlight = RecurringPayment.InFlight(0, 0)))
        w.executor.overrideAndResume("rent", false); assertNotNull(w.schedule!!.inFlight)
        w.auditFails = true; assertTrue(runCatching { w.executor.overrideAndResume("rent", true) }.isFailure); assertNotNull(w.schedule!!.inFlight)
        w.auditFails = false; w.executor.overrideAndResume("rent", true)
        assertEquals(1, w.audits); assertEquals(1, w.schedule!!.nextOccurrenceIndex); assertNull(w.schedule!!.inFlight)
        w.executor.execute(); assertEquals(0, w.wallet.calls)
    }
    @Test fun timezoneRefreshUpdatesPersistedDatesWakePayloadAndReminders() = runBlocking {
        val w = RecurringWorld(this); val s = w.add(1772337600000).copy(interval = RecurringInterval("month", 1), nextOccurrenceIndex = 1, nextRunAt = 1774753200000)
        w.store.upsert(s); w.zone = TimeZone.getTimeZone("UTC")
        w.executor.execute(); assertEquals(1775016000000, w.schedule!!.nextRunAt)
        assertEquals(1775016000, RecurringExecutor.entries(listOf(w.schedule!!)).single().nextRunAt)
    }
    @Test fun syncSerializesReplacementAndRetainsPendingRetry() = runBlocking {
        val w = RecurringWorld(this); w.add(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        w.sync = { if (w.syncCalls == 1) { entered.complete(Unit); release.await() } }
        val first = async { w.executor.syncServer() }; entered.await()
        w.store.upsert(w.schedule!!.copy(status = "paused")); val second = async { w.executor.syncServer() }; yield(); release.complete(Unit)
        first.await(); second.await(); assertEquals(2, w.syncCalls); assertFalse(w.store.load().pending)
        w.sync = { error("offline") }; w.executor.syncServer(); assertTrue(w.store.load().pending)
        val payload = Json.encodeToString(com.noahwallet.api.generated.models.SyncRecurringPaymentsPayload(RecurringExecutor.entries(listOf(w.schedule!!.copy(status = "active")))))
        assertFalse(payload.contains("recipient")); assertFalse(payload.contains("amount")); assertTrue(payload.contains("schedule_id"))
    }
}
