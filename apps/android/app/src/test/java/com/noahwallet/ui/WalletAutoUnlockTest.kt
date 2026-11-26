package com.noahwallet.ui

import android.app.Application
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.noahwallet.services.storage.NativeSettingsFile
import com.noahwallet.services.storage.PersistedStateCodec
import com.noahwallet.services.storage.PersistedWalletState
import com.noahwallet.services.storage.SettingsStorage
import com.noahwallet.ui.settings.WalletLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBiometricPrompt
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WalletAutoUnlockTest {
    @get:Rule val compose = createComposeRule()

    private fun lock(enabled: Boolean = true): WalletLock {
        val context = RuntimeEnvironment.getApplication()
        val wallet = PersistedStateCodec.encode(PersistedWalletState(isBiometricsEnabled = enabled), 0)
        NativeSettingsFile(File(context.noBackupFilesDir, "settings.native.json"), sync = {})
            .save(NativeSettingsFile.State(values = mapOf(PersistedWalletState.key to wallet)))
        return WalletLock(SettingsStorage(context, sync = {}))
    }

    private fun show(lock: WalletLock, activity: FragmentActivity): CoroutineScope {
        lateinit var scope: CoroutineScope
        compose.setContent {
            scope = rememberCoroutineScope()
            CompositionLocalProvider(LocalLifecycleOwner provides activity) { WalletLockEffects(lock, activity) }
        }
        compose.waitForIdle()
        return scope
    }

    @Test fun launchWaitsUntilResumedThenUnlocksWithoutATap() {
        Robolectric.buildActivity(FragmentActivity::class.java).create().start().use { controller ->
            val lock = lock()
            show(lock, controller.get())
            assertNull(ShadowBiometricPrompt.getCurrentPrompt())
            compose.runOnUiThread { controller.resume() }
            compose.waitForIdle()
            assertTrue(lock.authenticating)
            assertNotNull(ShadowBiometricPrompt.getCurrentPrompt())
            compose.runOnUiThread { ShadowBiometricPrompt.authenticateCurrentSessionSuccessfully() }
            compose.waitForIdle()
            assertFalse(lock.locked)
            assertFalse(lock.authenticating)
        }
    }

    @Test fun cancellationWaitsForManualRetryUntilTheNextAppVisit() {
        Robolectric.buildActivity(FragmentActivity::class.java).setup().use { controller ->
            val lock = lock()
            val scope = show(lock, controller.get())
            compose.runOnUiThread {
                ShadowBiometricPrompt.authenticateCurrentSessionWithError(BiometricPrompt.ERROR_USER_CANCELED, "Canceled")
            }
            compose.waitForIdle()
            assertTrue(lock.locked)
            assertFalse(lock.authenticating)
            // A transient pause/resume must not trap the user in repeated prompts.
            compose.runOnUiThread { controller.pause().resume() }
            compose.waitForIdle()
            assertFalse(lock.authenticating)
            // The existing Unlock button's action can still retry immediately.
            compose.runOnUiThread { scope.launch { lock.unlock(controller.get()) } }
            compose.waitForIdle()
            assertTrue(lock.authenticating)
            compose.runOnUiThread {
                ShadowBiometricPrompt.authenticateCurrentSessionWithError(BiometricPrompt.ERROR_USER_CANCELED, "Canceled")
            }
            compose.waitForIdle()
            assertFalse("Manual retry cancellation finishes authentication", lock.authenticating)
            compose.runOnUiThread { controller.pause().stop() }
            compose.waitForIdle()
            assertFalse(lock.authenticating)
            compose.runOnUiThread { controller.start().resume() }
            compose.waitForIdle()
            assertTrue(lock.authenticating)
            compose.runOnUiThread { ShadowBiometricPrompt.authenticateCurrentSessionSuccessfully() }
            compose.waitForIdle()
            assertFalse(lock.locked)
        }
    }

    @Test fun credentialHandoffKeepsTheRequestAliveAndCancellationDoesNotReopenIt() {
        Robolectric.buildActivity(FragmentActivity::class.java).setup().use { controller ->
            val lock = lock()
            show(lock, controller.get())
            val generation = lock.generation
            // Simulate MainActivity's pre-29 PIN/password handoff.
            compose.runOnUiThread { lock.credentialStarted(); controller.pause().stop() }
            compose.waitForIdle()
            assertTrue(lock.authenticating)
            assertEquals(generation, lock.generation)
            compose.runOnUiThread {
                lock.credentialReturned(false)
                controller.start().resume()
            }
            compose.waitForIdle()
            assertTrue(lock.locked)
            assertFalse(lock.authenticating)
            assertFalse(lock.automaticUnlockPending)
        }
    }

    @Test fun successfulUnlockPromptsAgainAfterBackgrounding() {
        Robolectric.buildActivity(FragmentActivity::class.java).setup().use { controller ->
            val lock = lock()
            show(lock, controller.get())
            compose.runOnUiThread { ShadowBiometricPrompt.authenticateCurrentSessionSuccessfully() }
            compose.waitForIdle()
            compose.runOnUiThread { controller.pause().stop() }
            compose.waitForIdle()
            assertTrue(lock.locked)
            assertFalse(lock.authenticating)
            compose.runOnUiThread { controller.start().resume() }
            compose.waitForIdle()
            assertTrue(lock.authenticating)
        }
    }

    @Test fun disabledAppLockNeverPrompts() {
        Robolectric.buildActivity(FragmentActivity::class.java).setup().use { controller ->
            val lock = lock(enabled = false)
            show(lock, controller.get())
            compose.runOnUiThread { controller.pause().stop().start().resume() }
            compose.waitForIdle()
            assertFalse(lock.locked)
            assertFalse(lock.authenticating)
            assertNull(ShadowBiometricPrompt.getCurrentPrompt())
        }
    }
}
