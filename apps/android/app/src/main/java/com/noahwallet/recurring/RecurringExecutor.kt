package com.noahwallet.recurring

import androidx.compose.runtime.*
import com.noahwallet.api.generated.models.RecurringPaymentScheduleEntry
import com.noahwallet.send.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import java.util.TimeZone
import java.util.UUID

/** Joins foreground/push/manual triggers. Only the Send controller can submit money. */
class RecurringExecutor(private val d: Dependencies,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)) {
    internal class NoWallet : Exception()
    data class Dependencies(val store: RecurringStore, val journal: RecurringJournal, val controller: SendController,
        val ensureOpen: suspend () -> Unit, val binding: () -> RecurringStore.Binding, val allowed: () -> Unit,
        val quote: suspend (RecurringPayment) -> Pair<SendPayment, SendQuote>, val automationGeneration: () -> String?,
        val now: () -> Long = System::currentTimeMillis, val zone: () -> TimeZone = TimeZone::getDefault,
        val sync: suspend (List<RecurringPaymentScheduleEntry>) -> Unit = {},
        val notify: (List<RecurringPayment>, RecurringPayment?) -> Unit = { _, _ -> },
        val audit: (RecurringPayment, RecurringJournal.Entry?, String, String) -> Unit = { _, _, _, _ -> error("""Audit unavailable""") },
        val refresh: suspend () -> Unit = {},
        val withWalletMutation: suspend (suspend () -> Unit) -> Unit = { it() })
    init {
        d.store.protectPolicies(java.io.File("${d.journal.file.path}.policies"), binding = d.binding)
        d.controller.persistRecurringEvidence = { attempt, generation, walletGeneration, _, message ->
            check(d.journal.file.parentFile!!.isDirectory)
            val binding = d.binding()
            check(d.binding() == binding)
            d.journal.record(attempt, generation, walletGeneration, message, binding, d.now())
        }
    }
    data class Review(val schedule: RecurringPayment, val plan: RecurringPayment.Plan, val payment: SendPayment, val quote: SendQuote)
    var schedules by mutableStateOf<List<RecurringPayment>>(emptyList()); private set
    var error by mutableStateOf<String?>(null); private set
    var running by mutableStateOf(false); private set
    var syncPending by mutableStateOf(false); private set
    val controller get() = d.controller
    private var task: Deferred<Unit>? = null
    private var taskBudget: RecurringBudget? = null
    private var syncTask: Deferred<Unit>? = null
    private var executionBinding: RecurringStore.Binding? = null
    private suspend fun <T> walletMutation(block: suspend () -> T): T {
        var result: Result<T>? = null
        d.withWalletMutation { result = runCatching { block() } }
        return checkNotNull(result).getOrThrow()
    }
    private fun readAdmission(): RecurringStore.Binding {
        if (!d.journal.file.parentFile!!.isDirectory) throw NoWallet()
        val owner = d.binding()
        val generation = d.automationGeneration()
        if (admissionChanged(owner, generation)) throw NoWallet()
        d.journal.bind(owner)
        return owner
    }
    private fun admissionChanged(owner: RecurringStore.Binding, generation: String?): Boolean =
        !d.journal.file.parentFile!!.isDirectory || d.binding() != owner || d.automationGeneration() != generation ||
            (executionBinding != null && executionBinding != owner)
    private suspend fun <T> storage(block: () -> T): T = walletMutation {
        withContext(Dispatchers.IO) { readAdmission(); block() }
    }
    private suspend fun ensureOpen() = walletMutation { d.ensureOpen() }
    suspend fun reload() {
        try {
            val state = storage { d.store.load() }
            schedules = state.schedules.values.sortedBy { it.nextRunAt ?: Long.MAX_VALUE }; syncPending = state.pending
        } catch (_: NoWallet) { schedules = emptyList(); syncPending = false }
        catch (_: Exception) { error = "Recurring payment data couldn't be read. Your saved schedules have been preserved. Sending is stopped." }
    }
    internal suspend fun hasDuePayments(now: Long): Boolean = try {
        storage { d.allowed(); RecurringRuntime.hasDuePayments(d.store.load(), now) }
    } catch (_: NoWallet) { false }
    internal suspend fun reminderSchedule(id: String?, due: Long): RecurringPayment? = try {
        storage {
            d.allowed()
            val state = d.store.load()
            state.schedules[id]?.takeIf { it.status == "active" && it.inFlight == null && it.nextRunAt == due && state.binding == d.binding() }
        }
    } catch (_: NoWallet) { null }
    suspend fun execute(manual: Boolean = false, approval: Review? = null, budget: RecurringBudget? = null) {
        while (true) {
            currentCoroutineContext().ensureActive()
            if (budget?.available == false) return
            val pending = task?.takeIf { !it.isCompleted } ?: break
            val drainOnly = budget == null || taskBudget === budget
            val result = runCatching { pending.await() }
            currentCoroutineContext().ensureActive()
            if (drainOnly) { result.getOrThrow(); return }
            // Another admission may have been revoked; reread durable due work with our budget.
        }
        task = null
        val caller = currentCoroutineContext()[Job]
        val executionBudget = budget ?: RecurringBudget()
        val work = scope.async(start = CoroutineStart.LAZY) { run(manual, approval, executionBudget, caller) }
        task = work; taskBudget = executionBudget; work.start()
        try { work.await() } finally { if (work.isCompleted && task === work) { task = null; taskBudget = null } }
    }
    private suspend fun boundState(): RecurringStore.State = storage { boundStateNow() }
    private fun boundStateNow(): RecurringStore.State {
        d.allowed()
        val owner = d.binding()
        if (executionBinding != null && owner != executionBinding) throw NoWallet()
        d.store.automationGeneration()
        var state = d.store.load()
        if (state.binding == null || (state.binding != owner && state.schedules.isEmpty())) { d.store.bind(owner); state = d.store.load() }
        check(state.binding == owner) {
            "These recurring payments belong to a previous wallet. They are preserved but cannot run. Cancel them and create new schedules for this wallet."
        }
        return state
    }
    private suspend fun recoverMarkers(state: RecurringStore.State) = storage {
        var loaded = false
        // Corrupt shared evidence cannot prove any policy in this wallet is safe.
        val entries = try { d.journal.load().also { loaded = true } } finally { if (!loaded) stopForInvalidJournal(state) }
        for (schedule in state.schedules.values.filter { !it.requiresPolicyApproval }) stopIfInterrupted(state, entries, schedule)
    }
    private fun stopForInvalidJournal(state: RecurringStore.State) {
        for (schedule in state.schedules.values.filter { !it.requiresPolicyApproval }) {
            d.store.updateIf(schedule,
                schedule.copy(status = "needs_attention", lastError = RecurringJournal.invalidMessage,
                    issue = RecurringIssue(RecurringIssue.Code.invalidJournal)))
        }
    }
    private fun stopIfInterrupted(state: RecurringStore.State, entries: Map<String, RecurringJournal.Entry>, schedule: RecurringPayment) {
        val planning = if (schedule.status == "needs_approval") schedule.copy(status = "active") else schedule
        val plan = planning.plan(d.now(), d.zone(), true)
        val legacyUncertain = d.journal.legacyUncertain(state.binding!!, schedule.id)
        val consumed = plan.scheduledFor?.let { d.journal.consumed(state.binding, schedule.id, it, schedule.nativeRevision, plan.occurrenceIndex) } ?: false
        val unresolved = entries[schedule.id]?.let { !it.resolved && it.binding == state.binding } == true
        val interrupted = legacyUncertain || schedule.inFlight != null || unresolved || consumed
        if (schedule.status != "needs_attention" && interrupted) {
            val stopped = schedule.copy(status = "needs_attention",
                issue = if (legacyUncertain) RecurringJournal.legacyReason else RecurringIssue(RecurringIssue.Code.interrupted),
                lastError = if (legacyUncertain) RecurringJournal.legacyMessage
                else "Noah was interrupted while sending this payment. Check your transaction history before resuming.")
            if (d.store.updateIf(schedule, stopped)) d.notify(emptyList(), stopped)
        }
    }
    private suspend fun requireStage(budget: RecurringBudget, caller: Job?) {
        currentCoroutineContext().ensureActive()
        check(live(budget, caller)) { "Recurring background work stopped." }
    }
    private fun live(budget: RecurringBudget, caller: Job?) = budget.available && caller?.isActive != false

