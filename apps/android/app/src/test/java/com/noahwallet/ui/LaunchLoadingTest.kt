package com.noahwallet.ui

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.noahwallet.services.launch.LaunchState
import com.noahwallet.ui.launch.PlatformGateRules
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Startup's delayed "Opening wallet" message and resume-time gate re-checks without a loading cover. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LaunchLoadingTest {
    @get:Rule val compose = createComposeRule()

    private fun gate(ready: Boolean, resolved: Boolean, gate: PlatformGateRules.Gate? = null) =
        PlatformGate(ready, resolved, gate, requesting = false, request = {}, recheck = {})

    @Test fun loadingMessageWaitsForTheDelayAndClearsImmediatelyWhenReady() {
        var loading by mutableStateOf(true)
        var shown = false
        compose.mainClock.autoAdvance = false
        compose.setContent { shown = rememberDelayedLaunchLoading(loading) }
        assertFalse("Hidden on first frame", shown)

        // 80 ms + 16 ms frame = 96 ms < 100 ms.
        compose.mainClock.advanceTimeBy(LAUNCH_LOADING_DELAY_MILLIS - 20L, ignoreFrameDuration = true)
        compose.mainClock.advanceTimeByFrame()
        assertFalse("Still hidden before the timeout", shown)

        // Reaching 100 ms + frame shows the message.
        compose.mainClock.advanceTimeBy(20L, ignoreFrameDuration = true)
        compose.mainClock.advanceTimeByFrame()
        assertTrue("Shown once the timeout elapses", shown)

        compose.runOnUiThread { loading = false }
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        assertFalse("Clears immediately when loading ends", shown)

        // Starting a new load waits out the timeout again.
        compose.runOnUiThread { loading = true }
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        assertFalse(shown)
        compose.mainClock.advanceTimeBy(LAUNCH_LOADING_DELAY_MILLIS - 20L, ignoreFrameDuration = true)
        compose.mainClock.advanceTimeByFrame()
        assertFalse(shown)
        compose.mainClock.advanceTimeBy(20L, ignoreFrameDuration = true)
        compose.mainClock.advanceTimeByFrame()
        assertTrue(shown)
    }

    @Test fun fastStartupNeverShowsTheLoadingMessage() {
        var loading by mutableStateOf(true)
        var shownEver = false
        compose.mainClock.autoAdvance = false
        compose.setContent {
            if (rememberDelayedLaunchLoading(loading)) shownEver = true
        }
        compose.mainClock.advanceTimeBy(50L, ignoreFrameDuration = true)
        compose.mainClock.advanceTimeByFrame()
        compose.runOnUiThread { loading = false }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(LAUNCH_LOADING_DELAY_MILLIS * 2)
        assertFalse(shownEver)
    }

    @Test fun launchLoadingClassificationDistinguishesTransientStatesFromErrors() {
        assertTrue(isLaunchLoading(LaunchRoute.loading, null, openFailed = false))
        assertTrue(isLaunchLoading(LaunchRoute.loading, LaunchState.existingWallet, openFailed = false))
        assertTrue(isLaunchLoading(LaunchRoute.message, null, openFailed = false))
        assertTrue(isLaunchLoading(LaunchRoute.message, LaunchState.existingWallet, openFailed = false))

        assertFalse(isLaunchLoading(LaunchRoute.message, LaunchState.existingWallet, openFailed = true))
        for (errorState in listOf(
            LaunchState.restoreIncomplete,
            LaunchState.walletDataWithoutSeed,
            LaunchState.secureStorageUnavailable,
            LaunchState.invalidSeed,
            LaunchState.seedConflict,
        )) {
            assertFalse(errorState.name, isLaunchLoading(LaunchRoute.message, errorState, openFailed = false))
        }
        for (settled in listOf(LaunchRoute.app, LaunchRoute.onboarding, LaunchRoute.push, LaunchRoute.battery, LaunchRoute.customServer)) {
            assertFalse(settled.name, isLaunchLoading(settled, LaunchState.existingWallet, openFailed = false))
        }
    }

    @Test fun initialUnresolvedGateWaitsWhileResumeRecheckKeepsTheCurrentRoute() {
        // Before the first gate check finishes for an onboarded wallet, don't flash the wallet tabs.
        assertEquals(
            LaunchRoute.loading,
            launchRoute(LaunchState.existingWallet, false, false, showsWallet = true, open = true, onboarded = true,
                gate(ready = false, resolved = false)),
        )
        // Finishing a restore keeps Onboarding mounted for its Ready moment even while the onboarded gate checks.
        assertEquals(
            LaunchRoute.onboarding,
            launchRoute(LaunchState.existingWallet, false, finishingRestore = true, showsWallet = true, open = true, onboarded = true,
                gate(ready = false, resolved = false)),
        )
        // After the gate has resolved once, ON_RESUME re-checks permissions (ready = false, resolved = true):
        // the wallet stays on screen without flashing LaunchRoute.loading, while payment links still wait.
        assertEquals(
            LaunchRoute.app,
            launchRoute(LaunchState.existingWallet, false, false, showsWallet = true, open = true, onboarded = true,
                gate(ready = false, resolved = true, gate = null)),
        )
        assertEquals(
            LaunchRoute.push,
            launchRoute(LaunchState.existingWallet, false, false, showsWallet = true, open = true, onboarded = true,
                gate(ready = false, resolved = true, gate = PlatformGateRules.Gate.push)),
        )
        assertEquals(LinkStep.wait, linkStep(LaunchRoute.app, locked = false, checking = true, LaunchState.existingWallet,
            openFailed = false, suspended = false))
        assertEquals(LinkStep.open, linkStep(LaunchRoute.app, locked = false, checking = false, LaunchState.existingWallet,
            openFailed = false, suspended = false))
    }

    @Test fun platformGateStaysResolvedAcrossBackgroundToForegroundResume() {
        Robolectric.buildActivity(FragmentActivity::class.java).setup().use { controller ->
            val model = AppModel(RuntimeEnvironment.getApplication())
            var latest: PlatformGate? = null
            var everUnresolvedAfterResolve = false
            var resolvedOnce = false
            compose.setContent {
                CompositionLocalProvider(LocalLifecycleOwner provides controller.get()) {
                    val current = rememberPlatformGate(model, controller.get())
                    latest = current
                    if (current.resolved) resolvedOnce = true
                    else if (resolvedOnce) everUnresolvedAfterResolve = true
                }
            }
            compose.waitUntil(timeoutMillis = 5_000) { latest?.let { it.ready && it.resolved } == true }

            // Background -> foreground triggers ON_RESUME's permission re-check without unresolving the gate.
            compose.runOnUiThread { controller.pause().stop().start().resume() }
            compose.waitUntil(timeoutMillis = 5_000) { latest?.let { it.ready && it.resolved } == true }
            assertFalse("Resume re-check must not drop resolved back to false", everUnresolvedAfterResolve)
        }
    }
}
