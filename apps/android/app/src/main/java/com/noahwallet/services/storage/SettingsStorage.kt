package com.noahwallet.services.storage

import android.content.Context
import com.tencent.mmkv.MMKV
import com.tencent.mmkv.MMKVLogLevel
import com.tencent.mmkv.MMKVHandler
import com.tencent.mmkv.MMKVRecoverStrategic
import com.noahwallet.services.logging.AppLog
import java.io.File

class SettingsStorage(context: Context) {
    private val mmkv: MMKV?
    private val backup = MMKVUpgradeBackup(File(context.filesDir, "mmkv"), instanceID)

    init {
        val rootPath = File(context.filesDir, "mmkv").absolutePath
        mmkv = synchronized(openLock) {
            if (opened.containsKey(rootPath)) opened[rootPath]
            else {
                val log = AppLog(context, "mmkv")
                var store: MMKV? = null
                try {
                    val recovery = RecoveryHandler(log)
                    MMKV.initialize(context.applicationContext, rootPath, null, MMKVLogLevel.LevelWarning, recovery)
                    check(File(rootPath).isDirectory || File(rootPath).mkdirs())
                    backup.prepare()
                    store = checkNotNull(MMKV.mmkvWithID(instanceID, MMKV.SINGLE_PROCESS_MODE, null, rootPath))
                    val retired = backup.retiredKeys
                    if (retired == null && backup.hasContent && !hasRNKeys(store)) {
                        store.close(); store = null
                        check(!backup.restored)
                        backup.restore()
                        log.w("Restoring pre-native settings backup after empty recovery")
                        store = checkNotNull(MMKV.mmkvWithID(instanceID, MMKV.SINGLE_PROCESS_MODE, null, rootPath))
                        check(hasRNKeys(store))
                    }
                    validate(store, retired.orEmpty())
                    check(retired == null || !recovery.didRecover) { "Native settings required recovery" }
                    backup.retire(rnKeys(store))
                    store
                } catch (_: Exception) {
                    store?.close()
                    log.e("Settings unavailable; original files preserved. Restart Noah to retry or contact support.")
                    null
                }.also { opened[rootPath] = it }
            }
        }
    }

    class Unavailable : Exception("Wallet settings need recovery. Restart Noah to retry or contact support; wallet files have been kept.")
    fun requireHealthy() { if (mmkv == null) throw Unavailable() }
    fun string(key: String): String? = mmkv?.let { store ->
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
        validate(mmkv!!, backup.retiredKeys.orEmpty())
        // Wallet-storage already missing at first open resumes onboarding with the existing wallet.
        // Losing an admitted key later blocks above.
    }
    fun sync() { requireHealthy(); mmkv!!.sync() }
    fun removeAll() = synchronized(openLock) { requireHealthy(); backup.markReset(); mmkv!!.clearAll(); mmkv.sync() }
    inline fun <reified State> persistedState(key: String): State? =
        string(key)?.let { PersistedStateCodec.decode<State>(it) }
    inline fun <reified State> setPersistedState(state: State, key: String, version: Int) =
        set(PersistedStateCodec.encode(state, version), key)

    companion object {
        const val instanceID = "noah-wallet-storage"
        private val openLock = Any()
        private val opened = mutableMapOf<String, MMKV?>()
        private fun rnKeys(store: MMKV) = RNStoreCodec.versions.keys.filter(store::containsKey)
        private fun hasRNKeys(store: MMKV) = rnKeys(store).isNotEmpty()
        private fun validate(store: MMKV, requiredKeys: List<String>) {
            check(requiredKeys.all(store::containsKey)) { "Native settings lost persisted keys" }
            RNStoreCodec.versions.keys.filter { it != "esplora-storage" }.forEach { key ->
                RNStoreCodec.storedString(store.containsKey(key), store.decodeBytes(key))?.let { RNStoreCodec.decode(key, it) }
            }
        }
    }
}

private class RecoveryHandler(private val log: AppLog) : MMKVHandler {
    @Volatile var didRecover = false; private set
    override fun onMMKVCRCCheckFail(mmapID: String): MMKVRecoverStrategic {
        didRecover = true
        log.w("CRC check failed; recovering settings")
        return MMKVRecoverStrategic.OnErrorRecover
    }
    override fun onMMKVFileLengthError(mmapID: String): MMKVRecoverStrategic {
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
