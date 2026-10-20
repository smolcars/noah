package com.noahwallet.services.storage

import android.content.Context
import com.tencent.mmkv.MMKV
import com.tencent.mmkv.MMKVLogLevel
import com.tencent.mmkv.MMKVHandler
import com.tencent.mmkv.MMKVRecoverStrategic
import com.noahwallet.services.logging.AppLog
import java.io.File

class SettingsStorage private constructor(private val context: Context, acknowledgingRecovery: Boolean) {
    constructor(context: Context) : this(context, false)
    private val rootPath = File(context.filesDir, "mmkv").absolutePath
    private val mmkv: MMKV? get() = synchronized(openLock) { opened[rootPath] }
    private val quarantine = MMKVQuarantine(context.noBackupFilesDir, instanceID)
    private val backup = MMKVUpgradeBackup(File(context.filesDir, "mmkv"), instanceID, backupRoot = context.noBackupFilesDir)

    init {
        synchronized(openLock) {
            if (opened.containsKey(rootPath)) opened[rootPath]
            else {
                val log = AppLog(context, "mmkv")
                var store: MMKV? = null
                try {
                    val recovery = RecoveryHandler(log, quarantine)
                    MMKV.initialize(context.applicationContext, rootPath, null, MMKVLogLevel.LevelWarning, recovery)
                    check(File(rootPath).isDirectory || File(rootPath).mkdirs())
                    MMKVIntegrity.quarantineBeforeOpen(File(rootPath), instanceID, quarantine)
                    backup.prepare()
                    MMKVIntegrity.quarantineBeforeOpen(File(rootPath), instanceID, quarantine)
                    val retired = backup.retiredKeys
                    val snapshot = if (retired == null) snapshotInventory(backup, quarantine) else null
                    recovery.didRecover = false
                    store = checkNotNull(MMKV.mmkvWithID(instanceID, MMKV.SINGLE_PROCESS_MODE, null, rootPath))
                    if (snapshot != null) {
                        val valid = runCatching { validate(store!!, snapshot.keys); check(snapshot.readable) }.isSuccess
                        if (!valid || quarantine.exists && (!backup.restored || recovery.didRecover)) {
                            store.close(); store = null
                            check(!backup.restored)
                            backup.restore()
                            MMKVIntegrity.quarantineBeforeOpen(File(rootPath), instanceID, quarantine)
                            log.w("Restoring pre-native settings backup after incomplete recovery")
                            store = checkNotNull(MMKV.mmkvWithID(instanceID, MMKV.SINGLE_PROCESS_MODE, null, rootPath))
                        }
                        validate(store, snapshot.keys)
                        check(snapshot.readable)
                    } else validate(store, retired.orEmpty())
                    quarantine.requireDurable()
                    if (retired != null && quarantine.exists && !acknowledgingRecovery) throw RecoveryRequired()
                    backup.retire(rnKeys(store))
                    quarantine.clear()
                    recoveryRequired.remove(rootPath)
                    store
                } catch (failure: Exception) {
                    if (failure is RecoveryRequired) recoveryRequired.add(rootPath)
                    else recoveryRequired.remove(rootPath)
                    store?.close()
                    log.e("Settings unavailable; original files preserved. Restart Noah to retry or contact support.")
                    null
                }.also { opened[rootPath] = it }
            }
        }
    }

