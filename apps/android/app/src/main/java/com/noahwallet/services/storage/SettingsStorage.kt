package com.noahwallet.services.storage

import android.content.Context
import android.os.Build
import android.system.Os
import com.tencent.mmkv.MMKV
import com.tencent.mmkv.MMKVLogLevel
import java.io.File
import kotlinx.serialization.json.*

/** MMKV is only opened on a verified disposable snapshot; native writes use one atomic file. */
class SettingsStorage(context: Context, private val sync: (File) -> Unit = ::syncDirectory,
    /** [settingsStamp]; Robolectric's Os.stat is a stub (inode 0, whole seconds), so JVM tests pass the host's stat. */
    private val stat: (File) -> List<Long>? = ::settingsStamp) {
    private val root = File(context.filesDir, "mmkv")
    private val backupRoot = context.noBackupFilesDir
    internal val nativeFile = NativeSettingsFile(File(backupRoot, "settings.native.json"), sync = sync)

    private var fallback: NativeSettingsFile.State?
        get() = fallbacks[nativeFile.file.path]
        set(value) { if (value == null) fallbacks.remove(nativeFile.file.path) else fallbacks[nativeFile.file.path] = value }
    /** The last verified read, with the [stamp] taken before it: a file replaced mid-read just looks changed next time. */
    private var cached: Pair<List<Long>, NativeSettingsFile.State>?
        get() = caches[nativeFile.file.path]
        set(value) { if (value == null) caches.remove(nativeFile.file.path) else caches[nativeFile.file.path] = value }
    private fun stamp() = stat(nativeFile.file)

    init { synchronized(openLock) {
        try {
            check(backupRoot.isDirectory || backupRoot.mkdirs())
            if (nativeFile.exists || fallback != null) {
                load()
            } else {
                val backup = MMKVUpgradeBackup(root, instanceID, backupRoot = backupRoot, sync = sync)
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
                }.getOrElse { NativeSettingsFile.State(values = mapOf(com.noahwallet.services.wallet.EsploraSettings.historyUnavailableKey to "1"), settingsNoticePending = true) }
                fallback = state
                persist(state)
            }
            if (fallback != null) persist(load())
            finishCleanup()
        } catch (_: Exception) {
            if (fallback == null) fallback = NativeSettingsFile.State(values = mapOf(com.noahwallet.services.wallet.EsploraSettings.historyUnavailableKey to "1"), settingsNoticePending = true)
        }
    } }

    class Unavailable : Exception("Native settings write failed")
    fun requireHealthy() = synchronized(openLock) {
        load(verify = true)
        if (fallback != null) throw Unavailable()
    }
    /**
     * Reads come from memory while the file's stamp is unchanged (tests write it directly). [verify] re-reads and
     * re-verifies it anyway: health checks, and every write before it merges, so a write never builds on a stale copy.
     */
    private fun load(verify: Boolean = false): NativeSettingsFile.State {
        fallback?.let { return it }
        val stamp = stamp()
        cached?.takeIf { !verify && stamp != null && it.first == stamp }?.let { return it.second }
        return try { nativeFile.load().also { cached = stamp?.let { s -> s to it } } } catch (_: Exception) {
            cached = null
            val defaults = NativeSettingsFile.State(values = mapOf(com.noahwallet.services.wallet.EsploraSettings.historyUnavailableKey to "1"), settingsNoticePending = true)
            fallback = defaults
            runCatching { persist(defaults) }
            defaults
        }
    }
    private fun persist(state: NativeSettingsFile.State) {
        check(backupRoot.isDirectory || backupRoot.mkdirs())
        // No stamp after saving: another writer may replace the file first. The next read verifies what's there.
        cached = null
        nativeFile.save(state)
        fallback = null
    }
    fun string(key: String): String? = synchronized(openLock) { load().values[key] }
    fun set(value: String, key: String) = synchronized(openLock) {
        val state = load(verify = true)
        val preserved = if (key in RNStoreCodec.versions) RNStoreCodec.merge(key, state.values[key], value) else value
        try { persist(state.copy(values = state.values + (key to preserved))) }
        catch (failure: Exception) { fallback = state; throw failure }
    }
    /** User-selected safe fallback stays effective in foreground when disk writes fail. */
    fun useDefaultEsplora(variant: String) = synchronized(openLock) {
        val record = com.noahwallet.services.wallet.EsploraSettings.Record(
            com.noahwallet.services.wallet.EsploraSettings.Selection(variant = variant))
        val state = load(verify = true).let {
            val retained = com.noahwallet.services.wallet.EsploraSettings.retainingLogPrivacy(it.values)
            val previous = retained[com.noahwallet.services.wallet.EsploraSettings.key]?.let { raw -> Json.decodeFromString<com.noahwallet.services.wallet.EsploraSettings.Record>(raw) }
            it.copy(values = it.values + (com.noahwallet.services.wallet.EsploraSettings.key to Json.encodeToString(record.copy(logEndpoints = previous?.logEndpoints.orEmpty()))))
        }
        try { persist(state) } catch (_: Exception) { fallback = state }
    }
    fun validateStores() { synchronized(openLock) { load(verify = true) } }
    // set() commits synchronously; retain this API for automation callers.
    fun sync() = requireHealthy()
    fun takeUpgradeNotices(): List<String> = synchronized(openLock) {
        val state = load(verify = true)
        val notices = listOfNotNull(settingsNotice(state.values).takeIf { state.settingsNoticePending }, recurringNotice.takeIf { state.recurringNoticePending })
        if (notices.isNotEmpty()) {
            val consumed = state.copy(settingsNoticePending = false, recurringNoticePending = false)
            fallback = consumed
            runCatching { persist(consumed) }
        }
        notices
    }
    fun removeAll() = synchronized(openLock) {
        val state = load(verify = true)
        try { persist(NativeSettingsFile.State(values = com.noahwallet.services.wallet.EsploraSettings.retainingLogPrivacy(state.values), cleanupPending = true)) }
        catch (failure: Exception) { fallback = state; throw failure }
        finishCleanup()
    }
    private fun finishCleanup() {
        if (!load().cleanupPending) return
        check(!root.exists() || root.deleteRecursively())
        sync(root.parentFile!!)
        MMKVUpgradeBackup(root, instanceID, backupRoot = backupRoot, sync = sync).deleteSnapshot()
        persist(NativeSettingsFile.State(values = com.noahwallet.services.wallet.EsploraSettings.retainingLogPrivacy(load(verify = true).values))) // Keep log protection through re-onboarding.
    }
    /** Committed wallet recovery may publish flags in memory; durable completion retries later. */
    fun completeRestore(): Boolean = synchronized(openLock) {
        val state = load(verify = true)
        val wallet = persistedState<PersistedWalletState>(PersistedWalletState.key) ?: PersistedWalletState()
        val raw = PersistedStateCodec.encode(wallet.copy(isInitialized = true, isWalletLoaded = true), 0)
        val completed = state.copy(values = state.values + (PersistedWalletState.key to RNStoreCodec.merge(PersistedWalletState.key, state.values[PersistedWalletState.key], raw)))
        try { persist(completed); true } catch (_: Exception) { fallback = completed; false }
    }
    fun beginOnboarding() {
        val wallet = persistedState<PersistedWalletState>(PersistedWalletState.key) ?: PersistedWalletState()
        setPersistedState(wallet.copy(isInitialized = false), PersistedWalletState.key, 0)
    }
    inline fun <reified State> persistedState(key: String): State? = string(key)?.let { PersistedStateCodec.decode<State>(it) }
    inline fun <reified State> setPersistedState(state: State, key: String, version: Int) = set(PersistedStateCodec.encode(state, version), key)

    companion object {
        const val instanceID = "noah-wallet-storage"
        const val settingsNotice = "Some settings couldn't be carried over from the previous version, so they're back to their defaults: biometric lock is off, balances are visible, and currency and display settings are reset. Please review Settings."
        internal fun settingsNotice(values: Map<String, String>): String {
            val wallet = values[PersistedWalletState.key]?.let { PersistedStateCodec.decode<PersistedWalletState>(it) }
            val privacy = values["privacy-storage"]?.let { runCatching { Json.parseToJsonElement(it).jsonObject["state"]?.jsonObject?.get("isHomeBalanceHidden")?.jsonPrimitive?.booleanOrNull }.getOrNull() }
            val defaults = mutableListOf<String>()
            if (wallet?.isBiometricsEnabled == null) defaults += "biometric lock is off"
            if (privacy == null) defaults += "balances are visible"
            if (defaults.isEmpty()) return "Some display settings couldn't be carried over from the previous version and were reset. Please review Settings."
            if ("profile-storage" !in values) defaults += "currency and display settings are reset"
            val descriptions = if (defaults.size == 1) defaults.single() else defaults.dropLast(1).joinToString(", ") +
                (if (defaults.size > 2) ", and " else " and ") + defaults.last()
            return "Some settings couldn't be carried over from the previous version, so they're back to their defaults: $descriptions. Please review Settings."
        }
        const val recurringNotice = "Recurring payments need to be set up again after this update."
        const val emptyRecurring = """{"state":{"schedules":{},"serverSyncPending":true},"version":0}"""
        private val openLock = Any()
        private val fallbacks = mutableMapOf<String, NativeSettingsFile.State>()
        private val caches = mutableMapOf<String, Pair<List<Long>, NativeSettingsFile.State>>()
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

/**
 * One stat of [file]: its inode, size, and modification and change times (nanoseconds from API 27, whole seconds
 * before, where writes still re-read the file before merging); null when it can't be read. java.nio's times are whole
 * seconds on Android, so it doesn't do here.
 */
internal fun settingsStamp(file: File): List<Long>? = try {
    Os.stat(file.path).let {
        if (Build.VERSION.SDK_INT >= 27) listOf(it.st_ino, it.st_size, it.st_mtim.tv_sec, it.st_mtim.tv_nsec, it.st_ctim.tv_sec, it.st_ctim.tv_nsec)
        else listOf(it.st_ino, it.st_size, it.st_mtime, it.st_ctime)
    }
} catch (_: android.system.ErrnoException) { null }
