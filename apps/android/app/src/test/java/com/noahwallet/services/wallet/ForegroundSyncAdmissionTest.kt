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

    @Test fun pauseRevokesLaterStagesAndTicksWhileRecurringKeepsStartedLifetime() = runBlocking {
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
                            { firstStages++; entered.complete(Unit); release.await(); settled = true },
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
            withTimeout(5000) { waiting.receive() }
            assertTrue(settled)
            assertFalse(WalletBackgroundActivity.isRunning)
            assertEquals(0, laterStages)
            assertEquals(1, recurring)
            withTimeout(5000) { ticks.send(Unit); waiting.receive() }
            assertEquals(1, firstStages)
            assertEquals(2, recurring)
            activity.event(Lifecycle.Event.ON_RESUME)
            withTimeout(5000) { ticks.send(Unit); waiting.receive() }
            assertEquals(2, firstStages)
            assertEquals(2, laterStages)
            assertEquals(3, recurring)
        } finally { release.complete(Unit); activity.event(Lifecycle.Event.ON_STOP) }
    }
}
