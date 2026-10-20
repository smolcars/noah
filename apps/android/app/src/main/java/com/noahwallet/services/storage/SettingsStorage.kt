package com.noahwallet.services.storage

import android.content.Context
import com.tencent.mmkv.MMKV
import com.tencent.mmkv.MMKVLogLevel
import java.io.File
import kotlinx.serialization.json.*

/** MMKV is only opened on a verified disposable snapshot; native writes use one atomic file. */
class SettingsStorage(context: Context) {
    private val root = File(context.filesDir, "mmkv")
    private val backupRoot = context.noBackupFilesDir
    internal val nativeFile = NativeSettingsFile(File(backupRoot, "settings.native.json"))

    init { synchronized(openLock) {
        check(backupRoot.isDirectory || backupRoot.mkdirs())
        if (nativeFile.exists) {
            if (runCatching { nativeFile.load() }.isFailure) nativeFile.save(NativeSettingsFile.State(settingsNoticePending = true))
        } else {
            val backup = MMKVUpgradeBackup(root, instanceID, backupRoot = backupRoot)
            val state = runCatching {
                backup.prepare()
                if (!backup.hasContent) NativeSettingsFile.State() else backup.inspectSnapshot { scratch ->
                    check(File(scratch, instanceID).isFile && File(scratch, "$instanceID.crc").isFile)
                    check(MMKVIntegrity.verifies(scratch, instanceID))
                    MMKV.initialize(context.applicationContext, scratch.path, null, MMKVLogLevel.LevelNone, null)
                    val snapshot = checkNotNull(MMKV.mmkvWithID(instanceID, MMKV.SINGLE_PROCESS_MODE, null, scratch.path))
                    try {
                        importValues(snapshot.allKeys().orEmpty().associateWith { key ->
                            runCatching { RNStoreCodec.storedString(true, snapshot.decodeBytes(key)) }.getOrNull()
                        })
                    } finally { snapshot.close() }
                }
            }.getOrElse { NativeSettingsFile.State(settingsNoticePending = true) }
            nativeFile.save(state)
        }
        finishCleanup()
    } }

    class Unavailable : Exception("Native settings write failed")
    fun requireHealthy() { synchronized(openLock) { load() } }
    private fun load(): NativeSettingsFile.State = try { nativeFile.load() } catch (_: Exception) {
        NativeSettingsFile.State(settingsNoticePending = true).also(nativeFile::save)
    }
    fun string(key: String): String? = synchronized(openLock) { load().values[key] }
    fun set(value: String, key: String) = synchronized(openLock) {
        val state = load()
        val preserved = if (key in RNStoreCodec.versions) RNStoreCodec.merge(key, state.values[key], value) else value
        nativeFile.save(state.copy(values = state.values + (key to preserved)))
    }
    fun validateStores() = requireHealthy()
    // set() commits synchronously; retain this API for automation callers.
    fun sync() = requireHealthy()
    fun takeUpgradeNotices(): List<String> = synchronized(openLock) {
        val state = load()
        val notices = listOfNotNull(settingsNotice.takeIf { state.settingsNoticePending }, recurringNotice.takeIf { state.recurringNoticePending })
        if (notices.isNotEmpty()) nativeFile.save(state.copy(settingsNoticePending = false, recurringNoticePending = false))
        notices
    }
    fun removeAll() = synchronized(openLock) {
        nativeFile.save(NativeSettingsFile.State(cleanupPending = true))
        finishCleanup()
    }
    private fun finishCleanup() {
        if (!nativeFile.load().cleanupPending) return
        check(!root.exists() || root.deleteRecursively())
        syncDirectory(root.parentFile!!)
        MMKVUpgradeBackup(root, instanceID, backupRoot = backupRoot).deleteSnapshot()
        nativeFile.save(NativeSettingsFile.State()) // Keep the marker through re-onboarding.
    }
    inline fun <reified State> persistedState(key: String): State? = string(key)?.let { PersistedStateCodec.decode<State>(it) }
    inline fun <reified State> setPersistedState(state: State, key: String, version: Int) = set(PersistedStateCodec.encode(state, version), key)

    companion object {
        const val instanceID = "noah-wallet-storage"
        const val settingsNotice = "Some settings couldn't be carried over from the previous version. Please check Settings, including security, currency and Esplora server."
        const val recurringNotice = "Recurring payments need to be set up again after this update."
        const val emptyRecurring = """{"state":{"schedules":{},"serverSyncPending":true},"version":0}"""
        private val openLock = Any()
        internal fun importValues(source: Map<String, String?>): NativeSettingsFile.State {
            val values = mutableMapOf<String, String>()
            var settingsNotice = source.isEmpty()
            var recurringNotice = false
            source.forEach { (key, raw) ->
                if (key == "recurring-payment-storage") {
                    val schedules = runCatching { Json.parseToJsonElement(checkNotNull(raw)).jsonObject.getValue("state").jsonObject.getValue("schedules").jsonObject }.getOrNull()
                    if (schedules?.isEmpty() != true) { recurringNotice = true; values[key] = emptyRecurring }
                } else if (raw == null || key in RNStoreCodec.versions && runCatching { RNStoreCodec.validateStoredValue(key, raw) }.isFailure) {
                    settingsNotice = true
                } else values[key] = raw
            }
            return NativeSettingsFile.State(values, settingsNotice, recurringNotice)
        }
    }
}
