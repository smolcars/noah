import BarkBoltFFI
import Foundation
import Observation

@MainActor @Observable final class RecurringExecutor {
    struct Dependencies {
        var store: RecurringStore
        var journal: RecurringJournal
        var controller: SendPaymentController
        var ensureOpen: @Sendable () async throws -> Void
        var binding: @Sendable () throws -> RecurringStore.Binding
        var allowed: @Sendable () throws -> Void
        var quote: @Sendable (RecurringPayment) async throws -> (SendPayment, SendQuote, LightningSendFees?)
        var automationGeneration: @Sendable () -> String? = { nil }
        var zone: @Sendable () -> TimeZone = { .current }
        var now: @Sendable () -> Double = { RecurringPayment.now }
        var sync: @Sendable ([Components.Schemas.RecurringPaymentScheduleEntry]) async throws -> Void = { _ in }
        var notifications: @Sendable ([RecurringPayment], RecurringPayment?) async -> Void = { _, _ in }
        var refresh: @Sendable () async -> Void = {}
    }
    struct Review: Identifiable {
        let id = UUID()
        let schedule: RecurringPayment
        let plan: RecurringPayment.Plan
        let payment: SendPayment
        let quote: SendQuote
    }
    static let overrideWarning = SendOverrideAudit.warningText + "\n\nResuming skips all occurrences that are already due."
    static let overrideConfirmation = "Resume anyway"
    private let d: Dependencies
    private var task: Task<Void, Never>?
    private var syncTask: Task<Void, Never>?
    private(set) var schedules: [RecurringPayment] = []
    private(set) var error: String?
    private(set) var running = false
    private(set) var syncPending = false
    var controller: SendPaymentController { d.controller }

