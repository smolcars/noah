package com.noahwallet.recurring

import com.noahwallet.send.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Crash/restart regressions from the round-eight settlement review. */
class RecurringSettlementDurabilityTest {
    private suspend fun paidButUnsettled(w: RecurringWorld): RecurringPayment {
        val s = w.add().copy(nativeRevision = java.util.UUID.randomUUID().toString())
        w.store.upsert(s)
        val plan = s.plan(w.now, w.zone, true)
        val e = RecurringJournal.Entry(w.binding, s.id, plan, w.now, s.nativeRevision)
        w.journal.save(e)
        w.store.upsert(s.copy(inFlight = RecurringPayment.InFlight(plan.occurrenceIndex!!, w.now)))
        w.controller.submit(arkPayment, FakeSendWallet.quote.copy(walletIdentity = w.wallet.owner), e.key)!!.await()
        assertEquals(SendController.State.succeeded, w.controller.state)
        return s
    }

    private fun restartedExecutor(w: RecurringWorld, c: SendController) = RecurringExecutor(
        RecurringExecutor.Dependencies(w.store, w.journal, c, ensureOpen = {}, binding = { w.binding }, allowed = {},
            quote = { error("Recovery must not quote or pay") }, automationGeneration = { null }, now = { w.now }, zone = { w.zone }), w.scope)

    @Test fun restartedDoneMustNotEraseUnsettledPaidEvidence() = runBlocking {
        val w = RecurringWorld(this)
        try {
            val s = paidButUnsettled(w)
            val receipt = w.sendJournal.attempt
            w.controller.acknowledge()
            assertNotNull(w.journal.load().getValue(s.id).terminal)
            // Death before Done's Send-journal removal reaches disk restores the receipt.
            w.sendJournal.attempt = receipt
            val c = controller(w.wallet, w.sendJournal, this) // Process restart: only durable state survives.
            val executor = restartedExecutor(w, c)
            c.reloadForWalletChange()
            assertEquals(SendController.State.succeeded, c.state)
            c.acknowledge() // Done on the recovered receipt.
            executor.execute(); executor.checkStatus(s.id)
            assertTrue("Paid evidence lost after restart/Done: consumed=${w.journal.load().getValue(s.id).consumed}, count=${w.schedule!!.occurrencesPaid}, send=${w.sendJournal.attempt}",
                w.journal.load().getValue(s.id).consumed && w.schedule!!.occurrencesPaid == 1)
            executor.execute(); executor.checkStatus(s.id)
            assertEquals(1, w.schedule!!.runs.count { it.status == "success" })
            assertEquals(1, w.wallet.calls)
        } finally { w.policyDirectory.parentFile!!.deleteRecursively() }
    }

    @Test fun replacementSendMustNotOverwriteUnsettledPaidEvidence() = runBlocking {
        val w = RecurringWorld(this)
        try {
            val s = paidButUnsettled(w)
            w.controller.acknowledge()
            // Manual Send can run while recurring settlement is queued or storage is blocked.
            w.controller.submit(arkPayment, FakeSendWallet.quote.copy(walletIdentity = w.wallet.owner))!!.await()
            val c = controller(w.wallet, w.sendJournal, this)
            c.reloadForWalletChange()
            val executor = restartedExecutor(w, c)
            executor.execute(); executor.checkStatus(s.id)
            assertTrue("Replacement overwrote paid recurring proof before restart: consumed=${w.journal.load().getValue(s.id).consumed}, count=${w.schedule!!.occurrencesPaid}, key=${w.sendJournal.attempt?.recurringOccurrenceKey}",
                w.journal.load().getValue(s.id).consumed && w.schedule!!.occurrencesPaid == 1)
            executor.execute(); executor.checkStatus(s.id)
            assertEquals(1, w.schedule!!.runs.count { it.status == "success" })
            assertEquals(2, w.wallet.calls)
        } finally { w.policyDirectory.parentFile!!.deleteRecursively() }
    }

