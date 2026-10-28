package com.noahwallet.services.push

import com.noahwallet.api.generated.models.*
import com.noahwallet.recurring.RecurringBudget
import com.noahwallet.services.wallet.WalletExecutionBudget
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Collections

class PushStageAdmissionTest {
    @Test fun cancelledMaintenanceCannotStartReportOrWidget() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val events = Collections.synchronizedList(mutableListOf<String>())
        val jobs = PushJobs(dependencies { stage ->
            events += stage
            if (stage == "maintenance") withContext(NonCancellable) {
                entered.complete(Unit); release.await()
                events += "maintenance-settled"
            }
        })
        val work = launch(Dispatchers.IO) { jobs.handle(NotificationDataMaintenance("public-correlation")) }
        entered.await(); work.cancel(); release.complete(Unit); work.join()
        assertEquals(listOf("open", "maintenance", "maintenance-settled"), events.toList())
    }

    @Test fun eachIndependentStageRequiresCallerAndBudget() = runBlocking {
        val paths = listOf(
            NotificationDataMaintenance("k1") to listOf("open", "maintenance", "report", "widget", "exits"),
            NotificationDataLightningInvoiceRequest(1000, "tx") to listOf("open", "invoice", "submit"),
            NotificationDataLightningClaimRequest(1, null, "hash") to listOf("open", "claim", "announce", "widget"),
            NotificationDataBackupTrigger("k1") to listOf("open", "backup", "backup-report"),
            NotificationDataRecurringPaymentDue(1) to listOf("open", "recurring", "widget"),
        )
        for ((notification, stages) in paths) for (stopAt in stages.dropLast(1)) for (cancel in listOf(true, false)) {
            val budget = RecurringBudget()
            val events = mutableListOf<String>()
            var caller: Job? = null
            val jobs = PushJobs(dependencies { stage ->
                events += stage
                if (stage == stopAt) { if (cancel) caller!!.cancel() else budget.revoke() }
            })
            launch(WalletExecutionBudget(budget)) {
                caller = currentCoroutineContext()[Job]
                runCatching { jobs.handle(notification) }
            }.join()
            // A backup that settled still reports after the budget; caller cancellation stops it.
            val settledBackup = notification is NotificationDataBackupTrigger && stopAt == "backup" && !cancel
            val expected = if (settledBackup) stages else stages.takeWhile { it != stopAt } + stopAt
            assertEquals("$notification after $stopAt (cancel=$cancel)", expected, events)
        }
    }

    @Test fun revocationDuringShieldedWidgetReadPreventsPublication() = runBlocking {
        val notifications = listOf(
            NotificationDataMaintenance("k1"),
            NotificationDataLightningClaimRequest(1, null, "hash"),
            NotificationDataRecurringPaymentDue(1),
        )
        for (notification in notifications) for (cancel in listOf(true, false)) {
            val budget = RecurringBudget()
            val events = Collections.synchronizedList(mutableListOf<String>())
            var caller: Job? = null
            val base = dependencies { events += it }
            val jobs = PushJobs(PushJobs.Dependencies(
                ensureWalletOpen = base.ensureWalletOpen, maintenance = base.maintenance,
                createInvoice = base.createInvoice, submitInvoice = base.submitInvoice,
                claimLightning = base.claimLightning, reportMaintenance = base.reportMaintenance,
                heartbeat = base.heartbeat, announceReceived = base.announceReceived,
                // Mirrors WalletSession.withOpen: the read keeps running after the caller is revoked.
                refreshWidget = { admitted ->
                    withContext(NonCancellable) {
                        events += "widget-read"
                        if (cancel) caller!!.cancel() else budget.revoke()
                        admitted()
                        events += "widget-write"
                    }
                },
                log = base.log, backup = base.backup, reportBackup = base.reportBackup, recurring = base.recurring,
            ))
            launch(WalletExecutionBudget(budget)) {
                caller = currentCoroutineContext()[Job]
                runCatching { jobs.handle(notification) }
            }.join()
            assertTrue("$notification (cancel=$cancel)", "widget-read" in events)
            assertFalse("$notification (cancel=$cancel)", "widget-write" in events)
        }
    }

    @Test fun revokedBudgetRejectsEvenHeartbeat() = runBlocking {
        val budget = RecurringBudget().apply { revoke() }
        val events = mutableListOf<String>()
        val result = runCatching {
            withContext(WalletExecutionBudget(budget)) {
                PushJobs(dependencies { events += it }).handle(NotificationDataHeartbeat("id"))
            }
        }
        assertTrue(result.exceptionOrNull() is CancellationException)
        assertTrue(events.isEmpty())
    }

    private fun dependencies(stage: suspend (String) -> Unit) = PushJobs.Dependencies(
        ensureWalletOpen = { stage("open") }, maintenance = { stage("maintenance") },
        createInvoice = { stage("invoice"); "bolt11" }, submitInvoice = { _, _ -> stage("submit") },
        claimLightning = { stage("claim") }, reportMaintenance = { _, _ -> stage("report") },
        heartbeat = { stage("heartbeat") },
        // Notification publication is synchronous; run the same injected boundary inline.
        announceReceived = { _, _, _ -> runBlocking { stage("announce") } },
        refreshWidget = { stage("widget") }, log = {}, backup = { stage("backup") },
        reportBackup = { _, _ -> stage("backup-report") },
        recurring = { stage("recurring") }, progressExits = { stage("exits") },
    )
}
