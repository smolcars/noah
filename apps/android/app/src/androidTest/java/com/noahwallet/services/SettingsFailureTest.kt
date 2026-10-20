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