    class RecoveryRequired : Exception("Settings were recovered from an older state")
    val recoveryNeedsAcknowledgement get() = synchronized(openLock) { rootPath in recoveryRequired }
    /** Called only by the resumed foreground launch gate's Continue action. */
    fun acknowledgeRecovery() = synchronized(openLock) {
        check(recoveryNeedsAcknowledgement && quarantine.exists)
        opened.remove(rootPath)
        SettingsStorage(context, true).requireHealthy()
    }
    class Unavailable : Exception("Wallet settings need recovery. Restart Noah to retry or contact support; wallet files have been kept.")
    fun requireHealthy() {
        if (recoveryNeedsAcknowledgement) throw RecoveryRequired()
        if (mmkv == null || quarantine.exists) throw Unavailable()
        validate(mmkv!!, backup.retiredKeys.orEmpty())
    }
    fun string(key: String): String? = if (quarantine.exists) null else mmkv?.let { store ->
        if (key in RNStoreCodec.versions) RNStoreCodec.storedString(store.containsKey(key), store.decodeBytes(key))
        else store.decodeString(key)
    }
    fun set(value: String, key: String) = synchronized(openLock) {
        validateStores()
        val keys = rnKeys(mmkv!!).toMutableList()
        if (key in RNStoreCodec.versions && key !in keys) keys.add(key)
        backup.retire(keys)
        val preserved = if (key in RNStoreCodec.versions) RNStoreCodec.merge(key, string(key), value) else value
        check(mmkv!!.encode(key, preserved))
    }
    fun validateStores() {
        requireHealthy()
        // Wallet-storage already missing at first open resumes onboarding with the existing wallet.
        // Losing an admitted key later blocks above.
    }
    fun sync() { requireHealthy(); mmkv!!.sync() }
    fun removeAll() = synchronized(openLock) { requireHealthy(); backup.markReset(); mmkv!!.clearAll(); mmkv!!.sync(); backup.deleteSnapshot() }
    inline fun <reified State> persistedState(key: String): State? =
        string(key)?.let { PersistedStateCodec.decode<State>(it) }
    inline fun <reified State> setPersistedState(state: State, key: String, version: Int) =
        set(PersistedStateCodec.encode(state, version), key)

    companion object {
        const val instanceID = "noah-wallet-storage"
        private val openLock = Any()
        private val opened = mutableMapOf<String, MMKV?>()
        private val recoveryRequired = mutableSetOf<String>()
        private fun rnKeys(store: MMKV) = RNStoreCodec.versions.keys.filter(store::containsKey)
        private data class SnapshotInventory(val keys: List<String>, val readable: Boolean)
        private fun snapshotInventory(backup: MMKVUpgradeBackup, quarantine: MMKVQuarantine): SnapshotInventory {
            if (!backup.hasContent) return SnapshotInventory(emptyList(), true)
            return backup.inspectSnapshot { root ->
                MMKVIntegrity.quarantineBeforeOpen(root, instanceID, quarantine)
                val snapshot = checkNotNull(MMKV.mmkvWithID(instanceID, MMKV.SINGLE_PROCESS_MODE, null, root.path))
                try {
                    val keys = rnKeys(snapshot)
                    SnapshotInventory(keys, keys.isNotEmpty() && runCatching { validate(snapshot, keys) }.isSuccess)
                } finally { snapshot.close() }
            }
        }
        private fun validate(store: MMKV, requiredKeys: List<String>) {
            if (!requiredKeys.all(store::containsKey)) throw Unavailable()
            RNStoreCodec.versions.keys.filter { it != "esplora-storage" }.forEach { key ->
                RNStoreCodec.storedString(store.containsKey(key), store.decodeBytes(key))?.let { RNStoreCodec.validateStoredValue(key, it) }
            }
        }
    }
}

private class RecoveryHandler(private val log: AppLog, private val quarantine: MMKVQuarantine) : MMKVHandler {
    var didRecover = false
    override fun onMMKVCRCCheckFail(mmapID: String): MMKVRecoverStrategic {
        quarantine.recordRecovery()
        didRecover = true
        log.w("CRC check failed; recovering settings")
        return MMKVRecoverStrategic.OnErrorRecover
    }
    override fun onMMKVFileLengthError(mmapID: String): MMKVRecoverStrategic {
        quarantine.recordRecovery()
        didRecover = true
        log.w("File length check failed; recovering settings")
        return MMKVRecoverStrategic.OnErrorRecover
    }
    override fun wantLogRedirecting() = true
    override fun mmkvLog(level: MMKVLogLevel, file: String, line: Int, function: String, message: String) {
        // MMKV diagnostics may contain keys/values; only fixed messages reach the redacting logger.
        if (level == MMKVLogLevel.LevelError) log.e("MMKV reported a storage error")
        else if (level == MMKVLogLevel.LevelWarning) log.w("MMKV reported a storage warning")
    }
}
