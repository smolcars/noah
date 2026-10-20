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
        val audit: (RecurringPayment, RecurringJournal.Entry?, String, String) -> Unit = { _, _, _, _ -> error("Audit unavailable") },
        val refresh: suspend () -> Unit = {})
    init { d.store.protectPolicies(java.io.File("${d.journal.file.path}.policies"), binding = d.binding) }
    data class Review(val schedule: RecurringPayment, val plan: RecurringPayment.Plan, val payment: SendPayment, val quote: SendQuote)
    var schedules by mutableStateOf<List<RecurringPayment>>(emptyList()); private set
    var error by mutableStateOf<String?>(null); private set
    var running by mutableStateOf(false); private set
    var syncPending by mutableStateOf(false); private set
    val controller get() = d.controller
    private var task: Deferred<Unit>? = null
    private var syncTask: Deferred<Unit>? = null
    private suspend fun <T> storage(block: () -> T): T = withContext(Dispatchers.IO) { block() }
    suspend fun reload() {
        try { val state = storage { d.store.load() }; schedules = state.schedules.values.sortedBy { it.nextRunAt ?: Long.MAX_VALUE }; syncPending = state.pending }
        catch (_: Exception) { error = "Recurring payment data couldn't be read. Your saved schedules have been preserved. Sending is stopped." }
    }
    suspend fun execute(manual: Boolean = false, approval: Review? = null, budget: RecurringBudget = RecurringBudget()) {
        task?.takeIf { !it.isCompleted }?.let { it.await(); return }
        task = null
        val caller = currentCoroutineContext()[Job]
        val work = scope.async(start = CoroutineStart.LAZY) { run(manual, approval, budget, caller) }
        task = work; work.start()
        try { work.await() } finally { if (work.isCompleted && task === work) task = null }
    }
    private suspend fun boundState(): RecurringStore.State = storage { boundStateNow() }
    private fun boundStateNow(): RecurringStore.State {
        d.allowed()
        d.store.automationGeneration()
        val owner = d.binding()
        var state = d.store.load()
        if (state.binding == null || state.binding != owner && state.schedules.isEmpty()) { d.store.bind(owner); state = d.store.load() }
        check(state.binding == owner) { "These recurring payments belong to a previous wallet. They are preserved but cannot run. Cancel them and create new schedules for this wallet." }
        return state
    }
    private suspend fun recoverMarkers(state: RecurringStore.State) = storage {
        val entries = try { d.journal.load() } catch (failure: Exception) {
            // Corrupt shared evidence cannot prove any policy in this wallet is safe.
            for (schedule in state.schedules.values.filter { it.lastError != RecurringPolicyLedger.message }) {
                d.store.updateIf(schedule, schedule.copy(status = "needs_attention", lastError = RecurringJournal.invalidMessage))
            }
            throw failure
        }
        for (schedule in state.schedules.values.filter { it.lastError != RecurringPolicyLedger.message }) {
            val planning = if (schedule.status == "needs_approval") schedule.copy(status = "active") else schedule
            val plan = planning.plan(d.now(), d.zone(), true)
            val legacyUncertain = d.journal.legacyUncertain(state.binding!!, schedule.id)
            val consumed = plan.scheduledFor?.let { d.journal.consumed(state.binding, schedule.id, it, schedule.nativeRevision, plan.occurrenceIndex) } ?: false
            if (schedule.status != "needs_attention" && (legacyUncertain || schedule.inFlight != null || entries[schedule.id]?.let { !it.resolved && it.binding == state.binding } == true || consumed)) {
                val stopped = schedule.copy(status = "needs_attention", lastError = if (legacyUncertain) RecurringJournal.legacyMessage else "Noah was interrupted while sending this payment. Check your transaction history before resuming.")
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
        try {
            requireStage(budget, caller)
            storage { d.allowed() }
            val generation = storage { d.automationGeneration() }
            val durableGeneration = storage { d.store.automationGeneration() }
            requireStage(budget, caller)
            d.ensureOpen()
            requireStage(budget, caller)
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
                    storage { d.store.updateIf(schedule, schedule.copy(status = "needs_attention", lastError = "This schedule has more than 5,000 missed occurrences. Cancel it and create a schedule with a future first payment.")) }; continue
                }
                if (plan.kind == "complete") { storage { d.store.updateIf(schedule, schedule.copy(status = "completed", nextRunAt = null)) }; continue }
                if (plan.kind != "pay" || controller.locked || approval != null && approval.plan != plan) continue
                try {
                    val (payment, quote) = approval?.let { it.payment to it.quote } ?: d.quote(schedule)
                    if (!budget.available || caller?.isActive == false) break
                    val state = boundState()
                    if (state.schedules[id] != schedule || planning.plan(d.now(), d.zone(), manual) != plan || quote.walletIdentity != state.binding!!.wallet) continue
                    require(payment.amount == schedule.amountSat && quote.amount == schedule.amountSat && !payment.max)
                    if (approval == null) {
                        val reason = RecurringFeePolicy.approvalReason(payment.amount, payment.method, quote.fee, quote.lightningFeeSchedule)
                        if (reason != null) {
                            val stopped = schedule.copy(status = "needs_approval", lastError = reason, retryNotBefore = null, updatedAt = d.now())
                            if (storage { d.store.updateIf(schedule, stopped) }) d.notify(emptyList(), stopped)
                            continue
                        }
                    }
                    check(quote.checkpoint == null) { "Another Lightning payment is still in progress. Try again once it completes." }
                    storage { check(!d.journal.consumed(state.binding, id, plan.scheduledFor!!, schedule.nativeRevision, plan.occurrenceIndex)) }
                    val entry = RecurringJournal.Entry(state.binding, id, plan, d.now(), schedule.nativeRevision)
                    storage { d.journal.save(entry) }
                    val marked = schedule.copy(inFlight = RecurringPayment.InFlight(plan.occurrenceIndex!!, d.now()), updatedAt = d.now())
                    if (!storage { d.store.updateIf(schedule, marked) }) { storage { d.journal.save(entry.copy(resolved = true)) }; continue }
                    val job = controller.submit(payment, quote, entry.key) {
                        try {
                            d.allowed()
                            val current = d.store.load()
                            val currentPlan = marked.copy(inFlight = null, status = "active").plan(d.now(), d.zone(), manual)
                            check(budget.available && caller?.isActive != false && currentPlan == entry.plan && generation == d.automationGeneration() && durableGeneration == d.store.automationGeneration() && marked.amountSat <= RecurringPayment.maximumAmount)
                            check(current.binding == d.binding() && current.binding == entry.binding && current.schedules[id] == marked)
                            check(!d.journal.consumed(entry.binding, id, entry.plan.scheduledFor!!, entry.policyRevision, entry.plan.occurrenceIndex))
                            check(approval != null || RecurringFeePolicy.evaluate(payment.amount, payment.method, quote.fee, quote.lightningFeeSchedule).autoPay)
                        } catch (_: Exception) { throw SendNotSubmitted(SendFailure.Reason.recurringStopped) }
                    }
                    if (job == null) { markUncertain(entry); continue }
                    job.join()
                    settle(entry, marked, retainReceipt = approval != null)
                } catch (_: Exception) {
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
        } catch (_: NoWallet) { /* Welcome has no automation authority yet. */ }
        catch (failure: Exception) { error = failure.message ?: "Recurring sending is stopped." }
        finally { try {
            reload()
            if (budget.available && caller?.isActive != false && currentCoroutineContext().isActive) {
                val visible = storage { runCatching { d.store.load().schedules.values.toList() }.getOrDefault(emptyList()) }
                requireStage(budget, caller)
                d.notify(visible, null)
            }
        } finally { running = false } }
    }
    private suspend fun markUncertain(entry: RecurringJournal.Entry, message: String? = controller.message) = storage {
        synchronized(RecurringStore.lock) {
            if (d.binding() != entry.binding) return@storage
            val state = d.store.load()
            if (state.binding != entry.binding) return@storage
            val latest = state.schedules[entry.scheduleID] ?: return@storage
            if (latest.nativeRevision != entry.policyRevision) return@storage
            val stopped = latest.copy(status = "needs_attention", lastError = message ?: SendFailure.Reason.unresolved.message)
            d.store.upsert(stopped); d.notify(emptyList(), stopped)
        }
    }
    private suspend fun settle(entry: RecurringJournal.Entry, original: RecurringPayment?, retainReceipt: Boolean = false) {
        // Own this exact outcome before storage can suspend and the UI can start another attempt.
        val attempt = controller.attempt ?: return
        val outcome = controller.state
        val message = controller.message
        if (attempt.recurringOccurrenceKey != entry.key) return
        val success = outcome == SendController.State.succeeded && attempt.receipt?.let { !it.isUnresolved && !it.broadcastRetryPending && it.isProven(attempt.payment) } == true
        val paid = success || outcome == SendController.State.alreadyPaid
        val failed = outcome == SendController.State.failed
        if (!paid && !failed) { markUncertain(entry, message); return }
        // Consumption precedes advancing MMKV or releasing Send, so rollback can never replay it.
        var policyFailure: Exception? = null
        val settled = storage {
            synchronized(RecurringStore.lock) {
                if (d.binding() != entry.binding) return@storage false
                val saved = d.journal.load()[entry.scheduleID]
                if (saved?.binding != entry.binding || saved.plan != entry.plan || saved.policyRevision != entry.policyRevision || saved.markedAt != entry.markedAt) return@storage false
                if (saved.policyApplied || saved.consumed) {
                    // Pre-marker receipts may already have advanced MMKV. Never guess by applying twice.
                    if (!saved.policyApplied) d.journal.save(saved.copy(policyApplied = true))
                    return@storage true
                }
                if (d.store.settlementState().binding != entry.binding) return@storage false
                // Claim application with consumption: interruption may stop advancement, never repeat it.
                d.journal.save(entry.copy(resolved = true, consumed = paid, policyApplied = true))
                try {
                val latest = d.store.settlementState().schedules[entry.scheduleID]
                if (latest != null && latest.nativeRevision == entry.policyRevision) {
                    val result = when {
                        success -> {
                            val advanced = latest.copy(status = "active").success(entry.plan, d.now(), d.zone())
                            if (latest.status !in listOf("active", "needs_approval") && advanced.status == "active")
                                advanced.copy(status = latest.status, consecutiveFailures = latest.consecutiveFailures,
                                    lastError = latest.lastError, retryNotBefore = latest.retryNotBefore) else advanced
                        }
                        paid -> latest.failure(entry.plan, "This invoice was already paid. This occurrence was not sent again. Check the recipient before resuming.", false, d.now())
                        else -> latest.failure(entry.plan, message ?: "No payment was submitted.", true, d.now())
                    }
                    d.store.upsert(result); d.notify(emptyList(), result)
                } else if (success && original != null && original.nativeRevision == entry.policyRevision) d.notify(emptyList(), original.copy(status = "active").success(entry.plan, d.now(), d.zone()))
                } catch (failure: Exception) { policyFailure = failure }
            }
            true
        }
        if (settled && !retainReceipt) controller.acknowledge(attempt)
        if (policyFailure != null) error = RecurringPolicyLedger.message
    }
    suspend fun checkStatus(id: String) {
        if (running) return
        try {
            val entry = storage { d.journal.load()[id] } ?: return
            val schedule = storage { runCatching { d.store.load().schedules[id] }.getOrNull() }
            if (schedule == null && !entry.policyApplied && !entry.consumed) return
            controller.checkStatus(); settle(entry, schedule)
        } catch (_: Exception) { error = "Unable to check this payment. Sending remains stopped." }
        reload()
    }
    suspend fun review(id: String): Review {
        check(!running && !controller.locked) { "Check the current payment before making another payment." }
        d.ensureOpen()
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
            val reapproval = editing && old?.lastError == RecurringPolicyLedger.message
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
            val reapproval = schedule.lastError == RecurringPolicyLedger.message
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
        storage {
            if (entry != null) d.journal.save(entry.copy(resolved = true, consumed = true))
            check(d.store.updateIf(schedule, schedule.resume(d.now(), d.zone())))
        }
        changed()
    }
    private suspend fun rejectLegacyUncertainty(id: String) = storage {
        check(!d.journal.legacyUncertain(d.binding(), id)) { RecurringJournal.legacyMessage }
    }
    private suspend fun refreshDates() = storage {
        for (schedule in boundStateNow().schedules.values.filter { it.status != "completed" && !it.unsupportedStorage && it.lastError != RecurringPolicyLedger.message }) {
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
    private val revoked = java.util.concurrent.atomic.AtomicBoolean(false)
    fun revoke() { revoked.set(true) }
    val available get() = !revoked.get() && (expiresAt == null || clock() < expiresAt)
    companion object { fun push() = RecurringBudget(System.nanoTime() + 25_000_000_000) }
}
