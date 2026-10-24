package com.noahwallet.services

import android.content.Intent
import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.noahwallet.MainActivity
import com.noahwallet.services.storage.*
import com.noahwallet.ui.AppModel
import com.noahwallet.ui.NoahTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt in only on a disposable API 28 emulator with screen-lock PIN 1234 and no wallet. */
@RunWith(AndroidJUnit4::class)
class WalletPrivacyDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val automation get() = instrumentation.uiAutomation
    private fun optIn() = assumeTrue(InstrumentationRegistry.getArguments().getString("noahPrivacyIT") == "disposable-api28")
    private fun shell(command: String) = automation.executeShellCommand(command).use { descriptor ->
        android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
    }
    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertTrue("Timed out waiting for privacy lifecycle transition", condition())
    }
    private fun matches(node: android.view.accessibility.AccessibilityNodeInfo?, predicate: (android.view.accessibility.AccessibilityNodeInfo) -> Boolean): Boolean {
        if (node == null) return false
        if (predicate(node)) return true
        return (0 until node.childCount).any { matches(node.getChild(it), predicate) }
    }
    private fun intent() = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    private fun enterPIN() { shell("input text 1234"); shell("input keyevent 66") }

    @Test fun credentialHopAcceptsPINButHomeInvalidatesPendingAuthentication() {
        optIn(); assumeTrue(Build.VERSION.SDK_INT == 28)
        val settings = SettingsStorage(context)
        assertFalse(WalletDataLocation(context.filesDir, "regtest").hasAnyData)
        settings.setPersistedState(PersistedWalletState(isBiometricsEnabled = true), PersistedWalletState.key, 0)
        settings.sync()
        val activity = instrumentation.startActivitySync(intent()) as MainActivity
        lateinit var model: AppModel
        instrumentation.runOnMainSync { model = ViewModelProvider(activity)[AppModel::class.java] }
        try {
            assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            fun prompt() {
                waitFor { matches(automation.rootInActiveWindow) { it.text?.contains("Noah is locked") == true } }
                instrumentation.runOnMainSync { model.viewModelScope.launch { model.walletLock.unlock(activity) } }
                waitFor { model.walletLock.authenticating && activity.lifecycle.currentState == Lifecycle.State.CREATED }
            }
            val initial = model.walletLock.generation
            prompt()
            assertEquals("credential stop must preserve generation", initial, model.walletLock.generation)
            enterPIN()
            waitFor { !model.walletLock.authenticating && !model.walletLock.locked }
            automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
            waitFor { model.walletLock.locked }
            context.startActivity(intent())
            waitFor { activity.lifecycle.currentState == Lifecycle.State.RESUMED }
            val started = model.walletLock.generation
            prompt()
            val task = (context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager)
                .appTasks.first { it.taskInfo?.id == activity.taskId }
            automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
            waitFor { model.walletLock.generation != started }
            task.moveToFront()
            waitFor { matches(automation.rootInActiveWindow) { it.isFocused && it.isEditable } }
            enterPIN()
            waitFor { !model.walletLock.authenticating }
            assertTrue("a successful PIN after leaving Noah must not unlock", model.walletLock.locked)
            prompt(); enterPIN()
            waitFor { !model.walletLock.authenticating && !model.walletLock.locked }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            settings.setPersistedState(PersistedWalletState(isBiometricsEnabled = false), PersistedWalletState.key, 0)
            settings.sync()
        }
    }

    @Test fun directRelaunchDuringSettingsCredentialHandoffShowsLockAndRejectsRequest() {
        optIn(); assumeTrue(Build.VERSION.SDK_INT == 28)
        val settings = SettingsStorage(context)
        assertFalse(WalletDataLocation(context.filesDir, "regtest").hasAnyData)
        settings.setPersistedState(PersistedWalletState(isBiometricsEnabled = true), PersistedWalletState.key, 0)
        settings.sync()
        val activity = instrumentation.startActivitySync(intent()) as MainActivity
        lateinit var model: AppModel
        instrumentation.runOnMainSync { model = ViewModelProvider(activity)[AppModel::class.java] }
        try {
            instrumentation.runOnMainSync { model.viewModelScope.launch { model.walletLock.unlock(activity) } }
            waitFor { model.walletLock.authenticating && activity.lifecycle.currentState == Lifecycle.State.CREATED }
            enterPIN()
            waitFor { !model.walletLock.authenticating && !model.walletLock.locked }
            val started = model.walletLock.generation
            var revealed = false
            instrumentation.runOnMainSync {
                model.viewModelScope.launch { revealed = model.walletLock.authenticate(activity, "Show recovery phrase") }
            }
            waitFor { model.walletLock.authenticating && activity.lifecycle.currentState == Lifecycle.State.CREATED }
            assertTrue("handoff hides an already unlocked wallet", model.walletLock.locked)
            assertEquals(started, model.walletLock.generation)
            shell("am start -W -n ${context.packageName}/com.noahwallet.MainActivity")
            waitFor { activity.lifecycle.currentState == Lifecycle.State.RESUMED }
            assertTrue("direct relaunch: locked=${model.walletLock.locked}, authenticating=${model.walletLock.authenticating}, generation=${model.walletLock.generation}, started=$started", model.walletLock.locked)
            assertNotEquals("unexpected direct relaunch invalidates the request", started, model.walletLock.generation)
            waitFor { matches(automation.rootInActiveWindow) { it.text?.contains("Noah is locked") == true } }
            waitFor { !model.walletLock.authenticating }
            assertFalse("Settings cannot reveal the phrase", revealed)
            assertTrue(model.walletLock.locked)
            instrumentation.runOnMainSync { model.viewModelScope.launch { model.walletLock.unlock(activity) } }
            waitFor { model.walletLock.authenticating && activity.lifecycle.currentState == Lifecycle.State.CREATED }
            enterPIN()
            waitFor { !model.walletLock.authenticating && !model.walletLock.locked }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            settings.setPersistedState(PersistedWalletState(isBiometricsEnabled = false), PersistedWalletState.key, 0)
            settings.sync()
        }
    }
}
