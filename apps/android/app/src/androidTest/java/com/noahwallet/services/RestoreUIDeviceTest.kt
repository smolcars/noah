package com.noahwallet.services

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.noahwallet.BuildConfig
import com.noahwallet.MainActivity
import com.noahwallet.api.generated.client.V0BackupV2DeleteClient
import com.noahwallet.api.generated.client.V0BackupV2ListClient
import com.noahwallet.services.backup.RestoreTransaction
import java.io.File
import com.noahwallet.api.generated.models.DeleteBackupObjectPayload
import com.noahwallet.services.backup.BackupCoordinator
import com.noahwallet.services.launch.LaunchState
import com.noahwallet.services.server.*
import com.noahwallet.services.storage.*
import com.noahwallet.ui.*
import io.github.niteshbalusu11.bark.generateMnemonic
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt in only on an agent-owned disposable emulator. No phrase is logged, exported or captured. */
@RunWith(AndroidJUnit4::class)
class RestoreUIDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val automation get() = instrumentation.uiAutomation
    private fun optIn() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("noahRestoreUIIT") == "disposable-emulator")
        assertEquals("regtest", BuildConfig.APP_VARIANT)
        assertEquals("http://10.0.2.2:3100", BuildConfig.REGTEST_SERVER_URL)
    }
    private fun waitFor(message: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 120_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(100)
        assertTrue(message, condition())
    }
    private fun find(node: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (node == null) return null
        if (predicate(node)) return node
        for (i in 0 until node.childCount) find(node.getChild(i), predicate)?.let { return it }
        return null
    }
    private fun text(value: String) = find(automation.rootInActiveWindow) { !it.isPassword && it.text?.toString() == value }
    private fun clickText(value: String) {
        waitFor("Public UI control appears: $value") { text(value) != null }
        val node = checkNotNull(text(value)) { "Expected public UI control" }
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        assertTrue("Public control can be clicked", target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
    }
    private fun enterPhrase(phrase: String) {
        waitFor("Private phrase field appears") { find(automation.rootInActiveWindow) { it.isEditable && it.isPassword } != null }
        val field = checkNotNull(find(automation.rootInActiveWindow) { it.isEditable && it.isPassword }) { "Private phrase field exists" }
        assertTrue("Whole phrase paste accepted", field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, phrase) }))
        instrumentation.waitForIdleSync()
        waitFor("Pasted phrase enables restore") {
            val button = submitButton()
            if (button == null) find(automation.rootInActiveWindow) { it.isScrollable }
                ?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            button != null
        }
    }
    private fun submitButton() = find(automation.rootInActiveWindow) { node ->
        node.isClickable && node.isEnabled && find(node) { !it.isPassword && it.text?.toString() == "Restore Wallet" } != null
    }

    private fun launch(): Pair<MainActivity, AppModel> {
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        lateinit var model: AppModel
        instrumentation.runOnMainSync { model = ViewModelProvider(activity)[AppModel::class.java] }
        waitFor("Launch settles") { model.launchState != null }
        return activity to model
    }
    private fun submit() {
        waitFor("Restore submit is enabled") { submitButton() != null }
        assertTrue(checkNotNull(submitButton()).performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    @Test fun liveBackupWipePasteRestoreAndMissingBackup() = runBlocking<Unit> {
        optIn()
        val location = WalletDataLocation(context.filesDir, "regtest")
        assertFalse("Requires a fresh disposable app", location.hasAnyData)
        var activity: MainActivity? = null
        var current: AppModel? = null
        var cleanupClient: io.ktor.client.HttpClient? = null
        var cleanupPublic: io.ktor.client.HttpClient? = null
        var stage = "launch"
        try {
            val launched = launch(); activity = launched.first; current = launched.second
            val originalModel = launched.second
            stage = "create throwaway wallet"
            withContext(Dispatchers.Main) { originalModel.create() }
            var phrase = withContext(Dispatchers.Main) { originalModel.mnemonic().joinToString(" ") }
            assertTrue(RecoveryPhraseInput.isValid(phrase))
            val cleanupPhrase = phrase
            val public = ServerClient.make(BuildConfig.REGTEST_SERVER_URL)
            cleanupPublic = public
            var cleanupToken: String? = null
            val tokens = AuthTokenProvider({ cleanupToken }, { cleanupToken = it }, { cleanupToken = null },
                { cleanupPhrase }, { ServerClient.login(public, "regtest", it) })
            cleanupClient = ServerClient.make(BuildConfig.REGTEST_SERVER_URL, tokens)
            withContext(Dispatchers.Main) { originalModel.finish() }
            originalModel.server.ensureRegistered()
            stage = "upload backup"
            originalModel.services.backupService.registerBackup()
            originalModel.services.backupCoordinator.flush(BackupCoordinator.Reason.manual, requireEnabled = false)
            val backed = PersistedBackupState.decode(originalModel.settings.string(PersistedBackupState.key))
            assertNotNull(backed.lastBackupId)
            cleanupToken = originalModel.services.tokens.token()
            val expected = checkNotNull(originalModel.session.summary())

            stage = "wipe local wallet without removing server backup"
            // This is a reinstall-equivalent local wipe, scoped to this opt-in disposable package.
            instrumentation.runOnMainSync { activity!!.finish() }
            originalModel.settings.setPersistedState(PersistedWalletState(isInitialized = true, isWalletSuspended = true), PersistedWalletState.key, 0)
            originalModel.services.reconcileBackupTriggers()
            originalModel.services.backupCoordinator.settle()
            originalModel.session.closeSettled()
            assertTrue(location.directory.deleteRecursively())
            SeedStorage(context, "regtest").deleteWalletSeed()
            originalModel.services.tokens.clear()
            originalModel.settings.removeAll(); originalModel.settings.sync()
            instrumentation.waitForIdleSync()
            val relaunched = launch(); activity = relaunched.first; current = relaunched.second
            val restoredModel = relaunched.second
            assertEquals(LaunchState.onboarding, restoredModel.launchState)

            stage = "open restore entry"
            clickText("Restore Wallet")
            waitFor("Private screen protected") { activity!!.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0 }
            stage = "paste phrase with no backup"
            val noBackupPhrase = generateMnemonic()
            enterPhrase(noBackupPhrase)
            stage = "submit phrase with no backup"
            submit()
            waitFor("Missing backup shown") { !restoredModel.busy && restoredModel.restore.issue != null }
            assertTrue(restoredModel.restore.issue!!.message.contains("No backup"))
            assertTrue(restoredModel.restore.canLeave)
            assertFalse(location.databaseExists)
            assertEquals(SecretResult.NotFound, SeedStorage(context, "regtest").read())

            stage = "clear phrase on leaving"
            // Entry was already discarded on submit; leave and return through the actual controls.
            clickText("Back"); waitFor("Welcome returns") { text("Welcome to Noah") != null }
            assertEquals(0, activity!!.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
            clickText("Restore Wallet")
            waitFor("Phrase input returns") { find(automation.rootInActiveWindow) { it.isEditable && it.isPassword } != null }
            stage = "clear phrase on background"
            enterPhrase(phrase)
            automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
            waitFor("Activity backgrounded") { !activity!!.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) }
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitFor("Phrase cleared on background") { text("0 of 12 words") != null }
            enterPhrase(phrase)
            // No screenshots, screen dumps, clipboard persistence, shell arguments or assertion diffs contain the phrase.
            stage = "submit restore UI"
            submit(); phrase = ""
            waitFor("Restored wallet opens") { restoredModel.restore.completed || restoredModel.restore.issue != null }
            assertTrue("Restore completed without a retryable failure", restoredModel.restore.completed)
            assertTrue(restoredModel.open); assertTrue(restoredModel.onboarded)
            val actual = checkNotNull(restoredModel.session.summary())
            assertTrue("Fingerprint preserved", expected.fingerprint == actual.fingerprint)
            assertEquals(expected.balance, actual.balance)
            assertEquals(expected.movementCount, actual.movementCount)
            val seeded = PersistedBackupState.decode(restoredModel.settings.string(PersistedBackupState.key))
            assertEquals(backed.lastUploadedSnapshotSha256, seeded.lastUploadedSnapshotSha256)
            assertFalse(location.restorePending)
            waitFor("Server services restarted") { restoredModel.server.state.isRegisteredWithServer == true }
            stage = "restore from securely stored phrase after database loss"
            withContext(Dispatchers.Main) { restoredModel.suspendWallet() }
            instrumentation.runOnMainSync { activity!!.finish() }
            assertTrue(location.directory.deleteRecursively())
            restoredModel.services.lifecycle.resume()
            val seedOnly = launch(); activity = seedOnly.first; current = seedOnly.second
            assertEquals(LaunchState.seedWithoutWalletData, seedOnly.second.launchState)
            waitFor("Saved phrase recovery offered") { text("Recovery phrase found") != null }
            assertNull(find(automation.rootInActiveWindow) { it.isEditable })
            submit()
            waitFor("Saved phrase restore opens wallet") { seedOnly.second.restore.completed || seedOnly.second.restore.issue != null }
            assertTrue(seedOnly.second.restore.completed)
            val seedOnlySummary = checkNotNull(seedOnly.second.session.summary())
            assertEquals(expected.balance, seedOnlySummary.balance)
            assertEquals(expected.movementCount, seedOnlySummary.movementCount)
            assertTrue(expected.fingerprint == seedOnlySummary.fingerprint)
            instrumentation.sendStatus(0, Bundle().apply {
                putString("restoreEvidence", "balance=${actual.balance.totalSats};movements=${actual.movementCount};fingerprintMatches=true;backupStorageSeeded=true;missingBackupActionable=true;savedPhraseRestore=true")
            })
        } catch (failure: Exception) {
            // Do not let provider/FFI or accessibility exceptions include secret input in runner output.
            fail("Restore UI integration failed at $stage (${failure.javaClass.simpleName}; details suppressed)")
        } finally {
            runCatching {
                cleanupClient?.let { client ->
                    V0BackupV2ListClient(client).listBackupObjects().value().forEach {
                        V0BackupV2DeleteClient(client).deleteBackupObject(DeleteBackupObjectPayload(it.backupId)).value()
                    }
                }
            }
            cleanupClient?.close(); cleanupPublic?.close()
            instrumentation.runOnMainSync { activity?.finish() }
            runCatching { current?.services?.lifecycle?.delete() }
        }
    }


    @Test fun interruptedJournalAndWaitingSecretNeverOfferCreation() = runBlocking<Unit> {
        optIn()
        val location = WalletDataLocation(context.filesDir, "regtest")
        assertFalse("Requires a fresh disposable app", location.hasAnyData)
        var activity: MainActivity? = null
        val seedFile = File(context.noBackupFilesDir, "noah-native-seed-regtest.bin")
        try {
            location.restoreDirectory.mkdirs()
            File(location.restoreDirectory, "journal.json").writeText("{}")
            var launched = launch(); activity = launched.first
            assertEquals(LaunchState.restoreIncomplete, launched.second.launchState)
            waitFor("Recovery error shown") { text("OK") != null }
            clickText("OK")
            waitFor("Interrupted restore screen shown") { text("Restore didn't finish") != null }
            assertNull(text("Create Wallet"))
            instrumentation.runOnMainSync { activity!!.finish() }
            location.restoreDirectory.deleteRecursively()

            // Simulate a committed journal whose Keystore record is temporarily unreadable.
            val transaction = RestoreTransaction(location)
            transaction.prepare(); transaction.staged.mkdirs()
            File(transaction.staged, "db.sqlite").writeText("disposable pending restore")
            transaction.begin(generateMnemonic()); transaction.markCommitting()
            seedFile.writeBytes(byteArrayOf(0))
            launched = launch(); activity = launched.first
            assertEquals(LaunchState.secureStorageUnavailable, launched.second.launchState)
            waitFor("Waiting for secure storage screen shown") { text("Unlock your device") != null }
            assertTrue(location.restorePending); assertTrue(location.databaseExists)
            assertNull(text("Create Wallet")); assertNull(text("Restore Wallet"))
        } finally {
            instrumentation.runOnMainSync { activity?.finish() }
            location.directory.deleteRecursively(); location.restoreDirectory.deleteRecursively(); seedFile.delete()
            SettingsStorage(context).removeAll()
        }
    }
}