    @Test fun walletReloadAndOverrideMustReleaseOldAmbiguousResults() = runBlocking {
        val w = RecurringWorld(this)
        try {
            w.add()
            w.wallet.payBlock = { error("Unknown outcome") }
            w.executor.execute()
            repeat(5) { w.controller.reloadForWalletChange(); w.controller.checkStatus() }
            w.controller.startNewPaymentAfterHistoryCheck(true)
            assertTrue(SendController::class.java.declaredFields.none { it.name == "recurringResults" })
            w.binding = w.binding.copy(instance = java.util.UUID.randomUUID().toString())
            w.controller.reloadForWalletChange()
            w.executor.execute()
            assertEquals(1, w.wallet.calls)
            assertFalse(w.journal.load().getValue("rent").consumed)
        } finally { w.policyDirectory.parentFile!!.deleteRecursively() }
    }

    @Test fun consumptionClaimBeforePolicyWriteMustRecoverPaidHistory() = runBlocking {
        val w = RecurringWorld(this)
        try {
            val s = paidButUnsettled(w)
            // Persist exactly settlement's first write, then restart before policy upsert.
            val e = w.journal.load().getValue(s.id)
            w.journal.save(e.copy(resolved = true, consumed = true, policyApplied = true))
            val c = controller(w.wallet, w.sendJournal, this)
            c.reloadForWalletChange()
            val executor = restartedExecutor(w, c)
            executor.execute(); executor.checkStatus(s.id); executor.checkStatus(s.id)
            assertEquals("The claim prevents replay but also permanently skips the first history application", 1, w.schedule!!.occurrencesPaid)
        } finally { w.policyDirectory.parentFile!!.deleteRecursively() }
    }
    @Test fun v2JournalsUpgradePendingProofAndStopUnknowableHistory() = runBlocking {
        for (consumed in listOf(false, true)) {
            val w = RecurringWorld(this)
            try {
                val s = paidButUnsettled(w)
                val entry = w.journal.load().getValue(s.id)
                w.journal.save(entry.copy(terminal = null, resolved = consumed, consumed = consumed, policyApplied = consumed))
                val root = RecurringStore.json.parseToJsonElement(w.journalRaw!!).jsonObject
                w.journalRaw = JsonObject(root + ("version" to JsonPrimitive(2))).toString()
                w.sendJournal.attempt = w.sendJournal.attempt?.copy(recurringAttemptID = null)
                val c = controller(w.wallet, w.sendJournal, this)
                val executor = restartedExecutor(w, c)
                c.reloadForWalletChange()
                if (consumed) assertEquals(SendController.State.storageBlocked, c.state) else c.acknowledge()
                executor.execute(); executor.checkStatus(s.id)
                assertEquals(if (consumed) 0 else 1, w.schedule!!.occurrencesPaid)
                if (consumed) assertEquals(RecurringJournal.legacyMessage, w.schedule!!.lastError)
                assertEquals(1, w.wallet.calls)
            } finally { w.policyDirectory.parentFile!!.deleteRecursively() }
        }
    }

    @Test fun ambiguityOverrideRemainsReleasedAfterRestart() = runBlocking {
        val w = RecurringWorld(this)
        try {
            w.add(); w.wallet.payBlock = { error("Ambiguous") }; w.executor.execute()
            w.executor.overrideAndResume("rent", true)
            assertTrue(w.journal.load().getValue("rent").released)
            val c = controller(w.wallet, w.sendJournal, this)
            val executor = restartedExecutor(w, c)
            c.reloadForWalletChange(); executor.execute(); executor.checkStatus("rent")
            assertEquals("active", w.schedule!!.status)
            assertEquals(0, w.schedule!!.occurrencesPaid)
            assertEquals(1, w.wallet.calls)
        } finally { w.policyDirectory.parentFile!!.deleteRecursively() }
    }

}

