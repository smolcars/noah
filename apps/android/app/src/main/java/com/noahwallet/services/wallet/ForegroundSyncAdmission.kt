package com.noahwallet.services.wallet

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.noahwallet.recurring.RecurringBudget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Every foreground trigger requires RESUMED admission, retained through native settlement. */
internal class ForegroundSyncAdmission : DefaultLifecycleObserver {
    @Volatile private var resumed = false
    @Volatile internal var generation = 0L; private set
    private val budgets = mutableSetOf<RecurringBudget>()

    override fun onResume(owner: LifecycleOwner) { resumed = true }
    override fun onPause(owner: LifecycleOwner) {
        resumed = false
        generation++
        budgets.forEach { it.revoke() }
    }

    internal fun checkActive(generation: Long = this.generation) {
        if (!resumed || generation != this.generation) throw CancellationException()
    }

    suspend fun run(operation: suspend (RecurringBudget) -> Unit) {
        if (!resumed) return
        val tick = RecurringBudget().apply { admission = { resumed } }
        budgets.add(tick)
        try { operation(tick) }
        catch (cancelled: CancellationException) {
            // Admission revocation leaves already admitted native settlement owned.
            currentCoroutineContext().ensureActive()
            if (tick.available) throw cancelled
        }
        finally { tick.revoke(); budgets.remove(tick) }
    }
}
