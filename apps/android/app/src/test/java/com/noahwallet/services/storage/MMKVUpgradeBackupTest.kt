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
    @Test fun inventoryInspectionNeverMutatesTheImmutablePair() = withRoot { root ->
        seed(root); val b = backup(root); b.prepare()
        val original = File(b.directory, SettingsStorage.instanceID).readBytes()
        b.inspectSnapshot { inspection ->
            File(inspection, SettingsStorage.instanceID).writeBytes(byteArrayOf(1))
        }
        assertArrayEquals(original, File(b.directory, SettingsStorage.instanceID).readBytes())
        assertFalse(File(b.directory, "inspection").exists())
        assertTrue(runCatching { b.inspectSnapshot { error("undecodable") } }.isFailure)
        assertFalse(File(b.directory, "inspection").exists())
    }

    @Test fun resetDeletesSnapshotButKeepsRetirementAcrossReonboarding() = withRoot { root ->
        seed(root); val b = backup(root); b.prepare(); assertTrue(b.hasContent)
        val staging = File(b.directory.path + ".pending").also { check(it.mkdir()) }
        File(staging, "old").writeText("old")
        b.markReset(); b.deleteSnapshot()
        assertFalse(b.hasContent); assertFalse(b.directory.exists()); assertFalse(staging.exists())
        assertEquals(emptyList<String>(), b.retiredKeys)
        val restarted = backup(root); restarted.prepare()
        assertFalse(restarted.directory.exists())
        seed(root); restarted.retire(listOf("wallet-storage")); restarted.prepare()
        assertFalse(restarted.directory.exists()); assertTrue(runCatching { restarted.restore() }.isFailure)
    }
    @Test fun retirementSurvivesRestartAndRefusesAutomaticRestore() = withRoot { root ->
        seed(root); val b = backup(root); b.prepare()
        val original = File(b.directory, SettingsStorage.instanceID).readBytes()
        b.retire(listOf("wallet-storage", "profile-storage"))
        File(root, SettingsStorage.instanceID).writeBytes(byteArrayOf(7))
        val restarted = backup(root); restarted.prepare()
        assertEquals(listOf("profile-storage", "wallet-storage"), restarted.retiredKeys)
        assertTrue(runCatching { restarted.restore() }.isFailure)
        assertArrayEquals(byteArrayOf(7), File(root, SettingsStorage.instanceID).readBytes())
        assertArrayEquals(original, File(b.directory, SettingsStorage.instanceID).readBytes())
    }
    @Test fun failedRetirementSyncCannotAdmitMutationAndRetriesOnRestart() = withRoot { root ->
        seed(root); backup(root).prepare()
        val syncing = mutableListOf<File>()
        val b = backup(root) { syncing += it; error("fsync") }
        assertTrue(runCatching { b.retire(listOf("wallet-storage")) }.isFailure)
        val retry = backup(root) { syncing += it }; retry.prepare()
        assertTrue(syncing.contains(retry.retirementFile))
        assertTrue(syncing.contains(retry.retirementFile.parentFile))
        assertTrue(runCatching { retry.restore() }.isFailure)
    }
    @Test fun pendingRestoreCannotOverwriteStateAfterRetirement() = withRoot { root ->
        seed(root); val b = backup(root); b.prepare(); b.retire(listOf("wallet-storage"))
        File(b.directory, "restore-pending").writeBytes(byteArrayOf(1))
        File(root, SettingsStorage.instanceID).writeBytes(byteArrayOf(7))
        b.prepare()
        assertArrayEquals(byteArrayOf(7), File(root, SettingsStorage.instanceID).readBytes())
    }

    @Test fun backupAndRetirementUseTheProvidedNoBackupDirectory() = withRoot { root ->
        seed(root)
        val noBackup = File(root.parentFile, "no-backup").also { check(it.mkdir()) }
        val b = MMKVUpgradeBackup(root, SettingsStorage.instanceID, {}, noBackup)
        b.prepare(); b.retire(listOf("wallet-storage"))
        assertEquals(noBackup, b.directory.parentFile); assertEquals(noBackup, b.retirementFile.parentFile)
        assertFalse(File(root.path + ".native-backup").exists())
    }

    @Test fun lastConfirmedSizeAlsoCountsAsContent() = withRoot { root ->
        seed(root)
        File(root, SettingsStorage.instanceID).writeBytes(ByteArray(4096))
        File(root, "${SettingsStorage.instanceID}.crc").writeBytes(ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN).putInt(32, 100).array())
        val b = backup(root); b.prepare(); assertTrue(b.hasContent)
    }
}
