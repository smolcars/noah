package com.noahwallet.recurring

import com.noahwallet.send.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class RecurringSettlementTest {
    @Test fun paidOccurrenceKeepsItsOutcomeWhenAnotherPaymentFailsDuringSettlement() = runBlocking {
        val w = RecurringWorld(this); val schedule = w.add()
        val plan = schedule.plan(w.now, w.zone, true)
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val once = AtomicBoolean()
        var successfulPayments = 0
        w.wallet.payBlock = { successfulPayments++; SendReceipt(1000, proof = SendReceipt.Proof.submitReturnedArkSuccess) }
        w.beforeBinding = {
            if (w.controller.state == SendController.State.succeeded && w.controller.attempt?.recurringOccurrenceKey != null && once.compareAndSet(false, true)) {
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
                    when (action) {
                        "pause" -> w.store.upsert(w.schedule!!.copy(status = "paused"))
                        "cancel" -> w.store.remove(original.id)
                        "edit" -> w.store.upsert(original.copy(amountSat = 2000, nativeRevision = java.util.UUID.randomUUID().toString()))
                        "suspend" -> { w.generation = "stopped"; w.allowed = false }
                        "replace" -> w.binding = owner.copy(wallet = SendIdentity("other", "regtest"))
                        "restore" -> w.binding = owner.copy(instance = java.util.UUID.randomUUID().toString())
                        "failureLatch" -> w.store.upsert(w.schedule!!.copy(status = "needs_attention", consecutiveFailures = 3, lastError = "Safety storage failed"))
                    }
                    changed = w.schedule
                }
            }
            w.executor.execute()
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
            assertEquals(action !in listOf("replace", "restore"), w.journal.consumed(owner, original.id, 1000))
            w.executor.execute()
            assertEquals("$action must not replay the paid occurrence", 1, w.wallet.calls)
        }
    }
}
