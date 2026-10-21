package com.noahwallet.recurring

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.noahwallet.send.SendReceipt
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class RecurringForegroundRunnerTest {
    private class Activity : LifecycleOwner {
        override val lifecycle = LifecycleRegistry.createUnsafe(this)
        fun start() = lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        fun stop() = lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
    }
    private class Minutes {
        private val waits = Channel<Long>(Channel.UNLIMITED)
        private val ticks = Channel<Unit>()
        suspend fun sleep(milliseconds: Long) { waits.send(milliseconds); ticks.receive() }
        suspend fun waiting() { assertEquals(60_000L, withTimeout(2000) { waits.receive() }) }
        suspend fun tick() { withTimeout(2000) { ticks.send(Unit) } }
        fun wakeIfWaiting() = ticks.trySend(Unit).isSuccess
        fun noTimerWaiting() = !ticks.trySend(Unit).isSuccess
    }
    @Test fun timerPublishesSummaryAfterSyncSoConfirmedFundingBecomesPromptEligible() = runBlocking {
        val activity = Activity(); val minutes = Minutes()
        var sdkConfirmed = 0L
        var summaryConfirmed = 0L
        var refreshes = 0
        activity.lifecycle.addObserver(RecurringForegroundRunner(this, minutes::sleep, refreshSummary = {
            summaryConfirmed = sdkConfirmed; refreshes++
        }) { sdkConfirmed = 150_529 })
        try {
            activity.start(); minutes.waiting()
            assertFalse(com.noahwallet.onchain.AutoBoardRules.shouldPrompt(true, true, true, summaryConfirmed, false))
            minutes.tick(); minutes.waiting()
            assertEquals(1, refreshes)
            assertTrue(com.noahwallet.onchain.AutoBoardRules.shouldPrompt(true, true, true, summaryConfirmed, false))
            activity.stop(); yield(); assertEquals(1, refreshes)
        } finally { activity.stop() }
    }
    @Test fun workerWakeWhileActivityIsStartedCannotRunForegroundStages() = runBlocking {
        val activity = Activity(); val minutes = Minutes()
        val w = RecurringWorld(this); w.add()
        var syncStages = 0
        activity.lifecycle.addObserver(RecurringForegroundRunner(this, minutes::sleep) {
            syncStages += 3
            w.executor.execute(budget = it)
        })
        try {
            // WorkManager/FCM can wake the process without ON_RESUME, even with a STARTED activity.
            activity.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
            yield()
            if (minutes.wakeIfWaiting()) { minutes.waiting(); minutes.waiting() }
            assertEquals(0, syncStages)
            assertEquals(0, w.wallet.calls)
        } finally { activity.stop() }
    }

    @Test fun foregroundReadinessLoadsOffMainAndBackgroundRevokesPendingTick() = runBlocking {
        val activity = Activity(); val minutes = Minutes()
        val entered = CompletableDeferred<Thread>()
        val release = java.util.concurrent.CountDownLatch(1)
        var executions = 0
        val caller = Thread.currentThread()
        activity.lifecycle.addObserver(RecurringForegroundRunner(this, minutes::sleep, isOnboarded = {
            entered.complete(Thread.currentThread())
            check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
            true
        }) { executions++ })
        try {
            activity.start(); minutes.waiting(); minutes.tick()
            assertNotSame(caller, withTimeout(5000) { entered.await() })
            assertEquals(0, executions)
            activity.stop(); release.countDown(); yield()
            assertEquals(0, executions)
            assertTrue(minutes.noTimerWaiting())
        } finally { release.countDown(); activity.stop() }
    }

    @Test fun paysWhenDueWhileResumedAndStopsUntilResumed() = runBlocking {
        val w = RecurringWorld(this); w.add(61_000)
        val activity = Activity(); val minutes = Minutes()
        val runner = RecurringForegroundRunner(this, minutes::sleep) { w.executor.execute(budget = it) }
        activity.lifecycle.addObserver(runner)
        try {
            w.executor.execute(); assertEquals(0, w.wallet.calls)
            activity.start(); runner.onResume(activity); minutes.waiting()
            assertEquals(0, w.wallet.calls)
            w.now += 60_000; minutes.tick(); minutes.waiting()
            assertEquals(1, w.wallet.calls); assertEquals(1, w.schedule!!.occurrencesPaid)
            activity.stop(); yield(); assertTrue(minutes.noTimerWaiting())
            w.now += 86_400_000; yield(); assertEquals(1, w.wallet.calls)
            activity.start(); minutes.waiting(); assertEquals(1, w.wallet.calls)
            minutes.tick(); minutes.waiting()
            assertEquals(2, w.wallet.calls); assertEquals(2, w.schedule!!.occurrencesPaid)
        } finally { activity.stop() }
    }
    @Test fun timerPushScreenAndTimeChangeJoinOneExecution() = runBlocking {
        val w = RecurringWorld(this); w.add()
        val activity = Activity(); val minutes = Minutes()
        activity.lifecycle.addObserver(RecurringForegroundRunner(this, minutes::sleep) { w.executor.execute(budget = it) })
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        w.beforeQuote = { entered.complete(Unit); release.await() }
        try {
            activity.start(); minutes.waiting(); minutes.tick(); entered.await()
            val push = async { w.executor.execute(budget = RecurringBudget.push()) }
            val screen = async { w.executor.execute() }
            val timeChange = async { w.executor.timeChanged(); w.executor.execute() }
            yield(); release.complete(Unit)
            push.await(); screen.await(); timeChange.await(); minutes.waiting()
            assertEquals(1, w.quotes); assertEquals(1, w.wallet.calls); assertEquals(1, w.schedule!!.occurrencesPaid)
            minutes.tick(); minutes.waiting(); assertEquals(1, w.wallet.calls)
        } finally { release.complete(Unit); activity.stop() }
    }
    @Test fun backgroundRevokesAnAwakenedTickBeforeQuoteOrPreparationCanSubmit() = runBlocking {
        for (preparing in listOf(false, true)) {
            val w = RecurringWorld(this); w.add()
            val activity = Activity(); val minutes = Minutes()
            activity.lifecycle.addObserver(RecurringForegroundRunner(this, minutes::sleep) { w.executor.execute(budget = it) })
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            if (preparing) w.wallet.prepareBlock = { entered.complete(Unit); release.await(); w.wallet.snapshot }
            else w.beforeQuote = { entered.complete(Unit); release.await() }
            try {
                activity.start(); minutes.waiting(); minutes.tick(); entered.await()
                activity.stop(); release.complete(Unit)
                withTimeout(2000) { while (w.executor.running) yield() }
                assertEquals(0, w.wallet.calls); assertFalse(w.journal.consumed(w.binding, "rent", w.now))
                assertFalse(w.controller.locked)
                assertTrue(minutes.noTimerWaiting())
            } finally { release.complete(Unit); activity.stop() }
        }
    }
    @Test fun backgroundCannotAbandonAlreadySubmittedSettlement() = runBlocking {
        val w = RecurringWorld(this); w.add()
        val activity = Activity(); val minutes = Minutes()
        activity.lifecycle.addObserver(RecurringForegroundRunner(this, minutes::sleep) { w.executor.execute(budget = it) })
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        w.wallet.payBlock = { entered.complete(Unit); release.await(); SendReceipt(1000, proof = SendReceipt.Proof.submitReturnedArkSuccess) }
        try {
            activity.start(); minutes.waiting(); minutes.tick(); entered.await()
            activity.stop(); release.complete(Unit)
            withTimeout(2000) { while (w.executor.running) yield() }
            assertEquals(1, w.wallet.calls); assertEquals(1, w.schedule!!.occurrencesPaid)
            assertTrue(w.journal.consumed(w.binding, "rent", w.now)); assertFalse(w.controller.locked)
        } finally { release.complete(Unit); activity.stop() }
    }
    @Test fun eachMinuteRechecksSuspensionWalletAndStopGeneration() = runBlocking {
        val w = RecurringWorld(this); w.add()
        val activity = Activity(); val minutes = Minutes()
        activity.lifecycle.addObserver(RecurringForegroundRunner(this, minutes::sleep) { w.executor.execute(budget = it) })
        try {
            activity.start(); minutes.waiting()
            w.allowed = false; minutes.tick(); minutes.waiting(); assertEquals(0, w.wallet.calls)
            w.allowed = true
            val original = w.binding; w.binding = w.binding.copy(instance = UUID.randomUUID().toString())
            minutes.tick(); minutes.waiting(); assertEquals(0, w.wallet.calls)
            w.binding = original
            w.wallet.prepareBlock = { w.generation = UUID.randomUUID().toString(); w.wallet.snapshot }
            minutes.tick(); minutes.waiting(); assertEquals(0, w.wallet.calls)
            assertFalse(w.journal.consumed(w.binding, "rent", w.now))
        } finally { activity.stop() }
    }
    @Test fun pauseCancelAndEditWinOverPendingTimerExecution() = runBlocking {
        for (action in listOf("pause", "cancel", "edit", "suspend", "replace", "restore")) {
            val w = RecurringWorld(this); w.add()
            val activity = Activity(); val minutes = Minutes()
            activity.lifecycle.addObserver(RecurringForegroundRunner(this, minutes::sleep) { w.executor.execute(budget = it) })
            w.wallet.prepareBlock = { w.change(action); w.wallet.snapshot }
            try {
                activity.start(); minutes.waiting(); minutes.tick(); minutes.waiting()
                assertEquals(action, 0, w.wallet.calls)
                assertFalse(w.journal.consumed(w.binding, "rent", w.now))
            } finally { activity.stop() }
        }
    }
}
