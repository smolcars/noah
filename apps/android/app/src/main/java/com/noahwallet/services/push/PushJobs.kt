package com.noahwallet.services.push

import com.noahwallet.api.generated.models.*
import kotlinx.coroutines.CancellationException

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
        val refreshWidget: suspend () -> Unit,
        val log: (String) -> Unit,
    )

    suspend fun handle(notification: NotificationData) {
        val d = dependencies
        when (notification) {
            is NotificationDataMaintenance -> {
                val failure = try {
                    d.ensureWalletOpen()
                    d.maintenance()
                    null
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { error }
                bestEffort("Failed to report maintenance status") {
                    d.reportMaintenance(notification.notificationK1, failure == null)
                }
                if (failure != null) throw failure
                bestEffort("Failed to refresh widget") { d.refreshWidget() }
            }
            is NotificationDataLightningInvoiceRequest -> {
                require(notification.amount >= 0)
                d.ensureWalletOpen()
                val invoice = d.createInvoice(notification.amount.toULong() / 1000u)
                d.submitInvoice(notification.transactionId, invoice)
            }
            is NotificationDataLightningClaimRequest -> {
                require(notification.amountSat >= 0)
                d.ensureWalletOpen()
                d.claimLightning()
                bestEffort("Failed to announce Lightning receive") {
                    d.announceReceived(notification.amountSat, notification.amountUsd, notification.paymentHash)
                }
                bestEffort("Failed to refresh widget") { d.refreshWidget() }
            }
            is NotificationDataHeartbeat -> bestEffort("Failed to answer heartbeat") {
                d.heartbeat(notification.notificationId)
            }
            is NotificationDataBackupTrigger -> {
                // TODO: Port Android backups, then execute and report the correlated backup job.
                d.log("Backup push ignored; Android backups are not implemented")
            }
            is NotificationDataRecurringPaymentDue -> d.log("Recurring payment wake-up received; executor not ported yet")
        }
    }

    private suspend fun bestEffort(message: String, block: suspend () -> Unit) {
        try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { dependencies.log(message) }
    }
}
