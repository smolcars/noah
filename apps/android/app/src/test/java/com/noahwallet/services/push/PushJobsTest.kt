package com.noahwallet.services.push

import com.noahwallet.api.generated.models.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PushJobsTest {
    private val events = mutableListOf<String>()
    private var openFails = false
    private var reportFails = false
    private var cancelled = false
    private val jobs get() = PushJobs(PushJobs.Dependencies(
        ensureWalletOpen = {
            events += "open"
            if (cancelled) throw CancellationException()
            if (openFails) error("private provider error")
        },
        backup = { events += "backup" },
        recurring = { assertTrue(it.available); events += "recurring" },
        reportBackup = { k1, success -> events += "backup-report:$k1:$success" },
        maintenance = { events += "maintenance" },
        createInvoice = { events += "invoice:$it"; "bolt11" },
        submitInvoice = { id, invoice -> events += "submit:$id:$invoice" },
        claimLightning = { events += "claim" },
        reportMaintenance = { k1, success -> events += "report:$k1:$success"; if (reportFails) error("report") },
        heartbeat = { events += "heartbeat:$it"; error("offline") },
        announceReceived = { sats, usd, hash -> events += "announce:$sats:$usd:$hash" },
        refreshWidget = { events += "widget" }, log = { events += "log" }, progressExits = { events += "exits" },
    ))
    @Test fun maintenanceReportsSuccessThenRefreshes() = runBlocking {
        jobs.handle(NotificationDataMaintenance("correlation"))
        assertEquals(listOf("open", "maintenance", "report:correlation:true", "widget", "exits"), events)
    }
    @Test fun openFailureStillReportsAndPropagates() = runBlocking {
        openFails = true
        assertTrue(runCatching { jobs.handle(NotificationDataMaintenance("k1")) }.isFailure)
        assertEquals(listOf("open", "report:k1:false"), events)
    }
    @Test fun reportFailureDoesNotUndoMaintenanceOrSkipWidget() = runBlocking {
        reportFails = true
        jobs.handle(NotificationDataMaintenance("k1"))
        assertEquals(listOf("open", "maintenance", "report:k1:true", "log", "widget", "exits"), events)
    }
    @Test fun invoiceTruncatesMillisatsBeforeSubmitting() = runBlocking {
        jobs.handle(PushPayload.decode("""{"notification_type":"lightning_invoice_request","amount":1234999,"transaction_id":"tx"}"""))
        assertEquals(listOf("open", "invoice:1234", "submit:tx:bolt11"), events)
    }
    @Test fun claimAnnouncesAfterSuccessAndUpdatesWidget() = runBlocking {
        jobs.handle(PushPayload.decode("""{"notification_type":"lightning_claim_request","amount_sat":42,"amount_usd":"0.10","payment_hash":"hash"}"""))
        assertEquals(listOf("open", "claim", "announce:42:0.10:hash", "widget"), events)
    }
    @Test fun heartbeatSwallowsFailureWithoutOpeningWallet() = runBlocking {
        jobs.handle(NotificationDataHeartbeat("id"))
        assertEquals(listOf("heartbeat:id", "log"), events)
    }
    @Test fun backupReportsSuccessAndRecurringOpensThenExecutes() = runBlocking {
        jobs.handle(NotificationDataBackupTrigger("k1"))
        jobs.handle(NotificationDataRecurringPaymentDue(1))
        assertEquals(listOf("open", "backup", "backup-report:k1:true", "open", "recurring", "widget"), events)
    }
    @Test fun backupOpenFailureReportsFailure() = runBlocking {
        openFails = true
        assertTrue(runCatching { jobs.handle(NotificationDataBackupTrigger("k1")) }.isFailure)
        assertEquals(listOf("open", "backup-report:k1:false"), events)
    }
    @Test fun cancelledBackgroundEntryCannotStartWalletOpen() = runBlocking {
        val work = launch(start = CoroutineStart.UNDISPATCHED) {
            currentCoroutineContext().cancel()
            assertTrue(runCatching { jobs.handle(NotificationDataMaintenance("k1")) }.exceptionOrNull() is CancellationException)
        }
        work.join()
        assertTrue(events.isEmpty())
    }

    @Test fun cancellationDoesNotBecomeAReportedFailure() = runBlocking {
        cancelled = true
        assertTrue(runCatching { jobs.handle(NotificationDataMaintenance("k1")) }.exceptionOrNull() is CancellationException)
        assertEquals(listOf("open"), events)
    }
}
