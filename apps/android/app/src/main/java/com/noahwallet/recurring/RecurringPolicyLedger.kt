package com.noahwallet.recurring

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import com.noahwallet.services.storage.syncDirectory
import java.io.File
import java.security.MessageDigest

/** Write-ahead authority for policies; MMKV is never allowed to repair newer evidence. */
internal class RecurringPolicyLedger(val file: File, private val warn: () -> Unit = {}, private val sync: (File) -> Unit = ::syncDirectory, private val syncFile: (java.io.FileDescriptor) -> Unit = { it.sync() }) {
    @Serializable private data class Marker(val version: Int, val binding: RecurringStore.Binding)
    @Serializable private data class Entry(val binding: RecurringStore.Binding, val scheduleID: String, val generation: Long, val revision: String?, val status: String, val digest: String?, val settlementKey: String? = null, val pending: RecurringPayment? = null, val predecessor: String? = null, val digestVersion: Int? = null, val predecessorVersion: Int? = null, val formatMigration: Boolean = false)
    @Serializable private data class State(val version: Int, val binding: RecurringStore.Binding, val generation: Long, val entries: Map<String, Entry>)
    val marker get() = File("${file.path}.initialized")
    private val json = RecurringStore.json
    private var failed = false
    private fun key(binding: RecurringStore.Binding, id: String): String = MessageDigest.getInstance("SHA-256")
        .digest(json.encodeToString(binding).toByteArray()).joinToString("") { "%02x".format(it) } + ":" + id
    // Frozen projections: adding a model field must never alter an older digest schema.
    private fun digest(schedule: RecurringPayment, version: Int): String {
        check(version in 1..2) { message }
        fun fields(value: JsonElement, names: String): JsonObject = JsonObject(names.split(" ").associateWith { value.jsonObject.getValue(it) })
        val names = "id label destination destinationType amountSat comment interval startAt endAt maxOccurrences nativeRevision status nextOccurrenceIndex nextRunAt occurrencesPaid consecutiveFailures lastError" +
            (if (version == 2) " issue" else "") + " retryNotBefore inFlight runs createdAt updatedAt"
        val row = fields(json.encodeToJsonElement(schedule), names).toMutableMap()
        row["interval"] = fields(row.getValue("interval"), "unit every")
        row["inFlight"]?.takeUnless { it == JsonNull }?.let { row["inFlight"] = fields(it, "occurrenceIndex startedAt") }
        row["runs"] = JsonArray(row.getValue("runs").jsonArray.map { fields(it, "occurrenceIndex scheduledFor attemptedAt status amountSat error") })
        row["issue"]?.takeUnless { it == JsonNull }?.let { row["issue"] = fields(it, "code arguments") }
        return MessageDigest.getInstance("SHA-256").digest(JsonObject(row).toString().toByteArray()).joinToString("") { "%02x".format(it) }
    }
    private fun digestMatches(expected: String?, version: Int?, schedule: RecurringPayment): Boolean =
        if (version != null) expected == digest(schedule, version)
        else expected == digest(schedule, 2) || schedule.issue == null && expected == digest(schedule, 1)
    private fun matchingVersion(entry: Entry, schedule: RecurringPayment): Int = entry.digestVersion
        ?: if (entry.digest == digest(schedule, 2)) 2 else 1
    private fun entry(binding: RecurringStore.Binding, schedule: RecurringPayment, generation: Long) =
        Entry(binding, schedule.id, generation, schedule.nativeRevision, schedule.status, digest(schedule, 2), digestVersion = 2)
    private fun matches(entry: Entry?, binding: RecurringStore.Binding, schedule: RecurringPayment) = entry != null && entry.binding == binding && entry.scheduleID == schedule.id && entry.status == schedule.status &&
        entry.revision == schedule.nativeRevision && digestMatches(entry.digest, entry.digestVersion, schedule)
    private fun persist(state: State) = durableRecurringWrite(file, json.encodeToString(state).toByteArray(), sync, syncFile)
    private fun state(binding: RecurringStore.Binding, schedules: Map<String, RecurringPayment>): State {
        check(!failed) { message }
        try {
            if (!marker.exists() && !file.exists()) {
                // Marker first: a crash during bootstrap must never authorize a second import.
                durableRecurringWrite(marker, json.encodeToString(Marker(1, binding)).toByteArray(), sync, syncFile)
                persist(State(1, binding, 0, schedules.map { (id, schedule) -> key(binding, id) to entry(binding, schedule, 0) }.toMap()))
            }
            val markerState = json.decodeFromString<Marker>(marker.readText())
            val state = json.decodeFromString<State>(file.readText())
            require(markerState.version == 1 && markerState.binding == state.binding && state.version == 1 && state.generation >= 0)
            state.entries.forEach { (id, e) ->
                require(e.digestVersion == null || e.digestVersion in 1..2)
                require(e.predecessorVersion == null || e.predecessorVersion in 1..2)
                require(e.scheduleID.isNotBlank() && id == key(e.binding, e.scheduleID) && e.generation in 0..state.generation)
                require(e.binding.wallet.fingerprint.isNotBlank() && e.binding.wallet.network.isNotBlank())
                java.util.UUID.fromString(e.binding.instance)
                e.revision?.let { java.util.UUID.fromString(it) }
                require(e.status in listOf("active", "paused", "needs_attention", "needs_approval", "completed", "deleted"))
                require(if (e.status == "deleted") e.digest == null else e.digest?.matches(Regex("[0-9a-f]{64}")) == true)
            }
            return state
        } catch (failure: Exception) { failed = true; warn(); throw IllegalStateException(message, failure) }
    }
    fun stopped(binding: RecurringStore.Binding, schedules: Map<String, RecurringPayment>): Set<String> = try {
        val state = state(binding, schedules)
        schedules.filter { (id, schedule) -> !matches(state.entries[key(binding, id)], binding, schedule) }.keys.also { if (it.isNotEmpty()) warn() }
    } catch (_: Exception) { schedules.keys }

