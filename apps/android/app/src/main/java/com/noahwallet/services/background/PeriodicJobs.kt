package com.noahwallet.services.background

import com.noahwallet.recurring.RecurringBudget
import com.noahwallet.services.wallet.WalletBackgroundActivity
import com.noahwallet.services.wallet.WalletExecutionBudget
import com.noahwallet.services.wallet.checkWalletAdmission
import kotlinx.coroutines.*

/** Only stage boundaries stop; admitted native calls and detached executors must settle. */
class PeriodicJobs(private val d: Dependencies) {
    class Dependencies(
        val eligible: suspend () -> Boolean,
        val recurringDue: suspend () -> Boolean,
        val ensureWalletOpen: suspend () -> Unit,
        val sync: suspend () -> Unit,
        val recurring: suspend (RecurringBudget) -> Unit,
        val widgetSummary: suspend () -> Unit,
        val widgetExpiry: suspend () -> Unit,
        val publishWidget: suspend () -> Unit,
        val maintenance: suspend () -> Unit,
        val backupEnabled: () -> Boolean,
        val backup: suspend () -> Unit,
        /** Pending exits' progress; never throws but for cancellation. */
        val progressExits: suspend () -> Unit = {},
    )

    suspend fun run(kind: PeriodicWork.Kind, budget: RecurringBudget, stopped: () -> Boolean = { false }) =
        withContext(WalletExecutionBudget(budget)) {
            val caller = currentCoroutineContext()
            fun checkStage() {
                if (stopped()) { budget.revoke(); throw CancellationException() }
                caller.checkWalletAdmission()
            }
            PeriodicExecution.run(budget) {
                WalletBackgroundActivity.exclusive {
                    checkStage()
                    if (!d.eligible()) return@exclusive
                    checkStage()
                    // Read protected policies before any wallet open or network request.
                    val recurringDue = d.recurringDue()
                    checkStage(); d.ensureWalletOpen()
                    checkStage(); d.sync()
                    if (kind == PeriodicWork.Kind.maintenance) {
                        checkStage(); d.maintenance()
                    }
                    checkStage()
                    if (recurringDue) withContext(NonCancellable) { d.recurring(budget) }
                    checkStage()
                    if (kind == PeriodicWork.Kind.maintenance && d.backupEnabled()) {
                        checkStage()
                        // Retain ownership until BackupCoordinator's detached pass settles.
                        withContext(NonCancellable) { d.backup() }
                    }
                    checkStage(); d.widgetSummary()
                    checkStage(); d.widgetExpiry()
                    checkStage(); d.publishWidget()
                    // Last: exits move here even when bark's daemon isn't running.
                    checkStage(); d.progressExits()
                }
            }
        }
}
