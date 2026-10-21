package com.noahwallet.services.wallet

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.noahwallet.recurring.RecurringForegroundRunner
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Test

class ForegroundSyncAdmissionTest {
    private class Activity : LifecycleOwner {
        override val lifecycle = LifecycleRegistry.createUnsafe(this)
        fun event(event: Lifecycle.Event) = lifecycle.handleLifecycleEvent(event)
    }

    @Test fun summaryAdmissionStaysRevokedAfterResume() {
        val activity = Activity(); val admission = ForegroundSyncAdmission()
        activity.lifecycle.addObserver(admission)
        activity.event(Lifecycle.Event.ON_RESUME)
        val activation = admission.generation
        admission.checkActive(activation)
        activity.event(Lifecycle.Event.ON_PAUSE)
        activity.event(Lifecycle.Event.ON_RESUME)
        assertThrows(CancellationException::class.java) { admission.checkActive(activation) }
        admission.checkActive(admission.generation)
        activity.event(Lifecycle.Event.ON_STOP)
    }

    @Test fun pauseRevokesEveryDelayedForegroundCallbackEvenAfterAnotherResume() = runBlocking {
        val activity = Activity(); val admission = ForegroundSyncAdmission()
        activity.lifecycle.addObserver(admission)
        activity.event(Lifecycle.Event.ON_RESUME)
        val entered = Channel<Unit>(Channel.UNLIMITED); val release = CompletableDeferred<Unit>()
        var stages = 0
        val work = List(2) { async {
            admission.run { budget ->
                entered.send(Unit); release.await()
                if (budget.available) stages++
            }
        } }
        try {
            withTimeout(5000) { repeat(2) { entered.receive() } }
            activity.event(Lifecycle.Event.ON_PAUSE)
            activity.event(Lifecycle.Event.ON_RESUME)
            release.complete(Unit); work.awaitAll()
            assertEquals(0, stages)
        } finally { release.complete(Unit); activity.event(Lifecycle.Event.ON_STOP) }
    }

    @Test fun pauseRevokesSyncAndRecurringUntilResumed() = runBlocking {
        val activity = Activity()
        val admission = ForegroundSyncAdmission()
        val ticks = Channel<Unit>()
        val waiting = Channel<Unit>(Channel.UNLIMITED)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val flight = WalletSync()
        var firstStages = 0; var laterStages = 0; var recurring = 0
        var settled = false
        activity.lifecycle.addObserver(admission)
        activity.lifecycle.addObserver(RecurringForegroundRunner(this, sleep = {
            assertEquals(60_000L, it); waiting.send(Unit); ticks.receive()
        }) { recurringBudget ->
            admission.run { budget ->
                withContext(WalletExecutionBudget(budget)) {
                    WalletBackgroundActivity.during {
                        flight.run(
                            { firstStages++; entered.complete(Unit); withContext(NonCancellable) { release.await(); settled = true } },
                            { laterStages++ }, { laterStages++ },
                        )
                    }
                }
            }
            if (recurringBudget.available) recurring++
        })
        try {
            activity.event(Lifecycle.Event.ON_RESUME)
            withTimeout(5000) { waiting.receive(); ticks.send(Unit); entered.await() }
            activity.event(Lifecycle.Event.ON_PAUSE)
            assertFalse(settled)
            assertTrue(WalletBackgroundActivity.isRunning)
            assertEquals(0, recurring)
            release.complete(Unit)
            withTimeout(5000) { while (!settled || WalletBackgroundActivity.isRunning) yield() }
            yield()
            assertTrue(settled)
            assertFalse(WalletBackgroundActivity.isRunning)
            assertEquals(0, laterStages)
            assertEquals(0, recurring)
            assertFalse(ticks.trySend(Unit).isSuccess)
            assertEquals(1, firstStages)
            activity.event(Lifecycle.Event.ON_RESUME)
            withTimeout(5000) { waiting.receive(); ticks.send(Unit); waiting.receive() }
            assertEquals(2, firstStages)
            assertEquals(2, laterStages)
            assertEquals(1, recurring)
        } finally { release.complete(Unit); activity.event(Lifecycle.Event.ON_STOP) }
    }
}
