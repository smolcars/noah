package com.noahwallet.services.wallet

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.noahwallet.recurring.RecurringBudget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Sync has stricter foreground admission than recurring payments' STARTED lifetime. */
internal class ForegroundSyncAdmission : DefaultLifecycleObserver {
    private var resumed = false
    private var budget: RecurringBudget? = null

    override fun onResume(owner: LifecycleOwner) { resumed = true }
    override fun onPause(owner: LifecycleOwner) {
        resumed = false
        budget?.revoke()
    }

    suspend fun run(operation: suspend (RecurringBudget) -> Unit) {
        if (!resumed) return
        val tick = RecurringBudget()
        budget = tick
        try { operation(tick) }
        catch (cancelled: CancellationException) {
            // Sync revocation must not cancel recurring payments' STARTED timer.
            currentCoroutineContext().ensureActive()
            if (tick.available) throw cancelled
        }
        finally { tick.revoke(); if (budget === tick) budget = null }
    }
}
