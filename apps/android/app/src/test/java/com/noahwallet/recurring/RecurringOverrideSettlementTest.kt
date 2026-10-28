package com.noahwallet.recurring

import com.noahwallet.send.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RecurringOverrideSettlementTest {
    @Test fun recoveredHandoffDuringOverrideMustApplyPaidHistory() = runBlocking {
        val w = RecurringWorld(this)
        try {
            w.add()
            var failBinding = false
            w.wallet.payBlock = {
                failBinding = true
                SendReceipt(1000, proof = SendReceipt.Proof.submitReturnedArkSuccess)
            }
            w.beforeBinding = {
                if (failBinding) { failBinding = false; error("Transient wallet binding read failure") }
            }
            w.executor.execute()
            assertEquals(SendController.State.storageBlocked, w.controller.state)
            assertNotNull(w.sendJournal.attempt?.receipt)
            assertNull(w.journal.load().getValue("rent").terminal)
            w.executor.overrideAndResume("rent", true)
            w.controller.reloadForWalletChange()
            w.executor.execute(); w.executor.checkStatus("rent")
            val e = w.journal.load().getValue("rent")
            assertTrue("terminal=${e.terminal?.outcome}; consumed=${e.consumed}; historyApplied=${e.historyApplied}; released=${e.released}; " +
                "paid=${w.schedule!!.occurrencesPaid}; successes=${w.schedule!!.runs.count { it.status == "success" }}; " +
                "sendReceipt=${w.sendJournal.attempt?.receipt}", e.terminal?.outcome == RecurringJournal.Outcome.succeeded && w.schedule!!.occurrencesPaid == 1)
            assertEquals(1, w.wallet.calls)
            assertEquals("Proven paid occurrence was skipped by override", 1, w.schedule!!.occurrencesPaid)
            assertEquals(1, w.schedule!!.runs.count { it.status == "success" })
        } finally { w.policyDirectory.parentFile!!.deleteRecursively() }
    }
    private fun failInitialHandoff(w: RecurringWorld) {
        val sink = checkNotNull(w.controller.persistRecurringEvidence)
        var first = true
        w.controller.persistRecurringEvidence = { attempt, generation, walletGeneration, outcome, message ->
            if (first) { first = false; error("Transient handoff failure") }
            sink(attempt, generation, walletGeneration, outcome, message)
        }
    }

    private suspend fun recover(w: RecurringWorld): Pair<RecurringStore, RecurringJournal> {
        val store = RecurringStore({ w.raw }, { w.raw = it })
        store.protectPolicies(java.io.File("${w.journal.file.path}.policies"), sync = {}, syncFile = {}) { w.binding }
        val journal = RecurringJournal(w.journal.file, { w.journalRaw }, { w.journalRaw = it })
        val c = controller(w.wallet, w.sendJournal, w.scope)
        val executor = RecurringExecutor(RecurringExecutor.Dependencies(store, journal, c,
            ensureOpen = {}, binding = { w.binding }, allowed = { check(w.allowed) },
            quote = { error("Recovery must not submit again") }, automationGeneration = { w.generation },
            now = { w.now }, zone = { w.zone }), w.scope)
        c.reloadForWalletChange()
        repeat(3) { executor.execute(); executor.checkStatus("rent") }
        return store to journal
    }

    @Test fun definitiveOutcomesRepairedByCleanupUseNormalSettlement() = runBlocking {
        for (outcome in listOf("alreadyPaid", "failed")) {
            val w = RecurringWorld(this)
            try {
                w.add(); failInitialHandoff(w)
                w.wallet.payBlock = {
                    if (outcome == "alreadyPaid") throw SendAlreadyPaid(SendReceipt(1000, proof = SendReceipt.Proof.submitReturnedArkSuccess))
                    throw SendNotSubmitted(SendFailure.Reason.fundsChanged)
                }
                w.executor.execute()
                assertEquals(SendController.State.storageBlocked, w.controller.state)
                assertNull(w.journal.load().getValue("rent").terminal)
                w.executor.overrideAndResume("rent", true)
                val (store, journal) = recover(w)
                val e = journal.load().getValue("rent")
                assertEquals(if (outcome == "alreadyPaid") RecurringJournal.Outcome.alreadyPaid else RecurringJournal.Outcome.failed, e.terminal?.outcome)
                assertTrue(e.resolved && e.historyApplied && e.released)
                assertEquals(outcome == "alreadyPaid", e.consumed)
                assertEquals(0, store.load().schedules.getValue("rent").occurrencesPaid)
                assertEquals(1, store.load().schedules.getValue("rent").runs.count { it.status == "failed" })
                assertEquals(1, w.wallet.calls)
            } finally { w.policyDirectory.parentFile!!.deleteRecursively() }
        }
    }

    /** Whether this journal write is the one that records [boundary]. */
    private fun reaches(boundary: String, raw: String): Boolean {
        val e = RecurringStore.json.parseToJsonElement(raw).jsonObject["attempts"]!!.jsonObject.getValue("rent").jsonObject
        return when (boundary) {
            "terminal" -> e["terminal"] != null
            "sendCleared", "consumed" -> e["consumed"]?.jsonPrimitive?.booleanOrNull == true
            "policy", "historyApplied" -> e["historyApplied"]?.jsonPrimitive?.booleanOrNull == true
            "released" -> e["released"]?.jsonPrimitive?.booleanOrNull == true
            else -> false
        }
    }

    @Test fun deathDuringOverrideRecoversPaidHistoryExactlyOnce() = runBlocking {
        for (boundary in listOf("terminal", "sendCleared", "consumed", "policy", "historyApplied", "released", "resumed")) {
            val w = RecurringWorld(this)
            try {
                w.add(); failInitialHandoff(w); w.executor.execute()
                assertEquals(SendController.State.storageBlocked, w.controller.state)
                var crashed = false
                w.beforeJournalWrite = { raw ->
                    if (reaches(boundary, raw) && !crashed) {
                        crashed = true
                        // Before consumed: Send is already cleared. Before historyApplied: the policy row is durable.
                        if (boundary != "sendCleared" && boundary != "policy") w.journalRaw = raw
                        error("Process death at $boundary")
                    }
                }
                w.beforeWrite = { raw ->
                    if (boundary == "resumed" && !crashed && w.journalRaw!!.contains("\"released\":true")) {
                        crashed = true; w.raw = raw; error("Process death after resumed policy")
                    }
                }
                runCatching { w.executor.overrideAndResume("rent", true) }
                assertTrue(boundary, crashed)
                val (store, journal) = recover(w)
                val row = store.load().schedules.getValue("rent")
                assertEquals(boundary, 1, row.occurrencesPaid)
                assertEquals(boundary, 1, row.runs.count { it.status == "success" })
                assertTrue(journal.load().getValue("rent").let { it.consumed && it.historyApplied && it.released &&
                    it.terminal?.outcome == RecurringJournal.Outcome.succeeded })
                assertEquals(boundary, 1, w.wallet.calls)
                val again = recover(w).first.load().schedules.getValue("rent")
                assertEquals(1, again.occurrencesPaid)
            } finally { w.policyDirectory.parentFile!!.deleteRecursively() }
        }
    }
}
