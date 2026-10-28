package com.noahwallet.recurring

import com.noahwallet.send.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class RecurringSettlementTest {
    @Test fun doneBeforeReservationReturnStillRecordsPaidOccurrence() = reservationRace("done")
    @Test fun replacementBeforeReservationReturnKeepsOriginalOutcome() = reservationRace("replacement")
    @Test fun sameKeyReplacementBeforeReservationReturnKeepsOriginalOutcome() = reservationRace("sameKeyReplacement")
    @Test fun reloadBeforeReservationReturnKeepsOriginalOutcome() = reservationRace("reload")
    @Test fun failedResultBeforeReservationReturnKeepsItsMessage() = reservationRace("failed")
    @Test fun ambiguousResultBeforeReservationReturnNeverRetries() = reservationRace("ambiguous")
    @Test fun hiddenReceiptSurvivesBlockedSettlementUntilDurableConsumption() = reservationRace("blocked")
    @Test fun retainedResultFromPreviousWalletCannotSettleMatchingKey() = reservationRace("foreignReload")

    private fun reservationRace(change: String) = runBlocking {
        val wallet = FakeSendWallet()
        val terminal = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var holdReturn = true
        val w = RecurringWorld(this, wallet, reservation = { work ->
            work(wallet)
            // The exclusive wallet work is finished; the submission coroutine has not returned.
            if (holdReturn) {
                holdReturn = false
                terminal.complete(Unit)
                release.await()
            }
        })
        try {
            val schedule = w.add().copy(status = "needs_approval")
            w.store.upsert(schedule)
            if (change == "failed") wallet.payBlock = { throw SendNotSubmitted(SendFailure.Reason.fundsChanged) }
            if (change == "ambiguous") wallet.payBlock = { error("Outcome unknown") }
            val review = w.executor.review(schedule.id)
            val run = async { w.executor.execute(manual = true, approval = review) }
            var replacement: SendAttempt? = null
            var key = ""
            var originalMessage: String? = null
            try {
                withTimeout(5000) { terminal.await() }
                key = w.controller.attempt!!.recurringOccurrenceKey!!
                val result = w.journal.load().getValue(schedule.id).terminal!!
                originalMessage = result.message
                assertEquals(outcome(change), result.outcome)
                w.controller.acknowledge()
                if (change == "ambiguous") assertTrue(w.controller.locked) else {
                    assertFalse(w.controller.locked)
                    assertNull(w.controller.attempt)
                    assertNull(w.controller.attempt)
                    assertNotNull(w.journal.load().getValue(schedule.id).terminal)
                }
                replacement = interfere(change, w, wallet, key)
            } finally { release.complete(Unit) }
            withTimeout(5000) { run.await() }
            if (change == "foreignReload") return@runBlocking assertUnsettled(w, schedule.id)
            if (change == "blocked") unblock(w, schedule.id)
            assertSettled(change, w, schedule.id, originalMessage, replacement)
            val calls = wallet.calls
            val paidCount = w.schedule!!.occurrencesPaid
            w.executor.checkStatus(schedule.id)
            w.executor.execute()
            assertEquals(paidCount, w.schedule!!.occurrencesPaid)
            assertEquals(calls, wallet.calls)
            if (change == "ambiguous") assertEquals("needs_attention", w.schedule!!.status)
        } finally { release.complete(Unit); w.policyDirectory.parentFile!!.deleteRecursively() }
    }

    /** A result kept from another wallet settles nothing. */
    private fun assertUnsettled(w: RecurringWorld, id: String) {
        assertNull(w.controller.attempt)
        assertFalse(w.journal.load().getValue(id).consumed)
        assertEquals(0, w.schedule!!.occurrencesPaid)
        assertEquals(1, w.wallet.calls)
    }

    /** With storage blocked the receipt stays hidden and unconsumed until the block clears and the status is checked. */
    private suspend fun unblock(w: RecurringWorld, id: String) {
        assertNull(w.controller.attempt)
        assertNull(w.sendJournal.attempt)
        assertTrue(w.journal.quarantineFile.delete())
        assertFalse(w.journal.load().getValue(id).consumed)
        w.executor.checkStatus(id)
    }

    private fun outcome(change: String) = when (change) {
        "failed" -> RecurringJournal.Outcome.failed
        "ambiguous" -> RecurringJournal.Outcome.uncertain
        else -> RecurringJournal.Outcome.succeeded
    }

    /** What [change] does while the submission is held after the wallet work; returns the attempt that replaced it, if any. */
    private suspend fun interfere(change: String, w: RecurringWorld, wallet: FakeSendWallet, key: String): SendAttempt? {
        var replacement: SendAttempt? = null
        if (change in listOf("replacement", "sameKeyReplacement")) {
            wallet.payBlock = { throw SendNotSubmitted(SendFailure.Reason.recurringStopped) }
            w.controller.submit(arkPayment, FakeSendWallet.quote.copy(walletIdentity = wallet.owner),
                if (change == "sameKeyReplacement") key else "another-occurrence")!!.await()
            replacement = w.controller.attempt
        } else if (change == "reload") {
            w.controller.reloadForWalletChange()
            replacement = w.controller.attempt
        } else if (change == "foreignReload") {
            w.binding = w.binding.copy(instance = java.util.UUID.randomUUID().toString())
            w.sendJournal.attempt = null
            w.controller.reloadForWalletChange()
        } else if (change == "blocked") w.journal.quarantineFile.writeText("Storage admission blocked by test")
        return replacement
    }

    private fun assertSettled(change: String, w: RecurringWorld, id: String, originalMessage: String?, replacement: SendAttempt?) {
        val wallet = w.wallet
        val paid = change !in listOf("failed", "ambiguous")
        assertEquals(if (change in listOf("replacement", "sameKeyReplacement")) 2 else 1, wallet.calls)
        assertEquals(paid, w.journal.load().getValue(id).consumed)
        assertEquals(if (paid) 1 else 0, w.schedule!!.occurrencesPaid)
        if (change == "failed") assertEquals(originalMessage, w.schedule!!.lastError)
        if (replacement != null) {
            assertSame("Only the original generation may be acknowledged", replacement, w.controller.attempt)
            assertTrue(w.controller.locked)
        } else if (change != "ambiguous") {
            assertNull(w.controller.attempt)
            assertNull(w.sendJournal.attempt)
        }
    }

    @Test fun paidOccurrenceKeepsItsOutcomeWhenAnotherPaymentFailsDuringSettlement() = runBlocking {
        val w = RecurringWorld(this); val schedule = w.add()
        val plan = schedule.plan(w.now, w.zone, true)
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val once = AtomicBoolean()
        var successfulPayments = 0
        w.wallet.payBlock = { successfulPayments++; SendReceipt(1000, proof = SendReceipt.Proof.submitReturnedArkSuccess) }
        w.beforeBinding = {
            if (w.controller.state == SendController.State.succeeded && w.controller.attempt?.recurringOccurrenceKey != null &&
                once.compareAndSet(false, true)) {
                entered.complete(Unit)
                check(release.await(10, TimeUnit.SECONDS))
            }
        }
        val execution = async { w.executor.execute() }
        try {
            withTimeout(5000) { entered.await() }
            assertEquals(1, successfulPayments)
            w.controller.acknowledge()
            assertFalse(w.controller.locked)
            w.wallet.payBlock = { throw SendNotSubmitted(SendFailure.Reason.fundsChanged) }
            w.controller.submit(arkPayment, FakeSendWallet.quote.copy(walletIdentity = w.wallet.owner))!!.join()
            assertEquals(SendController.State.failed, w.controller.state)
            val replacement = w.controller.attempt
            release.countDown(); execution.await()
            assertSame("Settlement must not acknowledge the replacement attempt", replacement, w.controller.attempt)
            assertEquals("active", w.schedule!!.status)
            assertEquals(1, w.schedule!!.occurrencesPaid)
            assertTrue(w.journal.consumed(w.binding, schedule.id, plan.scheduledFor!!, schedule.nativeRevision, plan.occurrenceIndex))
            w.wallet.payBlock = { successfulPayments++; SendReceipt(1000, proof = SendReceipt.Proof.submitReturnedArkSuccess) }
            w.now += 900_000
            w.executor.execute()
            assertEquals("Already paid occurrence must not be sent twice", 1, successfulPayments)
        } finally { release.countDown(); execution.await() }
    }

    @Test fun policyAndWalletChangesDuringSettlementKeepTheirOwnership() = runBlocking {
        for (action in listOf("pause", "cancel", "edit", "suspend", "replace", "restore", "failureLatch")) {
            val w = RecurringWorld(this); val original = w.add(); val owner = w.binding
            val once = AtomicBoolean()
            var changed: RecurringPayment? = null
            w.beforeBinding = {
                if (w.controller.state == SendController.State.succeeded && once.compareAndSet(false, true)) {
                    change(action, w, original, owner)
                    changed = w.schedule
                }
            }
            w.executor.execute()
            assertKept(action, w, changed)
            assertEquals(action !in listOf("replace", "restore"), w.journal.consumed(owner, original.id, 1000))
            w.executor.execute()
            assertEquals("$action must not replay the paid occurrence", 1, w.wallet.calls)
        }
    }

    /** The policy or wallet change [action] names, made while a paid occurrence settles. */
    private fun change(action: String, w: RecurringWorld, original: RecurringPayment, owner: RecurringStore.Binding) {
        when (action) {
            "pause" -> w.store.upsert(w.schedule!!.copy(status = "paused"))
            "cancel" -> w.store.remove(original.id)
            "edit" -> w.store.upsert(original.copy(amountSat = 2000, nativeRevision = java.util.UUID.randomUUID().toString()))
            "suspend" -> { w.generation = "stopped"; w.allowed = false }
            "replace" -> w.binding = owner.copy(wallet = SendIdentity("other", "regtest"))
            "restore" -> w.binding = owner.copy(instance = java.util.UUID.randomUUID().toString())
            "failureLatch" -> w.store.upsert(w.schedule!!.copy(status = "needs_attention", consecutiveFailures = 3,
                lastError = "Safety storage failed"))
        }
    }

    private fun assertKept(action: String, w: RecurringWorld, changed: RecurringPayment?) {
        when (action) {
            "cancel" -> assertNull(w.schedule)
            "edit", "replace", "restore" -> assertEquals(changed, w.schedule)
            "pause" -> { assertEquals("paused", w.schedule!!.status); assertEquals(1, w.schedule!!.occurrencesPaid) }
            "failureLatch" -> {
                assertEquals("needs_attention", w.schedule!!.status)
                assertEquals(3, w.schedule!!.consecutiveFailures)
                assertEquals("Safety storage failed", w.schedule!!.lastError)
            }
            "suspend" -> assertEquals(1, w.schedule!!.occurrencesPaid)
        }
    }

    @Test fun oldSuccessfulReceiptCannotSettleAnEditedPolicyRevision() = runBlocking {
        val w = RecurringWorld(this)
        val original = w.add()
        w.beforeJournalWrite = { raw ->
            // Paid receipt is durable; only its subsequent acknowledged removal fails.
            if (raw.contains("\"outcome\":\"succeeded\"")) w.sendJournal.fail = true
        }
        w.executor.execute()
        assertEquals(1, w.wallet.calls)
        assertEquals(1, w.schedule!!.occurrencesPaid)
        assertEquals(SendController.State.succeeded, w.controller.state)
        w.beforeJournalWrite = {}
        w.sendJournal.fail = false
        // The real editor resets occurrence counters and preserves the selected first date.
        w.executor.save(original.copy(destination = "different-recipient", amountSat = 2000, maxOccurrences = 1), editing = true)
        w.executor.execute()
        assertEquals("needs_attention", w.schedule!!.status)
        assertEquals(0, w.schedule!!.occurrencesPaid)
        val edited = w.schedule!!
        assertNotEquals(w.journal.load().getValue("rent").policyRevision, edited.nativeRevision)
        // This is the visible Check payment status action on the stopped edited row.
        val notificationsBefore = w.notifications.toList()
        w.executor.checkStatus("rent")
        assertEquals("An old receipt must not advance the edited recipient/amount", edited, w.schedule)
        assertEquals(1, w.wallet.calls)
        assertTrue(w.journal.load().getValue("rent").let { it.resolved && it.consumed })
        assertNull(w.controller.attempt)
        assertFalse(w.controller.locked)
        assertEquals("Checking A must not announce the edited payment as sent", notificationsBefore, w.notifications)
        w.executor.checkStatus("rent")
        w.executor.execute()
        assertEquals(edited, w.schedule)
        assertEquals(1, w.wallet.calls)
    }
    @Test fun statusCheckOutcomesCannotMutateAnotherPolicyRevision() = runBlocking {
        for (outcome in listOf("success", "alreadyPaid", "failed", "uncertain")) {
            val w = RecurringWorld(this); val original = w.add()
            w.wallet.payBlock = { error("Outcome unknown") }
            w.executor.execute()
            val entry = w.journal.load().getValue(original.id)
            // Recovery can display an edited policy while the old Send attempt is unresolved.
            val edited = original.copy(destination = "different-recipient", amountSat = 2000,
                nativeRevision = java.util.UUID.randomUUID().toString(), status = "needs_attention", maxOccurrences = 1)
            w.store.upsert(edited)
            val notificationsBefore = w.notifications.toList()
            w.wallet.reconciliation = {
                yield() // Status reconciliation suspends before settlement reloads the policy.
                when (outcome) {
                    "success" -> SendResolution.Paid(SendReceipt(1000, proof = SendReceipt.Proof.submitReturnedArkSuccess))
                    "alreadyPaid" -> SendResolution.AlreadyPaid(SendReceipt(1000, proof = SendReceipt.Proof.submitReturnedArkSuccess))
                    "failed" -> SendResolution.Failed
                    else -> SendResolution.Uncertain
                }
            }
            w.executor.checkStatus(original.id)
            assertEquals(outcome, edited, w.schedule)
            assertEquals(outcome, notificationsBefore, w.notifications)
            val resolved = w.journal.load().getValue(original.id)
            assertEquals(outcome, outcome != "uncertain", resolved.resolved)
            assertEquals(outcome, outcome in listOf("success", "alreadyPaid"), resolved.consumed)
            assertEquals(outcome, entry.policyRevision, resolved.policyRevision)
            assertEquals(outcome, outcome == "uncertain", w.controller.locked)
            w.executor.execute()
            assertEquals("$outcome must not retry A or pay the edited policy", 1, w.wallet.calls)
        }
    }
}
