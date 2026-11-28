package com.noahwallet.ui

import android.app.Application
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.noahwallet.recurring.RecurringRuntime
import com.noahwallet.services.wallet.WalletBalance
import com.noahwallet.services.wallet.WalletSession
import io.github.niteshbalusu11.bark.Network
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The launch preview shows the widget's last balance while the wallet opens, then hands off to the wallet's first
 * summary without blanking Home's balance or letting a closed-session refresh clear a summary already shown.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LaunchPreviewTest {
    private class Activity : LifecycleOwner {
        override val lifecycle = LifecycleRegistry.createUnsafe(this)
    }

    private fun summary(sats: ULong) = WalletSession.Summary(
        Network.REGTEST, "fixture",
        WalletBalance(WalletBalance.Ark(sats, 0u, 0u, 0u, 0u, 0u, 0u, 0u, 0u), WalletBalance.Onchain(0u, 0u, 0u)),
        1,
    )

    @Test fun launchPreviewHandsOffToSummaryWithoutBlankingBalance() = runBlocking {
        val application = RuntimeEnvironment.getApplication()
        RecurringRuntime.shared(application).syncForegroundAdmission.onResume(Activity())
        val app = AppModel(application)
        app.previewLaunchBalance(380_901uL)
        assertTrue(app.showsLaunchPreview)
        assertEquals(380_901uL, app.summary?.balance?.totalSats ?: app.previewBalance)

        // While the preview is active, Home's balance stays populated until the initial summary lands before open flips true.
        var openDuringRead: Boolean? = null
        var shownDuringRead: ULong? = null
        app.publishLaunchOpen {
            openDuringRead = app.open
            shownDuringRead = app.summary?.balance?.totalSats ?: app.previewBalance
            summary(380_901uL)
        }
        assertEquals(false, openDuringRead)
        assertEquals(380_901uL, shownDuringRead)
        assertTrue(app.open)
        assertFalse(app.showsLaunchPreview)
        assertNull(app.previewBalance)
        assertEquals(380_901uL, app.summary?.balance?.totalSats)
        assertEquals(1L, app.summaryRevision)

        // A follow-up refresh while the session is not open does not wipe the summary already shown.
        app.refresh()
        assertEquals(380_901uL, app.summary?.balance?.totalSats)
    }

    @Test fun launchSummaryFailureStillOpensWalletAndSurfacesError() = runBlocking {
        val application = RuntimeEnvironment.getApplication()
        val app = AppModel(application)
        app.previewLaunchBalance(380_901uL)
        app.publishLaunchOpen { error("boom") }
        assertTrue(app.open)
        assertFalse(app.showsLaunchPreview)
        assertNull(app.previewBalance)
        assertNull(app.summary)
        assertEquals("Couldn't load your balance. Pull down to try again.", app.error)
    }
}
