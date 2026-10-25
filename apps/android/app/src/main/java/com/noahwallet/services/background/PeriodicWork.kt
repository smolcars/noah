package com.noahwallet.services.background

import android.content.Context
import androidx.work.*
import com.noahwallet.services.AppServices
import com.noahwallet.services.logging.AppLog
import com.noahwallet.recurring.RecurringBudget
import com.noahwallet.services.wallet.WalletExecutionBudget
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit

/** WorkManager persists these requests and restores them after reboot/package replacement. */
object PeriodicWork {
    enum class Kind(val workName: String, val intervalMinutes: Long) {
        refresh("noah-wallet-refresh", 60), maintenance("noah-wallet-maintenance", 6 * 60)
    }
    internal const val KIND = "kind"

    fun schedule(manager: WorkManager) {
        for (kind in Kind.entries) {
            val request = PeriodicWorkRequestBuilder<PeriodicWalletWorker>(kind.intervalMinutes, TimeUnit.MINUTES)
                .setInitialDelay(kind.intervalMinutes, TimeUnit.MINUTES)
                .setInputData(workDataOf(KIND to kind.name))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            // KEEP preserves both the running instance and its next due time on every launch.
            manager.enqueueUniquePeriodicWork(kind.workName, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }

    fun cancel(manager: WorkManager) {
        for (kind in Kind.entries) manager.cancelUniqueWork(kind.workName).result.get()
    }

    suspend fun reconcile(context: Context, services: AppServices) {
        services.lifecycle.withWalletMutation {
            val manager = WorkManager.getInstance(context)
            if (services.backgroundWalletEligible()) schedule(manager) else cancel(manager)
        }
    }
}

class PeriodicWalletWorker internal constructor(context: Context, parameters: WorkerParameters,
    private val makeJobs: () -> PeriodicJobs,
) : CoroutineWorker(context, parameters) {
    constructor(context: Context, parameters: WorkerParameters) : this(context, parameters, {
        PeriodicRuntime(context, AppServices.shared(context)).jobs
    })
    private val budget = RecurringBudget.push()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO + WalletExecutionBudget(budget)) {
        val kind = inputData.getString(PeriodicWork.KIND)?.let { raw -> PeriodicWork.Kind.entries.find { it.name == raw } }
            ?: return@withContext Result.failure()
        val log = AppLog(applicationContext, "backgroundTasks")
        try {
            makeJobs().run(kind, budget) { isStopped }
            Result.success()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            // A later periodic wake is fresh admission, never a retry of an ambiguous payment.
            log.w("Periodic wallet work failed")
            Result.failure()
        } finally { budget.revoke() }
    }
}
