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
        settings.set(raw, key); settings.sync(); check(settings.string(key) == raw) { "Recurring payment storage failed. Restart Noah; sending is stopped." }
    }, policyWarning)
    @Serializable data class Binding(val wallet: SendIdentity, val instance: String)
    data class State(val schedules: Map<String, RecurringPayment>, val pending: Boolean, val binding: Binding?)
    private var writeFailed = false
    private var policyLedger: RecurringPolicyLedger? = null
    private var automation: RecurringAutomationAuthority? = null
    private var policyBinding: (() -> Binding)? = null
    internal fun protectPolicies(file: File, sync: (File) -> Unit = ::syncDirectory, syncFile: (java.io.FileDescriptor) -> Unit = { it.sync() }, binding: () -> Binding) = synchronized(lock) {
        if (policyLedger == null) { policyLedger = RecurringPolicyLedger(file, policyWarning, sync, syncFile); policyBinding = binding; automation = RecurringAutomationAuthority(file.parentFile!!) { target, bytes -> durableRecurringWrite(target, bytes, sync, syncFile) } }
    }
    private fun protected(state: State, automationOverlay: Boolean = true): State {
        val ledger = policyLedger ?: return state
        // Resolve the live wallet first: Welcome has no authority to bootstrap or fail.
        val owner = runCatching { policyBinding!!() }.getOrNull()
        val stopped = if (owner == null) state.schedules.keys else runCatching {
            ledger.stopped(state.binding ?: owner, state.schedules)
        }.getOrElse { policyWarning(); state.schedules.keys }
        val automationStopped = owner != null && automationOverlay && automation?.allowed() == false
        return state.copy(schedules = state.schedules.mapValues { (id, schedule) ->
            if ((id in stopped || automationStopped) && !schedule.unsupportedStorage) schedule.copy(status = "needs_attention", lastError = if (id in stopped) RecurringPolicyLedger.message else RecurringAutomationAuthority.message,
                issue = RecurringIssue(if (id in stopped) RecurringIssue.Code.policyEvidence else RecurringIssue.Code.automationStopped),
                stopReason = if (id in stopped) RecurringPayment.StopReason.policyEvidence else RecurringPayment.StopReason.automationStopped) else schedule
        })
    }
    internal fun automationGeneration(): String? = synchronized(lock) { automation?.generation() }
    private fun envelope(raw: String? = read()): JsonObject {
        check(!writeFailed) { "Recurring payment storage failed. Restart Noah; sending is stopped." }
        val stored = raw ?: return buildJsonObject { put("version", 0); putJsonObject("state") { putJsonObject("schedules") {}; put("serverSyncPending", false) } }
        return json.parseToJsonElement(stored).jsonObject.also {
            val version = it["version"]
            require(version == null || version is JsonPrimitive && !version.isString && version.content == "0") { "Recurring payment data couldn't be read. Your saved schedules have been preserved. Sending is stopped." }
            it.getValue("state").jsonObject.getValue("schedules").jsonObject
        }
    }
    fun load(): State = synchronized(lock) { recoverSettlements(); val raw = read(); protected(decode(envelope(raw), raw)) }
    /** Settlement records submitted money against policy authority, without a temporary wallet overlay. */
    internal fun settlementState(): State = synchronized(lock) { recoverSettlements(); read().let { protected(decode(envelope(it), it), automationOverlay = false) } }
    private fun recoverSettlements() {
        val ledger = policyLedger ?: return
        fun replay() {
            if (!ledger.hasPending) return
            val original = read(); val envelope = envelope(original); val before = decode(envelope, original, migrateIssue = false)
            val owner = policyBinding!!()
            check(before.binding == null || before.binding == owner) { RecurringPolicyLedger.message }
            val pending = ledger.pending(before.binding ?: owner, before.schedules)
            if (pending.isEmpty()) return
            val state = envelope.getValue("state").jsonObject.toMutableMap()
            val rows = state.getValue("schedules").jsonObject.toMutableMap()
            pending.forEach { row -> rows[row.id] = JsonObject(rows.getValue(row.id).jsonObject + json.encodeToJsonElement(row).jsonObject) }
            state["schedules"] = JsonObject(rows); state["serverSyncPending"] = JsonPrimitive(true)
            val updated = JsonObject(envelope + ("state" to JsonObject(state)))
            val raw = if (original == null) updated.toString() else preserveRows(original, envelope, updated)
            try { write(raw) } catch (failure: Exception) { writeFailed = true; throw failure }
            ledger.finish(before.binding ?: owner, decode(updated, raw, migrateIssue = false).schedules)
        }
        replay()
        // Reason conversion must not change an unfinished record's original digest.
        val original = read(); val before = decode(envelope(original), original, migrateIssue = false)
        val owner = runCatching { policyBinding!!() }.getOrNull() ?: return
        if (before.binding != null && before.binding != owner) return
        if (ledger.migrate(before.binding ?: owner, before.schedules)) replay()
    }
    internal fun settlementApplied(id: String, token: String): Boolean = synchronized(lock) {
        recoverSettlements()
        val raw = read(); val state = decode(envelope(raw), raw)
        policyLedger?.applied(state.binding ?: policyBinding!!(), state.schedules, id, token) == true
    }
    private fun decode(envelope: JsonObject, original: String? = null, migrateIssue: Boolean = true): State {
        val rows = original?.let { recurringRawFields(recurringRawFields(recurringRawFields(it).getValue("state")).getValue("schedules")) }
        val state = envelope.getValue("state").jsonObject
        val schedules = state.getValue("schedules").jsonObject.mapValues { (id, raw) -> payment(id, raw, rows?.get(id), migrateIssue) }
        val binding = state["nativeWalletBinding"]?.takeUnless { it == JsonNull }?.let { json.decodeFromJsonElement<Binding>(it) }
        binding?.let { require(it.wallet.fingerprint.isNotBlank() && it.wallet.network.isNotBlank()); UUID.fromString(it.instance) }
        // RN may store amounts above native approval limits; retain each row and its original bytes.
        val visible = schedules.mapValues { (_, schedule) ->
            if (!schedule.unsupportedStorage && schedule.amountSat > RecurringPayment.maximumAmount) schedule.copy(status = "needs_attention", lastError = RecurringPayment.amountLimitMessage, issue = RecurringIssue(RecurringIssue.Code.amountLimit))
            else schedule
        }
        return State(visible, state["serverSyncPending"]?.jsonPrimitive?.booleanOrNull ?: true, binding)
    }
    private fun change(policyID: String? = null, reapproval: Boolean = false, settlementKey: String? = null, mutate: (MutableMap<String, JsonElement>, State) -> Unit) = synchronized(lock) {
        recoverSettlements()
        val original = read(); val envelope = envelope(original); val before = decode(envelope, original)
        val state = envelope.getValue("state").jsonObject.toMutableMap(); mutate(state, before)
        val updated = JsonObject(envelope + ("state" to JsonObject(state)))
        val raw = if (original == null) updated.toString() else preserveRows(original, envelope, updated)
        val after = decode(updated, raw)
        policyLedger?.commit(before.binding ?: policyBinding!!(), before.schedules, after.schedules, policyID, reapproval, settlementKey)
        try { write(raw) } catch (failure: Exception) { writeFailed = true; throw failure }
        if (settlementKey != null) policyLedger?.finish(before.binding ?: policyBinding!!(), after.schedules)
    }
    fun bind(binding: Binding) = change { state, _ -> state["nativeWalletBinding"] = json.encodeToJsonElement(binding); state["serverSyncPending"] = JsonPrimitive(true) }
    private fun payment(id: String, raw: JsonElement, original: String? = null, migrateIssue: Boolean = true): RecurringPayment {
        val decoded = runCatching {
            requireUniqueRecurringProperties(original ?: raw.toString())
            // RN writes nullable keys too; absence cannot grant execution defaults.
            require(raw.jsonObject.keys.containsAll(requiredRNFields))
            requireNumericTokens(raw.jsonObject)
            json.decodeFromJsonElement<RecurringPayment>(raw)
        }.getOrNull()
        return if (decoded != null && decoded.id == id && decoded.validPolicy) (if (migrateIssue) decoded.decodeLegacyIssue() else decoded)
        else {
            val fields = raw as? JsonObject
            // The original JSON remains authoritative for rows native code cannot represent.
            (decoded ?: RecurringPayment(id, (fields?.get("label") as? JsonPrimitive)?.contentOrNull ?: "Saved recurring payment",
                (fields?.get("destination") as? JsonPrimitive)?.contentOrNull.orEmpty(),
                (fields?.get("destinationType") as? JsonPrimitive)?.contentOrNull.orEmpty(),
                (fields?.get("amountSat") as? JsonPrimitive)?.content?.toLongOrNull() ?: 0,
                interval = RecurringInterval("month", 1), startAt = 0, createdAt = 0, updatedAt = 0))
                .copy(id = id, status = "needs_attention", lastError = RecurringPayment.unsupportedMessage, issue = RecurringIssue(RecurringIssue.Code.unsupported), unsupportedStorage = true)
        }
    }
    private fun requireNumericTokens(row: JsonObject) {
        fun number(value: JsonElement, minimum: Long, maximum: Long) {
            require(value is JsonPrimitive && !value.isString && integerToken.matches(value.content))
            // Parse the original integer token: kotlinx longOrNull can wrap overflowing literals.
            val exact = value.content.toBigIntegerOrNull() ?: error("""Expected an integer token""")
            require(exact >= minimum.toBigInteger() && exact <= maximum.toBigInteger())
        }
        fun numbers(fields: JsonObject, minimum: Long, maximum: Long, vararg keys: String) {
            keys.forEach { key -> fields[key]?.takeUnless { it == JsonNull }?.let { number(it, minimum, maximum) } }
        }
        val safeInteger = 9_007_199_254_740_991L
        val timeLimit = 8_639_999_999_999_999L
        numbers(row, 1, safeInteger, "amountSat")
        numbers(row, 1, Long.MAX_VALUE, "maxOccurrences")
        numbers(row, -timeLimit, timeLimit, "startAt", "endAt", "nextRunAt", "retryNotBefore", "createdAt", "updatedAt")
        numbers(row, 0, Int.MAX_VALUE.toLong(), "nextOccurrenceIndex", "occurrencesPaid", "consecutiveFailures")
        val interval = row.getValue("interval").jsonObject
        number(interval.getValue("every"), 1, if (interval["unit"]?.jsonPrimitive?.content == "day") 365 else 12)
        row["inFlight"]?.takeUnless { it == JsonNull }?.jsonObject?.let {
            numbers(it, 0, Int.MAX_VALUE.toLong(), "occurrenceIndex")
            numbers(it, -timeLimit, timeLimit, "startedAt")
        }
        row.getValue("runs").jsonArray.forEach { value ->
            val run = value.jsonObject
            numbers(run, 0, Int.MAX_VALUE.toLong(), "occurrenceIndex")
            numbers(run, -timeLimit, timeLimit, "scheduledFor", "attemptedAt")
            numbers(run, 1, safeInteger, "amountSat")
        }
    }
    fun upsert(schedule: RecurringPayment, reapproval: Boolean = false, settlementKey: String? = null) = change(if (schedule.unsupportedStorage) null else schedule.id, reapproval, settlementKey) { state, before ->
        val schedules = state.getValue("schedules").jsonObject.toMutableMap()
        val previous = (schedules[schedule.id] as? JsonObject).orEmpty()
        schedules[schedule.id] = if (schedule.unsupportedStorage || before.schedules[schedule.id]?.unsupportedStorage == true) {
            check(schedules.containsKey(schedule.id)) { RecurringPayment.unsupportedMessage }
            schedules.getValue(schedule.id)
        } else JsonObject(previous + json.encodeToJsonElement(schedule).jsonObject)
        state["schedules"] = JsonObject(schedules); state["serverSyncPending"] = JsonPrimitive(true)
    }
    /** Compare and replace under the same process-wide MMKV gate as pause/cancel/edit. */
    fun updateIf(expected: RecurringPayment, replacement: RecurringPayment, reapproval: Boolean = false): Boolean = synchronized(lock) {
        if (load().schedules[expected.id] != expected) false else { upsert(replacement, reapproval); true }
    }
    fun remove(id: String) = change(id) { state, _ -> state["schedules"] = JsonObject(state.getValue("schedules").jsonObject - id); state["serverSyncPending"] = JsonPrimitive(true) }
    fun setPending(value: Boolean) = change { state, _ -> state["serverSyncPending"] = JsonPrimitive(value) }
    fun protectLegacyBeforeReplacement() = synchronized(lock) {
        val state = load()
        if (state.binding == null && state.schedules.isNotEmpty()) bind(Binding(SendIdentity("unverified-previous-wallet", "unknown"), UUID.randomUUID().toString()))
    }
    private fun preserveRows(raw: String, before: JsonObject, after: JsonObject): String {
        val originalRows = recurringRawFields(recurringRawFields(recurringRawFields(raw).getValue("state")).getValue("schedules"))
        val oldRows = before.getValue("state").jsonObject.getValue("schedules").jsonObject
        val state = after.getValue("state").jsonObject
        val rows = state.getValue("schedules").jsonObject.mapValues { (id, row) ->
            if (row == oldRows[id]) originalRows.getValue(id) else row.toString()
        }
        fun objectJSON(fields: Map<String, String>) = fields.entries.joinToString(",", "{", "}") { (key, value) -> "${JsonPrimitive(key)}:$value" }
        val stateJSON = objectJSON(state.mapValues { it.value.toString() } + ("schedules" to objectJSON(rows)))
        return objectJSON(after.mapValues { it.value.toString() } + ("state" to stateJSON))
    }
    companion object {
        // createRecurringPayment in client/src/lib/recurringSchedule.ts (retryNotBefore is optional).
        // All policy numbers are integer literals; RFC 8259 forbids leading zeros and a plus sign.
        private val integerToken = Regex("-?(0|[1-9][0-9]*)")
        private val requiredRNFields = setOf("id", "label", "destination", "destinationType", "amountSat", "comment",
            "interval", "startAt", "endAt", "maxOccurrences", "status", "nextOccurrenceIndex", "nextRunAt",
            "occurrencesPaid", "consecutiveFailures", "lastError", "inFlight", "runs", "createdAt", "updatedAt")
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

internal fun durableRecurringWrite(file: File, bytes: ByteArray, sync: (File) -> Unit = ::syncDirectory) =
    durableRecurringWrite(file, bytes, sync) { it.sync() }

internal fun durableRecurringWrite(file: File, bytes: ByteArray, sync: (File) -> Unit, syncFile: (java.io.FileDescriptor) -> Unit) {
    check(file.parentFile!!.isDirectory)
    val temporary = File(file.parentFile, ".recurring-${UUID.randomUUID()}")
    try {
        FileOutputStream(temporary).use { it.write(bytes); syncFile(it.fd) }
        check(temporary.renameTo(file)) { "Recurring payment storage failed. Restart Noah; sending is stopped." }
        sync(file.parentFile!!)
    } finally { temporary.delete() }
}

/** Input has already passed JSON validation; retain original value slices on unrelated writes. */
private fun recurringRawFields(raw: String): Map<String, String> {
    val fields = linkedMapOf<String, String>()
    var index = raw.indexOf('{') + 1
    fun whitespace() { while (raw[index].isWhitespace()) index++ }
    whitespace()
    while (raw[index] != '}') {
        val keyStart = index++
        while (raw[index] != '"') { if (raw[index] == '\\') index++; index++ }
        index++
        val key = RecurringStore.json.decodeFromString<String>(raw.substring(keyStart, index))
        whitespace(); index++; whitespace()
        val start = index
        var depth = 0; var quoted = false; var escaped = false
        while (index < raw.length) {
            val char = raw[index]
            if (quoted) {
                if (escaped) escaped = false
                else if (char == '\\') escaped = true
                else if (char == '"') quoted = false
            } else {
                if (depth == 0 && (char == ',' || char == '}')) break
                if (char == '"') quoted = true
                else if (char == '{' || char == '[') depth++
                else if (char == '}' || char == ']') depth--
            }
            index++
        }
        fields[key] = raw.substring(start, index)
        if (raw[index] == ',') { index++; whitespace() }
    }
    return fields
}

/** Check original property tokens before kotlinx collapses duplicate keys, including unknown objects. */
private fun requireUniqueRecurringProperties(raw: String) {
    val objects = mutableListOf<MutableSet<String>?>()
    var index = 0
    while (index < raw.length) {
        when (raw[index]) {
            '{' -> objects.add(mutableSetOf())
            '[' -> objects.add(null)
            '}', ']' -> objects.removeAt(objects.lastIndex)
            '"' -> {
                val start = index++
                while (raw[index] != '"') { if (raw[index] == '\\') index++; index++ }
                val end = ++index
                while (index < raw.length && raw[index].isWhitespace()) index++
                if (index < raw.length && raw[index] == ':') {
                    val key = RecurringStore.json.decodeFromString<String>(raw.substring(start, end))
                    require(objects.last()!!.add(key)) { """Duplicate recurring property""" }
                }
                continue
            }
        }
        index++
    }
}