    init(dependencies: Dependencies) { d = dependencies }
    func reload() {
        do {
            let state = try d.store.load()
            schedules = state.schedules.values.sorted { ($0.nextRunAt ?? .greatestFiniteMagnitude) < ($1.nextRunAt ?? .greatestFiniteMagnitude) }
            syncPending = state.pending
        } catch { self.error = error.localizedDescription }
    }
    /// Join triggers. Settlement outlives the caller; new submissions respect its deadline.
    func execute(manual: Bool = false, approval: Review? = nil) async {
        if let task { await task.value; return }
        let work = Task { await run(manual: manual, approval: approval) }
        task = work
        await work.value
        task = nil
    }
    private func boundState() throws -> RecurringStore.State {
        try d.allowed()
        try d.store.checkAutomationAllowed()
        let owner = try d.binding()
        var state = try d.store.load()
        if state.binding == nil || (state.binding != owner && state.schedules.isEmpty) {
            // First RN upgrade only. All existing schedules are retained, including in-flight ones.
            try d.store.bind(owner)
            state.binding = owner
        }
        guard state.binding == owner else { throw RecurringError.message("These recurring payments belong to a previous wallet. They are preserved but cannot run. Cancel them and create new schedules for this wallet.") }
        return state
    }
    private func recoverMarkers(_ state: RecurringStore.State) async throws {
        let entries: [String: RecurringJournal.Entry]
        do { entries = try d.journal.load() }
        catch {
            // A corrupt journal cannot identify which policies are safe in this wallet.
            for var schedule in state.schedules.values {
                schedule.status = "needs_attention"
                schedule.lastError = error.localizedDescription
                try d.store.upsert(schedule)
            }
            throw error
        }
        for var schedule in state.schedules.values where !schedule.unsupportedStorage {
            let entry = entries[schedule.id]
            let legacyUncertain = try d.journal.legacyUncertain(binding: state.binding!, scheduleID: schedule.id)
            let pendingMarker = entry.map { !$0.resolved && $0.binding == state.binding } == true
            var planning = schedule
            if planning.status == "needs_approval" { planning.status = "active" }
            let plan = planning.plan(now: d.now(), zone: d.zone(), ignoreBackoff: true)
            let consumed = try plan.scheduledFor.map { at in
                try d.journal.consumed(binding: state.binding!, scheduleID: schedule.id, at: at,
                                       policyRevision: schedule.nativeRevision, occurrenceIndex: plan.occurrenceIndex!)
            } ?? false
            if ((legacyUncertain || schedule.inFlight != nil || pendingMarker || consumed) && schedule.status != "needs_attention")
                || (legacyUncertain && schedule.lastError != RecurringJournal.legacyMessage) {
                schedule.status = "needs_attention"
                schedule.lastError = legacyUncertain ? RecurringJournal.legacyMessage : "Noah was interrupted while sending this payment. Check your transaction history before resuming."
                // Retain the marker; ordinary pause/resume/edit cannot erase uncertainty.
                try d.store.upsert(schedule)
                await d.notifications([], schedule)
            }
        }
    }
    private func run(manual: Bool, approval: Review?) async {
        running = true
        error = nil
        defer { running = false; reload() }
        do {
            let automationGeneration = d.automationGeneration()
            try await d.ensureOpen()
            try refreshDates()
            let initial = try boundState()
            try await recoverMarkers(initial)
            for id in initial.schedules.keys.sorted() {
                if let approval, approval.schedule.id != id { continue }
                guard RecurringSubmissionBudget.available, RecurringExecutionBudget.available else { break }
                guard var schedule = try boundState().schedules[id], !schedule.unsupportedStorage else { continue }
                guard schedule.validPolicy else { throw SendFailure.invalid }
                var planning = schedule
                if let approval {
                    guard schedule == approval.schedule, schedule.status == "needs_approval" else { continue }
                    planning.status = "active"
                }
                let plan = planning.plan(now: d.now(), zone: d.zone(), ignoreBackoff: manual)
                if plan.kind == "too_many_missed" {
                    schedule.status = "needs_attention"
                    schedule.lastError = "This schedule has more than 5,000 missed occurrences. Cancel it and create a schedule with a future first payment."
                    try d.store.upsert(schedule)
                    continue
                }
                if plan.kind == "complete" {
                    schedule.status = "completed"; schedule.nextRunAt = nil
                    try d.store.upsert(schedule)
                    continue
                }
                guard plan.kind == "pay", !d.controller.locked else { continue }
                do {
                    let payment: SendPayment, quote: SendQuote
                    let fees: LightningSendFees?
                    if let approval {
                        guard plan == approval.plan else { continue }
                        (payment, quote, fees) = (approval.payment, approval.quote, nil)
                    } else { (payment, quote, fees) = try await d.quote(schedule) }
                    guard RecurringSubmissionBudget.available, RecurringExecutionBudget.available else { break }
                    let state = try boundState()
                    guard let latest = state.schedules[id], latest == schedule,
                          planning.plan(now: d.now(), zone: d.zone(), ignoreBackoff: manual) == plan,
                          quote.walletIdentity == state.binding?.wallet else { continue }
                    if approval == nil, let reason = RecurringFeePolicy.evaluate(amount: payment.amount, method: payment.method, quoteFee: quote.fee, schedule: fees).approvalMessage(quoteFee: quote.fee) {
                        schedule.status = "needs_approval"
                        schedule.lastError = reason
                        schedule.retryNotBefore = nil
                        schedule.updatedAt = d.now()
                        try d.store.upsert(schedule)
                        await d.notifications([], schedule)
                        continue
                    }
                    // Automation never adopts a pre-existing Lightning checkpoint: no user fee review.
                    guard quote.checkpoint == nil else { throw SendFailure.lightningInProgress }
                    let entry = RecurringJournal.Entry(binding: state.binding!, scheduleID: id, plan: plan, markedAt: d.now(), policyRevision: schedule.nativeRevision)
                    try d.journal.save(entry)
                    schedule.inFlight = .init(occurrenceIndex: plan.occurrenceIndex!, startedAt: d.now())
                    schedule.updatedAt = d.now()
                    try d.store.upsert(schedule)
                    let marked = schedule
                    let unattended = approval == nil
                    let store = d.store, binding = d.binding, allowed = d.allowed, generation = d.automationGeneration, now = d.now, zone = d.zone
                    await d.controller.submitAndWait(payment, quote: quote, recurringKey: entry.key) {
                        do { try allowed(); try store.checkAutomationAllowed() } catch { throw SendNotSubmitted(reason: .recurringStopped) }
                        let state = try store.load()
                        // Remove only our marker; the persisted policy must still match below.
                        var currentPlan = marked
                        currentPlan.inFlight = nil
                        if !unattended { currentPlan.status = "active" }
                        guard RecurringSubmissionBudget.available, RecurringExecutionBudget.available,
                              currentPlan.plan(now: now(), zone: zone(), ignoreBackoff: manual) == entry.plan,
                              generation() == automationGeneration,
                              state.binding == (try binding()), state.binding == entry.binding,
                              state.schedules[id] == marked,
                              marked.amountSat <= RecurringPayment.maximumAmount,
                              marked.status == (unattended ? "active" : "needs_approval"),
                              !unattended || RecurringFeePolicy.evaluate(amount: payment.amount, method: payment.method, quoteFee: quote.fee, schedule: fees).autoPay else {
                            throw SendNotSubmitted(reason: .recurringStopped)
                        }
                    }
                    try await settle(entry, original: marked, retainReceipt: approval != nil)
                } catch {
                    // Only a failure before the independent marker exists is automatically retryable.
                    let entry = try d.journal.load()[id]
                    if entry?.resolved == false, entry?.plan.occurrenceIndex == plan.occurrenceIndex {
                        if var current = try d.store.load().schedules[id] {
                            current.status = "needs_attention"
                            current.lastError = "This payment may have been sent. Check its status before making another payment."
                            try d.store.upsert(current)
                        }
                    } else if let current = try d.store.load().schedules[id], current == schedule {
                        schedule.failure(plan, message: error.localizedDescription, retryable: true, now: d.now())
                        try d.store.upsert(schedule)
                        if schedule.status == "active", schedule.consecutiveFailures == 1 || manual { await d.notifications([], schedule) }
                    }
                }
            }
            try refreshDates()
            await syncServer()
            await d.refresh()
        } catch { self.error = error.localizedDescription }
        await d.notifications((try? d.store.load().schedules.values.map { $0 }) ?? [], nil)
    }
    private func settle(_ entry: RecurringJournal.Entry, original: RecurringPayment, retainReceipt: Bool = false) async throws {
        guard d.controller.attempt?.recurringOccurrenceKey == entry.key else { return }
        guard try d.binding() == entry.binding else { return }
        guard var latest = try d.store.load().schedules[entry.scheduleID] else {
            // Cancellation removes policy, never money already sent or its receipt.
            switch d.controller.state {
            case .succeeded(let receipt) where !receipt.isUnresolved && receipt.broadcastRetryPending != true:
                var resolved = entry; resolved.resolved = true; resolved.consumed = true; try d.journal.save(resolved)
                if !retainReceipt { d.controller.acknowledge() }
                await d.notifications([], original)
            case .failed:
                var resolved = entry; resolved.resolved = true; try d.journal.save(resolved)
                if !retainReceipt { d.controller.acknowledge() }
            default: break
            }
            return
        }
        switch d.controller.state {
        case .succeeded(let receipt) where !receipt.isUnresolved && receipt.broadcastRetryPending != true:
            var result = original
            if result.status == "needs_approval" { result.status = "active" }
            result.success(entry.plan, now: d.now(), zone: d.zone())
            if latest.status != "active", latest.status != "needs_approval", result.status == "active" { result.status = latest.status }
            // Editing an in-flight policy is prohibited; a concurrent cancel is never recreated.
            try d.store.upsert(result)
            var resolved = entry; resolved.resolved = true; resolved.consumed = true
            try d.journal.save(resolved)
            if !retainReceipt { d.controller.acknowledge() }
            await d.notifications([], result)
        case .alreadyPaid:
            latest.failure(entry.plan, message: "This invoice was already paid. This occurrence was not sent again. Check the recipient before resuming.", retryable: false, now: d.now())
            try d.store.upsert(latest)
            var resolved = entry; resolved.resolved = true; resolved.consumed = true
            try d.journal.save(resolved)
            if !retainReceipt { d.controller.acknowledge() }
            await d.notifications([], latest)
        case .failed:
            latest.inFlight = nil
            latest.failure(entry.plan, message: d.controller.message ?? "No payment was submitted.", retryable: true, now: d.now())
            try d.store.upsert(latest)
            var resolved = entry; resolved.resolved = true
            try d.journal.save(resolved)
            if !retainReceipt { d.controller.acknowledge() }
        case .submitting:
            return
        default:
            latest.status = "needs_attention"
            latest.lastError = d.controller.message ?? "This payment may have been sent. Check its status before making another payment."
            try d.store.upsert(latest)
            await d.notifications([], latest)
        }
    }
    func checkStatus(_ id: String) async {
        guard !running else { return }
        do {
            guard let entry = try d.journal.load()[id], let original = try d.store.load().schedules[id], !original.unsupportedStorage else { return }
            await d.controller.checkStatus()
            try await settle(entry, original: original)
        } catch { self.error = error.localizedDescription }
        reload()
    }
    func review(_ id: String) async throws -> Review {
        guard !running, !d.controller.locked else { throw SendFailure.unresolved }
        try await d.ensureOpen()
        guard let schedule = try boundState().schedules[id], schedule.status == "needs_approval" else { throw SendFailure.invalid }
        var planning = schedule; planning.status = "active"
        let plan = planning.plan(now: d.now(), zone: d.zone(), ignoreBackoff: true)
        guard plan.kind == "pay" else { throw RecurringError.message("This payment is no longer due.") }
        let (payment, quote, _) = try await d.quote(schedule)
        guard try boundState().schedules[id] == schedule else { throw SendFailure.invalid }
        return Review(schedule: schedule, plan: plan, payment: payment, quote: quote)
    }
    func save(_ input: RecurringPayment, editing: Bool) async throws {
        var schedule = input
        let state = try boundState()
        if editing {
            try rejectLegacyUncertainty(schedule.id)
            guard let old = state.schedules[schedule.id], old.inFlight == nil, old.status != "needs_attention" else { throw RecurringError.message("Check this payment's outcome before editing it.") }
        } else if state.schedules.count >= 50 { throw RecurringError.message("You've reached the maximum number of recurring payments.") }
        if editing { schedule.nativeRevision = UUID() }
        try schedule.validateInput(now: d.now())
        try d.store.upsert(schedule)
        reload()
        await syncServer()
        await d.notifications(schedules, nil)
    }
    func pause(_ id: String) async throws {
        guard var schedule = try d.store.load().schedules[id], schedule.status != "completed" else { return }
        // A pause must not hide an uncertain outcome.
        if schedule.status != "needs_attention" { schedule.status = "paused" }
        schedule.updatedAt = d.now()
        try d.store.upsert(schedule)
        await changed()
    }
    func resume(_ id: String) async throws {
        _ = try boundState()
        try rejectLegacyUncertainty(id)
        guard var schedule = try d.store.load().schedules[id], schedule.status != "completed" else { return }
        guard !schedule.unsupportedStorage else { throw RecurringError.message(RecurringPayment.unsupportedMessage) }
        guard schedule.amountSat <= RecurringPayment.maximumAmount else { throw RecurringError.message(RecurringPayment.amountLimitMessage) }
        guard schedule.inFlight == nil, schedule.status != "needs_attention" else { throw RecurringError.message("Check your transaction history, then confirm the destructive override before resuming.") }
        schedule.resume(now: d.now(), zone: d.zone())
        try d.store.upsert(schedule)
        await changed()
    }
    func cancel(_ id: String) async throws {
        // Removing policy never removes either durable journal or unlocks an ambiguous Send.
        try d.store.remove(id)
        await changed()
    }
    func overrideAndResume(_ id: String, confirmed: Bool) async throws {
        guard confirmed, !running else { return }
        _ = try boundState()
        guard var schedule = try d.store.load().schedules[id] else { return }
        guard !schedule.unsupportedStorage else { throw RecurringError.message(RecurringPayment.unsupportedMessage) }
        try rejectLegacyUncertainty(id)
        guard schedule.amountSat <= RecurringPayment.maximumAmount else { throw RecurringError.message(RecurringPayment.amountLimitMessage) }
        if let entry = try d.journal.load()[id], d.controller.attempt?.recurringOccurrenceKey == entry.key {
            d.controller.startNewPaymentAfterHistoryCheck(confirmed: true, confirmationText: Self.overrideConfirmation, warningText: Self.overrideWarning)
            guard !d.controller.locked else { throw RecurringError.message("Unable to save the override confirmation. Sending remains locked.") }
        }
        // Includes RN in-flight markers that predate Send. Audit before releasing the occurrence.
        try auditLegacyOverride(schedule)
        if var entry = try d.journal.load()[id] { entry.resolved = true; entry.consumed = true; try d.journal.save(entry) }
        schedule.resume(now: d.now(), zone: d.zone())
        try d.store.upsert(schedule)
        await changed()
    }
    private func rejectLegacyUncertainty(_ id: String) throws {
        guard try !d.journal.legacyUncertain(binding: d.binding(), scheduleID: id) else {
            throw RecurringError.message(RecurringJournal.legacyMessage)
        }
    }
    private func auditLegacyOverride(_ schedule: RecurringPayment) throws {
        let entry = try d.journal.load()[schedule.id]
        let audit = SendOverrideAudit(walletIdentity: entry?.binding.wallet, ownershipUnverified: entry == nil,
                                      warning: Self.overrideWarning, confirmation: Self.overrideConfirmation,
                                      statusMessage: "Recurring \(schedule.id), occurrence \(schedule.inFlight?.occurrenceIndex ?? schedule.nextOccurrenceIndex)")
        let file = d.journal.file.deletingLastPathComponent().appending(path: "noah-recurring-overrides.json")
        try SendOverrideAudit.append(audit, to: file)
        try RestoreTransaction.fullSync(file)
        try RestoreTransaction.fullSync(file.deletingLastPathComponent())
    }
    private func refreshDates() throws {
        for var schedule in try boundState().schedules.values where !schedule.unsupportedStorage && schedule.status != "completed" {
            let next = schedule.next(schedule.nextOccurrenceIndex, zone: d.zone())
            if next != schedule.nextRunAt {
                schedule.nextRunAt = next
                try d.store.upsert(schedule)
            }
        }
    }
    func timeChanged() async {
        do { try refreshDates(); await changed() }
        catch { self.error = error.localizedDescription }
    }
    private func changed() async {
        reload(); await syncServer(); await d.notifications(schedules, nil)
    }
    /// Each request replaces the whole server list. Joiners wait; edits during a request
    /// force another pass before clearing the durable pending flag.
    func syncServer() async {
        if let syncTask { await syncTask.value; return }
        let work = Task { await performSync() }
        syncTask = work
        await work.value
        syncTask = nil
    }
    private func performSync() async {
        do {
            for _ in 0..<20 {
                try refreshDates()
                let state = try boundState()
                let entries = Self.entries(state.schedules.values.map { $0 })
                try d.store.setPending(true)
                try await d.sync(entries)
                try refreshDates()
                let latest = try boundState()
                if Self.entries(latest.schedules.values.map { $0 }) == entries {
                    try d.store.setPending(false); syncPending = false; return
                }
            }
        } catch { syncPending = true; AppLog("recurring").w("Recurring wake schedule sync failed; retained for retry") }
    }
    static func entries(_ schedules: [RecurringPayment]) -> [Components.Schemas.RecurringPaymentScheduleEntry] {
        schedules.filter { $0.status == "active" && $0.inFlight == nil && $0.nextRunAt != nil }
            .sorted { $0.id < $1.id }.map { .init(nextRunAt: Int64(floor($0.nextRunAt! / 1000)), scheduleId: $0.id) }
    }
}
