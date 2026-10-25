package com.noahwallet.services.storage

import android.app.Application
import android.content.ContextWrapper
import com.noahwallet.services.wallet.EsploraSettings
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
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
    /** The host's stat, as precise as [settingsStamp] on a device (Robolectric's Os.stat is a stub). */
    private val hostStat: (File) -> List<Long>? = { file -> runCatching {
        Files.readAttributes(file.toPath(), "unix:ino,size,lastModifiedTime,ctime").let {
            listOf(it["ino"] as Long, it["size"] as Long, (it["lastModifiedTime"] as FileTime).to(TimeUnit.NANOSECONDS), (it["ctime"] as FileTime).to(TimeUnit.NANOSECONDS))
        }
    }.getOrNull() }
    private fun storage(context: ContextWrapper, sync: (File) -> Unit = {}) = SettingsStorage(context, sync, hostStat)
    /** Another writer's generation, bypassing the storage (and its cache). */
    private fun replace(file: File, values: Map<String, String>) = NativeSettingsFile(file, sync = {}).save(NativeSettingsFile.State(values))

    @Test fun readsSeeEveryWriteAtOnce() = withSettings { context, file ->
        val settings = storage(context)
        settings.set("first", "key")
        val other = storage(context)
        assertEquals("first", other.string("key"))
        other.set("second", "key")
        assertEquals("second", settings.string("key"))
        assertEquals("second", NativeSettingsFile(file).load().values["key"])
    }

    @Test fun anotherWritersGenerationIsReadBack() = withSettings { context, file ->
        val settings = storage(context)
        settings.set("ours", "key")
        assertEquals("ours", settings.string("key"))
        replace(file, mapOf("key" to "a longer value"))
        assertEquals("a longer value", settings.string("key"))
        file.writeText("damaged")
        assertNull(settings.string("key"))
        assertEquals("1", settings.string(EsploraSettings.historyUnavailableKey))
        assertEquals(listOf(SettingsStorage.settingsNotice), settings.takeUpgradeNotices())
    }

    @Test fun anEqualSizeReplacementWithItsModificationTimeIsReadAndKept() = withSettings { context, file ->
        val first = storage(context); val second = storage(context)
        first.set("a", "kept"); first.set("x", "other")
        assertEquals("x", second.string("other")) // generation A, now in memory
        val size = file.length(); val modified = Files.getLastModifiedTime(file.toPath())
        replace(file, mapOf("kept" to "b", "other" to "y"))
        Files.setLastModifiedTime(file.toPath(), modified)
        check(file.length() == size && Files.getLastModifiedTime(file.toPath()) == modified)
        assertEquals("b", first.string("kept")); assertEquals("y", second.string("other"))
        second.set("c", "kept")
        assertEquals(mapOf("kept" to "c", "other" to "y"), NativeSettingsFile(file).load().values)
    }

    @Test fun aReplacementDuringOurWriteIsReadAndKept() = withSettings { context, file ->
        var replaceAfterRename = false
        // The directory sync runs after our rename: another writer replaces the file right then.
        val settings = storage(context) { if (replaceAfterRename && it.isDirectory) { replaceAfterRename = false; replace(file, mapOf("key" to "theirs", "their" to "kept")) } }
        settings.set("before", "key")
        replaceAfterRename = true
        settings.set("ours", "key")
        assertEquals("theirs", settings.string("key"))
        assertEquals("kept", storage(context).string("their"))
        settings.set("next", "key")
        assertEquals(mapOf("key" to "next", "their" to "kept"), NativeSettingsFile(file).load().values)
    }

    @Test fun aChangedPermissionIsReadAgain() = withSettings { context, file ->
        val settings = storage(context)
        settings.set("value", "key")
        assertEquals("value", settings.string("key"))
        // Only the change time moves; the unreadable file falls back to defaults and the health check fails.
        val before = hostStat(file)
        Thread.sleep(20) // Linux file times tick coarsely (~1-10 ms); a chmod in the same tick leaves ctime unchanged.
        check(file.setReadable(false) && file.parentFile!!.setWritable(false))
        assumeFalse("permissions are ignored here (e.g. running as root)", file.canRead())
        // If the filesystem still shows no change, skip; the device test covers the production stamp.
        assumeTrue("this filesystem's stat doesn't show a permission change", hostStat(file) != before)
        assertNull(settings.string("key"))
        assertThrows(SettingsStorage.Unavailable::class.java) { settings.requireHealthy() }
    }

    @Test fun failedWritesKeepTheCommittedValue() = withSettings { context, file ->
        for (failing in listOf<(File) -> Boolean>({ !it.isDirectory }, { it.isDirectory })) { // before and after the rename
            var fail = false
            val settings = storage(context) { if (fail && failing(it)) throw IOException("disk") }
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
