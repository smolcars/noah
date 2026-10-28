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
import com.noahwallet.api.generated.models.DeleteBackupObjectPayload
import com.noahwallet.services.backup.BackupCoordinator
import com.noahwallet.services.backup.RestoreTransaction
import com.noahwallet.services.launch.LaunchState
import com.noahwallet.services.server.*
import com.noahwallet.services.storage.*
import com.noahwallet.services.wallet.WalletSession.Summary
import com.noahwallet.ui.*
import io.github.niteshbalusu11.bark.generateMnemonic
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

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
    private fun text(value: String) = find(automation.rootInActiveWindow) { !it.isEditable && it.text?.toString() == value }
    private fun shell(command: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { it.readBytes() }
    /** A recovery word cell: visible text (as on iOS and RN), labelled by its invisible "Word N" hint. */
    private fun wordField() = find(automation.rootInActiveWindow) { it.isEditable && it.hintText?.toString()?.startsWith("Word ") == true }
    private fun clickDescription(value: String) {
        waitFor("Control appears: $value") { find(automation.rootInActiveWindow) { it.contentDescription?.toString() == value } != null }
        var target = find(automation.rootInActiveWindow) { it.contentDescription?.toString() == value }
        while (target != null && !target.isClickable) target = target.parent
        assertTrue("Control can be clicked", target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
    }
    private fun clickText(value: String) {
        waitFor("Public UI control appears: $value") { text(value) != null }
        val node = checkNotNull(text(value)) { "Expected public UI control" }
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        assertTrue("Public control can be clicked", target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
    }
    private fun enterPhrase(phrase: String) {
        waitFor("Private phrase field appears") { wordField() != null }
        val field = checkNotNull(wordField()) { "Private phrase field exists" }
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
    /** "Restore" on the phrase screen; the stored-seed recovery screen keeps "Restore Wallet". */
    private fun submitButton() = find(automation.rootInActiveWindow) { node ->
        node.isClickable && node.isEnabled && find(node) { !it.isEditable && it.text?.toString() in setOf("Restore", "Restore Wallet") } != null
    }

    private fun launch(): Pair<MainActivity, AppModel> {
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        var model: AppModel? = null
        // MainActivity builds its model off the main thread; until it lands the default factory can't make one.
        waitFor("Launch settles") {
            instrumentation.runOnMainSync { model = runCatching { ViewModelProvider(activity)[AppModel::class.java] }.getOrNull() }
            model?.launchState != null
        }
        return activity to checkNotNull(model)
    }
    private fun submit() {
        waitFor("Restore submit is enabled") { submitButton() != null }
        assertTrue(checkNotNull(submitButton()).performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    @Test fun liveBackupWipePasteRestoreAndMissingBackup() = runBlocking<Unit> {
        optIn()
        val location = WalletDataLocation(context.filesDir, "regtest")
        assertFalse("Requires a fresh disposable app", location.hasAnyData)
        val run = RestoreRun()
        try {
            val backup = run.createAndBackUp()
            val restoredModel = run.wipe(backup.model, location)
            run.missingBackup(restoredModel, location)
            run.keepAndClearPhrase()
            val actual = run.restoreFromPhrase(restoredModel, backup, location)
            run.restoreFromSavedPhrase(restoredModel, backup, location)
            instrumentation.sendStatus(0, Bundle().apply {
                putString("restoreEvidence",
                    "balance=${actual.balance.totalSats};movements=${actual.movementCount};fingerprintMatches=true;backupStorageSeeded=true;" +
                        "missingBackupActionable=true;savedPhraseRestore=true")
            })
        } catch (failure: Exception) {
            // Do not let provider/FFI or accessibility exceptions include secret input in runner output.
            fail("Restore UI integration failed at ${run.stage} (${failure.javaClass.simpleName}; details suppressed)")
        } finally {
            runCatching {
                run.cleanupClient?.let { client ->
                    V0BackupV2ListClient(client).listBackupObjects().value().forEach {
                        V0BackupV2DeleteClient(client).deleteBackupObject(DeleteBackupObjectPayload(it.backupId)).value()
                    }
                }
            }
            run.cleanupClient?.close(); run.cleanupPublic?.close()
            instrumentation.runOnMainSync { run.activity?.finish() }
            runCatching { run.current?.services?.lifecycle?.delete() }
            runCatching { shell("cmd deviceidle whitelist -${context.packageName}") }
        }
    }

    /** What the throwaway wallet held when its backup was uploaded. */
    private class Backed(val model: AppModel, val state: PersistedBackupState, val summary: Summary,
        val movements: List<com.noahwallet.history.HistoryTransaction>)

    /** One run's activity and model, its stage for failure messages, and the clients its cleanup deletes backups with. */
    private inner class RestoreRun {
        var activity: MainActivity? = null
        var current: AppModel? = null
        var cleanupClient: io.ktor.client.HttpClient? = null
        var cleanupPublic: io.ktor.client.HttpClient? = null
        var cleanupToken: String? = null
        var stage = "launch"
        var phrase = ""

        suspend fun createAndBackUp(): Backed {
            val launched = launch(); activity = launched.first; current = launched.second
            val originalModel = launched.second
            stage = "create throwaway wallet"
            withContext(Dispatchers.Main) { originalModel.create() }
            phrase = withContext(Dispatchers.Main) { originalModel.mnemonic().joinToString(" ") }
            assertTrue(RecoveryPhraseInput.isValid(phrase))
            val cleanupPhrase = phrase
            val public = ServerClient.make(BuildConfig.REGTEST_SERVER_URL)
            cleanupPublic = public
            val tokens = AuthTokenProvider({ cleanupToken }, { cleanupToken = it }, { cleanupToken = null },
                { cleanupPhrase }, { ServerClient.login(public, "regtest", it) })
            cleanupClient = ServerClient.make(BuildConfig.REGTEST_SERVER_URL, tokens)
            withContext(Dispatchers.Main) { originalModel.finish() }
            originalModel.server.ensureRegistered()
            if (InstrumentationRegistry.getArguments().getString("noahRestoreFunded") == "1") fund(originalModel)
            stage = "upload backup"
            originalModel.services.backupService.registerBackup()
            originalModel.services.backupCoordinator.flush(BackupCoordinator.Reason.manual, requireEnabled = false)
            val backed = PersistedBackupState.decode(originalModel.settings.string(PersistedBackupState.key))
            assertNotNull(backed.lastBackupId)
            cleanupToken = originalModel.services.tokens.token()
            val expected = checkNotNull(originalModel.session.summary())
            val expectedMovements = originalModel.session.transactionHistory()
            return Backed(originalModel, backed, expected, expectedMovements)
        }

        private suspend fun fund(originalModel: AppModel) {
            stage = "fund throwaway wallet"
            val addressFile = File(context.filesDir, "restore-funding-address.txt")
            try {
                addressFile.writeText(originalModel.session.peekAddress(0u))
                val deadline = System.currentTimeMillis() + 180_000
                while (System.currentTimeMillis() < deadline) {
                    originalModel.session.sync()
                    if ((originalModel.session.summary()?.balance?.totalSats ?: 0uL) > 0uL) break
                    delay(500)
                }
                val funded = checkNotNull(originalModel.session.summary())
                assertTrue("Funded wallet has a balance", funded.balance.totalSats > 0uL)
                assertTrue("Funded wallet has movements", funded.movementCount > 0)
            } finally { addressFile.delete() }
        }

        suspend fun wipe(originalModel: AppModel, location: WalletDataLocation): AppModel {
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
            return restoredModel
        }

        suspend fun missingBackup(restoredModel: AppModel, location: WalletDataLocation) {
            stage = "open restore entry"
            clickText("I already have a wallet")
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
        }

        fun keepAndClearPhrase() {
            stage = "clear phrase on leaving"
            // Entry was already discarded on submit; leave and return through the actual controls.
            clickText("Back"); waitFor("Phrase screen returns") { wordField() != null }
            clickDescription("Back"); waitFor("Welcome returns") { text("I already have a wallet") != null }
            assertEquals(0, activity!!.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
            clickText("I already have a wallet")
            waitFor("Phrase input returns") { wordField() != null }
            stage = "keep phrase through background"
            instrumentation.runOnMainSync {
                (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                    .setPrimaryClip(android.content.ClipData.newPlainText("test", phrase))
            }
            enterPhrase(phrase)
            instrumentation.runOnMainSync {
                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                assertTrue("Accepted whole phrase removed from clipboard", clipboard.primaryClip?.getItemAt(0)?.text?.toString() != phrase)
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("test", "unrelated replacement"))
            }
            val typedFirst = phrase.split(" ").first()
            automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
            waitFor("Activity backgrounded") { !activity!!.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) }
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitFor("Phrase kept through background") { activity!!.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) &&
                firstWord(activity!!) == typedFirst }
            assertTrue("Still secure after returning", activity!!.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            instrumentation.runOnMainSync {
                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                assertTrue("Background preserves replacement clipboard", clipboard.primaryClip?.getItemAt(0)?.text?.toString() == "unrelated replacement")
            }
            stage = "clear phrase on Back"
            var oldField: RecoveryWordEditText? = null
            instrumentation.runOnMainSync { oldField = firstField(activity!!.window.decorView) }
            clickDescription("Back"); waitFor("Welcome returns") { text("I already have a wallet") != null }
            clickText("I already have a wallet")
            waitFor("Phrase input returns empty") { wordField() != null && firstWord(activity!!)?.isEmpty() == true }
            instrumentation.runOnMainSync {
                val fresh = checkNotNull(firstField(activity!!.window.decorView))
                assertTrue("Leaving recreates native fields", fresh !== oldField)
                fresh.requestFocus()
                fresh.onKeyShortcut(android.view.KeyEvent.KEYCODE_Z,
                    android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_Z).let {
                        android.view.KeyEvent(0, 0, it.action, it.keyCode, 0, android.view.KeyEvent.META_CTRL_ON)
                    })
                assertTrue("Hardware Undo cannot restore cleared words", fresh.text.isEmpty())
            }
            enterPhrase(phrase)
        }

        suspend fun restoreFromPhrase(restoredModel: AppModel, backup: Backed, location: WalletDataLocation): Summary {
            // No screenshots, screen dumps, clipboard persistence, shell arguments or assertion diffs contain the phrase.
            stage = "submit restore UI"
            submit(); phrase = ""
            waitFor("Restored wallet opens") { restoredModel.restore.completed || restoredModel.restore.issue != null }
            assertTrue("Restore completed without a retryable failure", restoredModel.restore.completed)
            assertTrue(restoredModel.open); assertTrue(restoredModel.onboarded)
            val actual = checkNotNull(restoredModel.session.summary())
            assertTrue("Fingerprint preserved", backup.summary.fingerprint == actual.fingerprint)
            assertEquals(backup.summary.balance, actual.balance)
            assertEquals(backup.summary.movementCount, actual.movementCount)
            assertTrue("Restored movement details match", backup.movements == restoredModel.session.transactionHistory())
            val seeded = PersistedBackupState.decode(restoredModel.settings.string(PersistedBackupState.key))
            assertEquals(backup.state.lastUploadedSnapshotSha256, seeded.lastUploadedSnapshotSha256)
            assertFalse(location.restorePending)
            waitFor("Server services restarted") { restoredModel.server.state.isRegisteredWithServer == true }
            return actual
        }

        suspend fun restoreFromSavedPhrase(restoredModel: AppModel, backup: Backed, location: WalletDataLocation) {
            stage = "restore from securely stored phrase after database loss"
            withContext(Dispatchers.Main) { restoredModel.suspendWallet() }
            instrumentation.runOnMainSync { activity!!.finish() }
            assertTrue(location.directory.deleteRecursively())
            val seedOnly = launch(); activity = seedOnly.first; current = seedOnly.second
            assertEquals(LaunchState.seedWithoutWalletData, seedOnly.second.launchState)
            waitFor("Saved phrase recovery offered") { text("Recovery phrase found") != null }
            assertNull(find(automation.rootInActiveWindow) { it.isEditable })
            assertTrue(seedOnly.second.isWalletSuspended)
            assertNull(submitButton())
            waitFor("Deletion fallback remains available") { text("Delete Wallet") != null }
            clickText("Resume Recovery")
            waitFor("Recovery resumed") { seedOnly.second.canRestoreWallet }
            // Home, not the battery prompt or the notifications gate, follows Ready: this disposable app is exempt from
            // battery optimisation, and a fresh install on API 33+ starts with notifications denied.
            shell("cmd deviceidle whitelist +${context.packageName}")
            if (android.os.Build.VERSION.SDK_INT >= 33) shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
            val seedModel = seedOnly.second
            submit()
            // As on iOS: onboarding's Restoring scene, a visible "Ready", then Home. With App lock off, the restore shield
            // alone keeps FLAG_SECURE on the whole way and lets go only when Home takes over.
            waitFor("Ready shown") {
                assertTrue("Secure while restoring", shieldedWhileHeld(activity!!, seedModel))
                text("Ready") != null || seedModel.restore.issue != null
            }
            assertNull("Saved phrase restore succeeded", seedModel.restore.issue)
            assertTrue(seedModel.restore.completed)
            waitFor("Home follows Ready") { assertTrue("Secure until Home", shieldedWhileHeld(activity!!, seedModel)); home() }
            assertFalse(seedModel.holdRestoreCompletion)
            waitFor("Home isn't shielded with App lock off") { !secureWindow(activity!!) }
            val seedOnlySummary = checkNotNull(seedOnly.second.session.summary())
            assertEquals(backup.summary.balance, seedOnlySummary.balance)
            assertEquals(backup.summary.movementCount, seedOnlySummary.movementCount)
            assertTrue("Stored-seed movement details match", backup.movements == seedOnly.second.session.transactionHistory())
            assertTrue(backup.summary.fingerprint == seedOnlySummary.fingerprint)
        }
    }

    private fun firstField(view: android.view.View): RecoveryWordEditText? {
        if (view is RecoveryWordEditText) return view
        if (view is android.view.ViewGroup) for (i in 0 until view.childCount)
            firstField(view.getChildAt(i))?.let { return it }
        return null
    }
    private fun firstWord(activity: MainActivity): String? {
        var word: String? = null
        instrumentation.runOnMainSync { word = firstField(activity.window.decorView)?.text?.toString() }
        return word
    }
    private fun secureWindow(activity: MainActivity): Boolean {
        var on = false
        instrumentation.runOnMainSync { on = activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0 }
        return on
    }
    // Read with the hold on the main thread, so a sample can't land between Ready ending and Home appearing.
    private fun shieldedWhileHeld(activity: MainActivity, seedModel: AppModel): Boolean {
        var ok = true
        instrumentation.runOnMainSync {
            if (seedModel.holdRestoreCompletion) ok = activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
        }
        return ok
    }
    private fun home() = find(automation.rootInActiveWindow) { it.contentDescription?.toString() == "Settings" } != null

    @Test fun seedOnlyLaunchCanConfirmDeletionAndStartOver() = runBlocking<Unit> {
        optIn()
        val location = WalletDataLocation(context.filesDir, "regtest")
        assertFalse("Disposable wallet is empty", location.hasAnyData)
        SeedStorage(context, "regtest").save(generateMnemonic())
        val launched = launch()
        val activity = launched.first; val model = launched.second
        try {
            assertEquals(LaunchState.seedWithoutWalletData, model.launchState)
            clickText("Delete Wallet")
            waitFor("Typed confirmation appears") { find(automation.rootInActiveWindow) { it.isEditable && !it.isPassword } != null }
            val confirm = checkNotNull(find(automation.rootInActiveWindow) { it.isEditable && !it.isPassword })
            assertTrue(confirm.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
                Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "delete") }))
            waitFor("Deletion confirmation enabled") {
                find(automation.rootInActiveWindow) { node -> node.isClickable && node.isEnabled &&
                    find(node) { it.text?.toString() == "Delete Wallet" } != null } != null
            }
            assertTrue(checkNotNull(find(automation.rootInActiveWindow) { node -> node.isClickable && node.isEnabled &&
                find(node) { it.text?.toString() == "Delete Wallet" } != null }).performAction(AccessibilityNodeInfo.ACTION_CLICK))
            waitFor("Creation available after confirmed deletion") { text("Create wallet") != null }
            assertEquals(LaunchState.onboarding, model.launchState)
            assertEquals(SecretResult.NotFound, SeedStorage(context, "regtest").read())
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            model.services.lifecycle.delete()
        }
    }

    @Test fun platformWordFieldsAreVisibleAndKeepTheKeyboardPrivate() {
        optIn()
        val activity = launch().first
        try {
            lateinit var field: RecoveryWordEditText
            instrumentation.runOnMainSync {
                field = RecoveryWordEditText(activity, 0)
                activity.setContentView(field)
                field.requestFocus()
            }
            instrumentation.waitForIdleSync()
            waitFor("Word field is attached") { wordField() != null }
            val node = checkNotNull(wordField())
            // Visible words are a deliberate usability decision, as on iOS and RN; FLAG_SECURE covers screenshots.
            assertFalse("Words are not masked", node.isPassword)
            assertTrue("Word position remains accessible", node.hintText?.contains("Word 1") == true)
            assertTrue("Editing remains available", node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT })
            instrumentation.runOnMainSync {
                val info = android.view.inputmethod.EditorInfo()
                field.onCreateInputConnection(info)
                assertTrue(info.imeOptions and android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0)
                assertTrue(info.inputType and android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0)
                assertTrue(info.inputType and android.text.InputType.TYPE_MASK_VARIATION == android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
                assertNull("No masking transformation", field.transformationMethod)
                assertFalse(field.isSaveEnabled)
                if (android.os.Build.VERSION.SDK_INT >= 26)
                    assertEquals(android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS, field.importantForAutofill)
                field.setText("")
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
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
