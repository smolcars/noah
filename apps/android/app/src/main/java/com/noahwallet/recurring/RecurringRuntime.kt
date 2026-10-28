package com.noahwallet.recurring

import android.content.Context
import com.noahwallet.BuildConfig
import com.noahwallet.api.generated.client.V0RecurringPaymentsSyncClient
import com.noahwallet.api.generated.models.SyncRecurringPaymentsPayload
import com.noahwallet.send.*
import com.noahwallet.services.AppServices
import com.noahwallet.services.server.value
import com.noahwallet.services.storage.*
import com.noahwallet.services.wallet.WalletLifecycle
import com.noahwallet.services.wallet.WalletSession
import kotlinx.coroutines.*
import java.io.File

class RecurringRuntime private constructor(context: Context) {
    private val services = AppServices.shared(context)
    private val settings = SettingsStorage(context)
    private val location = WalletDataLocation(context.filesDir, BuildConfig.APP_VARIANT)
    private val store = RecurringStore(settings) { com.noahwallet.services.logging.AppLog(context, "recurring").w(RecurringPolicyLedger.logMessage) }
    private val audit = SendOverrideAuditStore(File(location.directory, "noah-recurring-overrides.json"))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val executor = RecurringExecutor(RecurringExecutor.Dependencies(store,
        RecurringJournal.shared(File(location.directory, "noah-recurring-journal.json")), SendRuntime.shared(context).controller,
        ensureOpen = { services.ensureWalletOpen(walletMutationHeld = true) },
        binding = {
            if (!location.databaseExists) throw RecurringExecutor.NoWallet()
            RecurringStore.binding(location)
        },
        allowed = {
            requireAdmission(location, settings.persistedState<PersistedWalletState>(PersistedWalletState.key), services.recurringAutomationStorageFailed)
        }, quote = { RecurringRecipient.quote(it, services.session) },
        automationGeneration = { settings.string(WalletLifecycle.automationStopKey) },
        sync = {
            check(services.registration.state.isRegisteredWithServer == true) { "Register this wallet before syncing recurring payments." }
            V0RecurringPaymentsSyncClient(services.client).syncRecurringPayments(SyncRecurringPaymentsPayload(it)).value()
        }, notify = { schedules, result -> RecurringNotifications.refresh(context, schedules, result) },
        audit = { schedule, entry, warning, confirmation ->
            audit.append(SendOverrideAudit(walletIdentity = entry?.binding?.wallet, ownershipUnverified = entry == null,
                warning = warning, confirmation = confirmation, status = schedule.status,
                statusMessage = "Recurring ${schedule.id}, occurrence ${schedule.inFlight?.occurrenceIndex ?: schedule.nextOccurrenceIndex}"))
        }, refresh = {
            runCatching { services.session.summary() }.getOrNull()?.let {
                com.noahwallet.services.push.WidgetDataWriter.write(context, it.balance, null, BuildConfig.APP_VARIANT, preserveExpiry = true)
            }
        }, withWalletMutation = { services.lifecycle.withWalletMutation(it) }), scope)
    internal val syncForegroundAdmission = com.noahwallet.services.wallet.ForegroundSyncAdmission()
    internal fun foregroundRunner(refreshSummary: suspend () -> Unit) = RecurringForegroundRunner(scope, refreshSummary = refreshSummary,
        isOnboarded = services::isOnboarded) { budget ->
        syncForegroundAdmission.run { admission ->
            services.syncInForeground(admission)
            if (budget.available && admission.available) executor.execute(budget = admission)
        }
    }
    /** Protected load overlays policy rollback and durable automation stops; no wallet opening. */
    suspend fun hasDuePayments(now: Long = System.currentTimeMillis()): Boolean = withContext(Dispatchers.IO) {
        executor.hasDuePayments(now)
    }
    suspend fun executeInForeground(manual: Boolean = false, approval: RecurringExecutor.Review? = null) =
        withContext(Dispatchers.Main.immediate) {
            syncForegroundAdmission.run { executor.execute(manual = manual, approval = approval, budget = it) }
        }
    fun foreground() { scope.launch { executeInForeground() } }
    fun timeChanged() { scope.launch {
        if (!services.isOnboarded()) return@launch
        executor.timeChanged()
        executeInForeground()
    } }
    suspend fun execute(budget: RecurringBudget = RecurringBudget()) = withContext(Dispatchers.Main) { executor.execute(budget = budget) }
    companion object {
        internal fun hasDuePayments(state: RecurringStore.State, now: Long): Boolean = state.schedules.values.any {
            it.status == "active" && !it.unsupportedStorage && it.validPolicy &&
                it.nextRunAt?.let { due -> due <= now } == true && (it.retryNotBefore == null || it.retryNotBefore <= now)
        }
        internal fun requireAdmission(location: WalletDataLocation, wallet: PersistedWalletState?, storageFailed: Boolean) {
            if (!location.databaseExists) throw RecurringExecutor.NoWallet()
            check(!storageFailed && !location.restorePending && !location.deletionMarker.exists() &&
                wallet?.isInitialized != false && wallet?.isWalletSuspended != true) { "Wallet suspended or replacement pending. Sending is stopped." }
        }
        @Volatile private var instance: RecurringRuntime? = null
        fun shared(context: Context) = instance ?: synchronized(this) { instance ?: RecurringRuntime(context.applicationContext).also { instance = it } }
    }
}

object RecurringRecipient {
    fun resolve(raw: String, network: String = BuildConfig.APP_VARIANT): Pair<String, String> {
        val clean = SendDestination.cleaned(raw)
        val parsed = SendDestination.parse(clean, network)
        parsed.error?.let { throw it }
        return when (parsed.destinationType) {
            "ark", "offer" -> clean to parsed.destinationType
            "lnurl" -> clean.lowercase() to "lnurl"
            "bip321" -> parsed.bip321?.get("arkAddress")?.let { it to "ark" }
                ?: parsed.bip321?.get("offer")?.let { it to "offer" }
                ?: error("This payment request has no reusable Ark address or Lightning offer")
            "lightning" -> error("Lightning invoices can only be paid once. Use a Lightning address or offer")
            "onchain" -> error("On-chain addresses are not supported for recurring payments yet")
            else -> error("Unsupported recipient")
        }
    }
    suspend fun quote(schedule: RecurringPayment, session: WalletSession): Pair<SendPayment, SendQuote> {
        require(schedule.validPolicy)
        val (destination, type) = resolve(schedule.destination)
        require(type == schedule.destinationType)
        var payment = SendPayment(destination, type, schedule.amountSat, comment = schedule.comment)
        if (type == "lnurl") {
            val route = session.resolveSendRecipient(destination)
            require(Math.multiplyExact(schedule.amountSat,
                1000) in route.minMsat..route.maxMsat) { "The recipient no longer accepts this amount. Update or cancel the recurring payment." }
            // Match attended Send: a note must reach the recipient through LNURL.
            if (route.arkAddress != null && schedule.comment.isEmpty()) payment = payment.copy(destination = route.arkAddress, method = "ark")
        }
        payment = payment.copy(repeatMetadata = SendPayment.metadata(schedule.destination, schedule.comment, schedule.amountSat.toString(), null))
        return payment to session.quoteSend(payment)
    }
}