/** Each restarted instance reads only the durable doubles and the on-disk policy ledger. */
class RecurringSettlementCrashTest {
    private data class Restarted(val store: RecurringStore, val journal: RecurringJournal, val controller: SendController, val executor: RecurringExecutor)
    private suspend fun restart(w: RecurringWorld): Restarted {
        val store = RecurringStore({ w.raw }, { w.raw = it })
        store.protectPolicies(java.io.File("${w.journal.file.path}.policies"), sync = {}, syncFile = {}) { w.binding }
        val journal = RecurringJournal(w.journal.file, { w.journalRaw }, { w.journalRaw = it })
        val controller = controller(w.wallet, w.sendJournal, w.scope)
        val executor = RecurringExecutor(RecurringExecutor.Dependencies(store, journal, controller,
            ensureOpen = {}, binding = { w.binding }, allowed = { check(w.allowed) },
            quote = { error("Recovery must not prepare a second payment") }, automationGeneration = { w.generation },
            now = { w.now }, zone = { w.zone }), w.scope)
        controller.reloadForWalletChange()
        return Restarted(store, journal, controller, executor)
    }
    private suspend fun assertPaidExactlyOnce(w: RecurringWorld) {
        val r = restart(w)
        repeat(3) { r.executor.execute(); r.executor.checkStatus("rent") }
        val paid = r.store.load().schedules.getValue("rent")
        assertEquals(1, paid.occurrencesPaid)
        assertEquals(1, paid.runs.count { it.status == "success" })
        assertTrue(r.journal.load().getValue("rent").let { it.consumed && it.historyApplied && it.released })
        assertEquals(1, w.wallet.calls)
        r.controller.acknowledge()
        val again = restart(w)
        again.executor.execute(); again.executor.checkStatus("rent")
        assertEquals(1, again.store.load().schedules.getValue("rent").occurrencesPaid)
        assertEquals(1, w.wallet.calls)
    }
    @Test fun processDeathAfterEachDurableStepFinishesExactlyOnce() = runBlocking {
        for (boundary in listOf("terminal", "consumed", "historyApplied", "released")) {
            val w = RecurringWorld(this)
            try {
                w.add()
                var crashed = false
                w.beforeJournalWrite = { raw ->
                    val matches = when (boundary) {
                        "terminal" -> raw.contains("\"outcome\":\"succeeded\"")
                        else -> raw.contains("\"$boundary\":true")
                    }
                    if (matches && !crashed) {
                        w.journalRaw = raw // Atomic write/fsync completed; the process never returns.
                        crashed = true
                        error("Process death after $boundary")
                    }
                }
                w.executor.execute()
                assertTrue(boundary, crashed)
                assertPaidExactlyOnce(w)
            } finally { w.policyDirectory.parentFile!!.deleteRecursively() }
        }
    }
    @Test fun deathBeforeTerminalEvidenceWriteRecoversTheExactSendReceipt() = runBlocking {
        val w = RecurringWorld(this)
        try {
            w.add()
            w.beforeJournalWrite = { if (it.contains("\"outcome\":\"succeeded\"")) error("Evidence write failed") }
            w.executor.execute()
            assertEquals(SendController.State.storageBlocked, w.controller.state)
            assertNotNull(w.sendJournal.attempt?.receipt)
            w.controller.startNewPaymentAfterHistoryCheck(true)
            assertTrue(w.controller.locked)
            assertNotNull(w.sendJournal.attempt?.receipt)
            assertFalse(w.journalRaw!!.contains("\"outcome\":\"succeeded\""))
            assertPaidExactlyOnce(w)
        } finally { w.policyDirectory.parentFile!!.deleteRecursively() }
    }
    @Test fun deathBetweenLedgerWriteAheadAndSettingsWriteReplaysHistoryOnce() = runBlocking {
        val w = RecurringWorld(this)
        try {
            w.add()
            w.beforeWrite = { if (it.contains("\"occurrencesPaid\":1")) error("Death after ledger, before settings") }
            w.executor.execute()
            assertTrue(w.journal.load().getValue("rent").consumed)
            assertFalse(w.journal.load().getValue("rent").historyApplied)
            assertPaidExactlyOnce(w)
        } finally { w.policyDirectory.parentFile!!.deleteRecursively() }
    }
    @Test fun deathAfterPolicyRowBeforeHistoryFlagUsesLedgerIdempotencyKey() = runBlocking {
        val w = RecurringWorld(this)
        try {
            w.add(); w.executor.execute()
            val e = w.journal.load().getValue("rent")
            w.journal.save(e.copy(historyApplied = false, policyApplied = false, released = false))
            assertPaidExactlyOnce(w)
        } finally { w.policyDirectory.parentFile!!.deleteRecursively() }
    }
    @Test fun completedWriteAheadCannotRepairALaterRollbackOrUndoPause() = runBlocking {
        val w = RecurringWorld(this)
        try {
            w.add(); val before = w.raw
            w.executor.execute(); w.executor.pause("rent")
            w.raw = before
            val r = restart(w)
            r.executor.execute(); r.executor.checkStatus("rent")
            assertEquals("needs_attention", r.store.load().schedules.getValue("rent").status)
            assertEquals(1, w.wallet.calls)
        } finally { w.policyDirectory.parentFile!!.deleteRecursively() }
    }
}
