package com.noahwallet.recurring

import com.noahwallet.send.*
import com.noahwallet.services.storage.syncDirectory
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.io.File
import java.util.UUID

/** Independent of MMKV: consumed indices and timestamps survive revisions, zones and policy rollback. */
class RecurringJournal internal constructor(val file: File, private val read: () -> String? = { if (file.exists()) file.readText() else null },
    private val write: (String) -> Unit = { durableRecurringWrite(file, it.toByteArray(), ::syncDirectory) },
    private val quarantine: (ByteArray) -> Unit = { durableRecurringWrite(File("${file.path}.quarantine"), it) }) {
    @Serializable data class Entry(val binding: RecurringStore.Binding, val scheduleID: String, val plan: RecurringPayment.Plan,
        val markedAt: Long, val policyRevision: String? = null, val resolved: Boolean = false, val consumed: Boolean = false,
        val policyApplied: Boolean = false, val terminal: TerminalEvidence? = null, val historyApplied: Boolean = false, val released: Boolean = false,
        val policy: RecurringPayment? = null) {
        val key get() = "$scheduleID:${plan.occurrenceIndex}"
    }
    @Serializable data class TerminalEvidence(val settlementID: String, val attempt: SendAttempt, val generation: Int,
        val walletGeneration: Int, val outcome: Outcome, val message: String?, val recordedAt: Long) {
        val paid get() = outcome == Outcome.succeeded || outcome == Outcome.alreadyPaid
    }
    @Serializable enum class Outcome { succeeded, alreadyPaid, failed, uncertain }
    @Serializable data class Scope(val binding: RecurringStore.Binding, val scheduleID: String)
    @Serializable data class Watermark(val binding: RecurringStore.Binding, val scheduleID: String, val through: Long)
    @Serializable data class ConsumedIndices(val binding: RecurringStore.Binding, val scheduleID: String,
        val policyRevision: String? = null, val indices: List<Int>)
    @Serializable private data class RevisionWatermark(val binding: RecurringStore.Binding, val scheduleID: String,
        val policyRevision: String? = null, val throughIndex: Int)
    @Serializable private data class V1(val attempts: Map<String, Entry>, val consumed: List<Watermark>,
        val revisions: List<RevisionWatermark> = emptyList())
    @Serializable private data class State(val version: Int = 3, val attempts: Map<String, Entry> = emptyMap(),
        val consumed: List<Watermark> = emptyList(), val consumedIndices: List<ConsumedIndices> = emptyList(),
        val legacyUncertainty: List<Scope> = emptyList()) {
        fun remember(entry: Entry): State {
            if (!entry.consumed) return this
            val at = entry.plan.scheduledFor!!
            val previous = consumed.firstOrNull { sameScope(it.binding, it.scheduleID, entry.binding, entry.scheduleID) }
            val revision = consumedIndices.firstOrNull { sameScope(it.binding, it.scheduleID, entry.binding,
                entry.scheduleID) && sameRevision(it.policyRevision, entry.policyRevision) }
            return copy(consumed = consumed.filterNot { it == previous } + Watermark(entry.binding, entry.scheduleID,
                maxOf(previous?.through ?: Long.MIN_VALUE, at)),
                consumedIndices = consumedIndices.filterNot { it == revision } + ConsumedIndices(entry.binding, entry.scheduleID, entry.policyRevision,
                    (revision?.indices.orEmpty() + entry.plan.occurrenceIndex!!).distinct().sorted()))
        }
    }
    private val json = RecurringStore.json
    val quarantineFile get() = File("${file.path}.quarantine")
    private var blocked = false
    private var walletBinding: RecurringStore.Binding? = null
    internal fun bind(binding: RecurringStore.Binding) = synchronized(this) {
        if (walletBinding != null && walletBinding != binding) blocked = false
        walletBinding = binding
    }
    private fun persist(state: State) {
        var written = false
        try { write(json.encodeToString(state)); written = true } finally { if (!written) blocked = true }
    }
    private fun state(): State {
        check(!blocked && !quarantineFile.exists()) { invalidMessage }
        val raw = read() ?: return State()
        val bytes = if (file.exists()) file.readBytes() else raw.toByteArray()
        return try { decode(bytes.decodeToString(throwOnInvalidSequence = true)) } catch (_: Exception) {
            blocked = true
            // Keep the original journal and the first durable copy, including across relaunch.
            if (!quarantineFile.exists()) quarantine(bytes)
            error(invalidMessage)
        }
    }
    private fun decode(raw: String): State {
        val root = json.parseToJsonElement(raw).jsonObject
        val version = root["version"]?.jsonPrimitive
        require(version == null || (!version.isString && version.intOrNull != null))
        var migrated = when (version?.int) {
            3 -> {
                require(root.keys.containsAll(listOf("attempts", "consumed", "consumedIndices", "legacyUncertainty")))
                return json.decodeFromJsonElement<State>(root).also(::validate)
            }
            2 -> {
                require(root.keys.containsAll(listOf("attempts", "consumed", "consumedIndices", "legacyUncertainty")))
                val old = json.decodeFromJsonElement<State>(root)
                old.copy(version = 3, legacyUncertainty = (old.legacyUncertainty + old.attempts.values
                    .filter { it.resolved || it.consumed || it.policyApplied }.map { Scope(it.binding, it.scheduleID) }).distinct())
            }
            1 -> {
                val old = json.decodeFromJsonElement<V1>(root)
                require(old.revisions.all { it.throughIndex >= 0 })
                // v1 watermarks may cover overwritten revisions with unknowable indices.
                State(attempts = old.attempts, consumed = old.consumed,
                    consumedIndices = old.revisions.map { ConsumedIndices(it.binding, it.scheduleID, it.policyRevision, listOf(it.throughIndex)) },
                    legacyUncertainty = (old.consumed.map { Scope(it.binding, it.scheduleID) } + old.revisions.map { Scope(it.binding,
                        it.scheduleID) }).distinct())
            }
            null -> {
                val attempts = json.decodeFromJsonElement<Map<String, Entry>>(root)
                // Even a failed legacy attempt may have replaced earlier consumed evidence.
                State(attempts = attempts, legacyUncertainty = attempts.values.map { Scope(it.binding, it.scheduleID) }.distinct())
            }
            else -> error("Unsupported recurring journal version. Sending remains stopped.")
        }
        validate(migrated, complete = false)
        migrated = migrated.attempts.values.fold(migrated) { saved, entry -> saved.remember(entry) }
        validate(migrated); persist(migrated)
        return migrated
    }
    private fun validate(state: State, complete: Boolean = true) {
        state.attempts.forEach { (id, e) -> validateAttempt(state, id, e, complete) }
        state.consumed.forEach { requireScope(it.binding, it.scheduleID) }
        state.consumedIndices.forEach {
            requireScope(it.binding, it.scheduleID)
            it.policyRevision?.let(::requireUUID)
            require(it.indices.isNotEmpty() && it.indices.all { index -> index >= 0 } && it.indices == it.indices.distinct().sorted())
        }
        state.legacyUncertainty.forEach { requireScope(it.binding, it.scheduleID) }
    }
    private fun requireUUID(value: String) { require(UUID.fromString(value).toString().equals(value, ignoreCase = true)) }
    private fun requireScope(binding: RecurringStore.Binding, id: String) {
        require(id.isNotBlank() && binding.wallet.fingerprint.isNotBlank() && binding.wallet.network.isNotBlank())
        requireUUID(binding.instance)
    }
    private fun validateAttempt(state: State, id: String, e: Entry, complete: Boolean) {
        requireScope(e.binding, id)
        e.policyRevision?.let(::requireUUID)
        require(wellFormed(id, e))
        e.terminal?.let { terminal -> validateTerminal(e, terminal) }
        require(!e.historyApplied || (e.terminal != null && e.resolved))
        require(!e.released || e.historyApplied)
        if (complete && e.consumed) requireConsumedEvidence(state, id, e)
    }
    private fun wellFormed(id: String, e: Entry): Boolean = id == e.scheduleID && e.plan.kind == "pay" && e.plan.occurrenceIndex != null &&
        e.plan.occurrenceIndex >= 0 && e.plan.scheduledFor != null && (!e.consumed || e.resolved) && (!e.policyApplied || e.resolved)
    private fun requireConsumedEvidence(state: State, id: String, e: Entry) {
        val scheduledFor = checkNotNull(e.plan.scheduledFor)
        require(state.consumed.any { sameScope(it.binding, it.scheduleID, e.binding, id) && it.through >= scheduledFor } &&
            state.consumedIndices.any { sameScope(it.binding, it.scheduleID, e.binding, id) && sameRevision(it.policyRevision,
                e.policyRevision) && e.plan.occurrenceIndex in it.indices })
    }
    private fun validateTerminal(e: Entry, terminal: TerminalEvidence) {
        UUID.fromString(terminal.settlementID)
        require(terminal.attempt.recurringOccurrenceKey == e.key && terminal.attempt.walletIdentity == e.binding.wallet &&
            terminal.generation >= 0 && terminal.walletGeneration >= 0)
        if (terminal.outcome == Outcome.succeeded) {
            require(terminal.attempt.receipt?.let {
                !it.isUnresolved && !it.broadcastRetryPending && it.isProven(terminal.attempt.payment, terminal.attempt.paymentHash)
            } == true)
        }
        if (terminal.outcome == Outcome.alreadyPaid) require(terminal.attempt.alreadyPaid?.isProven(terminal.attempt.payment,
            terminal.attempt.paymentHash, true) == true)
        require(terminal.outcome != Outcome.failed || terminal.attempt.failed)
    }
    /** Runs inside Send's wallet reservation before its terminal outcome is published. */
    @Suppress("LongParameterList") // Send's evidence callback arguments, plus the binding and clock
    @Synchronized
    fun record(attempt: SendAttempt, generation: Int, walletGeneration: Int, message: String?, binding: RecurringStore.Binding, now: Long) {
        val outcome = when {
            attempt.alreadyPaid != null -> Outcome.alreadyPaid
            attempt.receipt?.let { !it.isUnresolved && !it.broadcastRetryPending } == true -> Outcome.succeeded
            attempt.failed -> Outcome.failed
            else -> Outcome.uncertain
        }
        val entry = state().attempts.values.firstOrNull { it.key == attempt.recurringOccurrenceKey && it.binding == binding }
        check(entry != null && attempt.walletIdentity == binding.wallet) { invalidMessage }
        check(!legacyUncertain(binding, entry.scheduleID)) { legacyMessage }
        entry.terminal?.let {
            check(it.attempt.recurringAttemptID == attempt.recurringAttemptID) { invalidMessage }
            if (it.outcome != Outcome.uncertain || (it.outcome == outcome && it.attempt == attempt)) return
        }
        save(entry.copy(terminal = TerminalEvidence(entry.terminal?.settlementID ?: UUID.randomUUID().toString(), attempt, generation, walletGeneration,
            outcome, message, now)))
    }
    @Synchronized fun load() = state().attempts
    @Synchronized fun legacyUncertain(binding: RecurringStore.Binding,
        id: String) = state().legacyUncertainty.any { sameScope(it.binding, it.scheduleID, binding, id) }
    @Synchronized fun consumed(binding: RecurringStore.Binding, id: String, at: Long, revision: String? = null, index: Int? = null): Boolean {
        val state = state()
        return state.legacyUncertainty.any { sameScope(it.binding, it.scheduleID, binding, id) } ||
            state.consumed.any { sameScope(it.binding, it.scheduleID, binding, id) && at <= it.through } ||
            (index != null && state.consumedIndices.any { sameScope(it.binding, it.scheduleID, binding, id) && sameRevision(it.policyRevision,
                revision) && index in it.indices })
    }
    @Synchronized fun save(entry: Entry) {
        val previous = state()
        validate(previous.copy(attempts = previous.attempts + (entry.scheduleID to entry)), complete = false)
        val state = previous.remember(entry).let { it.copy(attempts = it.attempts + (entry.scheduleID to entry)) }
        validate(state); persist(state)
    }
    companion object {
        /**
         * Each schedule's last paid occurrence, by the movement or payment hash its receipt names, with the schedule's
         * name and interval: Activity's recurring rows. Read-only; the journal keeps one attempt per schedule.
         */
        fun historyLinks(context: android.content.Context,
            settings: com.noahwallet.services.storage.SettingsStorage): List<com.noahwallet.history.HistoryMapping.RecurringLink> {
            val location = com.noahwallet.services.storage.WalletDataLocation(context.filesDir, com.noahwallet.BuildConfig.APP_VARIANT)
            val schedules = RecurringStore(settings).load().schedules
            return shared(File(location.directory, "noah-recurring-journal.json")).load().values.mapNotNull { historyLink(it, schedules[it.scheduleID]) }
        }
        /**
         * The payment an occurrence made itself: a succeeded submission whose receipt is proven for that attempt. Never an
         * already-paid outcome, whose receipt names a payment made before the occurrence (perhaps by hand).
         */
        internal fun historyLink(entry: Entry, schedule: RecurringPayment?): com.noahwallet.history.HistoryMapping.RecurringLink? {
            val terminal = entry.terminal?.takeIf { it.outcome == Outcome.succeeded } ?: return null
            val attempt = terminal.attempt
            if (attempt.alreadyPaid != null || attempt.recurringOccurrenceKey?.let { it != entry.key } == true) return null
            val receipt = attempt.receipt?.takeIf { !it.isUnresolved && it.isProven(attempt.payment, attempt.paymentHash) } ?: return null
            val policy = schedule ?: entry.policy ?: return null
            return com.noahwallet.history.HistoryMapping.RecurringLink(com.noahwallet.history.HistoryTransaction.Recurring(entry.scheduleID, policy.label,
                policy.interval), receipt.movementID, receipt.paymentHash)
        }
        private val journals = mutableMapOf<String, RecurringJournal>()
        /** One monitor and migration owner per canonical file, retained across all entry points. */
        fun shared(file: File, binding: RecurringStore.Binding? = null): RecurringJournal =
            shared(file, binding) { RecurringJournal(it) }
        internal fun shared(file: File, binding: RecurringStore.Binding?, create: (File) -> RecurringJournal): RecurringJournal {
            val journal = synchronized(journals) { journals.getOrPut(file.canonicalPath) { create(file.canonicalFile) } }
            if (binding != null) journal.bind(binding)
            return journal
        }

        const val invalidMessage = "Recurring payment history needs attention. Its saved evidence has been preserved; sending remains stopped."
        private fun sameScope(a: RecurringStore.Binding, aID: String, b: RecurringStore.Binding, bID: String) =
            aID == bID && a.wallet == b.wallet && UUID.fromString(a.instance) == UUID.fromString(b.instance)
        private fun sameRevision(a: String?, b: String?) = a.equals(b, ignoreCase = true)
        val legacyReason = RecurringIssue(RecurringIssue.Code.legacyHistory)
        const val legacyMessage = "Noah can't reconstruct this schedule's earlier payment history. Cancel it and explicitly approve a new recurring payment."
    }
}
