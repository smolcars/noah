package com.noahwallet.recurring

import com.noahwallet.services.storage.syncDirectory
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.io.File

/** Independent of MMKV: consumed timestamps survive every revision and policy rollback. */
class RecurringJournal(val file: File, private val read: () -> String? = { if (file.exists()) file.readText() else null },
    private val write: (String) -> Unit = { durableRecurringWrite(file, it.toByteArray(), ::syncDirectory) }) {
    @Serializable data class Entry(val binding: RecurringStore.Binding, val scheduleID: String, val plan: RecurringPayment.Plan,
        val markedAt: Long, val policyRevision: String? = null, val resolved: Boolean = false, val consumed: Boolean = false) {
        val key get() = "$scheduleID:${plan.occurrenceIndex}"
    }
    @Serializable data class Watermark(val binding: RecurringStore.Binding, val scheduleID: String, val through: Long)
    @Serializable data class State(val version: Int = 1, val attempts: Map<String, Entry> = emptyMap(), val consumed: List<Watermark> = emptyList()) {
        fun remember(entry: Entry): State {
            if (!entry.consumed) return this
            val at = entry.plan.scheduledFor!!
            val previous = consumed.firstOrNull { it.binding == entry.binding && it.scheduleID == entry.scheduleID }
            return copy(consumed = consumed.filterNot { it == previous } + Watermark(entry.binding, entry.scheduleID, maxOf(previous?.through ?: Long.MIN_VALUE, at)))
        }
    }
    private val json = RecurringStore.json
    private var blocked = false
    private fun persist(state: State) { try { write(json.encodeToString(state)) } catch (failure: Exception) { blocked = true; throw failure } }
    private fun state(): State {
        check(!blocked) { "Recurring journal write failed. Sending remains stopped." }
        val raw = read() ?: return State()
        val root = json.parseToJsonElement(raw).jsonObject
        if ("version" in root) {
            val state = json.decodeFromJsonElement<State>(root)
            require(state.version == 1)
            validate(state)
            return state
        }
        val attempts = json.decodeFromJsonElement<Map<String, Entry>>(root)
        var migrated = State(attempts = attempts)
        validate(migrated)
        attempts.values.forEach { migrated = migrated.remember(it) }
        persist(migrated)
        return migrated
    }
    private fun validate(state: State) {
        require(state.attempts.all { (id, e) -> id == e.scheduleID && e.plan.kind == "pay" && e.plan.occurrenceIndex != null && e.plan.scheduledFor != null && (!e.consumed || e.resolved) })
    }
    @Synchronized fun load() = state().attempts
    @Synchronized fun consumed(binding: RecurringStore.Binding, id: String, at: Long) = state().consumed.any { it.binding == binding && it.scheduleID == id && at <= it.through }
    @Synchronized fun save(entry: Entry) {
        val state = state().remember(entry).let { it.copy(attempts = it.attempts + (entry.scheduleID to entry)) }
        validate(state); persist(state)
    }
}
