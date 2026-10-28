package com.noahwallet.ui

import android.app.Application
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.noahwallet.BuildConfig
import com.noahwallet.formatting.BitcoinAmountUnit
import com.noahwallet.services.AppServices
import com.noahwallet.services.backup.*
import com.noahwallet.services.launch.LaunchState
import com.noahwallet.services.storage.*
import com.noahwallet.services.wallet.*
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
    var customServerUnresponsive by mutableStateOf(false); private set
    var switchingToDefaultServer by mutableStateOf(false); private set
    private var customOpenWait: EsploraOpenWait? = null
    var error by mutableStateOf<String?>(null)
    var summaryRevision by mutableLongStateOf(0L); private set
    var summary by mutableStateOf<WalletSession.Summary?>(null); private set
    var hidden by mutableStateOf((state("privacy-storage")["isHomeBalanceHidden"] as? JsonPrimitive)?.booleanOrNull ?: false)
        private set
    var amountUnit by mutableStateOf(SettingsPreferences.amountUnit(settings.string("profile-storage")))
        private set
    var preferredCurrency by mutableStateOf(SettingsPreferences.preferredCurrency(settings.string("profile-storage")))
        private set
    var isWalletSuspended by mutableStateOf(settings.persistedState<PersistedWalletState>(PersistedWalletState.key)?.isWalletSuspended == true)
        private set
    var localeIdentifier by mutableStateOf(com.noahwallet.services.storage.LegacyLocale.resolved(application))
        private set
    /** Home offers the backup prompt until this wallet's phrase is confirmed; missing means not backed up. */
    var recoveryPhraseBackedUp by mutableStateOf(RecoveryPhraseBackup.isBackedUp(settings))
        private set
    /** The onboarding restore screen finishes its "Ready" moment before Home appears. */
    var holdRestoreCompletion by mutableStateOf(false)

    private val restoreLocation = WalletDataLocation(application.filesDir, BuildConfig.APP_VARIANT)
    private val restoreCompletionJournal = RestoreCompletionJournal(restoreLocation)
    private fun restoreCompletion(openWallet: suspend () -> Unit) = RestoreCompletion.withSettings(settings, restoreCompletionJournal,
        seedRestored = { hash, id -> services.backupCoordinator.seedRestored(hash, id); settings.sync() },
        recover = { services.recoverRestore(walletMutationHeld = true) },
        openWallet = openWallet,
        clearToken = services.tokens::clear,
        acknowledged = { recoveryPhraseBackedUp = true },
    )
    /** Set for the restore in flight; the receipt carries it so completion or replay records the backup. */
    @Volatile private var restoringTypedPhrase = false

    val restore = RestoreFlow(
        restore = { phrase, progress ->
            val location = WalletDataLocation(getApplication<Application>().filesDir, BuildConfig.APP_VARIANT)
            if (location.restorePending || location.deletionMarker.exists()) throw RestoreCompletion.RestartRequired()
            if (location.hasAnyData) throw WalletSession.OpenError(WalletSession.OpenFailure.walletDatabaseExists)
            check(!open && !isWalletSuspended)
            val typed = restoringTypedPhrase
            @Suppress("TooGenericExceptionCaught") // clears the journal of a restore that didn't commit, then rethrows
            try {
                services.restorer().restore(phrase, beforeInstall = { restoreCompletionJournal.write(it.copy(typedPhrase = typed)) },
                    progress = progress).copy(typedPhrase = typed)
            }
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
                refresh(walletMutationHeld = true); services.startServerServices()
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
        services.lifecycle.resume { retryRestoreSettings() }
        isWalletSuspended = false
    }

    fun restoreWallet(phrase: String? = null) {
        if (!canRestoreWallet) return
        busy = true
        viewModelScope.launch {
            @Suppress("TooGenericExceptionCaught") // restore boundary: every failure becomes the restore screen's issue
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
                        restoringTypedPhrase = phrase != null
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
        esploraEndpoint = { WalletConfiguration.effective(BuildConfig.APP_VARIANT,
            EsploraSettings(settings).selection(BuildConfig.APP_VARIANT).endpoint).esplora },
        recurring = { withContext(Dispatchers.IO) { com.noahwallet.recurring.RecurringJournal.historyLinks(application, settings) } },
    ))
    var recurringNotification by mutableStateOf(false)
    var autoBoardSuccessBanner by mutableStateOf<Pair<Long, Long>?>(null)
    var scannedDestination by mutableStateOf<String?>(null)
    /** Send reviews and recurring approvals on screen ([ReviewOpen]); an OS link is rejected while any is open. */
    val openReviews = mutableIntStateOf(0)
    var repeatPayment by mutableStateOf<com.noahwallet.history.RepeatPayment?>(null)
    /** The expiring VTXOs Home's Renew nudge hands to today's VTXO refresh review; consumed once. */
    var renewVtxos by mutableStateOf<List<String>?>(null)
    /** Home's in-memory presentation state; never persisted. */
    var homeMemory = com.noahwallet.ui.home.HomeMemory(); private set

    fun preference(key: String, value: String) {
        settings.set(com.noahwallet.services.storage.SettingsPreferences.update(settings.string("profile-storage"), mapOf(key to JsonPrimitive(value))),
            "profile-storage")
        if (key == "bitcoinAmountUnit") amountUnit = if (value == "sats") BitcoinAmountUnit.sats else BitcoinAmountUnit.bip177
        if (key == "preferredCurrency") preferredCurrency = SettingsPreferences.preferredCurrency(settings.string("profile-storage"))
    }

    fun refreshLocale() { localeIdentifier = com.noahwallet.services.storage.LegacyLocale.resolved(getApplication()) }

    // KTX edit(commit = true) drops commit()'s result, which is checked here.
    @android.annotation.SuppressLint("UseKtx")
    fun setLanguage(code: String) {
        check(getApplication<Application>().getSharedPreferences("gt_store", android.content.Context.MODE_PRIVATE).edit()
            .putString(com.noahwallet.services.storage.LegacyLocale.key, code).commit())
        localeIdentifier = com.noahwallet.services.storage.LegacyLocale.resolved(getApplication())
        com.noahwallet.services.push.WidgetDataWriter.broadcast(getApplication(), BuildConfig.APP_VARIANT)
    }

    suspend fun suspendWallet() {
        services.lifecycle.suspend()
        history.clear()
        isWalletSuspended = true; open = false; summary = null
    }

    suspend fun resumeWallet() {
        services.lifecycle.resume { retryRestoreSettings() }
        @Suppress("TooGenericExceptionCaught") // suspends again after a failed open, then rethrows
        try { withContext(Dispatchers.IO) { services.openWalletInForeground() } }
        catch (failure: Exception) { services.lifecycle.suspend(); throw failure }
        open = true; isWalletSuspended = false
        refresh(); services.startServerServices()
    }

    /** Delete wallet's progress and its error, kept here so a fold or rotation can't unlock the sheet mid-delete. */
    var deletingWallet by mutableStateOf(false); internal set
    var deleteWalletError by mutableStateOf<String?>(null); internal set

    /** Delete wallet from its sheet, once at a time; a failure unlocks the sheet with [deleteWalletError]. */
    fun startDeletingWallet() {
        if (busy || deletingWallet) return
        deletingWallet = true
        action("Couldn't delete wallet. Restart Noah and try again.", inline = { message ->
            deleteWalletError = message
            if (message != null) deletingWallet = false
        }) { deleteWallet(); deletingWallet = false }
    }

    suspend fun deleteWallet() {
        @Suppress("TooGenericExceptionCaught") // restores the open/suspended state a failed delete left, then rethrows
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
        history.clear(); repeatPayment = null; scannedDestination = null; autoBoardSuccessBanner = null; renewVtxos = null
        homeMemory = com.noahwallet.ui.home.HomeMemory()
        walletLock.reset(); restore.reset()
        open = false; onboarded = false; isWalletSuspended = false; summary = null; recoveryPhraseBackedUp = false
        hidden = false; amountUnit = BitcoinAmountUnit.bip177; preferredCurrency = "USD"; refreshLocale()
        step = "welcome"; launchState = LaunchState.onboarding
        withContext(Dispatchers.IO) { refreshRecoveryFiles() }
    }

    suspend fun reRegisterWithServer() {
        check(!isWalletSuspended)
        server.reRegister(loadWallet = { services.openWalletInForeground() }, registerPush = {
            com.noahwallet.services.push.PushRuntime(getApplication(), services)
                .register(serverRegistrationPending = true).requireCompleted()
        })
        services.reconcileBackupTriggers()
    }

    private var starting = false

    private fun state(key: String): JsonObject = settings.string(key)?.let {
        runCatching { Json.parseToJsonElement(it).jsonObject["state"]?.jsonObject }.getOrNull()
    } ?: JsonObject(emptyMap())

    /** Launch runs once at a time, and never over a restore, an open wallet or a suspended onboarded one. */
    private val launchBlocked get() =
        starting || busy || customServerUnresponsive || !restore.canLeave || open || (isWalletSuspended && onboarded)

    fun start() {
        if (launchBlocked) return
        starting = true
        viewModelScope.launch {
            var recovering = true
            @Suppress("TooGenericExceptionCaught") // launch boundary: any open failure becomes the launch screen's state
            try {
                withContext(Dispatchers.IO) {
                    com.noahwallet.services.background.PeriodicExecution.awaitIdle()
                    services.lifecycle.withWalletMutation { recoverAndOpen { recovering = false } }
                }
            } catch (_: EsploraOpenWait.Timeout) {
                customServerUnresponsive = customOpenWait != null
                if (customServerUnresponsive) error = null
            } catch (failure: Exception) {
                customOpenWait = null
                if (recovering) launchState = LaunchState.restoreIncomplete
                else if (launchState == LaunchState.existingWallet) launchState = LaunchState.openFailure(failure)
                error = lifecycleMessage(failure)
                    ?: if (recovering) "Couldn't finish wallet recovery. Your wallet files have been kept. Please try again."
                    else "Couldn't open your wallet. Please try again."
            }
            finally { withContext(Dispatchers.IO) { refreshRecoveryFiles() }; starting = false }
        }
    }

    /** Launch under the wallet mutation: finish an interrupted restore, read the launch state, then open the wallet. */
    private suspend fun recoverAndOpen(recovered: () -> Unit) {
        val recovery = services.recoverRestore(walletMutationHeld = true)
        if (recovery == com.noahwallet.services.backup.RestoreTransaction.Recovery.waitingForSecret) {
            launchState = LaunchState.secureStorageUnavailable
            return
        }
        RestoreTransaction(restoreLocation).completeSettings(settings)
        restoreCompletion {}.replay(recovery, restoreLocation.databaseExists)
        if (recovery == RestoreTransaction.Recovery.completed) services.tokens.clear()
        recovered()
        isWalletSuspended = persistedSuspended()
        recoveryPhraseBackedUp = RecoveryPhraseBackup.isBackedUp(settings)
        // A stored phrase without wallet data opens onboarding at its own recovery step, set first so Welcome never flashes.
        launchState = withContext(Dispatchers.IO) { LaunchState.current(getApplication()) }
            .also { if (it == LaunchState.seedWithoutWalletData) step = "storedSeed" }
        if (launchState == LaunchState.existingWallet) openExistingWallet()
    }

    /** Launch's open of the wallet on disk, under the wallet mutation [start] holds; suspended wallets stay closed. */
    private suspend fun openExistingWallet() {
        onboarded = services.isOnboarded()
        if (isWalletSuspended) return
        withContext(Dispatchers.IO) {
            val wait = EsploraOpenWait(); customOpenWait = wait
            services.openWalletInForeground(walletMutationHeld = true, customWait = wait, lateCompletion = ::lateOpen)
            customOpenWait = null
        }
        open = true
        onboarded = services.isOnboarded()
        if (!onboarded) step = OnboardingNavigation.resumeStep
        refresh(walletMutationHeld = true)
        services.startServerServices()
    }

    /** A custom server's open that outlived its wait and has now finished, one way or the other. */
    private suspend fun lateOpen(success: Boolean) = withContext(Dispatchers.Main) {
        customServerUnresponsive = false; switchingToDefaultServer = false; customOpenWait = null
        if (success) {
            open = true; onboarded = services.isOnboarded()
            if (!onboarded) step = OnboardingNavigation.resumeStep
            refresh(); services.startServerServices()
        } else { error = "Couldn't open your wallet. Please try again." }
    }

    /** The message of a lifecycle failure that explains itself to the user; null for every other failure. */
    private fun lifecycleMessage(failure: Exception, restoreSettings: Boolean = false): String? = when (failure) {
        is WalletLifecycle.TemporaryFiles, is WalletLifecycle.BackupBusy, is WalletLifecycle.WaitingForSecureStorage -> failure.message
        is WalletLifecycle.RestoreSettingsUnavailable -> failure.message.takeIf { restoreSettings }
        else -> null
    }

    fun useDefaultEsploraServer() {
        switchingToDefaultServer = true
        viewModelScope.launch(Dispatchers.IO) {
            // Persist now so restart can recover even if native never returns.
            settings.useDefaultEsplora(BuildConfig.APP_VARIANT)
            customOpenWait?.chooseDefault()
        }
    }

    /** [inline] receives the message instead of the shared alert, for errors shown in place. */
    fun action(fallback: String, useServerMessage: Boolean = false, inline: ((String?) -> Unit)? = null, block: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null; inline?.invoke(null)
        viewModelScope.launch {
            @Suppress("TooGenericExceptionCaught") // action boundary: every failure is shown as the action's message
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                val message = lifecycleMessage(failure, restoreSettings = true)
                    ?: if (useServerMessage) com.noahwallet.services.server.ServerFailure.message(failure, fallback) else fallback
                if (inline != null) inline(message) else error = message
            }
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
        // A new wallet's phrase hasn't been seen; clear any stale answer (missing also means not backed up).
        runCatching { RecoveryPhraseBackup.set(settings, false) }
        recoveryPhraseBackedUp = false
        open = true; step = "creating"
        // Server failure must never block onboarding; the Name and Email steps register again.
        viewModelScope.launch { runCatching { server.ensureRegistered() } }
    }

    suspend fun confirmRecoveryPhraseBackup() {
        withContext(Dispatchers.IO) { RecoveryPhraseBackup.set(settings, true) }
        recoveryPhraseBackedUp = true
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

    private suspend fun retryRestoreSettings() {
        RestoreTransaction(restoreLocation).completeSettings(settings)
        restoreCompletion {}.replay(RestoreTransaction.Recovery.nothingPending, restoreLocation.databaseExists)
    }

    private suspend fun retryRestoreSettings(walletMutationHeld: Boolean) {
        if (walletMutationHeld) retryRestoreSettings() else services.lifecycle.withWalletMutation { retryRestoreSettings() }
    }

    private val summaryRefresh = java.util.concurrent.atomic.AtomicLong()
    /** A newer refresh, a suspension, a restore or a deletion has overtaken refresh [generation]. */
    private fun summaryStale(generation: Long) = summaryRefresh.get() != generation || isWalletSuspended || restoreLocation.restorePending ||
        restoreLocation.deletionMarker.exists() || persistedSuspended()
    private fun persistedSuspended() = settings.persistedState<PersistedWalletState>(PersistedWalletState.key)?.isWalletSuspended == true

    suspend fun refresh(sync: Boolean = false, walletMutationHeld: Boolean = false): Unit = withContext(Dispatchers.IO) {
        if (open && (RestoreTransaction(restoreLocation).settingsPending || restoreCompletionJournal.pending)) retryRestoreSettings(walletMutationHeld)
        if (isWalletSuspended || persistedSuspended()) return@withContext
        val generation = summaryRefresh.incrementAndGet()
        val caller = currentCoroutineContext()
        val admission = com.noahwallet.recurring.RecurringRuntime.shared(getApplication()).syncForegroundAdmission
        val activation = admission.generation
        fun checkStage() {
            caller.checkWalletAdmission()
            admission.checkActive(activation)
            if (summaryStale(generation)) throw CancellationException()
        }
        try {
            checkStage()
            if (sync) session.sync()
            summary = ForegroundWidgetRefresh.read(session::summary, session::closestExpiryBlocks, ::checkStage) { balance, expiry ->
                com.noahwallet.services.push.WidgetDataWriter.write(getApplication(), balance, expiry, BuildConfig.APP_VARIANT, checkStage = ::checkStage)
            }
            summaryRevision++
            error = null
        } catch (_: CancellationException) { caller.ensureActive() }
        catch (_: Exception) { error = "Couldn't load your balance. Pull down to try again." }
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
