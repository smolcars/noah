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
        peekAddress = { session.peekAddress(0u) }, warn = log::w)
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
        WalletRestorer.recoverInterruptedRestore(session, WalletDataLocation(context.filesDir, variant), SeedItem(seed))
    }
    suspend fun recoverRestore(): RestoreTransaction.Recovery = restoreRecovery.settle()

    fun restorer() = WalletRestorer(variant, session, WalletDataLocation(context.filesDir, variant), SeedItem(seed),
        makeClient = { phrase ->
            var temporaryToken: String? = null
            val temporaryTokens = AuthTokenProvider(
                readToken = { temporaryToken }, saveToken = { temporaryToken = it }, clearToken = { temporaryToken = null },
                readMnemonic = { phrase }, login = { ServerClient.login(publicClient, variant, it) })
            ServerClient.make(ServerEndpoint.baseURL(variant, BuildConfig.REGTEST_SERVER_URL), temporaryTokens)
        })

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
    suspend fun ensureWalletOpen() {
        if (recoverRestore() == RestoreTransaction.Recovery.waitingForSecret) throw RestoreNotSettled()
        if (settings.persistedState<PersistedWalletState>(PersistedWalletState.key)?.isWalletSuspended == true) {
            throw WalletSuspended()
        }
        val mnemonic = seed.read()
        check(mnemonic is SecretResult.Found && mnemonic.value.isNotEmpty()) { "Wallet seed unavailable" }
        session.ensureOpen(mnemonic.value, true, BuildConfig.VERSION_NAME)
    }

    fun startServerServices() {
        scope.launch {
            startup.withLock {
                if (!session.isOpen || !isOnboarded()) return@withLock
                try {
                    registration.ensureRegistered()
                    reconcileBackupTriggers()
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
