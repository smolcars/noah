package com.noahwallet.services.storage

import android.app.Application
import android.content.ContextWrapper
import com.noahwallet.services.logging.EndpointLogPrivacy
import com.noahwallet.services.logging.LogFile
import com.noahwallet.services.logging.SafeErrorDescription
import com.noahwallet.services.wallet.EsploraSettings
import io.github.niteshbalusu11.bark.BarkError
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SettingsHistoryPrivacyTest {
    @Test fun unreadableSettingsKeepUnknownHistoryAcrossRestartAndReset() {
        EndpointLogPrivacy.register("https://review.example/2037-11-29")
        val root = Files.createTempDirectory("lost-history").toFile()
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(root, "no-backup").apply { mkdirs() }
        }
        try {
            val file = NativeSettingsFile(File(context.noBackupFilesDir, "settings.native.json"), sync = {})
            // Previous-process history: never register it in this process.
            file.save(NativeSettingsFile.State(mapOf(EsploraSettings.key to """{"active":{"variant":"signet"},""" +
                """"logEndpoints":["https://fakeR4LostHistory.example/retiredFakeR4"]}""")))
            file.file.writeText(file.file.readText().replace("retiredFakeR4", "damagedFakeR4"))
            val logs = File(root, "logs").apply { mkdirs() }
            File(logs, "noah.log.1").writeText("HTTP failure https://fakeR4LostHistory.example/retiredFakeR4")
            repeat(2) {
                val storage = SettingsStorage(context, sync = {})
                val failure = runCatching { EsploraSettings(storage).restoreLogPrivacy() }.exceptionOrNull()
                assertNotNull("unknown history must reach startup's failClosed catch", failure)
                if (failure != null) EndpointLogPrivacy.failClosed()
                val input = "2037-11-29 12:21:42.292 D [sdk] ordinary"
                assertEquals("[custom-esplora]", EndpointLogPrivacy.redact(input))
                assertEquals("[custom-esplora]", LogFile.redact(input))
                assertEquals("[custom-esplora]", LogFile.redactForTelemetry(input))
                assertEquals("[custom-esplora]", SafeErrorDescription.of(BarkError(input)))
                assertEquals("[custom-esplora]", EndpointLogPrivacy.redactRecord(input))
                assertEquals(listOf("[custom-esplora]"), EndpointLogPrivacy.redactLogLines(listOf(input)))
                assertFalse(LogFile(logs, executor = Executor { it.run() }).lines().joinToString().contains("fakeR4LostHistory"))
                storage.set("fake fallback write", "fake-test-setting")
                storage.useDefaultEsplora("signet")
                storage.takeUpgradeNotices()
            }
            SettingsStorage(context, sync = {}).removeAll()
            val reset = SettingsStorage(context, sync = {})
            assertTrue(runCatching { EsploraSettings(reset).restoreLogPrivacy() }.isFailure)
            assertEquals("1", NativeSettingsFile(file.file).load().values["native-esplora-history-unavailable"])
        } finally { root.deleteRecursively() }
    }
}
