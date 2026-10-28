package com.noahwallet.recurring

import com.noahwallet.send.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.TimeZone
import java.util.UUID

internal class RecurringWorld(val scope: CoroutineScope, val wallet: FakeSendWallet = FakeSendWallet(),
    reservation: (suspend (suspend (SendWallet) -> Unit) -> Unit)? = null) {
    var beforeWrite: (String) -> Unit = {}
    var beforeJournalWrite: (String) -> Unit = {}
    var beforeRead: () -> Unit = {}
    var beforeJournalRead: () -> Unit = {}
    var beforeBinding: () -> Unit = {}
    var beforeGeneration: () -> Unit = {}
    var raw: String? = null
    val store = RecurringStore({ beforeRead(); raw }, {
        beforeWrite(it)
        if (mmkvFails) error("MMKV")
        raw = it
    })
    var journalRaw: String? = null
    var journalFails = false; var mmkvFails = false; var auditFails = false
    var quarantineRaw: ByteArray? = null
    val policyDirectory = File(java.nio.file.Files.createTempDirectory("noah-money-world-").toFile().also { it.deleteOnExit() }, "noah-data-regtest").also {
        it.mkdir()
        it.deleteOnExit()
    }
    val journal = RecurringJournal(File(policyDirectory, "journal.json"), {
        beforeJournalRead()
        journalRaw
    }, {
        beforeJournalWrite(it)
        if (journalFails) error("journal")
        journalRaw = it
    },
        quarantine = { if (quarantineRaw == null) quarantineRaw = it })
    init { wallet.owner = SendIdentity("wallet-a", "regtest"); wallet.snapshot = SendSnapshot(walletIdentity = wallet.owner) }
    val sendJournal = TestJournal()
    val controller = if (reservation == null)
        controller(wallet, sendJournal, scope) else SendController(SendController.Dependencies({ sendJournal.attempt }, sendJournal::write,
        recurringReservation = reservation, reservation = reservation), scope)
    var binding = RecurringStore.Binding(wallet.owner, UUID.randomUUID().toString())
    var allowed = true; var generation: String? = null; var now = 1000L; var zone = TimeZone.getTimeZone("UTC")
    val sentEntries = mutableListOf<List<com.noahwallet.api.generated.models.RecurringPaymentScheduleEntry>>()
    var quotes = 0; var fee = 0L; var method = "ark"; var feeSchedule: RecurringFeeSchedule? = null
    var beforeQuote: suspend () -> Unit = {}; var sync: suspend () -> Unit = {}; var syncCalls = 0
    val notifications = mutableListOf<RecurringPayment>()
    var audits = 0
    init { store.protectPolicies(File("${journal.file.path}.policies"), sync = { it.listFiles()?.forEach(File::deleteOnExit) }, syncFile = {}) { binding } }
    val executor = RecurringExecutor(RecurringExecutor.Dependencies(store, journal, controller,
        ensureOpen = {}, binding = { beforeBinding(); binding }, allowed = { check(allowed) },
        quote = { s ->
            quotes++
            beforeQuote()
            SendPayment(s.destination, method, s.amountSat) to SendQuote(s.amountSat, fee, s.amountSat + fee, walletIdentity = binding.wallet,
                lightningFeeSchedule = feeSchedule)
        },
        automationGeneration = { beforeGeneration(); generation }, now = { now }, zone = { zone },
        sync = { sentEntries.add(it); syncCalls++; sync() }, notify = { _, result -> result?.let { notifications.add(it) } },
        audit = { _, _, _, _ ->
            if (auditFails) error("audit")
            audits++
        }), scope)
    val schedule get() = store.load().schedules["rent"]
    fun add(start: Long = 1000): RecurringPayment {
        val schedule = RecurringPayment("rent", "Rent", "recipient", "ark", 1000, interval = RecurringInterval("day", 1), startAt = start,
            createdAt = 0, updatedAt = 0)
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
    @Test fun rnImportNeverPaysAndSyncsEmptyAfterAdmissionWithRetry() = runBlocking {
        val w = RecurringWorld(this)
        val imported = com.noahwallet.services.storage.SettingsStorage.importValues(mapOf(RecurringStore.key to
            """{"state":{"schedules":{"old":{"id":"old"}}},"version":0}"""))
        w.raw = imported.values.getValue(RecurringStore.key)
        w.allowed = false; w.executor.execute(); assertEquals(0, w.syncCalls)
        w.allowed = true; w.sync = { error("offline") }; w.executor.execute()
        assertTrue(w.store.load().pending)
        w.sync = {}; w.executor.execute()
        assertFalse(w.store.load().pending)
        assertTrue(w.sentEntries.isNotEmpty() && w.sentEntries.all { it.isEmpty() })
        assertEquals(0, w.wallet.calls)
    }

    @Test fun unpaidPauseRollbackCannotPay() = unpaidPolicyRollback("pause")
    @Test fun unpaidCancelRollbackCannotPay() = unpaidPolicyRollback("cancel")
    @Test fun unpaidEditRollbackCannotPayOldPolicy() = unpaidPolicyRollback("edit")
    private fun unpaidPolicyRollback(action: String) = runBlocking {
        val w = RecurringWorld(this); val original = w.add()
        w.executor.timeChanged() // First native load without submitting money.
        val active = w.raw
        when (action) {
            "pause" -> w.executor.pause("rent")
            "cancel" -> w.executor.cancel("rent")
            else -> w.executor.save(original.copy(amountSat = 500, destination = "new-recipient"), editing = true)
        }
        w.raw = active // CRC recovery returns the older, still-unpaid active policy.
        w.executor.execute(); w.executor.execute(manual = true)
        assertEquals(action, 0, w.wallet.calls)
        assertEquals(action, "needs_attention", w.schedule!!.status)
        assertTrue(action, w.journal.load().isEmpty())
    }

    @Test fun rollbackDuringFinalPreparationCannotBypassDurablePause() = runBlocking {
        val w = RecurringWorld(this); w.add()
        w.wallet.prepareBlock = {
            val markedActive = w.raw
            w.executor.pause("rent")
            w.raw = markedActive
            w.wallet.snapshot
        }
        w.executor.execute()
        assertEquals(0, w.wallet.calls)
        assertEquals("needs_attention", w.schedule!!.status)
    }
    @Test fun resumeSkipSurvivesRollbackToOriginalActivePolicy() = runBlocking {
        val w = RecurringWorld(this); w.add(); val active = w.raw
        w.executor.pause("rent"); w.executor.resume("rent")
        w.raw = active
        w.executor.execute(manual = true)
        assertEquals(0, w.wallet.calls)
        assertEquals("needs_attention", w.schedule!!.status)
    }

    @Test fun recoveredMMKVRollbackWaitsForHealthyStorageAndCannotPayTwice() = runBlocking {
        val w = RecurringWorld(this); w.add(); val lastConfirmed = w.raw
        w.executor.execute(); assertEquals(1, w.wallet.calls)
        // MMKV can return the last-confirmed policy while its independent journal stays newer.
        w.raw = lastConfirmed; w.allowed = false
        w.executor.execute(); assertEquals(1, w.wallet.calls)
        assertEquals(lastConfirmed, w.raw)
        w.allowed = true
        w.executor.execute(); w.executor.execute()
        assertEquals(1, w.wallet.calls)
        assertEquals("needs_attention", w.schedule!!.status)
        assertTrue(w.journal.consumed(w.binding, "rent", 1000))
    }

    @Test fun oversizedStoredPolicyCannotRunResumeOverrideOrBlockHealthyPayments() = runBlocking {
        val w = RecurringWorld(this); val healthy = w.add()
        val oversized = healthy.copy(id = "oversized", amountSat = RecurringPayment.maximumAmount + 1)
        w.store.upsert(oversized)
        w.executor.reload()
        assertEquals(2, w.executor.schedules.size)
        val visible = w.executor.schedules.single { it.id == oversized.id }
        assertEquals(oversized.amountSat, visible.amountSat)
        assertEquals("needs_attention", visible.status)
        assertEquals(RecurringPayment.amountLimitMessage, visible.lastError)
        w.executor.execute(); w.executor.execute()
        assertEquals(1, w.wallet.calls); assertEquals(1, w.quotes)
        assertEquals(listOf("rent"), w.journal.load().keys.toList())
        for (resume in listOf<suspend () -> Unit>({ w.executor.resume(oversized.id) }, { w.executor.overrideAndResume(oversized.id, true) })) {
            assertEquals(RecurringPayment.amountLimitMessage, runCatching { resume() }.exceptionOrNull()?.message)
        }
        assertEquals(0, w.audits)
        assertEquals("needs_attention", w.store.load().schedules.getValue(oversized.id).status)
        w.executor.cancel(oversized.id)
        assertEquals(setOf("rent"), w.store.load().schedules.keys)
    }
    @Test fun rnWideCountsPayAndUnsupportedRowsStayStoppedAndCancellable() = runBlocking {
        val w = RecurringWorld(this)
        w.raw = com.noahwallet.fixtures.SharedFixtures.document("recurring-rn-range").toString()
        w.store.bind(w.binding)
        w.executor.execute(); w.executor.execute()
        assertEquals(4, w.wallet.calls)
        assertEquals(11, w.executor.schedules.size)
        assertEquals(3_000_000_000L, w.store.load().schedules.getValue("count-1").maxOccurrences)
        assertEquals(1, w.store.load().schedules.getValue("count-1").occurrencesPaid)
        for (schedule in w.executor.schedules.filter { it.unsupportedStorage }) {
            for (action in listOf<suspend () -> Unit>({ w.executor.resume(schedule.id) },
                { w.executor.overrideAndResume(schedule.id, true) }, { w.executor.save(schedule, editing = true) })) {
                val raw = w.raw
                assertTrue(runCatching { action() }.isFailure)
                assertEquals(raw, w.raw)
            }
            w.executor.cancel(schedule.id)
        }
        assertEquals(0, w.audits)
        assertEquals(4, w.store.load().schedules.size)
        assertEquals(4, w.wallet.calls)
    }
    @Test fun newAndEditedApprovalsRejectAmountsAboveTheLimit() = runBlocking {
        val w = RecurringWorld(this); val original = w.add()
        for (editing in listOf(false, true)) {
            val input = original.copy(id = if (editing) original.id else "new", amountSat = RecurringPayment.maximumAmount + 1)
            val raw = w.raw
            assertTrue(runCatching { w.executor.save(input, editing) }.isFailure)
            assertEquals(raw, w.raw)
        }
        w.executor.save(original.copy(amountSat = RecurringPayment.maximumAmount), editing = true)
        assertEquals(RecurringPayment.maximumAmount, w.schedule!!.amountSat)
        assertEquals("active", w.schedule!!.status)
    }
    @Test fun preflightNotifiesFirstActiveFailureAndExplicitManualRetryOnly() = runBlocking {
        val w = RecurringWorld(this); w.add()
        w.beforeQuote = { error("offline") }
        w.executor.execute()
        assertEquals(1, w.notifications.size)
        assertEquals("failed", w.notifications.single().runs.first().status)
        assertEquals("active", w.notifications.single().status)
        assertEquals(1, w.notifications.single().consecutiveFailures)
        assertEquals(901_000L, w.schedule!!.retryNotBefore)
        w.executor.execute()
        assertEquals(1, w.quotes); assertEquals(1, w.notifications.size)
        w.now = w.schedule!!.retryNotBefore!!
        w.executor.execute()
        assertEquals(2, w.schedule!!.consecutiveFailures); assertEquals(1, w.notifications.size)
        w.executor.execute(manual = true)
        assertEquals(3, w.schedule!!.consecutiveFailures); assertEquals(2, w.notifications.size)
        assertEquals(0, w.wallet.calls); assertTrue(w.journal.load().isEmpty())
    }
    @Test fun preflightFailureAfterPauseOrCancelDoesNotNotifyOrOverwritePolicy() = runBlocking {
        for (cancel in listOf(false, true)) {
            val w = RecurringWorld(this); w.add()
            w.beforeQuote = {
                if (cancel) w.executor.cancel("rent") else w.executor.pause("rent")
                error("offline")
            }
            w.executor.execute()
            assertTrue(w.notifications.isEmpty())
            if (cancel) assertNull(w.schedule) else {
                assertEquals("paused", w.schedule!!.status)
                assertEquals(0, w.schedule!!.consecutiveFailures)
            }
            assertEquals(0, w.wallet.calls); assertTrue(w.journal.load().isEmpty())
        }
    }
    @Test fun cancelledPolicyWithFailedOrAmbiguousSettlementDoesNotNotifySuccess() = runBlocking {
        for (provenFailure in listOf(false, true)) {
            val w = RecurringWorld(this); w.add()
            w.wallet.payBlock = {
                w.executor.cancel("rent")
                if (provenFailure) throw SendNotSubmitted(SendFailure.Reason.constructionFailed)
                else error("connection lost")
            }
            w.executor.execute()
            assertNull(w.schedule); assertTrue(w.notifications.isEmpty())
            assertEquals(1, w.wallet.calls)
            assertFalse(w.journal.consumed(w.binding, "rent", 1000))
        }
    }
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
        w.store.upsert(a.copy(nativeRevision = UUID.randomUUID().toString(), startAt = 2000, nextRunAt = 2000)); w.now = 2000
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
        w.journalRaw = """{"version":1,"attempts":${RecurringStore.json.encodeToString(mapOf(a.id to b))},""" +
            """"consumed":${RecurringStore.json.encodeToString(listOf(RecurringJournal.Watermark(w.binding, a.id, w.now)))}}"""
        w.raw = rollback; w.zone = TimeZone.getTimeZone("UTC"); w.now = 1775016000000
        assertEquals(w.now, a.plan(w.now, w.zone).scheduledFor)
        w.executor.execute()
        assertEquals("Paid monthly index must never be submitted again", 1, w.wallet.calls)
        assertEquals("needs_attention", w.schedule!!.status)
        assertEquals(RecurringPolicyLedger.message, w.schedule!!.lastError)
        assertTrue(runCatching { w.executor.overrideAndResume(a.id, true) }.isFailure)
        assertTrue(runCatching { w.executor.resume(a.id) }.isFailure)
        assertTrue(runCatching { w.store.upsert(a.copy(nativeRevision = UUID.randomUUID().toString())) }.isFailure)
        w.executor.execute(); assertEquals(1, w.wallet.calls); assertEquals("needs_attention", w.schedule!!.status)
        w.executor.cancel(a.id)
        val fresh = a.copy(id = UUID.randomUUID().toString(), startAt = w.now + 1000, nextOccurrenceIndex = 0, nextRunAt = w.now + 1000)
        w.executor.save(fresh, editing = false); w.now += 1000
        w.executor.execute(); assertEquals(2, w.wallet.calls)
    }
    @Test fun legacyUncertaintyRejectsResumeAndEditBeforeRecoveryEvenWhenFutureOrCompleted() = runBlocking {
        for (status in listOf("active", "paused", "needs_attention", "completed")) {
            for (start in listOf(1000L, 2000L)) {
                val w = RecurringWorld(this)
                val original = w.add(start).copy(status = status, nativeRevision = UUID.randomUUID().toString())
                w.store.upsert(original)
                w.journalRaw = """{"version":2,"attempts":{},"consumed":[],"consumedIndices":[],"legacyUncertainty":[""" +
                    """${RecurringStore.json.encodeToString(RecurringJournal.Scope(w.binding, "rent"))}]}"""
                w.journal.load() // Finish the in-place v2 upgrade before checking refused mutations.
                val raw = w.raw; val journal = w.journalRaw
                for (action in listOf<suspend () -> Unit>({ w.executor.resume("rent") },
                    { w.executor.save(original.copy(label = "Edited", startAt = 3000), editing = true) },
                    { w.executor.overrideAndResume("rent", true) })) {
                    assertEquals(RecurringJournal.legacyMessage, runCatching { action() }.exceptionOrNull()?.message)
                    assertEquals(raw, w.raw); assertEquals(journal, w.journalRaw)
                    assertEquals(original, w.schedule)
                }
                assertEquals(0, w.wallet.calls); assertEquals(0, w.quotes); assertEquals(0, w.syncCalls)
                assertEquals(0, w.audits); assertTrue(w.notifications.isEmpty())
                w.executor.cancel("rent")
                assertNull(w.schedule); assertEquals(journal, w.journalRaw)
                val fresh = original.copy(id = "fresh", status = "active", startAt = 3000, nextRunAt = 3000)
                w.executor.save(fresh, editing = false)
                assertEquals(setOf("fresh"), w.store.load().schedules.keys)
            }
        }
    }
    @Test fun malformedIndexAfterRollbackAndZoneChangeCannotPayTwiceOrOverwriteEvidence() = runBlocking {
        for (indices in listOf(listOf(-1), listOf(2))) {
            val w = RecurringWorld(this)
            w.zone = TimeZone.getTimeZone("America/New_York"); w.now = 1774753200000
            val original = w.add(1772337600000).copy(interval = RecurringInterval("month", 1), nextOccurrenceIndex = 1, nextRunAt = w.now)
            w.store.upsert(original); val rollback = w.raw
            w.executor.execute(); assertEquals(1, w.wallet.calls)
            val root = RecurringStore.json.parseToJsonElement(w.journalRaw!!).jsonObject
            val scope = root.getValue("consumedIndices").jsonArray.single().jsonObject
            val malformed = JsonObject(root + ("consumedIndices" to JsonArray(listOf(JsonObject(scope +
                ("indices" to JsonArray(indices.map(::JsonPrimitive)))))))).toString()
            w.journalRaw = malformed; w.raw = rollback
            w.zone = TimeZone.getTimeZone("UTC"); w.now = 1775016000000
            assertEquals(w.now, original.plan(w.now, w.zone).scheduledFor)
            w.executor.execute(); w.executor.execute()
            assertEquals(1, w.wallet.calls)
            assertEquals(malformed, w.journalRaw)
            assertArrayEquals(malformed.toByteArray(), w.quarantineRaw)
            assertEquals("needs_attention", w.schedule!!.status)
            assertEquals(RecurringPolicyLedger.message, w.schedule!!.lastError)
            assertEquals("needs_attention", w.executor.schedules.single().status)
            assertTrue(runCatching { w.executor.overrideAndResume(original.id, true) }.isFailure)
            assertEquals(0, w.audits)
            assertEquals(malformed, w.journalRaw)
        }
    }
    @Test fun invalidSharedJournalStopsEveryVisiblePolicy() = runBlocking {
        val w = RecurringWorld(this); val original = w.add()
        for (status in listOf("active", "paused", "completed", "needs_approval")) {
            w.store.upsert(original.copy(id = status, status = status, startAt = w.now + 86_400_000))
        }
        w.journalRaw = "{broken"
        w.executor.execute()
        assertEquals(5, w.executor.schedules.size)
        assertTrue(w.executor.schedules.all { it.status == "needs_attention" })
        assertEquals(0, w.wallet.calls); assertEquals(0, w.quotes)
        assertEquals("{broken", w.journalRaw)
        assertArrayEquals("{broken".toByteArray(), w.quarantineRaw)
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
        j.save(a.copy(plan = a.plan.copy(scheduledFor = 2000), consumed = false, resolved = false, policyRevision = UUID.randomUUID().toString()))
        assertTrue(raw.contains("\"version\":3")); assertTrue(j.consumed(binding, "rent", 1000)); assertTrue(j.consumed(binding, "rent", 2000))
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
    @Test fun attendedReceiptStaysLockedThroughSlowSyncUntilDoneAndCannotReplay() = runBlocking {
        val w = RecurringWorld(this); w.add(); w.fee = 1
        w.executor.execute()
        val review = w.executor.review("rent")
        val syncing = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        w.sync = { syncing.complete(Unit); release.await() }
        val approval = async { w.executor.execute(manual = true, approval = review) }
        try {
            syncing.await()
            assertEquals(SendController.State.succeeded, w.controller.state)
            assertTrue(w.controller.locked); assertNotNull(w.controller.attempt?.receipt)
            assertEquals(1, w.schedule!!.occurrencesPaid)
            assertTrue(w.journal.load().getValue("rent").consumed)
        } finally { release.complete(Unit); approval.await() }
        w.sync = {}
        assertEquals(SendController.State.succeeded, w.controller.state)
        w.executor.execute(manual = true, approval = review)
        assertEquals(1, w.wallet.calls); assertTrue(w.controller.locked)
        w.controller.acknowledge() // The sheet's Done action.
        assertFalse(w.controller.locked); assertNull(w.controller.attempt)
        w.executor.execute(manual = true, approval = review)
        assertEquals(1, w.wallet.calls); assertEquals(1, w.schedule!!.occurrencesPaid)
    }
    @Test fun manualReviewCannotOutlivePause() = runBlocking {
        val w = RecurringWorld(this); w.add(); w.fee = 1; w.executor.execute()
        val review = w.executor.review("rent"); w.executor.pause("rent"); w.executor.execute(true, review)
        assertEquals(0, w.wallet.calls)
    }
    @Test fun manualReviewCannotApproveAnEditedOrResumedPolicy() = runBlocking {
        val w = RecurringWorld(this); val original = w.add(); w.fee = 1; w.executor.execute()
        val review = w.executor.review("rent")
        w.store.upsert(original.copy(destination = "different-recipient", nativeRevision = UUID.randomUUID().toString()))
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
            assertTrue(w.journal.consumed(w.binding, "rent", 1000))
            val notification = w.notifications.single()
            assertEquals("success", notification.runs.first().status)
            assertEquals(1, notification.occurrencesPaid)
            assertEquals(1000L, notification.amountSat)
            assertFalse(w.controller.locked)
            w.executor.execute(); assertEquals(1, w.wallet.calls)
            assertEquals(1, w.notifications.size)
            if (cancel) assertNull(w.schedule)
        }
    }
    @Test fun cancelledAttendedPaymentNotifiesSuccessAndRetainsReceiptUntilDone() = runBlocking {
        val w = RecurringWorld(this); w.add(); w.fee = 1
        w.executor.execute()
        val review = w.executor.review("rent")
        w.notifications.clear()
        w.wallet.payBlock = {
            w.executor.cancel("rent")
            SendReceipt(1000, proof = SendReceipt.Proof.submitReturnedArkSuccess)
        }
        w.executor.execute(manual = true, approval = review)
        assertNull(w.schedule)
        assertEquals("success", w.notifications.single().runs.first().status)
        assertTrue(w.journal.consumed(w.binding, "rent", 1000))
        assertTrue(w.controller.locked); assertEquals(SendController.State.succeeded, w.controller.state)
        w.controller.acknowledge()
        w.executor.execute(manual = true, approval = review)
        assertNull(w.schedule); assertEquals(1, w.wallet.calls); assertEquals(1, w.notifications.size)
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
        val w = RecurringWorld(this)
        val s = w.add(1772337600000).copy(interval = RecurringInterval("month", 1), nextOccurrenceIndex = 1, nextRunAt = 1774753200000)
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
        val entries = RecurringExecutor.entries(listOf(w.schedule!!.copy(status = "active")))
        val payload = Json.encodeToString(com.noahwallet.api.generated.models.SyncRecurringPaymentsPayload(entries))
        assertFalse(payload.contains("recipient")); assertFalse(payload.contains("amount")); assertTrue(payload.contains("schedule_id"))
    }
}
