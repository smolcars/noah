package com.noahwallet.services.storage

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class MMKVUpgradeBackupTest {
    private fun withRoot(test: (File) -> Unit) {
        val parent = Files.createTempDirectory("mmkv-upgrade").toFile()
        try { test(File(parent, "mmkv").also { check(it.mkdir()) }) }
        finally { parent.deleteRecursively() }
    }
    private fun seed(root: File) {
        File(root, SettingsStorage.instanceID).writeBytes(ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN).putInt(100).array())
        File(root, "${SettingsStorage.instanceID}.crc").writeBytes(ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN).putInt(28, 100).array())
    }
    private fun backup(root: File, sync: (File) -> Unit = {}) = MMKVUpgradeBackup(root, SettingsStorage.instanceID, sync)

    @Test fun backupIsByteExactOutsideRootAndNeverOverwritten() = withRoot { root ->
        seed(root); val original = File(root, SettingsStorage.instanceID).readBytes()
        val b = backup(root); b.prepare()
        assertEquals(root.parentFile, b.directory.parentFile)
        assertTrue(b.hasContent)
        assertArrayEquals(original, File(b.directory, SettingsStorage.instanceID).readBytes())
        File(root, SettingsStorage.instanceID).writeBytes(byteArrayOf(1))
        b.prepare()
        assertArrayEquals(original, File(b.directory, SettingsStorage.instanceID).readBytes())
    }
    @Test fun missingPairBlocksWithoutMutatingTheSource() = withRoot { root ->
        val file = File(root, SettingsStorage.instanceID); file.writeBytes(byteArrayOf(9))
        assertTrue(runCatching { backup(root).prepare() }.isFailure)
        assertArrayEquals(byteArrayOf(9), file.readBytes())
        assertFalse(backup(root).directory.exists())
    }
    @Test fun fsyncFailureBlocksAndNextLaunchRetries() = withRoot { root ->
        seed(root); var fail = true
        val b = backup(root) { if (fail) error("fsync") }
        assertTrue(runCatching { b.prepare() }.isFailure)
        assertFalse(b.directory.exists())
        fail = false; b.prepare(); assertTrue(b.hasContent)
    }
    @Test fun publishedBackupStillRequiresDirectorySyncOnRetry() = withRoot { root ->
        seed(root); val b = backup(root); b.prepare()
        assertTrue(runCatching { backup(root) { error("fsync") }.prepare() }.isFailure)
        b.prepare(); assertTrue(b.hasContent)
    }
    @Test fun emptyFirstInstallIsRecordedWithoutInventingRNFiles() = withRoot { root ->
        val b = backup(root); b.prepare(); assertFalse(b.hasContent)
        assertFalse(File(b.directory, SettingsStorage.instanceID).exists())
        seed(root); b.prepare(); assertFalse(b.hasContent)
    }
    @Test fun interruptedRestoreFinishesBeforeOpeningAndRetainsTheOriginal() = withRoot { root ->
        seed(root); val b = backup(root); b.prepare()
        val original = File(root, SettingsStorage.instanceID).readBytes()
        File(b.directory, "restore-pending").writeBytes(byteArrayOf(1))
        File(root, SettingsStorage.instanceID).writeBytes(byteArrayOf(7))
        b.prepare()
        assertTrue(b.restored); assertFalse(File(b.directory, "restore-pending").exists())
        assertArrayEquals(original, File(root, SettingsStorage.instanceID).readBytes())
        assertArrayEquals(original, File(b.directory, SettingsStorage.instanceID).readBytes())
    }
    @Test fun resetKeepsBackupButDisablesAutomaticResurrection() = withRoot { root ->
        seed(root); val b = backup(root); b.prepare(); assertTrue(b.hasContent)
        b.markReset(); assertFalse(backup(root).hasContent)
        assertTrue(File(b.directory, SettingsStorage.instanceID).exists())
    }
    @Test fun lastConfirmedSizeAlsoCountsAsContent() = withRoot { root ->
        seed(root)
        File(root, SettingsStorage.instanceID).writeBytes(ByteArray(4096))
        File(root, "${SettingsStorage.instanceID}.crc").writeBytes(ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN).putInt(32, 100).array())
        val b = backup(root); b.prepare(); assertTrue(b.hasContent)
    }
}
