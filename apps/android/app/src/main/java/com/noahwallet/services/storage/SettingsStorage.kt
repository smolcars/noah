package com.noahwallet.services.storage

import android.content.Context
import com.tencent.mmkv.MMKV
import com.tencent.mmkv.MMKVLogLevel
import java.io.File

class SettingsStorage(context: Context) {
    private val mmkv: MMKV

    init {
        val rootPath = File(context.filesDir, "mmkv").absolutePath
        MMKV.initialize(context.applicationContext, rootPath, MMKVLogLevel.LevelNone)
        mmkv = checkNotNull(MMKV.mmkvWithID(instanceID, MMKV.SINGLE_PROCESS_MODE, null, rootPath))
    }

    fun string(key: String): String? = mmkv.decodeString(key)
    fun set(value: String, key: String) { check(mmkv.encode(key, value)) }
    fun sync() { mmkv.sync() }
    fun removeAll() { mmkv.clearAll(); mmkv.sync() }
    inline fun <reified State> persistedState(key: String): State? =
        string(key)?.let { PersistedStateCodec.decode<State>(it) }
    inline fun <reified State> setPersistedState(state: State, key: String, version: Int) =
        set(PersistedStateCodec.encode(state, version), key)

    companion object { const val instanceID = "noah-wallet-storage" }
}
