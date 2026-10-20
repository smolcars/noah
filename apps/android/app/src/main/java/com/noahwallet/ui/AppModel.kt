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
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

class AppModel(application: Application) : AndroidViewModel(application) {
    val services = AppServices.shared(application)
    val session = services.session
    val server = services.registration
    val settings = SettingsStorage(application)
    val walletLock = com.noahwallet.ui.settings.WalletLock(settings)
    private val creator = WalletCreator.live(application)
    var launchState by mutableStateOf<LaunchState?>(null); private set
    var open by mutableStateOf(false); private set
    var onboarded by mutableStateOf(false); private set
    var step by mutableStateOf("welcome")
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null)
    var summary by mutableStateOf<WalletSession.Summary?>(null); private set
    var hidden by mutableStateOf((state("privacy-storage")["isHomeBalanceHidden"] as? JsonPrimitive)?.booleanOrNull ?: false)
        private set
    var amountUnit by mutableStateOf(if ((state("profile-storage")["bitcoinAmountUnit"] as? JsonPrimitive)?.contentOrNull == "sats") BitcoinAmountUnit.sats else BitcoinAmountUnit.bip177)
        private set
    var preferredCurrency by mutableStateOf((state("profile-storage")["preferredCurrency"] as? JsonPrimitive)?.contentOrNull ?: "USD")
        private set
    var isWalletSuspended by mutableStateOf(settings.persistedState<PersistedWalletState>(PersistedWalletState.key)?.isWalletSuspended == true)
        private set
    var localeIdentifier by mutableStateOf(com.noahwallet.services.storage.LegacyLocale.read(application) ?: "en")
        private set

    val historyExports = com.noahwallet.history.HistoryExportFiles(java.io.File(application.cacheDir, "history-exports"))
        .also { it.sweep() }
    val history = com.noahwallet.history.HistoryModel(com.noahwallet.history.HistoryModel.Dependencies.live(
        session, services.client, BuildConfig.APP_VARIANT,
        suspended = { settings.persistedState<PersistedWalletState>(PersistedWalletState.key)?.isWalletSuspended == true },
        warn = com.noahwallet.services.logging.AppLog(application, "history")::w,
    ))
    var repeatPayment by mutableStateOf<com.noahwallet.history.RepeatPayment?>(null)

    fun preference(key: String, value: String) {
        settings.set(com.noahwallet.services.storage.SettingsPreferences.update(settings.string("profile-storage"), mapOf(key to JsonPrimitive(value))), "profile-storage")
        if (key == "bitcoinAmountUnit") amountUnit = if (value == "sats") BitcoinAmountUnit.sats else BitcoinAmountUnit.bip177
        if (key == "preferredCurrency") preferredCurrency = value
    }

    fun setLanguage(code: String) {
        check(getApplication<Application>().getSharedPreferences("gt_store", android.content.Context.MODE_PRIVATE).edit()
            .putString(com.noahwallet.services.storage.LegacyLocale.key, code).commit())
        localeIdentifier = code
    }

    suspend fun suspendWallet() {
        services.lifecycle.suspend()
        isWalletSuspended = true; open = false; summary = null
    }

    suspend fun resumeWallet() {
        services.lifecycle.resume()
        try { withContext(Dispatchers.IO) { services.ensureWalletOpen() } }
        catch (failure: Exception) { services.lifecycle.suspend(); throw failure }
        open = true; isWalletSuspended = false
        refresh(); services.startServerServices()
    }

    suspend fun deleteWallet() {
        try { services.lifecycle.delete() }
        catch (failure: Exception) {
            isWalletSuspended = settings.persistedState<PersistedWalletState>(PersistedWalletState.key)?.isWalletSuspended == true
            open = session.isOpen
            if (!open) summary = null
            throw failure
        }
        walletLock.reset()
        open = false; onboarded = false; isWalletSuspended = false; summary = null
        hidden = false; amountUnit = BitcoinAmountUnit.bip177; preferredCurrency = "USD"; localeIdentifier = "en"
        step = "welcome"; launchState = LaunchState.onboarding
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
        if (starting || open || (isWalletSuspended && onboarded)) return
        starting = true
        viewModelScope.launch {
            var recovering = true
            try {
                val recovered = services.recoverRestore()
                if (recovered == com.noahwallet.services.backup.RestoreTransaction.Recovery.waitingForSecret) {
                    launchState = LaunchState.secureStorageUnavailable
                    return@launch
                }
                recovering = false
                launchState = withContext(Dispatchers.IO) { LaunchState.current(getApplication()) }
                if (launchState == LaunchState.existingWallet) {
                    onboarded = services.isOnboarded()
                    if (isWalletSuspended) return@launch
                    withContext(Dispatchers.IO) { services.ensureWalletOpen() }
                    open = true
                    onboarded = services.isOnboarded()
                    if (!onboarded) step = "mnemonic"
                    refresh()
                    services.startServerServices()
                }
            } catch (_: Exception) {
                if (recovering) launchState = LaunchState.restoreIncomplete
                error = if (recovering) "Couldn't finish wallet recovery. Your wallet files have been kept. Please try again."
                    else "Couldn't open your wallet. Please try again."
            }
            finally { starting = false }
        }
    }

    fun action(fallback: String, block: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null
        viewModelScope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = fallback }
            finally { busy = false }
        }
    }

    suspend fun create() {
        withContext(Dispatchers.IO) { creator.createOrResume() }
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

    suspend fun refresh(sync: Boolean = false) {
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
