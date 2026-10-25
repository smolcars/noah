package com.noahwallet.services.storage

import android.app.Application
import android.content.ContextWrapper
import com.noahwallet.services.wallet.EsploraSettings
import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SettingsCacheTest {
    private fun withSettings(body: (ContextWrapper, File) -> Unit) {
        val root = Files.createTempDirectory("settings-cache").toFile()
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(root, "no-backup").apply { mkdirs() }
        }
        try { body(context, File(context.noBackupFilesDir, "settings.native.json")) }
        finally { root.setWritable(true); File(root, "no-backup").setWritable(true); root.deleteRecursively() }
    }

    @Test fun readsComeFromMemoryAndWritesShowAtOnce() = withSettings { context, file ->
        val settings = SettingsStorage(context, sync = {})
        settings.set("first", "key")
        val other = SettingsStorage(context, sync = {})
        assertEquals("first", other.string("key"))
        other.set("second", "key")
        assertEquals("second", settings.string("key"))
        assertEquals("second", NativeSettingsFile(file).load().values["key"])
        // An unreadable but unchanged file still serves the committed generation; the health check reads the disk.
        check(file.setReadable(false) && file.parentFile!!.setWritable(false))
        assertEquals("second", settings.string("key"))
        assertThrows(SettingsStorage.Unavailable::class.java) { settings.requireHealthy() }
        assertNull(settings.string("key"))
    }

    @Test fun anotherWriterIsReadBack() = withSettings { context, file ->
        val settings = SettingsStorage(context, sync = {})
        settings.set("ours", "key")
        assertEquals("ours", settings.string("key"))
        NativeSettingsFile(file, sync = {}).save(NativeSettingsFile.State(mapOf("key" to "a longer value")))
        assertEquals("a longer value", settings.string("key"))
        // Same size, so only the modification time tells them apart.
        val modified = file.lastModified()
        NativeSettingsFile(file, sync = {}).save(NativeSettingsFile.State(mapOf("key" to "b longer value")))
        check(file.setLastModified(modified + 2000))
        assertEquals("b longer value", settings.string("key"))
        file.writeText("damaged")
        assertNull(settings.string("key"))
        assertEquals("1", settings.string(EsploraSettings.historyUnavailableKey))
        assertEquals(listOf(SettingsStorage.settingsNotice), settings.takeUpgradeNotices())
    }

    @Test fun failedWritesKeepTheCommittedValue() = withSettings { context, file ->
        for (failing in listOf<(File) -> Boolean>({ !it.isDirectory }, { it.isDirectory })) { // before and after the rename
            var fail = false
            val settings = SettingsStorage(context) { if (fail && failing(it)) throw IOException("disk") }
            settings.set("kept", "key")
            fail = true
            assertThrows(IOException::class.java) { settings.set("lost", "key") }
            assertEquals("kept", settings.string("key"))
            assertThrows(SettingsStorage.Unavailable::class.java) { settings.requireHealthy() }
            fail = false
            settings.set("next", "key")
            settings.requireHealthy()
            assertEquals("next", settings.string("key"))
            assertEquals("next", NativeSettingsFile(file).load().values["key"])
        }
    }
}
