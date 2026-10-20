package com.noahwallet.recurring

import com.noahwallet.services.storage.syncDirectory
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.io.File
import java.util.UUID

/** Independent of MMKV: consumed indices and timestamps survive revisions, zones and policy rollback. */
class RecurringJournal(val file: File, private val read: () -> String? = { if (file.exists()) file.readText() else null },
    private val write: (String) -> Unit = { durableRecurringWrite(file, it.toByteArray(), ::syncDirectory) }) {
    @Serializable data class Entry(val binding: RecurringStore.Binding, val scheduleID: String, val plan: RecurringPayment.Plan,
        val markedAt: Long, val policyRevision: String? = null, val resolved: Boolean = false, val consumed: Boolean = false) {
        val key get() = "$scheduleID:${plan.occurrenceIndex}"
    }
    @Serializable data class Scope(val binding: RecurringStore.Binding, val scheduleID: String)
    @Serializable data class Watermark(val binding: RecurringStore.Binding, val scheduleID: String, val through: Long)
    @Serializable data class ConsumedIndices(val binding: RecurringStore.Binding, val scheduleID: String,
        val policyRevision: String? = null, val indices: List<Int>)
    @Serializable private data class RevisionWatermark(val binding: RecurringStore.Binding, val scheduleID: String,
        val policyRevision: String? = null, val throughIndex: Int)
    @Serializable private data class V1(val attempts: Map<String, Entry>, val consumed: List<Watermark>,
        val revisions: List<RevisionWatermark> = emptyList())
    @Serializable private data class State(val version: Int = 2, val attempts: Map<String, Entry> = emptyMap(),
        val consumed: List<Watermark> = emptyList(), val consumedIndices: List<ConsumedIndices> = emptyList(),
        val legacyUncertainty: List<Scope> = emptyList()) {
        fun remember(entry: Entry): State {
            if (!entry.consumed) return this
            val at = entry.plan.scheduledFor!!
            val previous = consumed.firstOrNull { sameScope(it.binding, it.scheduleID, entry.binding, entry.scheduleID) }
            val revision = consumedIndices.firstOrNull { sameScope(it.binding, it.scheduleID, entry.binding, entry.scheduleID) && sameRevision(it.policyRevision, entry.policyRevision) }
            return copy(consumed = consumed.filterNot { it == previous } + Watermark(entry.binding, entry.scheduleID, maxOf(previous?.through ?: Long.MIN_VALUE, at)),
                consumedIndices = consumedIndices.filterNot { it == revision } + ConsumedIndices(entry.binding, entry.scheduleID, entry.policyRevision,
                    (revision?.indices.orEmpty() + entry.plan.occurrenceIndex!!).distinct().sorted()))
        }
    }
    private val json = RecurringStore.json
    private var blocked = false
    private fun persist(state: State) { try { write(json.encodeToString(state)) } catch (failure: Exception) { blocked = true; throw failure } }
    private fun state(): State {
        check(!blocked) { "Recurring journal write failed. Sending remains stopped." }
        val raw = read() ?: return State()
        val root = json.parseToJsonElement(raw).jsonObject
        var migrated = when (root["version"]?.jsonPrimitive?.int) {
            2 -> {
                require(root.keys.containsAll(listOf("attempts", "consumed", "consumedIndices", "legacyUncertainty")))
                return json.decodeFromJsonElement<State>(root).also(::validate)
            }
            1 -> {
                val old = json.decodeFromJsonElement<V1>(root)
                require(old.revisions.all { it.throughIndex >= 0 })
                // v1 watermarks may cover overwritten revisions with unknowable indices.
                State(attempts = old.attempts, consumed = old.consumed,
                    consumedIndices = old.revisions.map { ConsumedIndices(it.binding, it.scheduleID, it.policyRevision, listOf(it.throughIndex)) },
                    legacyUncertainty = (old.consumed.map { Scope(it.binding, it.scheduleID) } + old.revisions.map { Scope(it.binding, it.scheduleID) }).distinct())
            }
            null -> {
                val attempts = json.decodeFromJsonElement<Map<String, Entry>>(root)
                // Even a failed legacy attempt may have replaced earlier consumed evidence.
                State(attempts = attempts, legacyUncertainty = attempts.values.map { Scope(it.binding, it.scheduleID) }.distinct())
            }
            else -> error("Unsupported recurring journal version. Sending remains stopped.")
        }
        validate(migrated)
        migrated = migrated.attempts.values.fold(migrated) { saved, entry -> saved.remember(entry) }
        validate(migrated); persist(migrated)
        return migrated
    }
    private fun validate(state: State) {
        fun scope(binding: RecurringStore.Binding, id: String) {
            require(id.isNotBlank() && binding.wallet.fingerprint.isNotBlank() && binding.wallet.network.isNotBlank())
            UUID.fromString(binding.instance)
        }
        state.attempts.forEach { (id, e) ->
            scope(e.binding, id)
            require(id == e.scheduleID && e.plan.kind == "pay" && e.plan.occurrenceIndex != null && e.plan.occurrenceIndex >= 0 && e.plan.scheduledFor != null && (!e.consumed || e.resolved))
        }
        state.consumed.forEach { scope(it.binding, it.scheduleID) }
        state.consumedIndices.forEach {
            scope(it.binding, it.scheduleID)
            require(it.indices.isNotEmpty() && it.indices.all { index -> index >= 0 } && it.indices == it.indices.distinct().sorted())
        }
        state.legacyUncertainty.forEach { scope(it.binding, it.scheduleID) }
    }
    @Synchronized fun load() = state().attempts
    @Synchronized fun legacyUncertain(binding: RecurringStore.Binding, id: String) = state().legacyUncertainty.any { sameScope(it.binding, it.scheduleID, binding, id) }
    @Synchronized fun consumed(binding: RecurringStore.Binding, id: String, at: Long, revision: String? = null, index: Int? = null): Boolean {
        val state = state()
        return state.legacyUncertainty.any { sameScope(it.binding, it.scheduleID, binding, id) } ||
            state.consumed.any { sameScope(it.binding, it.scheduleID, binding, id) && at <= it.through } ||
            index != null && state.consumedIndices.any { sameScope(it.binding, it.scheduleID, binding, id) && sameRevision(it.policyRevision, revision) && index in it.indices }
    }
    @Synchronized fun save(entry: Entry) {
        val previous = state()
        validate(previous.copy(attempts = previous.attempts + (entry.scheduleID to entry)))
        val state = previous.remember(entry).let { it.copy(attempts = it.attempts + (entry.scheduleID to entry)) }
        validate(state); persist(state)
    }
    companion object {
        private fun sameScope(a: RecurringStore.Binding, aID: String, b: RecurringStore.Binding, bID: String) =
            aID == bID && a.wallet == b.wallet && UUID.fromString(a.instance) == UUID.fromString(b.instance)
        private fun sameRevision(a: String?, b: String?) = a.equals(b, ignoreCase = true)
        const val legacyMessage = "Noah can't reconstruct this schedule's earlier payment history. Cancel it and explicitly approve a new recurring payment."
    }
}
