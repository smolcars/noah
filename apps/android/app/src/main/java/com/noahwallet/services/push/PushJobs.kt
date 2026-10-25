package com.noahwallet.services.push

import com.noahwallet.api.generated.models.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import com.noahwallet.services.wallet.WalletExecutionBudget
import com.noahwallet.services.wallet.checkWalletAdmission

/** The two transports share routing, including status reporting when wallet open fails. */
class PushJobs(private val dependencies: Dependencies) {
    class Dependencies(
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
        val d = dependencies
        when (notification) {
            is NotificationDataMaintenance -> {
                val failure = try {
                    d.ensureWalletOpen()
                    caller.checkWalletAdmission()
                    d.maintenance()
                    null
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { error }
                bestEffort("Failed to report maintenance status") {
                    d.reportMaintenance(notification.notificationK1, failure == null)
                }
                if (failure != null) throw failure
                bestEffort("Failed to refresh widget") { d.refreshWidget { caller.checkWalletAdmission() } }
                // Last: exits move here even when bark's daemon isn't running.
                caller.checkWalletAdmission()
                d.progressExits()
            }
            is NotificationDataLightningInvoiceRequest -> {
                require(notification.amount >= 0)
                d.ensureWalletOpen()
                caller.checkWalletAdmission()
                val invoice = d.createInvoice(notification.amount.toULong() / 1000u)
                caller.checkWalletAdmission()
                d.submitInvoice(notification.transactionId, invoice)
            }
            is NotificationDataLightningClaimRequest -> {
                require(notification.amountSat >= 0)
                d.ensureWalletOpen()
                caller.checkWalletAdmission()
                d.claimLightning()
                bestEffort("Failed to announce Lightning receive") {
                    d.announceReceived(notification.amountSat, notification.amountUsd, notification.paymentHash)
                }
                bestEffort("Failed to refresh widget") { d.refreshWidget { caller.checkWalletAdmission() } }
            }
            is NotificationDataHeartbeat -> bestEffort("Failed to answer heartbeat") {
                d.heartbeat(notification.notificationId)
            }
            is NotificationDataBackupTrigger -> {
                val failure = try {
                    d.ensureWalletOpen()
                    caller.checkWalletAdmission()
                    d.backup()
                    null
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { error }
                bestEffort("Failed to report backup status") { d.reportBackup(notification.notificationK1, failure == null) }
                if (failure != null) throw failure
            }
            is NotificationDataRecurringPaymentDue -> com.noahwallet.recurring.RecurringPushDeadline().run { budget ->
                currentCoroutineContext().ensureActive()
                caller.checkWalletAdmission()
                if (!budget.available) throw CancellationException()
                d.ensureWalletOpen()
                currentCoroutineContext().ensureActive()
                caller.checkWalletAdmission()
                if (!budget.available) throw CancellationException()
                d.recurring(budget)
                caller.checkWalletAdmission()
                if (!budget.available) throw CancellationException()
                bestEffort("Failed to refresh widget") {
                    d.refreshWidget {
                        caller.checkWalletAdmission()
                        if (!budget.available) throw CancellationException()
                    }
                }
            }
        }
    }

    private suspend fun bestEffort(message: String, block: suspend () -> Unit) {
        currentCoroutineContext().checkWalletAdmission()
        try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { dependencies.log(message) }
    }
}
