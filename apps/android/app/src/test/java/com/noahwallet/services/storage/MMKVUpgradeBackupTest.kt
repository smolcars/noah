package com.noahwallet.services.storage

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class MMKVUpgradeBackupTest {
    @Test fun snapshotIsImmutableAndInspectionDisposable() {
        val dir = Files.createTempDirectory("mmkv-snapshot").toFile()
        try {
            val root = File(dir, "mmkv").apply { mkdir() }; val id = SettingsStorage.instanceID
            File(root, id).writeText("data"); File(root, "$id.crc").writeText("meta")
            val syncs = mutableListOf<File>(); val backup = MMKVUpgradeBackup(root, id, syncs::add)
            backup.prepare(); assertTrue(backup.hasContent)
            val before = File(backup.directory, id).readBytes()
            backup.inspectSnapshot { File(it, id).writeText("scratch-only") }
            assertArrayEquals(before, File(backup.directory, id).readBytes())
            File(root, id).writeText("RN later write"); backup.prepare()
            assertArrayEquals(before, File(backup.directory, id).readBytes())
            assertFalse(File(backup.directory, "inspection").exists())
            assertTrue(syncs.contains(backup.directory)); assertTrue(syncs.contains(dir))
            backup.deleteSnapshot(); assertFalse(backup.directory.exists())
        } finally { dir.deleteRecursively() }
    }
    @Test fun missingOrIncompletePairsNeverCreateLiveFiles() {
        for (partial in listOf(false, true)) {
            val dir = Files.createTempDirectory("mmkv-absent").toFile()
            try {
                val root = File(dir, "mmkv"); val id = SettingsStorage.instanceID
                if (partial) { root.mkdir(); File(root, id).writeText("truncated") }
                val backup = MMKVUpgradeBackup(root, id, {})
                backup.prepare(); assertEquals(partial, backup.hasContent)
                assertFalse(File(root, "$id.crc").exists())
                assertEquals(partial, root.exists())
            } finally { dir.deleteRecursively() }
        }
    }
    @Test fun failedPublicationRetriesWithoutTouchingSource() {
        val dir = Files.createTempDirectory("mmkv-retry").toFile()
        try {
            val root = File(dir, "mmkv").apply { mkdir() }; val id = SettingsStorage.instanceID
            File(root, id).writeText("data"); File(root, "$id.crc").writeText("meta")
            assertTrue(runCatching { MMKVUpgradeBackup(root, id, { error("sync") }).prepare() }.isFailure)
            MMKVUpgradeBackup(root, id, {}).prepare()
            assertEquals("data", File(root, id).readText()); assertEquals("meta", File(root, "$id.crc").readText())
        } finally { dir.deleteRecursively() }
    }
}
