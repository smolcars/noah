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
    private val settings = SettingsStorage(application)
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
    val amountUnit = if ((state("profile-storage")["bitcoinAmountUnit"] as? JsonPrimitive)?.contentOrNull == "sats") BitcoinAmountUnit.sats else BitcoinAmountUnit.bip177
    val preferredCurrency = (state("profile-storage")["preferredCurrency"] as? JsonPrimitive)?.contentOrNull ?: "USD"
    private var starting = false

    private fun state(key: String): JsonObject = settings.string(key)?.let {
        runCatching { Json.parseToJsonElement(it).jsonObject["state"]?.jsonObject }.getOrNull()
    } ?: JsonObject(emptyMap())

    fun start() {
        if (starting || open) return
        starting = true
        viewModelScope.launch {
            try {
                launchState = withContext(Dispatchers.IO) { LaunchState.current(getApplication()) }
                if (launchState == LaunchState.existingWallet) {
                    withContext(Dispatchers.IO) { services.ensureWalletOpen() }
                    open = true
                    onboarded = services.isOnboarded()
                    if (!onboarded) step = "mnemonic"
                    refresh()
                    services.startServerServices()
                }
            } catch (_: Exception) { error = "Couldn't open your wallet. Please try again." }
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
