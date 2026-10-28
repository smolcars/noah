package com.noahwallet.services

import android.content.Context
import com.noahwallet.BuildConfig
import com.noahwallet.services.backup.*
import com.noahwallet.services.launch.LaunchState
import com.noahwallet.services.logging.AppLog
import com.noahwallet.services.server.*
import com.noahwallet.services.storage.*
import com.noahwallet.services.wallet.WalletSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One shared session, token provider and server client for launch and future background work. */
class AppServices private constructor(private val context: Context) {
    init { com.noahwallet.services.wallet.WalletDatabase.sweepAbandoned() }
    val session = WalletSession.shared(context)
    private val log = AppLog(context, "server")
    private val variant = BuildConfig.APP_VARIANT
    private val tokenItem = KeychainPassword.serverAuthToken(context, variant)
    private val seed = SeedStorage(context, variant)
    private val publicClient = ServerClient.make(ServerEndpoint.baseURL(variant, BuildConfig.REGTEST_SERVER_URL))
    val tokens = AuthTokenProvider(
        readToken = { (tokenItem.read() as? SecretResult.Found)?.value },
        saveToken = tokenItem::saveAuthToken,
        clearToken = tokenItem::clearAuthToken,
        readMnemonic = { (seed.read() as? SecretResult.Found)?.value ?: throw ServerFailure("""Wallet seed unavailable""") },
        login = { ServerClient.login(publicClient, variant, it) },
        loginKey = { ServerClient.loginKey(variant, it) },
    )
    val client = ServerClient.make(ServerEndpoint.baseURL(variant, BuildConfig.REGTEST_SERVER_URL), tokens)
    val registration = ServerRegistration(client, ServerStateStorage(SettingsStorage(context)),
        peekAddress = { session.peekAddress(0u) }, warn = log::w,
        mailboxAuthorization = { expiry ->
            val authorization = session.mailboxAuthorization(expiry)
            com.noahwallet.api.generated.models.AuthorizeMailboxPayload(authorization.encoded, authorization.expiry, authorization.mailboxId)
        })
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val settings = SettingsStorage(context)
    /** Home's unit, hide setting and fiat currency, as the widget's background writers read them. */
    fun widgetDisplay() = com.noahwallet.services.push.WidgetDataWriter.Display.read(settings)
    val scratch = ScratchSpace(java.io.File(context.cacheDir, "noah-scratch")).also { it.sweepAbandoned() }
    val backupService = BackupService(session, client,
        { (seed.read() as? SecretResult.Found)?.value ?: throw SeedItem.Unavailable() }, scratch)
    val backupCoordinator = BackupCoordinator(BackupCoordinator.Dependencies(
        perform = backupService::performBackup,
        canBackup = { required ->
            val wallet = settings.persistedState<PersistedWalletState>(PersistedWalletState.key)
            wallet?.isInitialized != false && wallet?.isWalletSuspended != true && session.isOpen &&
                (!required || registration.state.isBackupEnabled == true)
        },
        loadState = { PersistedBackupState.decode(settings.string(PersistedBackupState.key)) },
        saveState = { settings.set(it.encode(), PersistedBackupState.key) },
    ))
    private var backupObserver: Job? = null

    private fun backupTriggersAllowed(): Boolean {
        val wallet = settings.persistedState<PersistedWalletState>(PersistedWalletState.key)
        val usable = wallet?.isInitialized != false && wallet?.isWalletSuspended != true
        return session.isOpen && usable && registration.state.isBackupEnabled == true
    }

