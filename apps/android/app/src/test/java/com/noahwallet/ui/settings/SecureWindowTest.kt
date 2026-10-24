package com.noahwallet.ui.settings

import android.app.Application
import android.view.Window
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
import java.time.Duration

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

    private val looper get() = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
    /** Whether a handover's fallback timer is still waiting: flag changes queue a frame, so let frames run first. */
    private fun queued(): Boolean {
        looper.idleFor(Duration.ofMillis(100))
        return looper.nextScheduledTaskTime != Duration.ZERO
    }
    private fun secure(window: Window) = window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
    private fun pastTimeout() = looper.idleFor(Duration.ofMillis(WalletLock.handoffMillis + 1))

    /** [count] activities' windows, oldest first: each rotation moves to the next. */
    private fun windows(count: Int, block: (List<Window>) -> Unit) {
        val controllers = List(count) { Robolectric.buildActivity(ComponentActivity::class.java).setup() }
        try {
            looper.idle()
            assertFalse("nothing else is waiting on the main looper", queued())
            block(controllers.map { it.get().window })
        } finally { controllers.forEach { it.close() } }
    }

    @Test fun aFoldOrRotationHandsTheSecretToTheRecreatedScreen() = windows(2) { (old, new) ->
        val lock = lock(enabled = false)
        // A rotation: the leaving screen hands over, so the replacement window starts secure (MainActivity's applySecure).
        lock.releaseSecret(recreating = true, lock.showSecret(old))
        assertTrue("the fallback waits", queued())
        lock.applySecure(new)
        assertTrue("the replacement starts secure", secure(new))
        // The recreated screen shows its secret again and takes the count over; the old timer goes with it.
        val again = lock.showSecret(new)
        assertFalse("no fallback queued after the takeover", queued())
        pastTimeout()
        assertTrue("past the old timeout the shown replacement stays secure", secure(new) && lock.secure)
        again()
        assertFalse("only the recreated screen held it", lock.secure)
        assertFalse(secure(new))
    }

    @Test fun rapidRotationsStaySecureAndLeavingEndsAtZero() = windows(4) { w ->
        val lock = lock(enabled = false)
        // Two secrets (onboarding's restore shield and the typed screen's own) hand over in one rotation...
        val shield = lock.showSecret(w[0]); val words = lock.showSecret(w[0])
        lock.releaseSecret(true, shield); lock.releaseSecret(true, words)
        // ...and a second rotation comes before the screen is back.
        lock.applySecure(w[1]); assertTrue(secure(w[1]))
        lock.applySecure(w[2]); assertTrue("still secure in the second replacement", secure(w[2]))
        // Back in the third activity: one showSecret takes both over.
        val back = lock.showSecret(w[2])
        assertFalse(queued())
        // Another rotation straight away, then the screen returns again.
        lock.releaseSecret(true, back)
        lock.applySecure(w[3]); assertTrue(secure(w[3]))
        val last = lock.showSecret(w[3])
        assertFalse(queued())
        pastTimeout()
        assertTrue(secure(w[3]))
        // Leaving: the count is back to zero, the window is clear and nothing waits.
        lock.releaseSecret(false, last)
        assertFalse(lock.secure); assertFalse(secure(w[3])); assertFalse(queued())
    }

    @Test fun anActivityFinishingDuringAHandoverLeavesNoFlag() = windows(2) { (old, new) ->
        val lock = lock(enabled = false)
        lock.releaseSecret(true, lock.showSecret(old))
        lock.applySecure(new)
        assertTrue(secure(new))
        // It finishes before any secret screen is back: the fallback ends the count.
        pastTimeout()
        assertFalse(lock.secure); assertFalse(queued())
        // LaunchGateView reapplies when secure changes, so the window isn't left stuck.
        lock.applySecure(new)
        assertFalse(secure(new))
        // Finishing after the takeover releases at once.
        lock.releaseSecret(true, lock.showSecret(new))
        lock.releaseSecret(false, lock.showSecret(new))
        assertFalse(lock.secure); assertFalse(secure(new)); assertFalse(queued())
    }

    @Test fun aHandoverLeavesAppLocksRuleAlone() = windows(2) { (old, new) ->
        val lock = lock(enabled = false)
        // App lock comes on mid-handover and stays the reason once the recreated screen closes.
        lock.releaseSecret(true, lock.showSecret(old))
        lock.saveEnabled(new, true)
        lock.showSecret(new)()
        assertTrue("App lock keeps it secure", secure(new) && lock.secure)
        // App lock goes off mid-handover: the handed-over count holds until the fallback.
        lock.releaseSecret(true, lock.showSecret(new))
        lock.saveEnabled(new, false)
        assertTrue("the handover still holds", secure(new) && lock.secure)
        pastTimeout()
        assertFalse(lock.secure)
        lock.applySecure(new)
        assertFalse(secure(new))
        // With App lock on, a fallback can't clear it.
        lock.saveEnabled(new, true)
        lock.releaseSecret(true, lock.showSecret(new))
        pastTimeout()
        lock.applySecure(new)
        assertTrue("App lock's own rule", secure(new) && lock.secure)
    }
}
