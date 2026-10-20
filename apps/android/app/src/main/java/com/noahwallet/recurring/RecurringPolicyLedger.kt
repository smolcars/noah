package com.noahwallet.recurring

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import com.noahwallet.services.storage.syncDirectory
import java.io.File
import java.security.MessageDigest

/** Write-ahead authority for policies; MMKV is never allowed to repair newer evidence. */
internal class RecurringPolicyLedger(val file: File, private val warn: () -> Unit = {}, private val sync: (File) -> Unit = ::syncDirectory, private val syncFile: (java.io.FileDescriptor) -> Unit = { it.sync() }) {
    @Serializable private data class Marker(val version: Int, val binding: RecurringStore.Binding)
    @Serializable private data class Entry(val binding: RecurringStore.Binding, val scheduleID: String, val generation: Long, val revision: String?, val status: String, val digest: String?)
    @Serializable private data class State(val version: Int, val binding: RecurringStore.Binding, val generation: Long, val entries: Map<String, Entry>)
    val marker get() = File("${file.path}.initialized")
    private val json = RecurringStore.json
    private var failed = false
    private fun key(binding: RecurringStore.Binding, id: String): String = MessageDigest.getInstance("SHA-256")
        .digest(json.encodeToString(binding).toByteArray()).joinToString("") { "%02x".format(it) } + ":" + id
    private fun digest(schedule: RecurringPayment): String = MessageDigest.getInstance("SHA-256")
        .digest(json.encodeToString(schedule).toByteArray()).joinToString("") { "%02x".format(it) }
    private fun entry(binding: RecurringStore.Binding, schedule: RecurringPayment, generation: Long) = Entry(binding, schedule.id, generation, schedule.nativeRevision, schedule.status, digest(schedule))
    private fun matches(entry: Entry?, binding: RecurringStore.Binding, schedule: RecurringPayment) = entry != null && entry.binding == binding && entry.scheduleID == schedule.id && entry.status == schedule.status &&
        entry.revision == schedule.nativeRevision && entry.digest == digest(schedule)
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

    fun commit(binding: RecurringStore.Binding, before: Map<String, RecurringPayment>, after: Map<String, RecurringPayment>, policyID: String? = null, reapproval: Boolean = false) {
        val state = state(binding, before)
        val changed = (before.keys + after.keys + listOfNotNull(policyID)).filter { it == policyID || before[it] != after[it] }
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
                entries[key] = entry(binding, schedule, generation)
            }
        }
        try { persist(state.copy(generation = generation, entries = entries)) }
        catch (failure: Exception) { failed = true; warn(); throw failure }
    }
    companion object {
        const val message = "Recurring policy evidence needs attention. Saved schedules are preserved; edit or resume them to approve a new revision. Sending remains stopped."
        const val logMessage = "Recurring policy rollback or unavailable evidence detected; sending stopped"
    }
}
