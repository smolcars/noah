package com.noahwallet.services

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.noahwallet.services.storage.*
import com.noahwallet.services.wallet.EsploraSettings
import com.tencent.mmkv.*
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.serialization.json.*

/** Actual JNI importer; every test has its own files/no-backup directories. */
@RunWith(AndroidJUnit4::class)
class MMKVRecoveryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext
    private val id = SettingsStorage.instanceID
    private fun isolated(realRN: Boolean = true, test: (Context, File) -> Unit) {
        val parent = File(app.cacheDir, "native-import-${UUID.randomUUID()}").apply { check(mkdir()) }
        val root = File(parent, "mmkv")
        val noBackup = File(parent, "no-backup").apply { check(mkdir()) }
        val context = object : ContextWrapper(app) {
            override fun getFilesDir() = parent
            override fun getNoBackupFilesDir() = noBackup
        }
        try {
            if (realRN) {
                check(root.mkdir())
                listOf(id, "$id.crc").forEach { name ->
                    instrumentation.context.assets.open("rn-mmkv/$name").use { input -> File(root, name).outputStream().use { input.copyTo(it) } }
                }
            }
            test(context, root)
        } finally { check(parent.deleteRecursively()) }
    }
    private fun bytes(root: File) = listOf(id, "$id.crc").map { File(root, it).readBytes().toList() }
    private fun writeRN(root: File, values: Map<String, String>) {
        check(root.isDirectory || root.mkdir())
        MMKV.initialize(app, root.path, null, MMKVLogLevel.LevelNone, null)
        val rn = MMKV.mmkvWithID(id, MMKV.SINGLE_PROCESS_MODE, null, root.path)!!
        rn.clearAll(); values.forEach { (key, raw) -> check(rn.encode(key, raw)) }; rn.sync(); rn.close()
    }
    @Test fun realRNImportAndThousandsOfNativeWritesPreserveSource() = isolated { context, root ->
        val before = bytes(root)
        val settings = SettingsStorage(context)
        assertEquals(true, settings.persistedState<PersistedWalletState>(PersistedWalletState.key)?.isInitialized)
        assertTrue(com.noahwallet.recurring.RecurringStore(settings).load().schedules.isEmpty())
        assertFalse(settings.nativeFile.load().recurringNoticePending)
        repeat(2000) { settings.set(it.toString(), "native-counter") }
        assertEquals(before, bytes(root))
        val backup = MMKVUpgradeBackup(root, id, backupRoot = context.noBackupFilesDir)
        assertEquals(before, bytes(backup.directory))
        assertEquals("1999", SettingsStorage(context).string("native-counter"))
    }
    @Test fun existingSnapshotWinsOverLaterLiveRNChanges() = isolated { context, root ->
        MMKVUpgradeBackup(root, id, backupRoot = context.noBackupFilesDir).prepare()
        writeRN(root, mapOf(PersistedWalletState.key to """{"state":{"isInitialized":false},"version":0}"""))
        val live = bytes(root)
        val imported = SettingsStorage(context)
        assertEquals(true, imported.persistedState<PersistedWalletState>(PersistedWalletState.key)?.isInitialized)
        assertEquals(live, bytes(root))
    }
    @Test fun damagedSnapshotIsNeverRecovered() {
        for (offset in listOf(0, 28)) isolated { context, root ->
            val meta = File(root, "$id.crc").readBytes().apply { this[offset] = (this[offset].toInt() xor 255).toByte() }
            File(root, "$id.crc").writeBytes(meta); val before = bytes(root)
            val settings = SettingsStorage(context)
            assertEquals(mapOf(EsploraSettings.historyUnavailableKey to "1"), settings.nativeFile.load().values)
            assertEquals(listOf(SettingsStorage.settingsNotice), settings.takeUpgradeNotices())
            assertEquals(before, bytes(root))
        }
    }
    @Test fun freshInstallCreatesNoMMKV() = isolated(false) { context, root ->
        val settings = SettingsStorage(context)
        settings.set("native", "counter")
        assertFalse(root.exists()); assertTrue(settings.nativeFile.exists)
        assertTrue(settings.takeUpgradeNotices().isEmpty())
    }
    @Test fun deletionAndReonboardingNeverImportAgain() = isolated { context, root ->
        val settings = SettingsStorage(context)
        settings.removeAll()
        assertFalse(File(root, id).exists()); assertFalse(File(root, "$id.crc").exists())
        assertFalse(MMKVUpgradeBackup(root, id, backupRoot = context.noBackupFilesDir).directory.exists())
        assertTrue(settings.nativeFile.exists)
        writeRN(root, mapOf(PersistedWalletState.key to """{"state":{"isInitialized":false},"version":0}"""))
        assertNull(SettingsStorage(context).string(PersistedWalletState.key))
        settings.setPersistedState(PersistedWalletState(isInitialized = true), PersistedWalletState.key, 0)
        assertEquals(true, SettingsStorage(context).persistedState<PersistedWalletState>(PersistedWalletState.key)?.isInitialized)
    }
    @Test fun noticeIsShownOnceAcrossRestarts() = isolated { context, root ->
        writeRN(root, mapOf("recurring-payment-storage" to """{"state":{"schedules":{"old":{"id":"old"}}},"version":0}"""))
        val settings = SettingsStorage(context)
        assertTrue(settings.takeUpgradeNotices().contains(SettingsStorage.recurringNotice))
        assertTrue(settings.takeUpgradeNotices().isEmpty())
        assertTrue(SettingsStorage(context).takeUpgradeNotices().isEmpty())
    }
    @Test fun damagedNativeFileUsesDefaultsAndNoticeWithoutReimport() = isolated { context, _ ->
        val settings = SettingsStorage(context)
        settings.nativeFile.file.writeText("damaged")
        val reset = SettingsStorage(context)
        assertEquals(mapOf(EsploraSettings.historyUnavailableKey to "1"), reset.nativeFile.load().values)
        assertEquals(listOf(SettingsStorage.settingsNotice), reset.takeUpgradeNotices())
        assertTrue(SettingsStorage(context).takeUpgradeNotices().isEmpty())
    }
    @Test fun backgroundAndForegroundConcurrentImportShareOneGeneration() = isolated { context, root ->
        writeRN(root, mapOf("recurring-payment-storage" to """{"state":{"schedules":{"old":{"id":"old"}}},"version":0}"""))
        val before = bytes(root); val pool = Executors.newFixedThreadPool(4)
        try {
            val results = pool.invokeAll((0..7).map { java.util.concurrent.Callable { SettingsStorage(context) } }).map { it.get() }
            assertTrue(results.all { it.nativeFile.load() == results.first().nativeFile.load() })
            assertEquals(1, results.count { it.takeUpgradeNotices().isNotEmpty() })
            assertEquals(before, bytes(root))
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)) }
    }
    @Test fun badKeyDropsIndependentlyAndAllOtherRNKeysRemainByteExact() = isolated(false) { context, root ->
        val wallet = """{"state":{"isInitialized":true},"version":0}"""
        writeRN(root, mapOf("wallet-storage" to wallet, "privacy-storage" to "broken", "unknown-key" to "raw-string"))
        val settings = SettingsStorage(context)
        assertEquals(wallet, settings.string("wallet-storage")); assertNull(settings.string("privacy-storage"))
        assertEquals("raw-string", settings.string("unknown-key"))
        assertEquals(listOf(SettingsStorage.settingsNotice), settings.takeUpgradeNotices())
    }
    @Test fun sharedSettingsLaunchPolicyIncludesHeadlessFirstImport() {
        val fixture = instrumentation.context.assets.open("mmkv-launch-state.json").bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
        val cases = fixture.getValue("cases").jsonArray; assertEquals(8, cases.size)
        for (case in cases) isolated(false) { context, root ->
            val item = case.jsonObject; val wallet = item.getValue("input").jsonObject.getValue("wallet").jsonPrimitive.content
            val raw = when (wallet) {
                "initialized", "damaged" -> """{"state":{"isInitialized":true},"version":0}"""
                "unfinished" -> """{"state":{"isInitialized":false},"version":0}"""
                "missingFlag" -> """{"state":{},"version":0}"""
                "undecodable" -> "broken"
                else -> null
            }
            if (wallet != "absent") {
                writeRN(root, raw?.let { mapOf(PersistedWalletState.key to it) }.orEmpty())
                if (wallet == "damaged") File(root, "$id.crc").let { file -> file.writeBytes(file.readBytes().apply { this[0] = (this[0].toInt() xor 255).toByte() }) }
            }
            val settings = SettingsStorage(context)
            val state = settings.persistedState<PersistedWalletState>(PersistedWalletState.key)
            val expected = item.getValue("expected").jsonObject
            assertEquals(item.getValue("name").jsonPrimitive.content, expected.getValue("outcome").jsonPrimitive.content,
                if (state?.isInitialized == false) "onboarding" else "existingWallet")
            assertEquals(expected.getValue("settingsNotice").jsonPrimitive.boolean, settings.nativeFile.load().settingsNoticePending)
        }
    }
}
