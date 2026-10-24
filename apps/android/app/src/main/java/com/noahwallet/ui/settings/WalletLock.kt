package com.noahwallet.ui.settings

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.*
import com.noahwallet.services.storage.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class BiometricLockPolicy(enabled: Boolean) {
    var enabled = enabled; private set
    var locked = enabled; private set
    var generation = 0; private set
    var confirmingCredential = false; private set
    fun credentialStarted() { confirmingCredential = true; locked = true }
    fun credentialFinished() { confirmingCredential = false }
    fun stopped() { if (!confirmingCredential) background() }
    fun resumed() { if (confirmingCredential) background() }
    fun background() { confirmingCredential = false; generation++; if (enabled || locked) locked = true }
    fun authenticated(generation: Int) { if (this.generation == generation) locked = false }
    fun setEnabled(value: Boolean) { enabled = value; locked = false }
}

class WalletLock(private val settings: SettingsStorage) {
    private val policy = BiometricLockPolicy(settings.persistedState<PersistedWalletState>(PersistedWalletState.key)?.isBiometricsEnabled == true)
    var locked by mutableStateOf(policy.locked); private set
    var enabled by mutableStateOf(policy.enabled); private set
    var authenticating by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var generation by mutableIntStateOf(0); private set
    private var prompt: BiometricPrompt? = null

    fun credentialStarted() {
        if (authenticating) { policy.credentialStarted(); locked = policy.locked }
    }

    // Activity results arrive before resume; any other return invalidates the pending request.
    fun credentialReturned(success: Boolean) { if (success) policy.credentialFinished() else background() }
    fun resumed() { if (policy.confirmingCredential) background() }

    fun stopped() {
        if (!policy.confirmingCredential) background()
    }

    fun background() {
        policy.background(); generation = policy.generation; locked = policy.locked
        prompt?.cancelAuthentication()
    }

    suspend fun authenticate(activity: FragmentActivity, reason: String): Boolean {
        if (authenticating) return false
        authenticating = true; error = null
        val started = generation
        return try {
            val passed = suspendCancellableCoroutine { continuation ->
                val request = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        if (continuation.isActive) continuation.resume(true)
                    }
                    override fun onAuthenticationError(code: Int, message: CharSequence) {
                        if (continuation.isActive) continuation.resume(false)
                    }
                })
                prompt = request
                continuation.invokeOnCancellation { request.cancelAuthentication() }
                request.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle(reason)
                    .setAllowedAuthenticators(authenticators).build())
            }
            (passed && started == generation).also {
                if (it) { policy.authenticated(started); locked = policy.locked }
                else error = "Authentication failed. Please try again."
            }
        } finally { policy.credentialFinished(); prompt = null; authenticating = false }
    }

    suspend fun unlock(activity: FragmentActivity) {
        val started = generation
        if (authenticate(activity, "Unlock Noah")) { policy.authenticated(started); locked = policy.locked }
    }

    suspend fun setEnabled(activity: FragmentActivity, value: Boolean) {
        if (!authenticate(activity, if (value) "Enable wallet authentication" else "Disable wallet authentication")) return
        saveEnabled(activity.window, value)
    }

    /** Stores App lock after [setEnabled]'s authentication and applies it to [window] at once. */
    @androidx.annotation.VisibleForTesting(otherwise = androidx.annotation.VisibleForTesting.PRIVATE)
    internal fun saveEnabled(window: Window, value: Boolean) {
        val wallet = settings.persistedState<PersistedWalletState>(PersistedWalletState.key) ?: PersistedWalletState()
        settings.setPersistedState(wallet.copy(isBiometricsEnabled = value), PersistedWalletState.key, 0)
        settings.sync()
        policy.setEnabled(value); enabled = value; locked = false
        // Apply protection synchronously with the preference, before the next Compose frame/background event.
        applySecure(window)
    }

    /** Secrets on screen right now (the recovery phrase, restore's words). */
    private var secrets by mutableIntStateOf(0)

    /** FLAG_SECURE's one rule: screenshots are blocked while App lock is on or a secret is on screen, and only then. */
    val secure get() = enabled || secrets > 0

    fun applySecure(window: Window) {
        if (secure) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    /** Keeps [window] secure while a secret is on screen; the returned release (idempotent) ends it. */
    fun showSecret(window: Window): () -> Unit {
        secrets++; applySecure(window)
        var released = false
        return { if (!released) { released = true; secrets--; applySecure(window) } }
    }

    fun reset() { prompt?.cancelAuthentication(); policy.setEnabled(false); enabled = false; locked = false }

    companion object {
        const val authenticators = BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        fun hasNoScreenLock(activity: FragmentActivity): Boolean =
            !(activity.getSystemService(android.content.Context.KEYGUARD_SERVICE) as android.app.KeyguardManager).isDeviceSecure
        fun available(activity: FragmentActivity) = BiometricManager.from(activity).canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS
    }
}