    /** One execution pass: its trigger, budget and the generations its admission saw. */
    private class Pass(val manual: Boolean, val approval: Review?, val budget: RecurringBudget, val caller: Job?,
        val generation: String?, val durableGeneration: String?) {
        lateinit var initial: RecurringStore.State
        val live get() = budget.available && caller?.isActive != false
    }

    private suspend fun run(manual: Boolean, approval: Review?, budget: RecurringBudget, caller: Job?) {
        running = true; error = null
        var noWallet = false
        try {
            requireStage(budget, caller)
            val (generation, durableGeneration) = admitExecution(budget, caller)
            val pass = Pass(manual, approval, budget, caller, generation, durableGeneration)
            requireStage(budget, caller)
            for (entry in journalRecovery()) if (entry.terminal != null && !entry.released) settle(entry)
            refreshDates()
            val initial = boundState(); recoverMarkers(initial)
            pass.initial = initial
            val ids = initial.schedules.keys.sorted().filter { approval == null || approval.schedule.id == it }
            for (id in ids) {
                if (!pass.live || !runOccurrence(pass, id)) break
            }
            requireStage(budget, caller)
            refreshDates()
            requireStage(budget, caller)
            syncServer { requireStage(budget, caller) }
            requireStage(budget, caller)
            d.refresh()
        } catch (_: NoWallet) { noWallet = true }
        catch (@Suppress("TooGenericExceptionCaught") failure: Exception) { // any failure stops this pass and is shown
            error = failure.message ?: "Recurring sending is stopped."
        } finally { finishRun(noWallet, budget, caller) }
    }
    /** Binds this pass to the wallet and opens it, revalidating the binding around the wait for mutation. */
    private suspend fun admitExecution(budget: RecurringBudget, caller: Job?): Pair<String?, String?> = walletMutation {
        val admission = withContext(Dispatchers.IO) {
            d.allowed()
            val owner = d.binding()
            val generation = d.automationGeneration()
            // Revalidate the binding before authority, including after waiting for mutation.
            d.allowed()
            if (d.binding() != owner) throw NoWallet()
            executionBinding = owner
            generation to d.store.automationGeneration()
        }
        requireStage(budget, caller)
        d.ensureOpen()
        withContext(Dispatchers.IO) {
            d.allowed()
            if (d.binding() != executionBinding) throw NoWallet()
        }
        admission
    }
    private suspend fun journalRecovery(): List<RecurringJournal.Entry> =
        runCatching { storage { d.journal.load().values.toList() } }.getOrElse { failure ->
            recoverMarkers(boundState()); throw failure
        }
    private suspend fun finishRun(noWallet: Boolean, budget: RecurringBudget, caller: Job?) {
        try {
            if (!noWallet) reload()
            if (!noWallet && live(budget, caller) && currentCoroutineContext()[Job]?.isActive != false) {
                val visible = runCatching { storage { d.store.load().schedules.values.toList() } }.getOrDefault(emptyList())
                if (live(budget, caller) && currentCoroutineContext()[Job]?.isActive != false) d.notify(visible, null)
            }
        } finally { executionBinding = null; running = false }
    }
    /** One schedule's due occurrence; false stops the pass. */
    private suspend fun runOccurrence(pass: Pass, id: String): Boolean {
        val schedule = boundState().schedules[id] ?: return true
        if (pass.approval != null && (schedule != pass.approval.schedule || schedule.status != "needs_approval")) return true
        val approval = pass.approval
        val planning = if (approval != null) schedule.copy(status = "active") else schedule
        val plan = planning.plan(d.now(), d.zone(), pass.manual)
        if (plan.kind == "too_many_missed") {
            storage { d.store.updateIf(schedule,
                schedule.copy(status = "needs_attention", issue = RecurringIssue(RecurringIssue.Code.tooManyMissed),
                    lastError = "This schedule has more than 5,000 missed occurrences. Cancel it and create a schedule with a future first payment.")) }
            return true
        }
        if (plan.kind == "complete") { storage { d.store.updateIf(schedule, schedule.copy(status = "completed", nextRunAt = null)) }; return true }
        val approved = approval == null || approval.plan == plan
        if (plan.kind != "pay" || controller.locked || !approved) return true
        requireStage(pass.budget, pass.caller)
        return try {
            pay(pass, schedule, planning, plan)
        } catch (stale: NoWallet) { throw stale }
        catch (_: Exception) {
            notPrepared(pass, schedule, plan)
            true
        }
    }
    private suspend fun pay(pass: Pass, schedule: RecurringPayment, planning: RecurringPayment, plan: RecurringPayment.Plan): Boolean {
        val quoted = pass.approval?.let { it.payment to it.quote } ?: d.quote(schedule)
        if (!pass.live) return false
        val state = boundState()
        if (!pass.live) return false
        val unchanged = state.schedules[schedule.id] == schedule && planning.plan(d.now(), d.zone(), pass.manual) == plan &&
            quoted.second.walletIdentity == state.binding!!.wallet
        if (unchanged) submit(pass, schedule, plan, quoted, state.binding)
        return true
    }
    private suspend fun submit(pass: Pass, schedule: RecurringPayment, plan: RecurringPayment.Plan, quoted: Pair<SendPayment, SendQuote>,
        binding: RecurringStore.Binding) {
        val (payment, quote) = quoted
        require(payment.amount == schedule.amountSat && quote.amount == schedule.amountSat && !payment.max)
        if (pass.approval == null && stoppedForApproval(schedule, payment, quote)) return
        check(quote.checkpoint == null) { "Another Lightning payment is still in progress. Try again once it completes." }
        val entry = RecurringJournal.Entry(binding, schedule.id, plan, d.now(), schedule.nativeRevision, policy = schedule)
        val marked = schedule.copy(inFlight = RecurringPayment.InFlight(plan.occurrenceIndex!!, d.now()), updatedAt = d.now())
        if (!admit(pass, schedule, entry, marked)) return
        val job = controller.submit(payment, quote, entry.key) {
            try { checkSubmission(pass, entry, marked, payment, quote) }
            catch (_: Exception) { throw SendNotSubmitted(SendFailure.Reason.recurringStopped) }
        }
        if (job == null) { markUncertain(entry); return }
        job.await()
        settle(entry, retainReceipt = pass.approval != null, notifyOriginal = true)
    }
    /** A fee over the automatic policy stops the schedule for approval. */
    private suspend fun stoppedForApproval(schedule: RecurringPayment, payment: SendPayment, quote: SendQuote): Boolean {
        val reason = RecurringFeePolicy.approvalIssue(payment.amount, payment.method, quote.fee, quote.lightningFeeSchedule) ?: return false
        val stopped = schedule.copy(status = "needs_approval", issue = reason,
            lastError = RecurringFeePolicy.approvalReason(payment.amount, payment.method, quote.fee,
                quote.lightningFeeSchedule), retryNotBefore = null, updatedAt = d.now())
        if (storage { d.store.updateIf(schedule, stopped) }) d.notify(emptyList(), stopped)
        return true
    }
    /** Journals the occurrence and marks it in flight, unless the schedule changed or the pass stopped. */
    private suspend fun admit(pass: Pass, schedule: RecurringPayment, entry: RecurringJournal.Entry, marked: RecurringPayment): Boolean =
        storage { synchronized(RecurringStore.lock) { synchronized(d.journal) {
            if (boundStateNow().schedules[entry.scheduleID] != schedule || !pass.live) false else {
                check(!d.journal.consumed(entry.binding, entry.scheduleID, entry.plan.scheduledFor!!, schedule.nativeRevision, entry.plan.occurrenceIndex))
                d.journal.save(entry)
                d.store.upsert(marked)
                true
            }
        } } }
    /** Send's last check before submitting: the same wallet, generations, plan, policy and fee approval. */
    private fun checkSubmission(pass: Pass, entry: RecurringJournal.Entry, marked: RecurringPayment, payment: SendPayment, quote: SendQuote) {
        val id = entry.scheduleID
        d.allowed()
        check(readAdmission() == entry.binding && pass.generation == d.automationGeneration())
        val current = d.store.load()
        val currentPlan = marked.copy(inFlight = null, status = "active").plan(d.now(), d.zone(), pass.manual)
        check(pass.live && currentPlan == entry.plan && pass.generation == d.automationGeneration() &&
            pass.durableGeneration == d.store.automationGeneration() && marked.amountSat <= RecurringPayment.maximumAmount)
        check(current.binding == d.binding() && current.binding == entry.binding && current.schedules[id] == marked)
        check(!d.journal.consumed(entry.binding, id, entry.plan.scheduledFor!!, entry.policyRevision, entry.plan.occurrenceIndex))
        check(pass.approval != null || RecurringFeePolicy.evaluate(payment.amount, payment.method, quote.fee,
            quote.lightningFeeSchedule).autoPay)
    }
    /** An occurrence that failed before or around submission: uncertain if journaled, else a failure. */
    private suspend fun notPrepared(pass: Pass, schedule: RecurringPayment, plan: RecurringPayment.Plan) {
        val entry = storage { d.journal.load()[schedule.id] }?.takeIf {
            !it.resolved && it.plan == plan && it.binding == pass.initial.binding && it.policyRevision == schedule.nativeRevision
        }
        if (entry != null) markUncertain(entry)
        else {
            val result = schedule.failure(plan, "This payment could not be prepared. No payment was submitted.", true, d.now())
            val updated = storage { d.store.updateIf(schedule, result) }
            val notable = result.status == "active" && (result.consecutiveFailures == 1 || pass.manual)
            if (updated && notable) d.notify(emptyList(), result)
        }
    }
    private suspend fun markUncertain(entry: RecurringJournal.Entry, message: String? = controller.message) = storage {
        synchronized(RecurringStore.lock) {
            val saved = d.journal.load()[entry.scheduleID]
            if (saved == null || saved.resolved || !saved.sameOccurrence(entry)) return@storage
            if (d.binding() != entry.binding) return@storage
            val state = d.store.load()
            if (state.binding != entry.binding) return@storage
            val latest = state.schedules[entry.scheduleID] ?: return@storage
            if (latest.nativeRevision != entry.policyRevision) return@storage
            val stopped = latest.copy(status = "needs_attention", issue = RecurringIssue(RecurringIssue.Code.uncertain), retryNotBefore = null,
                lastError = message ?: SendFailure.Reason.unresolved.message)
            d.store.upsert(stopped); d.notify(emptyList(), stopped)
        }
    }
    private fun RecurringJournal.Entry.sameOccurrence(other: RecurringJournal.Entry) =
        binding == other.binding && plan == other.plan && markedAt == other.markedAt && policyRevision == other.policyRevision
    private suspend fun settle(entry: RecurringJournal.Entry, retainReceipt: Boolean = false, notifyOriginal: Boolean = false) {
        val evidence = storage { d.journal.load()[entry.scheduleID]?.takeIf { it.sameOccurrence(entry) }?.terminal } ?: return
        if (evidence.outcome == RecurringJournal.Outcome.uncertain) { markUncertain(entry, evidence.message); return }
        if (storage { d.binding() == entry.binding }) controller.releaseRecurring(evidence.attempt.recurringAttemptID, evidence.generation,
            evidence.walletGeneration, retainReceipt)
        val notification = storage { applySettlement(entry, evidence, notifyOriginal) }
        notification?.let { d.notify(emptyList(), it) }
    }
    private fun applySettlement(entry: RecurringJournal.Entry, evidence: RecurringJournal.TerminalEvidence,
        notifyOriginal: Boolean = false): RecurringPayment? =
        synchronized(RecurringStore.lock) { synchronized(d.journal) { applySettlementLocked(entry, evidence, notifyOriginal) } }
    private fun applySettlementLocked(entry: RecurringJournal.Entry, evidence: RecurringJournal.TerminalEvidence,
        notifyOriginal: Boolean): RecurringPayment? {
        if (d.binding() != entry.binding) return null
        var saved = d.journal.load()[entry.scheduleID] ?: return null
        if (saved.terminal?.settlementID != evidence.settlementID || saved.released) return null
        val state = if (!saved.historyApplied) d.store.settlementState() else null
        if (state != null && state.binding != entry.binding) return null
        if (!saved.resolved) {
            saved = saved.copy(resolved = true, consumed = evidence.paid)
            d.journal.save(saved)
        }
        var notification: RecurringPayment? = null
        if (!saved.historyApplied) {
            notification = applyHistory(entry, evidence, checkNotNull(state), notifyOriginal)
            saved = saved.copy(historyApplied = true, policyApplied = true)
            d.journal.save(saved)
        }
        d.journal.save(saved.copy(released = true))
        return notification
    }
    private fun applyHistory(entry: RecurringJournal.Entry, evidence: RecurringJournal.TerminalEvidence, state: RecurringStore.State,
        notifyOriginal: Boolean): RecurringPayment? {
        val latest = state.schedules[entry.scheduleID]
        val current = latest?.takeIf { it.nativeRevision == entry.policyRevision }
        return if (!d.store.settlementApplied(entry.scheduleID, evidence.settlementID) && current != null) {
            settled(current, entry, evidence).also { d.store.upsert(it, settlementKey = evidence.settlementID) }
        } else if (notifyOriginal && evidence.outcome == RecurringJournal.Outcome.succeeded && entry.policy?.nativeRevision == entry.policyRevision) {
            entry.policy?.copy(status = "active")?.success(entry.plan, evidence.recordedAt, d.zone())
        } else null
    }
    private fun settled(latest: RecurringPayment, entry: RecurringJournal.Entry, evidence: RecurringJournal.TerminalEvidence) = when (evidence.outcome) {
        RecurringJournal.Outcome.succeeded -> {
            val advanced = latest.copy(status = "active").success(entry.plan, evidence.recordedAt, d.zone())
            if (latest.status !in listOf("active", "needs_approval") && advanced.status == "active")
                advanced.copy(status = latest.status, consecutiveFailures = latest.consecutiveFailures,
                    lastError = latest.lastError, issue = latest.issue, retryNotBefore = latest.retryNotBefore) else advanced
        }
        RecurringJournal.Outcome.alreadyPaid -> latest.failure(entry.plan,
            "This invoice was already paid. This occurrence was not sent again. Check the recipient before resuming.", false, evidence.recordedAt,
            RecurringIssue(RecurringIssue.Code.alreadyPaid))
        else -> latest.failure(entry.plan, evidence.message ?: "No payment was submitted.", true, evidence.recordedAt)
    }
    suspend fun checkStatus(id: String) {
        if (running) return
        try {
            val entry = storage { d.journal.load()[id] } ?: return
            // Status reconciliation may add exact evidence; settlement only reads the occurrence.
            if (entry.terminal == null || entry.terminal.outcome == RecurringJournal.Outcome.uncertain) controller.checkStatus()
            settle(entry)
        } catch (_: Exception) { error = "Unable to check this payment. Sending remains stopped." }
        reload()
    }
    suspend fun review(id: String): Review {
        check(!running && !controller.locked) { "This payment may have been sent. Check its status before making another payment." }
        ensureOpen()
        val schedule = boundState().schedules.getValue(id)
        check(schedule.status == "needs_approval")
        val plan = schedule.copy(status = "active").plan(d.now(), d.zone(), true)
        check(plan.kind == "pay") { "This payment is no longer due." }
        val (payment, quote) = d.quote(schedule)
        check(boundState().schedules[id] == schedule)
        return Review(schedule, plan, payment, quote)
    }
    suspend fun save(input: RecurringPayment, editing: Boolean) {
        storage { synchronized(RecurringStore.lock) {
            val state = boundStateNow()
            val old = state.schedules[input.id]
            val reapproval = editing && old?.requiresPolicyApproval == true
            if (reapproval) requireReapprovalSafe(input.id)
            if (editing) {
                check(!d.journal.legacyUncertain(d.binding(), input.id)) { RecurringJournal.legacyMessage }
                check(old != null && (reapproval || (old.inFlight == null && old.status != "needs_attention"))) {
                    "Check this payment's outcome before editing it."
                }
            }
            else check(state.schedules.size < 50 && old == null) { "You've reached the maximum number of recurring payments." }
            input.validateInput(d.now())
            val schedule = (if (reapproval) input.resume(d.now(), d.zone()) else input).copy(nativeRevision = UUID.randomUUID().toString())
            if (editing) check(d.store.updateIf(old!!, schedule, reapproval)) else d.store.upsert(schedule)
        } }
        changed()
    }
    suspend fun pause(id: String) {
        storage { synchronized(RecurringStore.lock) {
            val schedule = d.store.load().schedules[id] ?: return@storage
            if (schedule.status != "completed") d.store.upsert(schedule.copy(status = if (schedule.status == "needs_attention") schedule.status else "paused",
                updatedAt = d.now()))
        } }
        changed()
    }
    suspend fun resume(id: String) {
        storage { synchronized(RecurringStore.lock) {
            boundStateNow()
            check(!d.journal.legacyUncertain(d.binding(), id)) { RecurringJournal.legacyMessage }
            val schedule = d.store.load().schedules[id] ?: return@storage
            check(!schedule.unsupportedStorage) { RecurringPayment.unsupportedMessage }
            check(schedule.amountSat <= RecurringPayment.maximumAmount) { RecurringPayment.amountLimitMessage }
            val reapproval = schedule.requiresPolicyApproval
            if (reapproval) requireReapprovalSafe(id)
            check(reapproval || (schedule.inFlight == null && schedule.status != "needs_attention")) {
                "Check your transaction history, then confirm the destructive override before resuming."
            }
            if (schedule.status != "completed") d.store.upsert(schedule.resume(d.now(),
                d.zone()).let { if (reapproval) it.copy(nativeRevision = UUID.randomUUID().toString()) else it }, reapproval)
        } }
        changed()
    }
    private fun requireReapprovalSafe(id: String) {
        val entry = d.journal.load()[id]
        check(entry == null || entry.resolved) { "Check this payment's outcome before approving it again." }
        check(!controller.locked) { "Acknowledge the current payment before approving it again." }
    }
    suspend fun cancel(id: String) { storage { d.store.remove(id) }; changed() }
    suspend fun overrideAndResume(id: String, confirmed: Boolean) {
        if (!confirmed) return
        check(!running); boundState()
        val pending = storage { d.journal.load()[id] }
        if (pending?.terminal != null && pending.terminal.outcome != RecurringJournal.Outcome.uncertain) settle(pending)
        val schedule = storage { d.store.load().schedules[id] } ?: return
        check(!schedule.unsupportedStorage) { RecurringPayment.unsupportedMessage }
        check(schedule.amountSat <= RecurringPayment.maximumAmount) { RecurringPayment.amountLimitMessage }
        val entry = storage { d.journal.load()[id] }
        rejectLegacyUncertainty(id)
        // Audit the exact warning before either lock can be removed, including legacy RN markers.
        storage { d.audit(schedule, entry, overrideWarning, overrideConfirmation) }
        if (entry != null && controller.attempt?.recurringOccurrenceKey == entry.key) {
            controller.startNewPaymentAfterHistoryCheck(true, overrideWarning, overrideConfirmation)
            check(!controller.locked) { "Unable to save the override confirmation. Sending remains locked." }
        }
        val notification = storage { synchronized(RecurringStore.lock) { synchronized(d.journal) { skipAndResume(id, schedule, entry) } } }
        notification?.let { d.notify(emptyList(), it) }
        changed()
    }
    /** Called holding both locks: settles or consumes the occurrence the user overrode, then resumes. */
    private fun skipAndResume(id: String, schedule: RecurringPayment, entry: RecurringJournal.Entry?): RecurringPayment? {
        check(boundStateNow().schedules[id] == schedule)
        // Clearing Send can repair its failed handoff. Settle that proof before any skip.
        val current = d.journal.load()[id]
        check(current?.binding == entry?.binding && current?.plan == entry?.plan &&
            current?.markedAt == entry?.markedAt && current?.policyRevision == entry?.policyRevision)
        check(current == null || current.binding == d.binding())
        val evidence = current?.terminal
        val notification = if (current != null && evidence != null && evidence.outcome != RecurringJournal.Outcome.uncertain) {
            applySettlement(current, evidence).also { check(d.journal.load()[id]?.released == true) }
        } else {
            if (current != null) d.journal.save(current.copy(resolved = true, consumed = true, historyApplied = evidence != null,
                released = evidence != null))
            null
        }
        val latest = d.store.load().schedules.getValue(id)
        check(d.store.updateIf(latest, latest.resume(d.now(), d.zone())))
        return notification
    }
    private suspend fun rejectLegacyUncertainty(id: String) = storage {
        check(!d.journal.legacyUncertain(d.binding(), id)) { RecurringJournal.legacyMessage }
    }
    private suspend fun refreshDates() = storage {
        for (schedule in boundStateNow().schedules.values.filter { it.status != "completed" && !it.unsupportedStorage && !it.requiresPolicyApproval }) {
            val next = schedule.next(schedule.nextOccurrenceIndex, d.zone())
            if (next != schedule.nextRunAt) d.store.updateIf(schedule, schedule.copy(nextRunAt = next))
        }
    }
    suspend fun timeChanged() { try { refreshDates(); changed() } catch (_: Exception) { reload() } }
    private suspend fun changed() { reload(); syncServer(); d.notify(schedules, null) }
    suspend fun syncServer(stage: suspend () -> Unit = {}) {
        syncTask?.takeIf { !it.isCompleted }?.let { it.await(); return }
        syncTask = null
        val work = scope.async(start = CoroutineStart.LAZY) {
            try {
                repeat(20) {
                    refreshDates()
                    val entries = entries(boundState().schedules.values.toList())
                    storage { d.store.setPending(true) }; stage(); d.sync(entries); stage(); refreshDates()
                    val synced = storage { synchronized(RecurringStore.lock) {
                        if (entries(boundStateNow().schedules.values.toList()) == entries) { d.store.setPending(false); true } else false
                    } }
                    if (synced) { syncPending = false; return@async }
                }
            } catch (_: Exception) { syncPending = true }
        }
        syncTask = work; work.start()
        try { work.await() } finally { if (work.isCompleted && syncTask === work) syncTask = null }
    }
    companion object {
        val overrideWarning = SendOverrideAudit.warningText + "\n\nResuming skips all occurrences that are already due."
        const val overrideConfirmation = "Resume anyway"
        fun entries(schedules: List<RecurringPayment>) = schedules.filter { it.status == "active" && it.inFlight == null && it.nextRunAt != null }
            .sortedBy { it.id }.map { RecurringPaymentScheduleEntry(Math.floorDiv(it.nextRunAt!!, 1000), it.id) }
    }
}

/** Monotonic deadline is independent of settlement; expired pushes cannot start a new payment. */
class RecurringBudget(private val expiresAt: Long? = null, private val clock: () -> Long = System::nanoTime) {
    internal var admission: () -> Boolean = { true }
    private val revoked = java.util.concurrent.atomic.AtomicBoolean(false)
    fun revoke() { revoked.set(true) }
    val available get() = !revoked.get() && admission() && (expiresAt == null || clock() < expiresAt)
    companion object { fun push() = RecurringBudget(System.nanoTime() + 25_000_000_000) }
}
