package com.noahwallet.services

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.noahwallet.services.storage.*
import com.tencent.mmkv.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real JNI/MMKV checks: JVM tests cannot exercise mmap, checksums or native recovery. */
@RunWith(AndroidJUnit4::class)
class MMKVRecoveryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext
    private val id = SettingsStorage.instanceID
    private fun fixture(test: (Context, File) -> Unit) {
        val parent = File(app.cacheDir, "mmkv-recovery-${UUID.randomUUID()}").also { check(it.mkdir()) }
        val root = File(parent, "mmkv").also { check(it.mkdir()) }
        val context = object : ContextWrapper(app) { override fun getFilesDir() = parent }
        try {
            listOf(id, "$id.crc").forEach { name ->
                instrumentation.context.assets.open("rn-mmkv/$name").use { input -> File(root, name).outputStream().use { input.copyTo(it) } }
            }
            test(context, root)
        } finally {
            MMKV.mmkvWithID(id, MMKV.SINGLE_PROCESS_MODE, null, root.path)?.close()
            check(parent.deleteRecursively())
        }
    }
    private fun corrupt(root: File, mode: String) {
        val file = File(root, id); var data = file.readBytes(); val meta = File(root, "$id.crc").readBytes()
        meta[0] = (meta[0].toInt() xor 0xff).toByte()
        for (offset in 32..39) meta[offset] = 0xff.toByte()
        if (mode == "length") for (offset in 0..3) { data[offset] = 0xff.toByte(); meta[28 + offset] = 0xff.toByte() }
        if (mode == "truncate") data = data.copyOf(4 + ByteBuffer.wrap(meta, 28, 4).order(ByteOrder.LITTLE_ENDIAN).int - 16)
        if (mode == "empty") data.fill(0)
        file.writeBytes(data); File(root, "$id.crc").writeBytes(meta)
    }
    private class Discard : MMKVHandler {
        override fun onMMKVCRCCheckFail(mmapID: String) = MMKVRecoverStrategic.OnErrorDiscard
        override fun onMMKVFileLengthError(mmapID: String) = MMKVRecoverStrategic.OnErrorDiscard
        override fun wantLogRedirecting() = true
        override fun mmkvLog(level: MMKVLogLevel, file: String, line: Int, function: String, message: String) {}
    }
    @Test fun defaultDiscardLosesTheSameRNBytesThatRecoveryPreserves() {
        fixture { _, root ->
            MMKV.initialize(app, root.path, null, MMKVLogLevel.LevelWarning, Discard())
            corrupt(root, "crc")
            val store = MMKV.mmkvWithID(id, MMKV.SINGLE_PROCESS_MODE, null, root.path)!!
            assertFalse(RNStoreCodec.versions.keys.any(store::containsKey))
            store.close()
        }
        for (mode in listOf("crc", "length", "truncate")) fixture { context, root ->
            corrupt(root, mode); val original = File(root, id).readBytes()
            val store = SettingsStorage(context)
            store.requireHealthy()
            assertTrue(RNStoreCodec.versions.keys.any { store.string(it) != null })
            assertNotNull(store.string("wallet-storage"))
            assertTrue(original.contentEquals(File(root.path + ".native-backup/$id").readBytes()))
        }
    }
    @Test fun emptyRecoveryRestoresTheImmutableBackupOnce() = fixture { context, root ->
        val backup = MMKVUpgradeBackup(root, id); backup.prepare()
        val original = File(root, id).readBytes()
        corrupt(root, "empty")
        val store = SettingsStorage(context); store.requireHealthy()
        assertNotNull(store.string("wallet-storage")); assertTrue(backup.restored)
        assertTrue(original.contentEquals(File(backup.directory, id).readBytes()))
    }
    @Test fun unrecoverableBytesBlockReadsAndWritesInsteadOfOnboarding() = fixture { context, root ->
        corrupt(root, "empty")
        val store = SettingsStorage(context)
        assertTrue(runCatching { store.validateStores() }.exceptionOrNull() is SettingsStorage.Unavailable)
        assertTrue(runCatching { store.set("{}", "server-storage") }.isFailure)
        assertTrue(MMKVUpgradeBackup(root, id).restored)
    }
    @Test fun incompleteBackupPreventsOpenAndDoesNotCreateTheMissingCRC() = fixture { context, root ->
        val crc = File(root, "$id.crc"); check(crc.delete())
        val store = SettingsStorage(context)
        assertTrue(runCatching { store.requireHealthy() }.isFailure)
        assertFalse(crc.exists())
    }
    @Test fun existingDatabaseWithMissingWalletStoreBlocksButExplicitIncompleteStateCanResume() = fixture { context, root ->
        val store = SettingsStorage(context)
        val location = WalletDataLocation(context.filesDir, com.noahwallet.BuildConfig.APP_VARIANT)
        check(location.directory.mkdirs()); File(location.directory, "db.sqlite").writeBytes(byteArrayOf(1))
        MMKV.mmkvWithID(id, MMKV.SINGLE_PROCESS_MODE, null, root.path)!!.removeValueForKey("wallet-storage")
        assertTrue(runCatching { store.validateStores() }.exceptionOrNull() is SettingsStorage.Unavailable)
        store.set("""{"state":{"isInitialized":false},"version":0}""", "wallet-storage")
        store.validateStores()
        assertFalse(store.persistedState<PersistedWalletState>("wallet-storage")!!.isInitialized == true)
    }
}
