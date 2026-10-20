package com.noahwallet.ui

import android.app.Application
import android.app.KeyguardManager
import android.content.Context
import androidx.fragment.app.FragmentActivity
import com.noahwallet.ui.settings.WalletLock
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], application = Application::class)
class RecoveryPhraseCredentialTest {
    @Test fun fallbackRequiresNoSecureDeviceCredential() {
        Robolectric.buildActivity(FragmentActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            val keyguard = activity.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            shadowOf(keyguard).setIsDeviceSecure(false)
            assertTrue(WalletLock.hasNoScreenLock(activity))
            shadowOf(keyguard).setIsDeviceSecure(true)
            assertFalse(WalletLock.hasNoScreenLock(activity))
            // Biometric unavailability/lockout must never override a secure keyguard.
            shadowOf(keyguard).setIsKeyguardSecure(true)
            assertFalse(WalletLock.hasNoScreenLock(activity))
        }
    }
}
