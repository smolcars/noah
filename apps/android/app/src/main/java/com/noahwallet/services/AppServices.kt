package com.noahwallet.services

import android.content.Context
import com.noahwallet.BuildConfig
import com.noahwallet.services.logging.AppLog
import com.noahwallet.services.server.*
import com.noahwallet.services.storage.*
import com.noahwallet.services.wallet.WalletSession
import com.noahwallet.services.backup.*
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
        readMnemonic = { (seed.read() as? SecretResult.Found)?.value ?: throw ServerFailure("Wallet seed unavailable") },
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
    val scratch = ScratchSpace(java.io.File(context.cacheDir, "noah-scratch")).also { it.sweepAbandoned() }
    val backupService = BackupService(session, client,
        { (seed.read() as? SecretResult.Found)?.value ?: throw SeedItem.Unavailable() }, scratch)
    val backupCoordinator = BackupCoordinator(BackupCoordinator.Dependencies(
        perform = backupService::performBackup,
        canBackup = { required ->
            val wallet = settings.persistedState<PersistedWalletState>(PersistedWalletState.key)
            wallet?.isInitialized == true && wallet.isWalletSuspended != true && session.isOpen &&
                (!required || registration.state.isBackupEnabled == true)
        },
        loadState = { PersistedBackupState.decode(settings.string(PersistedBackupState.key)) },
        saveState = { settings.set(it.encode(), PersistedBackupState.key) },
    ))
    private var backupObserver: Job? = null

    @Synchronized fun reconcileBackupTriggers(foreground: Boolean = false) {
        val wallet = settings.persistedState<PersistedWalletState>(PersistedWalletState.key)
        if (!session.isOpen || wallet?.isInitialized != true || wallet.isWalletSuspended == true || registration.state.isBackupEnabled != true) {
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
                } while (resubscribe && isActive)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { backupCoordinator.schedule(BackupCoordinator.Reason.startup, true) }
        }
        if (foreground) backupCoordinator.schedule(BackupCoordinator.Reason.appForeground, true)
    }

    private val restoreRecovery = RestoreRecovery {
        val location = WalletDataLocation(context.filesDir, variant)
        if (location.restorePending) { stopRecurringAutomation(); com.noahwallet.recurring.RecurringStore(settings).protectLegacyBeforeReplacement() }
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
        })
    }

    @Volatile var recurringAutomationStorageFailed = false; private set
    fun stopRecurringAutomation() {
        val generation = java.util.UUID.randomUUID().toString()
        try {
            settings.set(generation, com.noahwallet.services.wallet.WalletLifecycle.automationStopKey); settings.sync()
            check(settings.string(com.noahwallet.services.wallet.WalletLifecycle.automationStopKey) == generation)
        } catch (failure: Exception) { recurringAutomationStorageFailed = true; throw failure }
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
            check(context.getSharedPreferences("gt_store", Context.MODE_PRIVATE).edit().clear().commit())
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
    )

    private val startup = Mutex()
    private var started = false

    fun isOnboarded(): Boolean = SettingsStorage(context)
        .persistedState<PersistedWalletState>(PersistedWalletState.key)?.isInitialized == true

    class WalletSuspended : Exception("Wallet suspended")
    class RestoreNotSettled : Exception("Interrupted restore not settled yet")

    /**
     * Opens the wallet if needed (RN `loadWalletIfNeeded`): never while the user suspended it, and
     * only after an interrupted restore is settled, so headless launches (FCM, WorkManager) recover
     * it too instead of failing until the app is opened. Recovery runs even without a journal (it
     * removes plaintext a restore left before writing one) and even while suspended (no opening).
     */
    suspend fun ensureWalletOpen(walletMutationHeld: Boolean = false) {
        if (!walletMutationHeld) { lifecycle.withWalletMutation { ensureWalletOpen(walletMutationHeld = true) }; return }
        if (recoverRestore(walletMutationHeld) == RestoreTransaction.Recovery.waitingForSecret) throw RestoreNotSettled()
        if (settings.persistedState<PersistedWalletState>(PersistedWalletState.key)?.isWalletSuspended == true) {
            throw WalletSuspended()
        }
        val mnemonic = seed.read()
        check(mnemonic is SecretResult.Found && mnemonic.value.isNotEmpty()) { "Wallet seed unavailable" }
        session.ensureOpen(mnemonic.value, true, BuildConfig.VERSION_NAME)
        seed.save(mnemonic.value)
    }

    fun startServerServices() {
        scope.launch {
            startup.withLock {
                if (!session.isOpen || !isOnboarded() || settings.persistedState<PersistedWalletState>(PersistedWalletState.key)?.isWalletSuspended == true) return@withLock
                try {
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
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { log.w("Server setup failed; it will retry on the next launch or resume") }
            }
        }
    }

    companion object {
        @Volatile private var instance: AppServices? = null
        fun shared(context: Context): AppServices = instance ?: synchronized(this) {
            instance ?: AppServices(context.applicationContext).also { instance = it }
        }
    }
}