    fun commit(binding: RecurringStore.Binding, before: Map<String, RecurringPayment>, after: Map<String, RecurringPayment>, policyID: String? = null, reapproval: Boolean = false, settlementKey: String? = null, migrationIDs: Set<String> = emptySet()) {
        val state = state(binding, before)
        val changed = (before.keys + after.keys + listOfNotNull(policyID)).filter { it == policyID || it in migrationIDs || before[it] != after[it] }
        if (changed.isEmpty()) return
        val generation = Math.addExact(state.generation, 1)
        val entries = state.entries.toMutableMap()
        for (id in changed) {
            val schedule = after[id]
            val key = key(binding, id)
            if (schedule == null) entries[key] = Entry(binding, id, generation, entries[key]?.revision, "deleted", null)
            else {
                // A user approval creates a new revision under this generation; evidence stays intact.
                check((reapproval && schedule.nativeRevision != null && schedule.nativeRevision != entries[key]?.revision && schedule.nativeRevision != before[id]?.nativeRevision && schedule.inFlight == null && schedule.status == "active") || if (before[id] == null) entries[key] == null else matches(entries[key], binding, before.getValue(id))) { message }
                entries[key] = entry(binding, schedule, generation).copy(settlementKey = settlementKey ?: entries[key]?.settlementKey,
                    pending = if (settlementKey != null || id in migrationIDs) schedule else null,
                    predecessor = if (settlementKey != null || id in migrationIDs) entries[key]?.digest else null,
                    predecessorVersion = if (settlementKey != null || id in migrationIDs) entries[key]?.let { matchingVersion(it, before.getValue(id)) } else null, formatMigration = id in migrationIDs)
            }
        }
        try { persist(state.copy(generation = generation, entries = entries)) }
        catch (failure: Exception) { failed = true; warn(); throw failure }
    }
    val hasPending get() = runCatching { json.decodeFromString<State>(file.readText()).entries.values.any { it.pending != null } }.getOrDefault(false)
    fun applied(binding: RecurringStore.Binding, schedules: Map<String, RecurringPayment>, id: String, token: String): Boolean =
        state(binding, schedules).entries[key(binding, id)]?.settlementKey == token

    /** Only an unfinished, exact predecessor may be repaired; completed snapshots still fail closed. */
    fun pending(binding: RecurringStore.Binding, schedules: Map<String, RecurringPayment>): List<RecurringPayment> {
        val state = state(binding, schedules)
        return state.entries.values.filter { it.binding == binding && it.pending != null }.map { e ->
            val row = schedules[e.scheduleID] ?: error(message)
            check((e.settlementKey != null || e.formatMigration) && e.pending!!.id == e.scheduleID && e.pending.nativeRevision == e.revision && digestMatches(e.digest, e.digestVersion, e.pending) &&
                (digestMatches(e.predecessor, e.predecessorVersion, row) || matches(e, binding, row))) { message }
            e.pending
        }
    }
    fun finish(binding: RecurringStore.Binding, schedules: Map<String, RecurringPayment>) {
        val state = state(binding, schedules)
        val pending = state.entries.filterValues { it.binding == binding && it.pending != null }
        if (pending.isEmpty()) return
        check(pending.values.all { e -> schedules[e.scheduleID]?.let { matches(e, binding, it) } == true }) { message }
        persist(state.copy(entries = state.entries.mapValues { (id, e) -> if (id in pending) e.copy(pending = null, predecessor = null, predecessorVersion = null, formatMigration = false) else e }))
    }
    /** Only exact, completed authority may enter the next format's write-ahead transaction. */
    fun migrate(binding: RecurringStore.Binding, schedules: Map<String, RecurringPayment>): Boolean {
        val state = try { state(binding, schedules) } catch (_: Exception) { return false }
        check(state.entries.values.none { it.binding == binding && it.pending != null }) { message }
        val ids = schedules.filter { (id, row) -> !row.unsupportedStorage && matches(state.entries[key(binding, id)], binding, row) &&
            (state.entries[key(binding, id)]?.digestVersion != 2 || row.decodeLegacyIssue() != row) }.keys
        if (ids.isEmpty()) return false
        val after = schedules.mapValues { (id, row) -> if (id in ids) row.decodeLegacyIssue() else row }
        commit(binding, schedules, after, migrationIDs = ids)
        return true
    }
    companion object {
        const val message = "Recurring policy evidence needs attention. Saved schedules are preserved; edit or resume them to approve a new revision. Sending remains stopped."
        const val logMessage = "Recurring policy rollback or unavailable evidence detected; sending stopped"
    }
}
