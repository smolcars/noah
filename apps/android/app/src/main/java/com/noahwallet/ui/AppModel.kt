package com.noahwallet.ui

import android.app.Application
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.noahwallet.BuildConfig
import com.noahwallet.formatting.BitcoinAmountUnit
import com.noahwallet.services.AppServices
import com.noahwallet.services.launch.LaunchState
import com.noahwallet.services.storage.*
import com.noahwallet.services.wallet.*
import com.noahwallet.services.backup.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

class AppModel(application: Application, val settings: SettingsStorage = SettingsStorage(application)) : AndroidViewModel(application) {
    val services = AppServices.shared(application)
    val session = services.session
    val server = services.registration
    var walletLock by mutableStateOf(com.noahwallet.ui.settings.WalletLock(settings)); private set
    private val creator = WalletCreator.live(application, settings = settings)
    var launchState by mutableStateOf<LaunchState?>(null); private set
    var open by mutableStateOf(false); private set
    var onboarded by mutableStateOf(false); private set
    var step by mutableStateOf("welcome")
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null)
    var summary by mutableStateOf<WalletSession.Summary?>(null); private set
    var hidden by mutableStateOf((state("privacy-storage")["isHomeBalanceHidden"] as? JsonPrimitive)?.booleanOrNull ?: false)
        private set
    var amountUnit by mutableStateOf(SettingsPreferences.amountUnit(settings.string("profile-storage")))
        private set
    var preferredCurrency by mutableStateOf(SettingsPreferences.preferredCurrency(settings.string("profile-storage")))
        private set
    var isWalletSuspended by mutableStateOf(settings.persistedState<PersistedWalletState>(PersistedWalletState.key)?.isWalletSuspended == true)
        private set
    var localeIdentifier by mutableStateOf(com.noahwallet.services.storage.LegacyLocale.read(application) ?: "en")
        private set

    private val restoreLocation = WalletDataLocation(application.filesDir, BuildConfig.APP_VARIANT)
    private val restoreCompletionJournal = RestoreCompletionJournal(restoreLocation)
    private fun restoreCompletion(openWallet: suspend () -> Unit) = RestoreCompletion(
        loadState = { settings.persistedState<PersistedWalletState>(PersistedWalletState.key) ?: PersistedWalletState() },
        saveState = { settings.setPersistedState(it, PersistedWalletState.key, 0); settings.sync() },
        seedRestored = { hash, id -> services.backupCoordinator.seedRestored(hash, id); settings.sync() },
        recover = { services.recoverRestore(walletMutationHeld = true) },
        openWallet = openWallet,
        journal = restoreCompletionJournal,
        clearToken = services.tokens::clear,
    )

    val restore = RestoreFlow(
        restore = { phrase, progress ->
            val location = WalletDataLocation(getApplication<Application>().filesDir, BuildConfig.APP_VARIANT)
            if (location.restorePending || location.deletionMarker.exists()) throw RestoreCompletion.RestartRequired()
            if (location.hasAnyData) throw WalletSession.OpenError(WalletSession.OpenFailure.walletDatabaseExists)
            check(!open && !isWalletSuspended)
            try { services.restorer().restore(phrase, beforeInstall = restoreCompletionJournal::write, progress = progress) }
            catch (failure: Exception) {
                if (!location.restorePending && !location.databaseExists) {
                    try { restoreCompletionJournal.clear() } catch (_: Exception) { throw RestoreCompletion.RestartRequired() }
                }
                throw failure
            }
        },
        complete = { outcome ->
            restoreCompletion {
                withContext(Dispatchers.IO) { services.openWalletInForeground(walletMutationHeld = true) }
                open = true; onboarded = true; launchState = LaunchState.existingWallet
                refresh(); services.startServerServices()
            }.finish(outcome)
        },
    )

    private var recoveryFilesClear by mutableStateOf(false)
    private fun refreshRecoveryFiles() {
        recoveryFilesClear = !restoreLocation.restorePending && !restoreLocation.deletionMarker.exists() && !restoreCompletionJournal.pending
    }

    val canRestoreWallet get() = !busy && restore.canLeave && !open && !isWalletSuspended &&
        launchState in setOf(LaunchState.onboarding, LaunchState.seedWithoutWalletData) &&
        recoveryFilesClear
    val canDeleteRecoveryWallet get() = !busy && restore.canLeave &&
        recoveryFilesClear

    suspend fun resumeRestoreRecovery() {
        check(launchState == LaunchState.seedWithoutWalletData && restore.canLeave)
        services.lifecycle.resume()
        isWalletSuspended = false
    }

    fun restoreWallet(phrase: String? = null) {
        if (!canRestoreWallet) return
        busy = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    services.lifecycle.withWalletMutation {
                        // Recheck after a deletion that may have been draining when this request queued.
                        check(launchState in setOf(LaunchState.onboarding, LaunchState.seedWithoutWalletData))
                        val mnemonic = phrase ?: withContext(Dispatchers.IO) {
                            when (val result = SeedStorage(getApplication(), BuildConfig.APP_VARIANT).read()) {
                                is SecretResult.Found -> result.value
                                else -> throw WalletRestorer.Failure(WalletRestorer.Reason.secureStorageUnavailable)
                            }
                        }
                        restore.run(mnemonic)
                    }
                }
            } catch (failure: Exception) { restore.reject(RestoreIssue.from(failure)) }
            finally { withContext(Dispatchers.IO) { refreshRecoveryFiles() }; busy = false }
        }
    }

    val settingsShares = com.noahwallet.services.sharing.SettingsShareFiles.live(application)
        .also { it.sweep() }
    val historyExports = com.noahwallet.history.HistoryExportFiles.live(application)
        .also { it.sweep() }
    val history = com.noahwallet.history.HistoryModel(com.noahwallet.history.HistoryModel.Dependencies.live(
        session, services.client, BuildConfig.APP_VARIANT,
        suspended = { settings.persistedState<PersistedWalletState>(PersistedWalletState.key)?.isWalletSuspended == true },
        warn = com.noahwallet.services.logging.AppLog(application, "history")::w,
    ))
    var recurringNotification by mutableStateOf(false)
    var autoBoardSuccessBanner by mutableStateOf<Pair<Long, Long>?>(null)
    var scannedDestination by mutableStateOf<String?>(null)
    var repeatPayment by mutableStateOf<com.noahwallet.history.RepeatPayment?>(null)

    fun preference(key: String, value: String) {
        settings.set(com.noahwallet.services.storage.SettingsPreferences.update(settings.string("profile-storage"), mapOf(key to JsonPrimitive(value))), "profile-storage")
        if (key == "bitcoinAmountUnit") amountUnit = if (value == "sats") BitcoinAmountUnit.sats else BitcoinAmountUnit.bip177
        if (key == "preferredCurrency") preferredCurrency = SettingsPreferences.preferredCurrency(settings.string("profile-storage"))
    }

    fun setLanguage(code: String) {
        check(getApplication<Application>().getSharedPreferences("gt_store", android.content.Context.MODE_PRIVATE).edit()
            .putString(com.noahwallet.services.storage.LegacyLocale.key, code).commit())
        localeIdentifier = code
    }

    suspend fun suspendWallet() {
        services.lifecycle.suspend()
        history.clear()
        isWalletSuspended = true; open = false; summary = null
    }

    suspend fun resumeWallet() {
        services.lifecycle.resume()
        try { withContext(Dispatchers.IO) { services.openWalletInForeground() } }
        catch (failure: Exception) { services.lifecycle.suspend(); throw failure }
        open = true; isWalletSuspended = false
        refresh(); services.startServerServices()
    }

    suspend fun deleteWallet() {
        try { services.lifecycle.delete() }
        catch (failure: Exception) {
            isWalletSuspended = settings.persistedState<PersistedWalletState>(PersistedWalletState.key)?.isWalletSuspended == true
            open = session.isOpen
            if (!open) {
                summary = null; history.clear()
                if (!isWalletSuspended) runCatching { withContext(Dispatchers.IO) { services.openWalletInForeground() }; open = true; refresh() }
            }
            throw failure
        }
        history.clear(); repeatPayment = null; scannedDestination = null; autoBoardSuccessBanner = null
        walletLock.reset(); restore.reset()
        open = false; onboarded = false; isWalletSuspended = false; summary = null
        hidden = false; amountUnit = BitcoinAmountUnit.bip177; preferredCurrency = "USD"; localeIdentifier = "en"
        step = "welcome"; launchState = LaunchState.onboarding
        withContext(Dispatchers.IO) { refreshRecoveryFiles() }
    }

    suspend fun reRegisterWithServer() {
        check(!isWalletSuspended)
        server.resetRegistration(); server.ensureRegistered()
        server.refreshMailboxIfNeeded()
        services.reconcileBackupTriggers()
        com.noahwallet.services.push.PushRuntime(getApplication(), services).register()
    }

    private var starting = false

    private fun state(key: String): JsonObject = settings.string(key)?.let {
        runCatching { Json.parseToJsonElement(it).jsonObject["state"]?.jsonObject }.getOrNull()
    } ?: JsonObject(emptyMap())

    fun start() {
        if (starting || busy || !restore.canLeave || open || (isWalletSuspended && onboarded)) return
        starting = true
        viewModelScope.launch {
            var recovering = true
            try {
                withContext(Dispatchers.IO) {
                    com.noahwallet.services.background.PeriodicExecution.awaitIdle()
                    services.lifecycle.withWalletMutation {
                        val recovered = services.recoverRestore(walletMutationHeld = true)
                        if (recovered == com.noahwallet.services.backup.RestoreTransaction.Recovery.waitingForSecret) {
                            launchState = LaunchState.secureStorageUnavailable
                            return@withWalletMutation
                        }
                        restoreCompletion {}.replay(recovered, restoreLocation.databaseExists)
                        if (recovered == RestoreTransaction.Recovery.completed) {
                            val wallet = settings.persistedState<PersistedWalletState>(PersistedWalletState.key) ?: PersistedWalletState()
                            settings.setPersistedState(wallet.copy(isInitialized = true, isWalletLoaded = true), PersistedWalletState.key, 0)
                            settings.sync()
                            services.tokens.clear()
                        }
                        recovering = false
                        isWalletSuspended = settings.persistedState<PersistedWalletState>(PersistedWalletState.key)?.isWalletSuspended == true
                        launchState = withContext(Dispatchers.IO) { LaunchState.current(getApplication()) }
                        if (launchState == LaunchState.existingWallet) {
                            onboarded = services.isOnboarded()
                            if (isWalletSuspended) return@withWalletMutation
                            withContext(Dispatchers.IO) { services.openWalletInForeground(walletMutationHeld = true) }
                            open = true
                            onboarded = services.isOnboarded()
                            if (!onboarded) step = "mnemonic"
                            refresh()
                            services.startServerServices()
                        }
                    }
                }
            } catch (failure: Exception) {
                if (recovering) launchState = LaunchState.restoreIncomplete
                else if (launchState == LaunchState.existingWallet) launchState = LaunchState.openFailure(failure)
                error = if (failure is WalletLifecycle.TemporaryFiles || failure is WalletLifecycle.BackupBusy || failure is WalletLifecycle.WaitingForSecureStorage) failure.message else if (recovering) "Couldn't finish wallet recovery. Your wallet files have been kept. Please try again."
                    else "Couldn't open your wallet. Please try again."
            }
            finally { withContext(Dispatchers.IO) { refreshRecoveryFiles() }; starting = false }
        }
    }

    fun action(fallback: String, block: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null
        viewModelScope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = if (failure is WalletLifecycle.TemporaryFiles || failure is WalletLifecycle.BackupBusy || failure is WalletLifecycle.WaitingForSecureStorage) failure.message else fallback }
            finally { busy = false }
        }
    }

    suspend fun create() {
        val location = WalletDataLocation(getApplication<Application>().filesDir, BuildConfig.APP_VARIANT)
        check(launchState == LaunchState.onboarding && !isWalletSuspended && restore.canLeave)
        if (location.restorePending || location.deletionMarker.exists()) throw RestoreCompletion.RestartRequired()
        services.lifecycle.withWalletMutation { withContext(Dispatchers.IO) { creator.createOrResume() } }
        val wallet = settings.persistedState<PersistedWalletState>(PersistedWalletState.key) ?: PersistedWalletState()
        val key = session.peekPublicKey(0u)
        settings.setPersistedState(wallet.copy(isInitialized = false, staticVtxoPubkey = key), PersistedWalletState.key, 0)
        open = true; step = "mnemonic"
        // Server failure must never prevent showing the saved recovery phrase.
        viewModelScope.launch { runCatching { server.ensureRegistered() } }
    }

    suspend fun mnemonic(): List<String> = withContext(Dispatchers.IO) {
        (SeedStorage(getApplication(), BuildConfig.APP_VARIANT).read() as? SecretResult.Found)
            ?.value?.split(" ") ?: emptyList()
    }

    suspend fun finish() {
        val wallet = settings.persistedState<PersistedWalletState>(PersistedWalletState.key) ?: PersistedWalletState()
        settings.setPersistedState(wallet.copy(isInitialized = true, isWalletLoaded = true), PersistedWalletState.key, 0)
        onboarded = true
        services.startServerServices()
        refresh()
    }

    suspend fun refresh(sync: Boolean = false): Unit = withContext(Dispatchers.IO) {
        if (isWalletSuspended || settings.persistedState<PersistedWalletState>(PersistedWalletState.key)?.isWalletSuspended == true) return@withContext
        try {
            if (sync) session.sync()
            summary = session.summary()
            error = null
        } catch (_: Exception) { error = "Couldn't load your balance. Pull down to try again." }
    }

    fun toggleBalance() {
        val value = !hidden
        val envelope = settings.string("privacy-storage")?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }.orEmpty()
        settings.set(JsonObject(envelope + ("state" to JsonObject(state("privacy-storage") +
            ("isHomeBalanceHidden" to JsonPrimitive(value)))) + ("version" to (envelope["version"] ?: JsonPrimitive(0)))).toString(), "privacy-storage")
        hidden = value
    }
}

/** Construct disk-backed dependencies once on IO; publish to the Activity store only after awaiting. */
internal class AppModelLoader(application: Application) : AndroidViewModel(application) {
    val ready = viewModelScope.async(Dispatchers.IO) { AppModel(application) }
}

internal suspend fun loadAppModel(activity: androidx.fragment.app.FragmentActivity): AppModel {
    val provider = androidx.lifecycle.ViewModelProvider(activity)
    val prepared = provider[AppModelLoader::class.java].ready.await()
    return androidx.lifecycle.ViewModelProvider(activity, object : androidx.lifecycle.ViewModelProvider.Factory {
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return prepared as T
        }
    })[AppModel::class.java]
}