    @Synchronized fun reconcileBackupTriggers(foreground: Boolean = false) {
        if (!backupTriggersAllowed()) {
            backupObserver?.cancel(); backupObserver = null; backupCoordinator.cancelScheduled(); return
        }
        if (backupObserver?.isActive != true) backupObserver = scope.launch {
            try {
                do {
                    var resubscribe = false
                    session.stateChanges().takeWhile { event ->
                        when (event.reason) {
                            "databaseChanged" -> backupCoordinator.schedule(BackupCoordinator.Reason.databaseChanged)
                            "resyncRequired" -> { backupCoordinator.schedule(BackupCoordinator.Reason.resyncRequired, true); resubscribe = true }
                            else -> backupCoordinator.schedule(BackupCoordinator.Reason.startup, true)
                        }
                        !resubscribe
                    }.collect { }
                } while (resubscribe && coroutineContext[Job]?.isActive != false)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { backupCoordinator.schedule(BackupCoordinator.Reason.startup, true) }
        }
        if (foreground) backupCoordinator.schedule(BackupCoordinator.Reason.appForeground, true)
    }

    private val restoreRecovery = RestoreRecovery {
        val location = WalletDataLocation(context.filesDir, variant)
        WalletRestorer.recoverInterruptedRestore(session, WalletDataLocation(context.filesDir, variant), SeedItem(seed))
    }
    /**
     * Settles an interrupted restore, then any committed deletion. Lock order is always lifecycle
     * gate → RestoreRecovery → session reservation; the gate isn't reentrant, so a caller already
     * holding it (startup or the restore flow) passes [walletMutationHeld].
     */
    suspend fun recoverRestore(walletMutationHeld: Boolean = false): RestoreTransaction.Recovery =
        lifecycle.recoverRestoreAndDeletion(walletMutationHeld) { restoreRecovery.settle() }

    fun restorer(): WalletRestorer {
        stopRecurringAutomation()
        com.noahwallet.recurring.RecurringStore(settings).protectLegacyBeforeReplacement()
        return WalletRestorer(variant, session, WalletDataLocation(context.filesDir, variant), SeedItem(seed),
            makeClient = { phrase ->
                var temporaryToken: String? = null
                val temporaryTokens = AuthTokenProvider(
                    readToken = { temporaryToken }, saveToken = { temporaryToken = it }, clearToken = { temporaryToken = null },
                    readMnemonic = { phrase }, login = { ServerClient.login(publicClient, variant, it) })
                ServerClient.make(ServerEndpoint.baseURL(variant, BuildConfig.REGTEST_SERVER_URL), temporaryTokens)
            }, beginOnboarding = settings::beginOnboarding)
    }

    @Volatile var recurringAutomationStorageFailed = false; private set
    fun stopRecurringAutomation() {
        val generation = java.util.UUID.randomUUID().toString()
        var stopped = false
        try {
            val directory = WalletDataLocation(context.filesDir, variant).directory
            if (directory.isDirectory) com.noahwallet.recurring.RecurringAutomationAuthority(directory).stop()
            settings.set(generation, com.noahwallet.services.wallet.WalletLifecycle.automationStopKey); settings.sync()
            check(settings.string(com.noahwallet.services.wallet.WalletLifecycle.automationStopKey) == generation)
            stopped = true
        } finally { if (!stopped) recurringAutomationStorageFailed = true }
    }

    val lifecycle = com.noahwallet.services.wallet.WalletLifecycle(
        session, WalletDataLocation(context.filesDir, variant),
        setSuspended = { suspended ->
            val wallet = settings.persistedState<PersistedWalletState>(PersistedWalletState.key) ?: PersistedWalletState()
            settings.setPersistedState(wallet.copy(isWalletSuspended = suspended), PersistedWalletState.key, 0)
            settings.sync()
        },
        deregister = { com.noahwallet.api.generated.client.V0DeregisterClient(client).deregister().value() },
        readSeed = seed::read,
        deleteSeed = seed::deleteWalletSeed,
        clearToken = tokens::clear,
        clearSettings = {
            settings.removeAll()
            backupCoordinator.reloadStatus()
            check(context.getSharedPreferences("gt_store", Context.MODE_PRIVATE).commitEdit { clear() })
        },
        clearWidget = { com.noahwallet.services.push.WidgetDataWriter.clear(context, variant) },
        sweepScratch = {
            scratch.sweepForWalletDeletion()
            com.noahwallet.services.sharing.SettingsShareFiles.live(context).deleteAll()
            com.noahwallet.history.HistoryExportFiles.live(context).deleteAll()
            com.noahwallet.services.wallet.WalletDatabase.sweepForWalletDeletion()
        },
        settleBackground = {
            reconcileBackupTriggers()
            backupCoordinator.settleForDeletion()
            startup.withLock { started = false }
        },
        drainExports = { scratch.drainForWalletDeletion() },
        finishBackground = scratch::finishWalletDeletion,
        isSuspended = { settings.persistedState<PersistedWalletState>(PersistedWalletState.key)?.isWalletSuspended == true },
        syncFiles = {
            val fd = android.system.Os.open(context.filesDir.path, android.system.OsConstants.O_RDONLY, 0)
            try { android.system.Os.fsync(fd) } finally { android.system.Os.close(fd) }
        }, warn = log::w, stopAutomation = ::stopRecurringAutomation,
        cancelPeriodicWork = { com.noahwallet.services.background.PeriodicWork.cancel(androidx.work.WorkManager.getInstance(context)) },
    )

    private val startup = Mutex()
    private var started = false

    fun isOnboarded(): Boolean = PersistedWalletState.onboarded(WalletDataLocation(context.filesDir, variant).databaseExists) {
        SettingsStorage(context).persistedState<PersistedWalletState>(PersistedWalletState.key)
    }

    internal suspend fun backgroundWalletEligible(): Boolean = withContext(Dispatchers.IO) {
        try { requireBackgroundWallet(); true }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { false }
    }

    private suspend fun requireBackgroundWallet() {
        settings.requireHealthy()
        val location = WalletDataLocation(context.filesDir, variant)
        check(com.noahwallet.services.background.BackgroundWalletAdmission.allowed(
            LaunchState.current(context, variant), settings.persistedState(PersistedWalletState.key), isOnboarded(),
            location.restorePending || RestoreTransaction(location).settingsPending ||
                com.noahwallet.ui.RestoreCompletionJournal(location).pending,
            location.deletionMarker.exists(), recurringAutomationStorageFailed,
        )) { "Wallet is not eligible for background work" }
    }

    class WalletSuspended : Exception("Wallet suspended")
    class RestoreNotSettled : Exception("""Interrupted restore not settled yet""")

    /** Headless opens only read seeds; pending restore/deletion always waits for foreground recovery. */
    private val walletOpener = StoredWalletOpener(seed,
        { settings.persistedState<PersistedWalletState>(PersistedWalletState.key) }) { session.ensureOpen(it, true, BuildConfig.VERSION_NAME) }

    suspend fun ensureWalletOpen(walletMutationHeld: Boolean = false) = openWallet(walletMutationHeld, foreground = false)

    internal suspend fun openWalletInForeground(walletMutationHeld: Boolean = false,
        customWait: com.noahwallet.services.wallet.EsploraOpenWait? = null,
        lateCompletion: suspend (Boolean) -> Unit = {}) {
        if (!walletMutationHeld) com.noahwallet.services.background.PeriodicExecution.awaitIdle()
        openWallet(walletMutationHeld, foreground = true, customWait, lateCompletion)
    }

    private suspend fun openWallet(walletMutationHeld: Boolean, foreground: Boolean,
        customWait: com.noahwallet.services.wallet.EsploraOpenWait? = null,
        lateCompletion: suspend (Boolean) -> Unit = {}): Unit = withContext(Dispatchers.IO) {
        if (!walletMutationHeld) { lifecycle.withWalletMutation { openWallet(walletMutationHeld = true, foreground, customWait,
            lateCompletion) }; return@withContext }
        prepareOpen(walletMutationHeld, foreground)
        val store = com.noahwallet.services.wallet.EsploraSettings(settings)
        val custom = foreground && customWait != null && variant != "regtest"
        val endpoint = if (custom && !session.isOpen) store.resolve(variant) else null
        if (endpoint != null && customWait != null) openWithCustomEndpoint(endpoint, customWait, lateCompletion)
        else if (foreground) walletOpener.openInForeground() else walletOpener.ensureOpen()
    }

    /** Called holding the wallet mutation: settles restore and refuses suspended or ineligible wallets. */
    private suspend fun prepareOpen(walletMutationHeld: Boolean, foreground: Boolean) {
        settings.validateStores()
        if (!foreground) requireBackgroundWallet()
        val location = WalletDataLocation(context.filesDir, variant)
        if (foreground && recoverRestore(walletMutationHeld) == RestoreTransaction.Recovery.waitingForSecret) throw RestoreNotSettled()
        if (foreground) RestoreTransaction(location).completeSettings(settings)
        if (settings.persistedState<PersistedWalletState>(PersistedWalletState.key)?.isWalletSuspended == true) {
            throw WalletSuspended()
        }
        settings.validateStores()
        if (!foreground) settings.requireHealthy()
    }

    private suspend fun openWithCustomEndpoint(endpoint: String, customWait: com.noahwallet.services.wallet.EsploraOpenWait,
        lateCompletion: suspend (Boolean) -> Unit) {
        val mnemonic = storedSeed()
        val selection = com.noahwallet.services.wallet.EsploraSettings.Selection(endpoint, variant)
        val reservation = session.reserve()
        customWait.run(lateCompletion = lateCompletion) { wait ->
            try {
                reopenOrFallBack(wait, reservation, mnemonic, selection)
                check(seed.save(mnemonic) is SecretResult.Found) { "Wallet seed migration unavailable" }
            } finally { session.release(reservation) }
        }
    }

    private suspend fun reopenOrFallBack(wait: com.noahwallet.services.wallet.EsploraOpenWait, reservation: WalletSession.Reservation,
        mnemonic: String, selection: com.noahwallet.services.wallet.EsploraSettings.Selection) {
        try {
            withContext(com.noahwallet.services.wallet.EsploraOpening(selection)) {
                session.reopen(reservation, mnemonic, BuildConfig.VERSION_NAME) { wait.commit {} }
            }
        } catch (@Suppress("TooGenericExceptionCaught") failure: Exception) { // any open failure after the UI deadline falls back
            if (!wait.timedOut) throw failure
            wait.waitForDefault()
            val fallback = com.noahwallet.services.wallet.EsploraSettings.Selection(variant = variant)
            settings.useDefaultEsplora(variant)
            withContext(com.noahwallet.services.wallet.EsploraOpening(fallback)) {
                session.reopen(reservation, mnemonic, BuildConfig.VERSION_NAME) {}
            }
        }
    }

    private suspend fun storedSeed(): String = (seed.read() as? SecretResult.Found)?.value ?: throw SeedItem.Unavailable()

    suspend fun switchEsploraEndpoint(input: String?, lateCompletion: suspend (Boolean) -> Unit = {}) {
        val default = com.noahwallet.services.wallet.WalletConfiguration.base(variant).esplora
        val endpoint = (input ?: default)?.let { com.noahwallet.services.wallet.EsploraEndpoint.validate(it, variant) }
        if (variant == "regtest") throw com.noahwallet.services.wallet.EsploraEndpoint.Failure("Regtest wallets use Bitcoin Core instead of Esplora.")
        val store = com.noahwallet.services.wallet.EsploraSettings(settings)
        val candidate = com.noahwallet.services.wallet.EsploraSettings.Selection(endpoint = endpoint?.takeUnless { it == default }, variant = variant)
        com.noahwallet.services.background.PeriodicExecution.awaitIdle()
        com.noahwallet.services.wallet.WalletBackgroundActivity.withExclusiveAccess {
            lifecycle.withWalletMutation {
                val mnemonic = endpointChangeMnemonic()
                // The detached interval owns both settings and handles until success or rollback.
                withContext(NonCancellable + Dispatchers.IO) {
                    val prepared = store.prepare(candidate)
                    val reservation = session.reserveForConfigurationChange()
                    backupObserver?.cancelAndJoin(); backupObserver = null
                    com.noahwallet.services.wallet.EsploraOpenWait().run(lateCompletion = {
                        reconcileBackupTriggers(); startServerServices()
                        lateCompletion(session.isOpen)
                    }) { wait ->
                        try {
                            store.change(candidate, prepared, wait::commit) { selection, commit ->
                                withContext(com.noahwallet.services.wallet.EsploraOpening(selection)) {
                                    session.reopen(reservation, mnemonic, BuildConfig.VERSION_NAME, commit)
                                }
                            }
                        } finally { session.release(reservation) }
                    }
                }
            }
        }
    }

    /** Called holding the wallet mutation: an endpoint change needs a settled, open, unsuspended wallet. */
    private suspend fun endpointChangeMnemonic(): String {
        if (recoverRestore(walletMutationHeld = true) == RestoreTransaction.Recovery.waitingForSecret) throw RestoreNotSettled()
        if (!session.isOpen || settings.persistedState<PersistedWalletState>(PersistedWalletState.key)?.isWalletSuspended == true) {
            throw com.noahwallet.services.wallet.EsploraEndpoint.Failure("Open your wallet before changing the endpoint.")
        }
        return storedSeed()
    }

    private val foregroundSync = com.noahwallet.services.wallet.ForegroundWalletSync { lifecycle.withWalletMutation(it) }

    /** Foreground only: retain existing session/reservation rules and never open a wallet here. */
    @OptIn(InternalCoroutinesApi::class)
    suspend fun syncInForeground(budget: com.noahwallet.recurring.RecurringBudget) {
        val cancellation = currentCoroutineContext().job.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause ->
            if (cause != null) budget.revoke()
        }
        try {
            withContext(Dispatchers.IO + com.noahwallet.services.wallet.WalletExecutionBudget(budget)) {
                foregroundSync.run {
                    currentCoroutineContext().ensureActive()
                    if (!budget.available || !foregroundSyncAllowed()) return@run
                    com.noahwallet.services.wallet.WalletBackgroundActivity.during {
                        session.sync(includingLightningClaims = true)
                    }
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* The next foreground tick retries without provider diagnostics. */ }
        finally { cancellation.dispose() }
    }

    private fun foregroundSyncAllowed(): Boolean {
        val location = WalletDataLocation(context.filesDir, variant)
        val wallet = settings.persistedState<PersistedWalletState>(PersistedWalletState.key)
        val settled = !location.restorePending && !location.deletionMarker.exists()
        return isOnboarded() && wallet?.isWalletSuspended != true && settled && session.isOpen
    }

    fun startServerServices() {
        scope.launch {
            try { com.noahwallet.services.background.PeriodicWork.reconcile(context, this@AppServices) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { log.w("Periodic wallet work scheduling failed") }
            startup.withLock {
                val suspended = settings.persistedState<PersistedWalletState>(PersistedWalletState.key)?.isWalletSuspended == true
                if (!session.isOpen || !isOnboarded() || suspended) return@withLock
                try { setUpServer() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { log.w("Server setup failed; it will retry on the next launch or resume") }
            }
        }
    }

    /** Called holding [startup]. */
    private suspend fun setUpServer() {
        registration.ensureRegistered()
        com.noahwallet.recurring.RecurringRuntime.shared(context).foreground()
        reconcileBackupTriggers()
        try { registration.refreshMailboxIfNeeded() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { log.w("Mailbox authorization failed; it will retry on resume") }
        com.noahwallet.services.push.PushWork.register(context)
        if (!started) {
            registration.reportLastLogin()
            started = true
        }
    }

    companion object {
        @android.annotation.SuppressLint("StaticFieldLeak") // only the application context
        @Volatile
        private var instance: AppServices? = null
        fun shared(context: Context): AppServices = instance ?: synchronized(this) {
            instance ?: AppServices(context.applicationContext).also { instance = it }
        }
    }
}
