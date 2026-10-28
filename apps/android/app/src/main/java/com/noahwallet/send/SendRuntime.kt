package com.noahwallet.send

import android.content.Context
import com.noahwallet.BuildConfig
import com.noahwallet.recurring.RecurringJournal
import com.noahwallet.recurring.RecurringStore
import com.noahwallet.services.storage.WalletDataLocation
import com.noahwallet.services.wallet.WalletSession
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import java.io.File

/** One controller per process; the durable journal, never a screen, authorizes Send. */
class SendRuntime private constructor(context: Context) {
    private val session = WalletSession.shared(context)
    private val journal = SendJournal(File(WalletDataLocation(context.filesDir, BuildConfig.APP_VARIANT).directory, "noah-send-attempt.json"))
    private val audit = SendOverrideAuditStore(File(WalletDataLocation(context.filesDir, BuildConfig.APP_VARIANT).directory, "noah-send-overrides.json"))
    val controller = SendController(SendController.Dependencies(journal::read, journal::write, quarantineUnreadable = journal::quarantineUnreadable,
        auditOverride = audit::append, recurringReservation = { body -> session.withRecurringReservation(body) },
        recurringEvidence = { attempt, generation, walletGeneration, _, message ->
            val location = WalletDataLocation(context.filesDir, BuildConfig.APP_VARIANT)
            RecurringJournal.shared(File(location.directory, "noah-recurring-journal.json"), RecurringStore.binding(location))
                .record(attempt, generation, walletGeneration, message, RecurringStore.binding(location), System.currentTimeMillis())
        }) { body -> session.withSendReservation(body) })
    // Defer startup until Compose has applied state created by remember.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    init {
        controller.invalidateForWalletChange()
        scope.launch {
            session.walletChanges.collectLatest {
                controller.invalidateForWalletChange()
                if (session.isOpen) {
                    controller.reloadForWalletChange()
                    controller.checkStatus()
                }
            }
        }
    }
    companion object {
        @Volatile private var instance: SendRuntime? = null
        fun shared(context: Context) = instance ?: synchronized(this) {
            instance ?: SendRuntime(context.applicationContext).also { instance = it }
        }
    }
}
