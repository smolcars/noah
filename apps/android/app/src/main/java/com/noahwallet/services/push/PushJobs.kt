package com.noahwallet.services.push

import com.noahwallet.api.generated.models.*
import com.noahwallet.services.wallet.WalletExecutionBudget
import com.noahwallet.services.wallet.checkWalletAdmission
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.CoroutineContext

/** The two transports share routing, including status reporting when wallet open fails. */
class PushJobs(private val dependencies: Dependencies, private val reportAllowanceMillis: Long = REPORT_ALLOWANCE_MILLIS) {
    companion object {
        /** A settled backup's report may run this long, past the budget if need be: room for its 404 backoff (0.25 + 1 + 2 s); push workers get minutes. */
        const val REPORT_ALLOWANCE_MILLIS = 5_000L
    }

    data class Dependencies(
        val ensureWalletOpen: suspend () -> Unit,
        val maintenance: suspend () -> Unit,
        val createInvoice: suspend (ULong) -> String,
        val submitInvoice: suspend (String, String) -> Unit,
        val claimLightning: suspend () -> Unit,
        val reportMaintenance: suspend (String, Boolean) -> Unit,
        val heartbeat: suspend (String) -> Unit,
        val announceReceived: (Long, String?, String) -> Unit,
        /** [admitted] throws once the caller or budget is revoked; check it between reads and before writing. */
        val refreshWidget: suspend (admitted: () -> Unit) -> Unit,
        val log: (String) -> Unit,
        val backup: suspend () -> Unit,
        val reportBackup: suspend (String, Boolean) -> Unit,
        val recurring: suspend (com.noahwallet.recurring.RecurringBudget) -> Unit = {},
        /** Pending exits' progress; never throws but for cancellation. */
        val progressExits: suspend () -> Unit = {},
    )

    suspend fun handle(notification: NotificationData) = withContext(
        currentCoroutineContext()[WalletExecutionBudget] ?: WalletExecutionBudget(com.noahwallet.recurring.RecurringBudget.push())
    ) {
        com.noahwallet.services.wallet.WalletBackgroundActivity.exclusive { handleNotification(notification) }
    }

    private suspend fun handleNotification(notification: NotificationData) {
        val caller = currentCoroutineContext()
        caller.checkWalletAdmission()
        when (notification) {
            is NotificationDataMaintenance -> maintenance(caller, notification)
            is NotificationDataLightningInvoiceRequest -> invoiceRequest(caller, notification)
            is NotificationDataLightningClaimRequest -> claimRequest(caller, notification)
            is NotificationDataHeartbeat -> bestEffort("Failed to answer heartbeat") {
                dependencies.heartbeat(notification.notificationId)
            }
            is NotificationDataBackupTrigger -> backupTrigger(caller, notification)
            is NotificationDataRecurringPaymentDue -> recurringDue(caller)
        }
    }

    private suspend fun maintenance(caller: CoroutineContext, notification: NotificationDataMaintenance) {
        val d = dependencies
        val failure = failureOf {
            d.ensureWalletOpen()
            caller.checkWalletAdmission()
            d.maintenance()
        }
        bestEffort("Failed to report maintenance status") {
            d.reportMaintenance(notification.notificationK1, failure == null)
        }
        if (failure != null) throw failure
        bestEffort("Failed to refresh widget") { d.refreshWidget { caller.checkWalletAdmission() } }
        // Last: exits move here even when bark's daemon isn't running.
        caller.checkWalletAdmission()
        d.progressExits()
    }

    private suspend fun invoiceRequest(caller: CoroutineContext, notification: NotificationDataLightningInvoiceRequest) {
        val d = dependencies
        require(notification.amount >= 0)
        d.ensureWalletOpen()
        caller.checkWalletAdmission()
        val invoice = d.createInvoice(notification.amount.toULong() / 1000u)
        caller.checkWalletAdmission()
        d.submitInvoice(notification.transactionId, invoice)
    }

    private suspend fun claimRequest(caller: CoroutineContext, notification: NotificationDataLightningClaimRequest) {
        val d = dependencies
        require(notification.amountSat >= 0)
        d.ensureWalletOpen()
        caller.checkWalletAdmission()
        d.claimLightning()
        bestEffort("Failed to announce Lightning receive") {
            d.announceReceived(notification.amountSat, notification.amountUsd, notification.paymentHash)
        }
        bestEffort("Failed to refresh widget") { d.refreshWidget { caller.checkWalletAdmission() } }
    }

    private suspend fun backupTrigger(caller: CoroutineContext, notification: NotificationDataBackupTrigger) {
        val d = dependencies
        var attempted = false
        val failure = failureOf {
            d.ensureWalletOpen()
            caller.checkWalletAdmission()
            attempted = true
            d.backup()
        }
        // A backup that settled is reported even after the budget; only its report gets the allowance.
        if (!attempted) caller.checkWalletAdmission()
        withReportAllowance("Failed to report backup status") { d.reportBackup(notification.notificationK1, failure == null) }
        if (failure != null) throw failure
    }

    private suspend fun recurringDue(caller: CoroutineContext) = com.noahwallet.recurring.RecurringPushDeadline().run { budget ->
        val d = dependencies
        currentCoroutineContext().ensureActive()
        caller.checkWalletAdmission()
        requireBudget(budget)
        d.ensureWalletOpen()
        currentCoroutineContext().ensureActive()
        caller.checkWalletAdmission()
        requireBudget(budget)
        d.recurring(budget)
        caller.checkWalletAdmission()
        requireBudget(budget)
        bestEffort("Failed to refresh widget") {
            d.refreshWidget {
                caller.checkWalletAdmission()
                requireBudget(budget)
            }
        }
    }

    private fun requireBudget(budget: com.noahwallet.recurring.RecurringBudget) {
        if (!budget.available) throw CancellationException()
    }

    /** What [block] threw, so it can be reported before it's rethrown; cancellation propagates. */
    private suspend fun failureOf(block: suspend () -> Unit): Exception? = try {
        block()
        null
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (@Suppress("TooGenericExceptionCaught") failure: Exception) { // reported to the server, then rethrown
        failure
    }

    /** Bounded by its own timeout instead of the push budget; caller cancellation still stops it. */
    private suspend fun withReportAllowance(message: String, block: suspend () -> Unit) {
        currentCoroutineContext().ensureActive()
        try { withTimeout(reportAllowanceMillis) { block() } }
        catch (_: TimeoutCancellationException) { dependencies.log(message) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { dependencies.log(message) }
    }

    private suspend fun bestEffort(message: String, block: suspend () -> Unit) {
        currentCoroutineContext().checkWalletAdmission()
        try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { dependencies.log(message) }
    }
}
