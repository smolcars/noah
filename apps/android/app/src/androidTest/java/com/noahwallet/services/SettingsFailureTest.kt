package com.noahwallet.services

import android.content.ContextWrapper
import android.system.Os
import androidx.test.platform.app.InstrumentationRegistry
import com.noahwallet.services.storage.*
import com.noahwallet.recurring.*
import com.noahwallet.send.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SettingsFailureTest {
    private val restoreSteps = listOf("journal", "previousMoved", "installed", "committing", "seed", "markerRemoved", "previousRemoved", "journalRemoved", "flags", "markerOnly", "stateOnly", "corrupt") +
        (1..6).map { "initializing$it" } + (1..9).map { "settling$it" }

    private fun isolatedServices(context: android.content.Context): AppServices =
        AppServices::class.java.getDeclaredConstructor(android.content.Context::class.java).let {
            it.isAccessible = true; it.newInstance(context)
        }

    @Test fun backgroundRejectsUnpersistedFallbackBeforeReadingSeed() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(app.cacheDir, "background-settings-${UUID.randomUUID()}").apply { check(mkdir()) }
        val context = object : ContextWrapper(app) {
            override fun getFilesDir() = root
            override fun getNoBackupFilesDir() = root
        }
        val settings = SettingsStorage(context)
        settings.setPersistedState(PersistedWalletState(isInitialized = true, isWalletSuspended = true), PersistedWalletState.key, 0)
        val location = WalletDataLocation(root, "regtest")
        location.directory.mkdir(); location.database.writeText("unchanged")
        val services = isolatedServices(context)
        val file = File(root, "settings.native.json")
        Os.chmod(file.path, 0); Os.chmod(root.path, 320)
        try {
            assertNull(settings.persistedState<PersistedWalletState>(PersistedWalletState.key))
            services.ensureWalletOpen()
            fail("Background opened unpersisted fallback")
        } catch (failure: Exception) {
            assertTrue("Background must reject unhealthy settings before reading the seed: ${failure.javaClass.simpleName}", failure is SettingsStorage.Unavailable)
            assertEquals("unchanged", location.database.readText())
            assertFalse(services.session.isOpen)
        } finally {
            Os.chmod(root.path, 448); Os.chmod(file.path, 384); check(root.deleteRecursively())
        }
    }

    @Test fun interruptedRestoreSettlesWhileSettingsFailAtEveryBoundary() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val boundary = arguments.getString("restoreStep")
        val mode = arguments.getString("restoreMode")
        for (step in boundary?.let(::listOf) ?: restoreSteps) {
            val root = File(app.cacheDir, if (boundary != null) "restore-settings-process" else "restore-settings-${UUID.randomUUID()}")
            val world = RestoreWorld(app, root)
            if (mode == "stage") {
                world.stage(step, mode)
                File(root, "ready").writeText(step)
                awaitCancellation() // The host force-stops this process with settings still failing.
            }
            try {
                if (mode != "recover") world.stage(step)
                world.recover(step)
            } finally { world.cleanup() }
        }
    }

    private inner class RestoreWorld(app: android.content.Context, val root: File) {
        val preferences = File(root, "preferences")
        val context = object : ContextWrapper(app) {
            override fun getFilesDir() = root
            override fun getNoBackupFilesDir() = preferences
        }
        val location = WalletDataLocation(root, "regtest")
        val tx = com.noahwallet.services.backup.RestoreTransaction(location)
        val file = File(preferences, "settings.native.json")
        val phrase = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        fun committed(step: String) = step !in listOf("journal", "previousMoved", "installed", "committing")
        fun checkpoint(step: String, mode: String?, count: Int, boundary: Int) {
            if (count != boundary) return
            if (mode == "stage") {
                File(root, "ready").writeText(step)
                while (true) Thread.sleep(1000) // Host kills the process at the actual durable-write boundary.
            }
            error("Simulated process death")
        }
        suspend fun stage(step: String, mode: String? = null) {
            check(root.mkdirs()); check(preferences.mkdir())
            SettingsStorage(context).beginOnboarding()
            location.directory.mkdir(); location.database.writeText("old")
            tx.prepare(); tx.staged.mkdir(); File(tx.staged, "db.sqlite").writeText("committed")
            tx.begin(phrase)
            if (step in listOf("journal", "previousMoved")) {
                check(location.directory.renameTo(tx.staged))
                if (step == "journal") check(tx.previous.renameTo(location.directory))
            }
            if (step !in listOf("journal", "previousMoved", "installed")) tx.markCommitting()
            if (committed(step)) check(SeedStorage(context, "regtest").save(phrase) is SecretResult.Found)
            if (step in listOf("markerRemoved", "previousRemoved")) {
                check(File(location.directory, com.noahwallet.services.backup.RestoreTransaction.markerName).delete())
                if (step == "previousRemoved") check(tx.previous.deleteRecursively())
            }
            if (step == "journalRemoved" || step == "flags") tx.finish()
            if (step != "flags") { file.writeText("damaged"); Os.chmod(file.path, 0) }
            Os.chmod(preferences.path, 320)
            if (step in listOf("markerOnly", "stateOnly", "corrupt")) {
                val authority = RecurringAutomationAuthority(location.directory)
                authority.stop()
                when (step) {
                    "markerOnly" -> check(authority.file.delete())
                    "stateOnly" -> check(authority.marker.delete())
                    else -> authority.file.writeText("{")
                }
            }
            if (step.startsWith("initializing")) {
                var count = 0
                val boundary = step.removePrefix("initializing").toInt()
                runCatching {
                    RecurringAutomationAuthority(location.directory) { file, bytes ->
                        durableRecurringWrite(file, bytes, {
                            syncDirectory(it); checkpoint(step, mode, ++count, boundary)
                        }, {
                            it.sync(); checkpoint(step, mode, ++count, boundary)
                        })
                    }.stop()
                }
                assertEquals(boundary, count)
            }
            if (step.startsWith("settling")) {
                var count = 0
                val boundary = step.removePrefix("settling").toInt()
                runCatching {
                    com.noahwallet.services.backup.RestoreTransaction(location, sync = {
                        com.noahwallet.services.backup.RestoreTransaction.syncFileOrDirectory(it)
                        checkpoint(step, mode, ++count, boundary)
                    }).finish()
                }
                assertEquals(boundary, count)
            }
        }
        suspend fun recover(step: String) {
            val settings = SettingsStorage(context)
            val services = isolatedServices(context)
            val recovery = services.recoverRestore()
            val expected = if (step in listOf("journalRemoved", "flags", "settling9")) com.noahwallet.services.backup.RestoreTransaction.Recovery.nothingPending
                else if (committed(step)) com.noahwallet.services.backup.RestoreTransaction.Recovery.completed
                else com.noahwallet.services.backup.RestoreTransaction.Recovery.rolledBack
            assertEquals(step, expected, recovery)
            tx.completeSettings(settings)
            assertFalse(step, location.restorePending)
            assertEquals(step, if (committed(step)) "committed" else "old", location.database.readText())
            if (committed(step)) {
                assertEquals(step, true, settings.persistedState<PersistedWalletState>(PersistedWalletState.key)?.isInitialized)
                assertTrue(tx.settingsPending)
                val authority = RecurringAutomationAuthority(location.directory)
                assertFalse(authority.allowed())
                authority.suspension(false) // Valid STOPPED state, rather than a corrupt file rejecting execution.
                assertFalse(authority.allowed())
                assertTrue(runCatching { services.ensureWalletOpen() }.exceptionOrNull() is SettingsStorage.Unavailable)
                if (step != "flags") assertEquals(listOf(SettingsStorage.settingsNotice), settings.takeUpgradeNotices())
                assertTrue(settings.takeUpgradeNotices().isEmpty())
                Os.chmod(preferences.path, 448); Os.chmod(file.path, 384)
                val reopened = SettingsStorage(context)
                tx.completeSettings(reopened)
                assertFalse(tx.settingsPending)
                assertFalse(RecurringAutomationAuthority(location.directory).allowed())
                reopened.requireHealthy()
                assertEquals(true, reopened.persistedState<PersistedWalletState>(PersistedWalletState.key)?.isInitialized)
            }
        }
        fun cleanup() {
            Os.chmod(preferences.path, 448); Os.chmod(file.path, 384); check(root.deleteRecursively())
        }
    }

    @Test fun explicitResumeRetriesPendingSettingsAndReceiptAfterStorageRecovers() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        for (stillFailing in listOf(false, true)) {
            val world = RestoreWorld(app, File(app.cacheDir, "restore-resume-${UUID.randomUUID()}"))
            try {
                world.stage("seed")
                val settings = SettingsStorage(world.context)
                val services = isolatedServices(world.context)
                services.recoverRestore()
                world.tx.completeSettings(settings)
                assertTrue(world.tx.settingsPending)
                Os.chmod(world.preferences.path, 448); Os.chmod(world.file.path, 384)
                services.lifecycle.suspend()
                val receipt = com.noahwallet.ui.RestoreCompletionJournal(world.location)
                receipt.write(com.noahwallet.services.backup.WalletRestorer.Outcome("restored-backup", "snapshot-hash", true))
                val completion = com.noahwallet.ui.RestoreCompletion(
                    loadState = { settings.persistedState<PersistedWalletState>(PersistedWalletState.key) ?: PersistedWalletState() },
                    saveState = { if (!settings.completeRestore()) throw SettingsStorage.Unavailable() },
                    seedRestored = { hash, id -> services.backupCoordinator.seedRestored(hash, id); settings.sync() },
                    recover = { services.recoverRestore(walletMutationHeld = true) }, openWallet = {},
                    journal = receipt, clearToken = services.tokens::clear,
                    settingsUnavailable = { runCatching { settings.requireHealthy() }.isFailure })
                if (stillFailing) Os.chmod(world.preferences.path, 320)
                val result = runCatching {
                    services.lifecycle.resume {
                        world.tx.completeSettings(settings)
                        completion.replay(com.noahwallet.services.backup.RestoreTransaction.Recovery.nothingPending, true)
                    }
                }
                assertEquals(stillFailing, result.isFailure)
                val state = settings.persistedState<PersistedWalletState>(PersistedWalletState.key)
                assertEquals(stillFailing, state?.isWalletSuspended)
                assertEquals(stillFailing, world.tx.settingsPending)
                assertEquals(stillFailing, receipt.pending)
                assertEquals(!stillFailing, RecurringAutomationAuthority(world.location.directory).allowed())
                if (stillFailing) {
                    assertTrue(result.exceptionOrNull()!!.message!!.contains("settings can't be saved yet"))
                    assertFalse(services.session.isOpen)
                } else {
                    assertEquals("restored-backup", PersistedBackupState.decode(settings.string(PersistedBackupState.key)).lastBackupId)
                    assertEquals("snapshot-hash", PersistedBackupState.decode(settings.string(PersistedBackupState.key)).lastUploadedSnapshotSha256)
                }
            } finally { world.cleanup() }
        }
    }

    @Test fun recurringSubmissionStopsWhenSettingsCannotPersist() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(app.cacheDir, "settings-money-${UUID.randomUUID()}").apply { check(mkdir()) }
        val settingsDirectory = File(directory, "settings").apply { check(mkdir()) }
        val context = object : ContextWrapper(app) {
            override fun getFilesDir() = settingsDirectory
            override fun getNoBackupFilesDir() = settingsDirectory
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val settings = SettingsStorage(context)
            val store = RecurringStore(settings)
            val binding = RecurringStore.Binding(SendIdentity("test", "regtest"), UUID.randomUUID().toString())
            store.bind(binding)
            store.upsert(RecurringPayment("rent", "Rent", "recipient", "ark", 1000,
                interval = RecurringInterval("day", 1), startAt = 1000, createdAt = 0, updatedAt = 0))
            var submissions = 0
            val controller = SendController(SendController.Dependencies(read = { null }, write = {},
                reservation = { submissions++; error("Submission must stay stopped") }), scope)
            val executor = RecurringExecutor(RecurringExecutor.Dependencies(store, RecurringJournal(File(directory, "journal.json")), controller,
                ensureOpen = {}, binding = { binding }, allowed = {},
                quote = { s -> SendPayment(s.destination, "ark", s.amountSat) to SendQuote(s.amountSat, 0, s.amountSat, walletIdentity = binding.wallet) },
                automationGeneration = { null }, now = { 1000 }), scope)
            Os.chmod(settingsDirectory.path, 320) // Only settings writes fail; wallet authority/journal remain writable.
            executor.execute()
            assertEquals(0, submissions)
            assertNotNull(executor.error)
            assertTrue(runCatching { store.load() }.isFailure)
        } finally {
            scope.cancel()
            Os.chmod(settingsDirectory.path, 448)
            check(directory.deleteRecursively())
        }
    }

    @Test fun unreadableSettingsAndUnwritableDirectoryKeepWalletAdmissionAndRecover() {
        for (existing in listOf(false, true)) {
            val app = InstrumentationRegistry.getInstrumentation().targetContext
            val directory = File(app.cacheDir, "settings-failure-${UUID.randomUUID()}").apply { check(mkdir()) }
            val context = object : ContextWrapper(app) {
                override fun getFilesDir() = directory
                override fun getNoBackupFilesDir() = directory
            }
            val file = File(directory, "settings.native.json")
            val prior = if (existing) SettingsStorage(context) else null
            file.writeText("unreadable")
            Os.chmod(file.path, 0); Os.chmod(directory.path, 320) // 0500
            try {
                val settings = prior ?: SettingsStorage(context)
                settings.validateStores() // Foreground open admission must not require a write.
                val wallet = settings.persistedState<PersistedWalletState>(PersistedWalletState.key)
                assertTrue(wallet?.isInitialized != false) // Seed + DB selects Home.
                assertTrue(wallet?.isBiometricsEnabled != true && wallet?.isWalletSuspended != true)
                assertNull(settings.string("privacy-storage"))
                assertEquals(listOf(SettingsStorage.settingsNotice), settings.takeUpgradeNotices())
                assertTrue(settings.takeUpgradeNotices().isEmpty())
                val stale = SettingsStorage(context)
                assertTrue(stale.takeUpgradeNotices().isEmpty())
                assertTrue(runCatching { settings.requireHealthy() }.isFailure)
                assertTrue(runCatching { settings.set("unsaved", "key") }.isFailure)
                assertNull(settings.string("key"))
                assertTrue(runCatching { RecurringStore(settings).load() }.isFailure)
                val authority = RecurringAutomationAuthority(File(directory, "unwritable"))
                assertTrue(runCatching { authority.stop() }.isFailure)
                assertFalse(authority.allowed())
                Os.chmod(directory.path, 448); Os.chmod(file.path, 384) // 0700 / 0600
                settings.set("saved", "key"); settings.sync()
                assertEquals("saved", stale.string("key"))
                stale.set("other", "second")
                assertEquals("saved", settings.string("key"))
                val reopened = SettingsStorage(context)
                assertEquals("saved", reopened.string("key"))
                assertTrue(reopened.takeUpgradeNotices().isEmpty())
            } finally {
                Os.chmod(directory.path, 448); Os.chmod(file.path, 384)
                check(directory.deleteRecursively())
            }
        }
    }
}
