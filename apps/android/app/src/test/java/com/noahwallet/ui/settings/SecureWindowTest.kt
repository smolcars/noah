package com.noahwallet.ui.settings

import android.app.Application
import android.view.WindowManager
import androidx.activity.ComponentActivity
import com.noahwallet.services.storage.PersistedWalletState
import com.noahwallet.services.storage.SettingsStorage
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** FLAG_SECURE has one rule: secure while App lock is on or a secret is on screen, cleared only when neither is. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SecureWindowTest {
    private fun lock(enabled: Boolean) = WalletLock(SettingsStorage(RuntimeEnvironment.getApplication(), sync = {}).apply {
        setPersistedState(PersistedWalletState(isBiometricsEnabled = enabled), PersistedWalletState.key, 0)
    })

    @Test fun appLockAndAShownPhraseEachKeepTheWindowSecure() {
        Robolectric.buildActivity(ComponentActivity::class.java).setup().use { controller ->
            val window = controller.get().window
            fun secure() = window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
            val lock = lock(enabled = true).apply { applySecure(window) }
            assertTrue(secure())

            // The phrase is shown, then App lock goes off in the split's list: the phrase keeps the window secure.
            val phrase = lock.showSecret(window)
            lock.saveEnabled(window, false)
            assertTrue("turning App lock off can't expose a shown phrase", secure())
            phrase()
            assertFalse("neither App lock nor a secret", secure())

            // App lock comes on while the phrase is shown: closing the phrase leaves App lock's protection.
            val again = lock.showSecret(window)
            lock.saveEnabled(window, true)
            again()
            assertTrue("closing the phrase can't clear App lock's protection", secure())
            lock.saveEnabled(window, false)
            assertFalse(secure())

            // Two secrets (the phrase and restore's words) count separately, and a release runs once.
            val first = lock.showSecret(window); val second = lock.showSecret(window)
            first(); first()
            assertTrue("the other secret is still on screen", secure())
            second()
            assertFalse(secure())
        }
    }

    @Test fun aFoldOrRotationHandsTheSecretToTheRecreatedScreen() {
        Robolectric.buildActivity(ComponentActivity::class.java).setup().use { old ->
            Robolectric.buildActivity(ComponentActivity::class.java).setup().use { new ->
                val lock = lock(enabled = false)
                val window = new.get().window
                // A rotation: the leaving screen hands over, so the replacement window starts secure (MainActivity's applySecure).
                lock.releaseSecret(recreating = true, lock.showSecret(old.get().window))
                lock.applySecure(window)
                assertTrue("the replacement starts secure", window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                // The recreated screen shows its secret again: the handed-over count goes and only its own remains.
                val again = lock.showSecret(window)
                assertTrue(lock.secure)
                again()
                assertFalse("only the recreated screen held it", lock.secure)
                // No secret screen comes back: the fallback lets go.
                lock.releaseSecret(recreating = true, lock.showSecret(window))
                assertTrue(lock.secure)
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(WalletLock.handoffMillis))
                assertFalse(lock.secure)
                // Leaving (no recreation) releases at once.
                lock.releaseSecret(recreating = false, lock.showSecret(window))
                assertFalse(lock.secure)
            }
        }
    }
}
