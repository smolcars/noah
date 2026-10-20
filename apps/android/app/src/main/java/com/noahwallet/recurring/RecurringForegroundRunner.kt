package com.noahwallet.recurring

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.*

/** The activity owns the timer; the shared executor owns payment settlement. */
internal class RecurringForegroundRunner(private val scope: CoroutineScope,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val execute: suspend (RecurringBudget) -> Unit) : DefaultLifecycleObserver {
    private var task: Job? = null
    private var budget: RecurringBudget? = null

    override fun onStart(owner: LifecycleOwner) {
        if (task != null) return
        task = scope.launch {
            while (isActive) {
                sleep(60_000)
                ensureActive()
                val tick = RecurringBudget()
                budget = tick
                try { execute(tick) }
                finally { tick.revoke(); if (budget === tick) budget = null }
            }
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        budget?.revoke(); budget = null
        task?.cancel(); task = null
    }
}
