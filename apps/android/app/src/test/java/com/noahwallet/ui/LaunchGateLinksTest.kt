package com.noahwallet.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** LaunchGateView's drop: a link or widget route that arrives while a gate already ignores links is dropped, not queued. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LaunchGateLinksTest {
    @get:Rule val compose = createComposeRule()

    @Test fun aRouteOrLinkArrivingBehindAShowingGateIsDropped() {
        val links = PaymentLinks()
        var step by mutableStateOf(LinkStep.ignore)
        compose.setContent { DropIgnoredLinks(links, step) }
        compose.waitForIdle()
        // A widget tap while onboarding (or another gate) is already on screen.
        compose.runOnIdle { links.receive(Intent(Intent.ACTION_VIEW, Uri.parse("com.noahwallet.regtest://widget/send"))) }
        compose.waitForIdle()
        assertNull("dropped, so it can't open once the gate clears", links.route)
        compose.runOnIdle { links.receive(Intent(Intent.ACTION_VIEW, Uri.parse("bitcoin:bcrt1q5mq29kaq9kq660m3eepqy8pkv7jhefd69klc22"))) }
        compose.waitForIdle()
        assertNull(links.pending)
        // The gate clears: nothing comes back, and a new tap is held for delivery as usual.
        compose.runOnIdle { step = LinkStep.open }
        compose.waitForIdle()
        assertNull(links.route)
        compose.runOnIdle { links.receive(Intent(Intent.ACTION_VIEW, Uri.parse("com.noahwallet.regtest://widget/receive"))) }
        compose.waitForIdle()
        assertEquals(WidgetRoute.receive, links.route)
    }
}
