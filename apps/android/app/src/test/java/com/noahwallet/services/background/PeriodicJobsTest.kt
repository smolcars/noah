package com.noahwallet.services.background

import com.noahwallet.recurring.RecurringBudget
import com.noahwallet.services.launch.LaunchState
import com.noahwallet.services.storage.PersistedWalletState
import com.noahwallet.services.wallet.WalletBackgroundActivity
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class PeriodicJobsTest {
    @After fun resetForeground() { PeriodicExecution.foreground(false) }

    @Test fun homeAdmissionRefusesEveryUnsafeState() {
        fun allowed(launch: LaunchState = LaunchState.existingWallet, wallet: PersistedWalletState? = null,
            onboarded: Boolean = true, restore: Boolean = false, deletion: Boolean = false, failed: Boolean = false) =
            BackgroundWalletAdmission.allowed(launch, wallet, onboarded, restore, deletion, failed)
        assertTrue(allowed()) // RN upgrades need not have an initialization flag.
        assertTrue(allowed(wallet = PersistedWalletState(isInitialized = true)))
        LaunchState.entries.filter { it != LaunchState.existingWallet }.forEach { assertFalse(it.name, allowed(launch = it)) }
        assertFalse(allowed(wallet = PersistedWalletState(isInitialized = false)))
        assertFalse(allowed(wallet = PersistedWalletState(isWalletSuspended = true)))
        assertFalse(allowed(onboarded = false))
        assertFalse(allowed(restore = true))
        assertFalse(allowed(deletion = true))
        assertFalse(allowed(failed = true))
    }

    @Test fun refreshOrdersStagesAndSkipsRecurringWhenNothingIsDue() = runBlocking {
        for (due in listOf(false, true)) {
            val stages = mutableListOf<String>()
            PeriodicJobs(dependencies(due = due) { stages += it }).run(PeriodicWork.Kind.refresh, RecurringBudget())
            assertEquals(listOf("eligible", "due", "open", "sync") +
                (if (due) listOf("recurring") else emptyList()) + listOf("summary", "expiry", "widget", "exits"), stages)
        }
    }

    @Test fun maintenanceUsesEnabledBackupOnly() = runBlocking {
        for (enabled in listOf(false, true)) {
            val stages = mutableListOf<String>()
            PeriodicJobs(dependencies(backupEnabled = enabled) { stages += it }).run(PeriodicWork.Kind.maintenance, RecurringBudget())
            assertEquals(listOf("eligible", "due", "open", "sync", "maintenance", "recurring", "backup-enabled") +
                (if (enabled) listOf("backup") else emptyList()) + listOf("summary", "expiry", "widget", "exits"), stages)
        }
    }

    @Test fun ineligibleWalletNeverReadsPoliciesOrOpens() = runBlocking {
        val stages = mutableListOf<String>()
        PeriodicJobs(dependencies(eligible = false) { stages += it }).run(PeriodicWork.Kind.refresh, RecurringBudget())
        assertEquals(listOf("eligible"), stages)
    }

    @Test fun stopAndRevocationGateEveryIndependentStage() = runBlocking {
        val expected = listOf("eligible", "due", "open", "sync", "maintenance", "recurring", "backup-enabled", "backup", "summary", "expiry", "widget", "exits")
        for (stopAt in expected.dropLast(1)) for (stop in listOf(true, false)) {
            val budget = RecurringBudget()
            val stages = mutableListOf<String>()
            var stopped = false
            val jobs = PeriodicJobs(dependencies { stage ->
                stages += stage
                if (stage == stopAt) { if (stop) stopped = true else budget.revoke() }
            })
            val outcome = runCatching { jobs.run(PeriodicWork.Kind.maintenance, budget) { stopped } }
            assertTrue("$stopAt: $outcome", outcome.exceptionOrNull() is CancellationException)
            assertEquals("$stopAt (stop=$stop)", expected.takeWhile { it != stopAt } + stopAt, stages)
        }
    }

    @Test fun alreadyStoppedOrExpiredDoesNoWork() = runBlocking {
        for (stopped in listOf(true, false)) {
            val stages = mutableListOf<String>()
            val budget = if (stopped) RecurringBudget() else RecurringBudget(1) { 2 }
            assertTrue(runCatching { PeriodicJobs(dependencies { stages += it })
                .run(PeriodicWork.Kind.refresh, budget) { stopped } }.exceptionOrNull() is CancellationException)
            assertTrue(stages.isEmpty())
        }
    }

    @Test fun cancellationRevokesBudgetBeforeDetachedRecurringSettles() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val budget = RecurringBudget(); val stages = mutableListOf<String>()
        val worker = launch {
            PeriodicJobs(dependencies { stage ->
                stages += stage
                if (stage == "recurring") { entered.complete(Unit); release.await(); stages += "settled" }
            }).run(PeriodicWork.Kind.refresh, budget)
        }
        entered.await(); worker.cancel()
        assertFalse(budget.available)
        assertTrue(WalletBackgroundActivity.isRunning)
        assertFalse(worker.isCompleted)
        release.complete(Unit); worker.join()
        assertEquals(listOf("eligible", "due", "open", "sync", "recurring", "settled"), stages)
        assertFalse(WalletBackgroundActivity.isRunning)
    }

    @Test fun foregroundRevokesAndAwaitsSettlementWithoutAdmittingAnotherJob() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val budget = RecurringBudget(); val stages = mutableListOf<String>()
        val worker = launch {
            runCatching { PeriodicJobs(dependencies { stage ->
                stages += stage
                if (stage == "sync") withContext(NonCancellable) { entered.complete(Unit); release.await() }
            }).run(PeriodicWork.Kind.refresh, budget) }
        }
        entered.await(); PeriodicExecution.foreground(true)
        assertFalse(budget.available)
        val waiting = async { PeriodicExecution.awaitIdle() }
        yield(); assertFalse(waiting.isCompleted)
        PeriodicJobs(dependencies { error("Foreground admitted $it") }).run(PeriodicWork.Kind.refresh, RecurringBudget())
        release.complete(Unit); worker.join(); waiting.await()
        assertEquals(listOf("eligible", "due", "open", "sync"), stages)
    }

    @Test fun queuedWorkRechecksRevocationAfterPushReleasesQueue() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val push = launch { WalletBackgroundActivity.exclusive { entered.complete(Unit); release.await() } }
        entered.await()
        val budget = RecurringBudget(); val stages = mutableListOf<String>()
        val worker = launch { runCatching { PeriodicJobs(dependencies { stages += it }).run(PeriodicWork.Kind.refresh, budget) } }
        yield(); budget.revoke(); release.complete(Unit); push.join(); worker.join()
        assertTrue(stages.isEmpty())
    }

    companion object {
        internal fun dependencies(eligible: Boolean = true, due: Boolean = true, backupEnabled: Boolean = true,
            stage: suspend (String) -> Unit = {}) = PeriodicJobs.Dependencies(
            eligible = { stage("eligible"); eligible }, recurringDue = { stage("due"); due },
            ensureWalletOpen = { stage("open") }, sync = { stage("sync") }, recurring = { stage("recurring") },
            widgetSummary = { stage("summary") }, widgetExpiry = { stage("expiry") }, publishWidget = { stage("widget") },
            maintenance = { stage("maintenance") }, backupEnabled = { runBlocking { stage("backup-enabled") }; backupEnabled },
            backup = { stage("backup") }, progressExits = { stage("exits") },
        )
    }
}
