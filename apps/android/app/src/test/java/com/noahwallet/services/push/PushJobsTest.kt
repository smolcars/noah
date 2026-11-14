package com.noahwallet.services.push

import com.noahwallet.api.generated.models.*
import com.noahwallet.history.HistoryRules
import com.noahwallet.recurring.RecurringBudget
import com.noahwallet.send.FakeSendWallet
import com.noahwallet.send.PaymentEncoding
import com.noahwallet.send.SendController
import com.noahwallet.send.SendLightningSubmission
import com.noahwallet.send.SendPayment
import com.noahwallet.send.SendPendingWatch
import com.noahwallet.send.SendReconciliation
import com.noahwallet.send.SendSnapshot
import com.noahwallet.send.TestJournal
import com.noahwallet.send.controller
import com.noahwallet.send.invoice
import com.noahwallet.send.movement
import com.noahwallet.services.background.SendPendingNotifications
import com.noahwallet.services.wallet.WalletExecutionBudget
import io.github.niteshbalusu11.bark.LightningSend
import io.github.niteshbalusu11.bark.LightningSendStatus
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Date

class PushJobsTest {
    private val events = mutableListOf<String>()
    private var openFails = false
    private var reportFails = false
    private var cancelled = false
    private var afterOpen: () -> Unit = {}
    private var afterBackup: () -> Unit = {}
    private var reportBackupWait: suspend () -> Unit = {}
    private var reportAllowance = PushJobs.REPORT_ALLOWANCE_MILLIS
    private var sendPending: suspend () -> Boolean = { false }
    private var checkSend: suspend (() -> Unit) -> Boolean = { events += "check"; true }
    private var showSend: () -> Unit = { events += "show" }
    private val jobs get() = PushJobs(PushJobs.Dependencies(
        ensureWalletOpen = {
            events += "open"
            afterOpen()
            if (cancelled) throw CancellationException()
            if (openFails) error("private provider error")
        },
        backup = { events += "backup"; afterBackup() },
        recurring = { assertTrue(it.available); events += "recurring" },
        reportBackup = { k1, success -> events += "backup-report:$k1:$success"; reportBackupWait() },
        maintenance = { events += "maintenance" },
        createInvoice = { events += "invoice:$it"; "bolt11" },
        submitInvoice = { id, invoice -> events += "submit:$id:$invoice" },
        claimLightning = { events += "claim" },
        reportMaintenance = { k1, success -> events += "report:$k1:$success"; if (reportFails) error("report") },
        heartbeat = { events += "heartbeat:$it"; error("offline") },
        announceReceived = { sats, usd, hash -> events += "announce:$sats:$usd:$hash" },
        refreshWidget = { events += "widget" }, log = { events += "log" }, progressExits = { events += "exits" },
        lightningSendPending = { sendPending() }, checkLightningSend = { checkSend(it) }, showLightningSend = { showSend() },
    ), reportAllowance)
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
    @Test fun sendFinishedWithNothingPendingOpensNothing() = runBlocking {
        jobs.handle(PushPayload.decode("""{"notification_type":"lightning_send_finished","notification_id":"f1"}"""))
        assertTrue(events.isEmpty())
    }
    @Test fun sendFinishedChecksThenShowsTheResult() = runBlocking {
        sendPending = { true }
        jobs.handle(NotificationDataLightningSendFinished("f1"))
        assertEquals(listOf("open", "check", "show", "widget"), events)
        // Still paying: the service's notification stands.
        events.clear()
        checkSend = { events += "check"; false }
        jobs.handle(NotificationDataLightningSendFinished("f2"))
        assertEquals(listOf("open", "check"), events)
    }
    /**
     * Against Send's own controller, wired as the live job is: the push records bark's result, the notification is
     * given that result, and nothing pays. A second push finds nothing pending.
     */
    @Test fun sendFinishedRecordsTheResultWithoutPaying() = runBlocking {
        for ((status, outcome) in listOf("successful" to SendPendingNotifications.Outcome.sent, "failed" to SendPendingNotifications.Outcome.failed)) {
            events.clear()
            val hash = PaymentEncoding.invoice(invoice())!!.hash
            val send = LightningSend(hash, emptyList(), 5u, invoice(), 1000u, 79u, 1u, false)
            // bark took the payment and returned it in progress, as SendLightningPendingTest's bark does.
            var paying: LightningSend? = send
            var moved = movement(status = "pending", id = 5u).copy(effectiveBalanceSats = -1079)
            val wallet = FakeSendWallet()
            wallet.prepareBlock = { SendSnapshot(walletIdentity = wallet.owner, invoicePaid = false) }
            wallet.payBlock = { throw SendLightningSubmission.handedOver(send, 1000) }
            wallet.reconciliation = { attempt ->
                SendReconciliation.pending(attempt, attempt.pending!!,
                    paying?.let { LightningSendStatus.InProgress(it) } ?: LightningSendStatus.Unknown, listOf(moved))
            }
            val subject = controller(wallet, TestJournal(), this)
            subject.submit(SendPayment(invoice(), "lightning", 1000), FakeSendWallet.quote.copy(fee = 79, total = 1079))!!.await()
            assertTrue(subject.lightningSendPending)
            paying = null
            moved = moved.copy(status = status, effectiveBalanceSats = if (status == "successful") -1079 else 0,
                completedAt = HistoryRules.isoDate(Date(System.currentTimeMillis() + 1_000)))

            sendPending = { subject.lightningSendPending }
            checkSend = { SendPendingWatch.checkOnce(subject, it) }
            showSend = { events += "show:${SendPendingNotifications.Outcome.of(subject.state, subject.attempt?.pending != null)}" }
            jobs.handle(NotificationDataLightningSendFinished("f1"))
            jobs.handle(NotificationDataLightningSendFinished("f2"))
            assertEquals(listOf("open", "show:$outcome", "widget"), events)
            assertEquals(if (status == "successful") SendController.State.succeeded else SendController.State.failed, subject.state)
            assertEquals("a push never pays", 1, wallet.calls)
        }
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
    @Test fun backupSettlingAfterTheBudgetIsStillReported() = runBlocking {
        val budget = RecurringBudget()
        afterBackup = { budget.revoke() }
        withContext(WalletExecutionBudget(budget)) { jobs.handle(NotificationDataBackupTrigger("k1")) }
        assertEquals(listOf("open", "backup", "backup-report:k1:true"), events)
    }
    @Test fun backupFailingAfterTheBudgetIsReportedAsFailed() = runBlocking {
        val budget = RecurringBudget()
        afterBackup = { budget.revoke(); error("upload failed") }
        val result = runCatching { withContext(WalletExecutionBudget(budget)) { jobs.handle(NotificationDataBackupTrigger("k1")) } }
        assertEquals("upload failed", result.exceptionOrNull()?.message)
        assertEquals(listOf("open", "backup", "backup-report:k1:false"), events)
    }
    @Test fun backupNeverStartedIsNotReportedAfterTheBudget() = runBlocking {
        for (fails in listOf(false, true)) {
            events.clear()
            openFails = fails
            val budget = RecurringBudget()
            afterOpen = { budget.revoke() }
            val result = runCatching { withContext(WalletExecutionBudget(budget)) { jobs.handle(NotificationDataBackupTrigger("k1")) } }
            assertTrue(result.exceptionOrNull() is CancellationException)
            assertEquals(listOf("open"), events)
        }
    }
    @Test fun backupReportIsBoundedByItsAllowance() = runBlocking {
        reportAllowance = 100
        reportBackupWait = {
            try { delay(60_000) }
            catch (cancelled: CancellationException) { events += "report-cancelled"; throw cancelled }
        }
        jobs.handle(NotificationDataBackupTrigger("k1"))
        assertEquals(listOf("open", "backup", "backup-report:k1:true", "report-cancelled", "log"), events)
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
