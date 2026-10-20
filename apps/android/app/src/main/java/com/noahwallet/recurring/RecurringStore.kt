package com.noahwallet.recurring

import com.noahwallet.send.SendIdentity
import com.noahwallet.services.storage.SettingsStorage
import com.noahwallet.services.storage.WalletDataLocation
import com.noahwallet.services.storage.syncDirectory
import com.noahwallet.services.wallet.WalletDatabase
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** RN's version-0 Zustand envelope. Unsupported rows stay visible; invalid envelopes stop mutations. */
class RecurringStore(private val read: () -> String?, private val write: (String) -> Unit, private val policyWarning: () -> Unit = {}) {
    constructor(settings: SettingsStorage, policyWarning: () -> Unit = {}) : this({ settings.requireHealthy(); settings.string(key) }, { raw ->
        settings.set(raw, key); settings.sync(); check(settings.string(key) == raw) { "Unable to save recurring payments" }
    }, policyWarning)
    @Serializable data class Binding(val wallet: SendIdentity, val instance: String)
    data class State(val schedules: Map<String, RecurringPayment>, val pending: Boolean, val binding: Binding?)
    private var writeFailed = false
    private var policyLedger: RecurringPolicyLedger? = null
    private var policyBinding: (() -> Binding)? = null
    internal fun protectPolicies(file: File, sync: (File) -> Unit = ::syncDirectory, binding: () -> Binding) = synchronized(lock) {
        if (policyLedger == null) { policyLedger = RecurringPolicyLedger(file, policyWarning, sync); policyBinding = binding }
    }
    private fun protected(state: State): State {
        val ledger = policyLedger ?: return state
        val stopped = runCatching { ledger.stopped(state.binding ?: policyBinding!!(), state.schedules) }.getOrElse { policyWarning(); state.schedules.keys }
        return state.copy(schedules = state.schedules.mapValues { (id, schedule) ->
            if (id in stopped && !schedule.unsupportedStorage) schedule.copy(status = "needs_attention", lastError = RecurringPolicyLedger.message) else schedule
        })
    }
    private fun envelope(): JsonObject {
        check(!writeFailed) { "Recurring payment storage failed. Restart Noah; sending is stopped." }
        val raw = read() ?: return buildJsonObject { put("version", 0); putJsonObject("state") { putJsonObject("schedules") {}; put("serverSyncPending", false) } }
        return json.parseToJsonElement(raw).jsonObject.also {
            val version = it["version"]
            require(version == null || version is JsonPrimitive && !version.isString && version.content == "0") { "Unsupported recurring payment version. Sending is stopped." }
            it.getValue("state").jsonObject.getValue("schedules").jsonObject
        }
    }
    fun load(): State = synchronized(lock) { protected(decode(envelope())) }
    private fun decode(envelope: JsonObject): State {
        val state = envelope.getValue("state").jsonObject
        val schedules = state.getValue("schedules").jsonObject.mapValues { (id, raw) ->
            val decoded = runCatching { json.decodeFromJsonElement<RecurringPayment>(raw) }.getOrNull()
            if (decoded != null && decoded.id == id && decoded.validPolicy) decoded
            else {
                val fields = raw as? JsonObject
                // The original JSON remains authoritative for rows native code cannot represent.
                (decoded ?: RecurringPayment(id, (fields?.get("label") as? JsonPrimitive)?.contentOrNull ?: "Saved recurring payment",
                    (fields?.get("destination") as? JsonPrimitive)?.contentOrNull.orEmpty(),
                    (fields?.get("destinationType") as? JsonPrimitive)?.contentOrNull.orEmpty(),
                    (fields?.get("amountSat") as? JsonPrimitive)?.longOrNull ?: 0,
                    interval = RecurringInterval("month", 1), startAt = 0, createdAt = 0, updatedAt = 0))
                    .copy(id = id, status = "needs_attention", lastError = RecurringPayment.unsupportedMessage, unsupportedStorage = true)
            }
        }
        val binding = state["nativeWalletBinding"]?.takeUnless { it == JsonNull }?.let { json.decodeFromJsonElement<Binding>(it) }
        binding?.let { require(it.wallet.fingerprint.isNotBlank() && it.wallet.network.isNotBlank()); UUID.fromString(it.instance) }
        // RN may store amounts above native approval limits; retain each row and its original bytes.
        val visible = schedules.mapValues { (_, schedule) ->
            if (!schedule.unsupportedStorage && schedule.amountSat > RecurringPayment.maximumAmount) schedule.copy(status = "needs_attention", lastError = RecurringPayment.amountLimitMessage)
            else schedule
        }
        return State(visible, state["serverSyncPending"]?.jsonPrimitive?.booleanOrNull ?: true, binding)
    }
    private fun change(policyID: String? = null, mutate: (MutableMap<String, JsonElement>) -> Unit) = synchronized(lock) {
        val envelope = envelope(); val before = decode(envelope)
        val state = envelope.getValue("state").jsonObject.toMutableMap(); mutate(state)
        val updated = JsonObject(envelope + ("state" to JsonObject(state)))
        val after = decode(updated)
        policyLedger?.commit(before.binding ?: policyBinding!!(), before.schedules, after.schedules, policyID)
        try { write(updated.toString()) } catch (failure: Exception) { writeFailed = true; throw failure }
    }
    fun bind(binding: Binding) = change { it["nativeWalletBinding"] = json.encodeToJsonElement(binding); it["serverSyncPending"] = JsonPrimitive(true) }
    fun upsert(schedule: RecurringPayment) = change(if (schedule.unsupportedStorage) null else schedule.id) { state ->
        val schedules = state.getValue("schedules").jsonObject.toMutableMap()
        val previous = (schedules[schedule.id] as? JsonObject).orEmpty()
        schedules[schedule.id] = if (schedule.unsupportedStorage) {
            check(schedules.containsKey(schedule.id)) { RecurringPayment.unsupportedMessage }
            schedules.getValue(schedule.id)
        } else JsonObject(previous + json.encodeToJsonElement(schedule).jsonObject)
        state["schedules"] = JsonObject(schedules); state["serverSyncPending"] = JsonPrimitive(true)
    }
    /** Compare and replace under the same process-wide MMKV gate as pause/cancel/edit. */
    fun updateIf(expected: RecurringPayment, replacement: RecurringPayment): Boolean = synchronized(lock) {
        if (load().schedules[expected.id] != expected) false else { upsert(replacement); true }
    }
    fun remove(id: String) = change(id) { it["schedules"] = JsonObject(it.getValue("schedules").jsonObject - id); it["serverSyncPending"] = JsonPrimitive(true) }
    fun setPending(value: Boolean) = change { it["serverSyncPending"] = JsonPrimitive(value) }
    fun protectLegacyBeforeReplacement() = synchronized(lock) {
        val state = load()
        if (state.binding == null && state.schedules.isNotEmpty()) bind(Binding(SendIdentity("unverified-previous-wallet", "unknown"), UUID.randomUUID().toString()))
    }
    companion object {
        const val key = "recurring-payment-storage"
        internal val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        internal val lock = Any()
        fun binding(location: WalletDataLocation): Binding = synchronized(lock) {
            check(!location.restorePending && !location.deletionMarker.exists())
            val properties = WalletDatabase.properties(location.database)
            val file = File(location.directory, ".noah-recurring-instance")
            val instance = if (file.exists()) file.readText().also { UUID.fromString(it) }
            else UUID.randomUUID().toString().also { durableRecurringWrite(file, it.toByteArray()) }
            Binding(SendIdentity(properties.fingerprint, properties.network.uppercase(java.util.Locale.ROOT)), instance)
        }
    }
}

internal fun durableRecurringWrite(file: File, bytes: ByteArray, sync: (File) -> Unit = ::syncDirectory) {
    check(file.parentFile!!.isDirectory)
    val temporary = File(file.parentFile, ".recurring-${UUID.randomUUID()}")
    try {
        FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
        check(temporary.renameTo(file)) { "Unable to save recurring payment evidence" }
        sync(file.parentFile!!)
    } finally { temporary.delete() }
}
