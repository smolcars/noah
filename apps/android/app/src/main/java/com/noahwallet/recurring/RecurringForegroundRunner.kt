package com.noahwallet.recurring

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The activity owns the timer; the shared executor owns payment settlement. */
internal class RecurringForegroundRunner(private val scope: CoroutineScope,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val refreshSummary: suspend () -> Unit = {},
    private val isOnboarded: () -> Boolean = { true },
    private val execute: suspend (RecurringBudget) -> Unit) : DefaultLifecycleObserver {
    private var task: Job? = null
    private var budget: RecurringBudget? = null

    override fun onResume(owner: LifecycleOwner) {
        if (task != null) return
        task = scope.launch {
            while (isActive) {
                sleep(60_000)
                ensureActive()
                if (!owner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return@launch
                val tick = RecurringBudget()
                budget = tick
                try { if (withContext(Dispatchers.IO) { isOnboarded() } && tick.available &&
                    owner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                    execute(tick)
                    if (tick.available) refreshSummary()
                } }
                finally { tick.revoke(); if (budget === tick) budget = null }
            }
        }
    }

    override fun onPause(owner: LifecycleOwner) {
        budget?.revoke(); budget = null
        task?.cancel(); task = null
    }
}
