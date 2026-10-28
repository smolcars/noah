package com.noahwallet.ui.settings

import android.view.Window
import android.view.WindowManager
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
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

    /** A secret on screen; invoking it ends it (once, however often it's called). */
    inner class Secret internal constructor(window: Window) : () -> Unit {
        private var window: Window? = window
        private var released = false
        override fun invoke() {
            if (released) return
            released = true; secrets--
            window?.let(::applySecure); window = null
        }
        /** A fold or rotation destroyed the window: only the count is handed over, so the old activity can be collected. */
        internal fun forgetWindow() { window = null }
    }

    /** Keeps [window] secure while a secret is on screen until the returned [Secret] is invoked. */
    fun showSecret(window: Window): Secret {
        secrets++; applySecure(window)
        // A recreated screen is back: what its old activity handed over can go now, timers included.
        handoffs.toList().also { handoffs.clear() }.forEach { main.removeCallbacks(it.fallback); it.secret() }
        return Secret(window)
    }

    /** A count a fold or rotation handed over, and the timer that ends it if no secret screen comes back. */
    private class Handoff(val secret: Secret) { lateinit var fallback: Runnable }
    private val handoffs = mutableListOf<Handoff>()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * Ends [secret] as its screen leaves. When [recreating] (a fold or rotation), LaunchGateView's loading pass can come
     * before the screen returns, so the count stays up until a secret is shown again: MainActivity and LaunchGateView then
     * keep the new window secure from its first frame (LaunchGateView reapplies whenever [secure] changes). After
     * [handoffMillis] with no secret screen back, the count is released.
     */
    fun releaseSecret(recreating: Boolean, secret: Secret) {
        if (!recreating) return secret()
        secret.forgetWindow()
        val handoff = Handoff(secret)
        handoff.fallback = Runnable { if (handoffs.remove(handoff)) handoff.secret() }
        handoffs += handoff
        main.postDelayed(handoff.fallback, handoffMillis)
    }

    fun reset() { prompt?.cancelAuthentication(); policy.setEnabled(false); enabled = false; locked = false }

    companion object {
        const val handoffMillis = 5_000L
        const val authenticators = BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        fun hasNoScreenLock(activity: FragmentActivity): Boolean =
            !(activity.getSystemService(android.content.Context.KEYGUARD_SERVICE) as android.app.KeyguardManager).isDeviceSecure
        fun available(activity: FragmentActivity) = BiometricManager.from(activity).canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS
    }
}
