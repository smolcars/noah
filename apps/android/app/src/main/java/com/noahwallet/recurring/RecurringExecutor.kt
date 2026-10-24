package com.noahwallet.recurring

import androidx.compose.runtime.*
import com.noahwallet.api.generated.models.RecurringPaymentScheduleEntry
import com.noahwallet.send.*
import kotlinx.coroutines.*
import java.util.TimeZone
import java.util.UUID

/** Joins foreground/push/manual triggers. Only the Send controller can submit money. */
class RecurringExecutor(private val d: Dependencies,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)) {
    internal class NoWallet : Exception()
    class Dependencies(val store: RecurringStore, val journal: RecurringJournal, val controller: SendController,
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
        if (!d.journal.file.parentFile!!.isDirectory || d.binding() != owner || d.automationGeneration() != generation ||
            executionBinding != null && executionBinding != owner) throw NoWallet()
        d.journal.bind(owner)
        return owner
    }
    private suspend fun <T> storage(block: () -> T): T = walletMutation {
        withContext(Dispatchers.IO) { readAdmission(); block() }
    }
    private suspend fun ensureOpen() = walletMutation { d.ensureOpen() }
    suspend fun reload() {
        try { val state = storage { d.store.load() }; schedules = state.schedules.values.sortedBy { it.nextRunAt ?: Long.MAX_VALUE }; syncPending = state.pending }
        catch (_: NoWallet) { schedules = emptyList(); syncPending = false }
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
        if (state.binding == null || state.binding != owner && state.schedules.isEmpty()) { d.store.bind(owner); state = d.store.load() }
        check(state.binding == owner) { "These recurring payments belong to a previous wallet. They are preserved but cannot run. Cancel them and create new schedules for this wallet." }
        return state
    }
    private suspend fun recoverMarkers(state: RecurringStore.State) = storage {
        val entries = try { d.journal.load() } catch (failure: Exception) {
            // Corrupt shared evidence cannot prove any policy in this wallet is safe.
            for (schedule in state.schedules.values.filter { !it.requiresPolicyApproval }) {
                d.store.updateIf(schedule, schedule.copy(status = "needs_attention", lastError = RecurringJournal.invalidMessage, issue = RecurringIssue(RecurringIssue.Code.invalidJournal)))
            }
            throw failure
        }
        for (schedule in state.schedules.values.filter { !it.requiresPolicyApproval }) {
            val planning = if (schedule.status == "needs_approval") schedule.copy(status = "active") else schedule
            val plan = planning.plan(d.now(), d.zone(), true)
            val legacyUncertain = d.journal.legacyUncertain(state.binding!!, schedule.id)
            val consumed = plan.scheduledFor?.let { d.journal.consumed(state.binding, schedule.id, it, schedule.nativeRevision, plan.occurrenceIndex) } ?: false
            if (schedule.status != "needs_attention" && (legacyUncertain || schedule.inFlight != null || entries[schedule.id]?.let { !it.resolved && it.binding == state.binding } == true || consumed)) {
                val stopped = schedule.copy(status = "needs_attention", issue = if (legacyUncertain) RecurringJournal.legacyReason else RecurringIssue(RecurringIssue.Code.interrupted), lastError = if (legacyUncertain) RecurringJournal.legacyMessage else "Noah was interrupted while sending this payment. Check your transaction history before resuming.")
                if (d.store.updateIf(schedule, stopped)) d.notify(emptyList(), stopped)
            }
        }
    }
    private suspend fun requireStage(budget: RecurringBudget, caller: Job?) {
        currentCoroutineContext().ensureActive()
        check(budget.available && caller?.isActive != false) { "Recurring background work stopped." }
    }
    private suspend fun run(manual: Boolean, approval: Review?, budget: RecurringBudget, caller: Job?) {
        running = true; error = null
        var noWallet = false
        try {
            requireStage(budget, caller)
            val (generation, durableGeneration) = walletMutation {
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
            requireStage(budget, caller)
            val recovery = runCatching { storage { d.journal.load().values.toList() } }.getOrElse { failure ->
                recoverMarkers(boundState()); throw failure
            }
            for (entry in recovery) if (entry.terminal != null && !entry.released) settle(entry)
            refreshDates()
            val initial = boundState(); recoverMarkers(initial)
            for (id in initial.schedules.keys.sorted()) {
                if (approval != null && approval.schedule.id != id) continue
                if (!budget.available || caller?.isActive == false) break
                val schedule = boundState().schedules[id] ?: continue
                if (approval != null && (schedule != approval.schedule || schedule.status != "needs_approval")) continue
                val planning = if (approval != null) schedule.copy(status = "active") else schedule
                val plan = planning.plan(d.now(), d.zone(), manual)
                if (plan.kind == "too_many_missed") {
                    storage { d.store.updateIf(schedule, schedule.copy(status = "needs_attention", issue = RecurringIssue(RecurringIssue.Code.tooManyMissed), lastError = "This schedule has more than 5,000 missed occurrences. Cancel it and create a schedule with a future first payment.")) }; continue
                }
                if (plan.kind == "complete") { storage { d.store.updateIf(schedule, schedule.copy(status = "completed", nextRunAt = null)) }; continue }
                if (plan.kind != "pay" || controller.locked || approval != null && approval.plan != plan) continue
                requireStage(budget, caller)
                try {
                    val (payment, quote) = approval?.let { it.payment to it.quote } ?: d.quote(schedule)
                    if (!budget.available || caller?.isActive == false) break
                    val state = boundState()
                    if (!budget.available || caller?.isActive == false) break
                    if (state.schedules[id] != schedule || planning.plan(d.now(), d.zone(), manual) != plan || quote.walletIdentity != state.binding!!.wallet) continue
                    require(payment.amount == schedule.amountSat && quote.amount == schedule.amountSat && !payment.max)
                    if (approval == null) {
                        val reason = RecurringFeePolicy.approvalIssue(payment.amount, payment.method, quote.fee, quote.lightningFeeSchedule)
                        if (reason != null) {
                            val stopped = schedule.copy(status = "needs_approval", issue = reason, lastError = RecurringFeePolicy.approvalReason(payment.amount, payment.method, quote.fee, quote.lightningFeeSchedule), retryNotBefore = null, updatedAt = d.now())
                            if (storage { d.store.updateIf(schedule, stopped) }) d.notify(emptyList(), stopped)
                            continue
                        }
                    }
                    check(quote.checkpoint == null) { "Another Lightning payment is still in progress. Try again once it completes." }
                    val entry = RecurringJournal.Entry(state.binding, id, plan, d.now(), schedule.nativeRevision, policy = schedule)
                    val marked = schedule.copy(inFlight = RecurringPayment.InFlight(plan.occurrenceIndex!!, d.now()), updatedAt = d.now())
                    val admitted = storage { synchronized(RecurringStore.lock) { synchronized(d.journal) {
                        if (boundStateNow().schedules[id] != schedule || !budget.available || caller?.isActive == false) false else {
                            check(!d.journal.consumed(state.binding, id, plan.scheduledFor!!, schedule.nativeRevision, plan.occurrenceIndex))
                            d.journal.save(entry)
                            d.store.upsert(marked)
                            true
                        }
                    } } }
                    if (!admitted) continue
                    val job = controller.submit(payment, quote, entry.key) {
                        try {
                            d.allowed()
                            check(readAdmission() == entry.binding && generation == d.automationGeneration())
                            val current = d.store.load()
                            val currentPlan = marked.copy(inFlight = null, status = "active").plan(d.now(), d.zone(), manual)
                            check(budget.available && caller?.isActive != false && currentPlan == entry.plan && generation == d.automationGeneration() && durableGeneration == d.store.automationGeneration() && marked.amountSat <= RecurringPayment.maximumAmount)
                            check(current.binding == d.binding() && current.binding == entry.binding && current.schedules[id] == marked)
                            check(!d.journal.consumed(entry.binding, id, entry.plan.scheduledFor!!, entry.policyRevision, entry.plan.occurrenceIndex))
                            check(approval != null || RecurringFeePolicy.evaluate(payment.amount, payment.method, quote.fee, quote.lightningFeeSchedule).autoPay)
                        } catch (_: Exception) { throw SendNotSubmitted(SendFailure.Reason.recurringStopped) }
                    }
                    if (job == null) { markUncertain(entry); continue }
                    job.await()
                    settle(entry, retainReceipt = approval != null, notifyOriginal = true)
                } catch (stale: NoWallet) { throw stale }
                catch (_: Exception) {
                    val entry = storage { d.journal.load()[id] }
                    if (entry != null && !entry.resolved && entry.plan == plan && entry.binding == initial.binding && entry.policyRevision == schedule.nativeRevision) markUncertain(entry)
                    else {
                        val result = schedule.failure(plan, "This payment could not be prepared. No payment was submitted.", true, d.now())
                        if (storage { d.store.updateIf(schedule, result) } && result.status == "active" && (result.consecutiveFailures == 1 || manual)) d.notify(emptyList(), result)
                    }
                }
            }
            requireStage(budget, caller)
            refreshDates()
            requireStage(budget, caller)
            syncServer { requireStage(budget, caller) }
            requireStage(budget, caller)
            d.refresh()
        } catch (_: NoWallet) { noWallet = true }
        catch (failure: Exception) { error = failure.message ?: "Recurring sending is stopped." }
        finally { try {
            if (!noWallet) reload()
            if (!noWallet && budget.available && caller?.isActive != false && currentCoroutineContext().isActive) {
                val visible = runCatching { storage { d.store.load().schedules.values.toList() } }.getOrDefault(emptyList())
                if (budget.available && caller?.isActive != false && currentCoroutineContext().isActive) d.notify(visible, null)
            }
        } finally { executionBinding = null; running = false } }
    }
    private suspend fun markUncertain(entry: RecurringJournal.Entry, message: String? = controller.message) = storage {
        synchronized(RecurringStore.lock) {
            val saved = d.journal.load()[entry.scheduleID]
            if (saved == null || saved.resolved || saved.binding != entry.binding || saved.plan != entry.plan || saved.policyRevision != entry.policyRevision || saved.markedAt != entry.markedAt) return@storage
            if (d.binding() != entry.binding) return@storage
            val state = d.store.load()
            if (state.binding != entry.binding) return@storage
            val latest = state.schedules[entry.scheduleID] ?: return@storage
            if (latest.nativeRevision != entry.policyRevision) return@storage
            val stopped = latest.copy(status = "needs_attention", issue = RecurringIssue(RecurringIssue.Code.uncertain), retryNotBefore = null, lastError = message ?: SendFailure.Reason.unresolved.message)
            d.store.upsert(stopped); d.notify(emptyList(), stopped)
        }
    }
    private suspend fun settle(entry: RecurringJournal.Entry, retainReceipt: Boolean = false, notifyOriginal: Boolean = false) {
        val evidence = storage { d.journal.load()[entry.scheduleID]?.takeIf { it.binding == entry.binding && it.plan == entry.plan && it.markedAt == entry.markedAt && it.policyRevision == entry.policyRevision }?.terminal } ?: return
        if (evidence.outcome == RecurringJournal.Outcome.uncertain) { markUncertain(entry, evidence.message); return }
        if (storage { d.binding() == entry.binding }) controller.releaseRecurring(evidence.attempt.recurringAttemptID, evidence.generation, evidence.walletGeneration, retainReceipt)
        val notification = storage { applySettlement(entry, evidence, notifyOriginal) }
        notification?.let { d.notify(emptyList(), it) }
    }
    private fun applySettlement(entry: RecurringJournal.Entry, evidence: RecurringJournal.TerminalEvidence, notifyOriginal: Boolean = false): RecurringPayment? {
        synchronized(RecurringStore.lock) { synchronized(d.journal) {
            var notification: RecurringPayment? = null
            if (d.binding() != entry.binding) return null
            var saved = d.journal.load()[entry.scheduleID] ?: return null
            if (saved.terminal?.settlementID != evidence.settlementID) return null
            if (saved.released) return null
            val state = if (!saved.historyApplied) d.store.settlementState() else null
            if (state != null && state.binding != entry.binding) return null
            if (!saved.resolved) {
                saved = saved.copy(resolved = true, consumed = evidence.paid)
                d.journal.save(saved)
            }
            if (!saved.historyApplied) {
                val latest = checkNotNull(state).schedules[entry.scheduleID]
                if (!d.store.settlementApplied(entry.scheduleID, evidence.settlementID) && latest != null && latest.nativeRevision == entry.policyRevision) {
                    val updated = when (evidence.outcome) {
                        RecurringJournal.Outcome.succeeded -> {
                            val advanced = latest.copy(status = "active").success(entry.plan, evidence.recordedAt, d.zone())
                            if (latest.status !in listOf("active", "needs_approval") && advanced.status == "active")
                                advanced.copy(status = latest.status, consecutiveFailures = latest.consecutiveFailures,
                                    lastError = latest.lastError, issue = latest.issue, retryNotBefore = latest.retryNotBefore) else advanced
                        }
                        RecurringJournal.Outcome.alreadyPaid -> latest.failure(entry.plan, "This invoice was already paid. This occurrence was not sent again. Check the recipient before resuming.", false, evidence.recordedAt, RecurringIssue(RecurringIssue.Code.alreadyPaid))
                        else -> latest.failure(entry.plan, evidence.message ?: "No payment was submitted.", true, evidence.recordedAt)
                    }
                    d.store.upsert(updated, settlementKey = evidence.settlementID)
                    notification = updated
                } else if (notifyOriginal && evidence.outcome == RecurringJournal.Outcome.succeeded && entry.policy?.nativeRevision == entry.policyRevision) {
                    notification = entry.policy?.copy(status = "active")?.success(entry.plan, evidence.recordedAt, d.zone())
                }
                saved = saved.copy(historyApplied = true, policyApplied = true)
                d.journal.save(saved)
            }
            d.journal.save(saved.copy(released = true))
            return notification
        } }
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
                check(old != null && (reapproval || old.inFlight == null && old.status != "needs_attention")) { "Check this payment's outcome before editing it." }
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
            if (schedule.status != "completed") d.store.upsert(schedule.copy(status = if (schedule.status == "needs_attention") schedule.status else "paused", updatedAt = d.now()))
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
            check(reapproval || schedule.inFlight == null && schedule.status != "needs_attention") { "Check your transaction history, then confirm the destructive override before resuming." }
            if (schedule.status != "completed") d.store.upsert(schedule.resume(d.now(), d.zone()).let { if (reapproval) it.copy(nativeRevision = UUID.randomUUID().toString()) else it }, reapproval)
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
        val notification = storage { synchronized(RecurringStore.lock) { synchronized(d.journal) {
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
                if (current != null) d.journal.save(current.copy(resolved = true, consumed = true, historyApplied = evidence != null, released = evidence != null))
                null
            }
            val latest = d.store.load().schedules.getValue(id)
            check(d.store.updateIf(latest, latest.resume(d.now(), d.zone())))
            notification
        } } }
        notification?.let { d.notify(emptyList(), it) }
        changed()
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
