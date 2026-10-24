import BarkBoltFFI
import Foundation
import Observation

@MainActor @Observable final class RecurringExecutor {
    struct NoWallet: Error {}
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
        var mutation: WalletMutationGate = .shared
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
    private var task: (work: Task<Void, Never>, budget: RecurringSubmissionBudget?)?
    private var syncTask: Task<Void, Never>?
    private(set) var schedules: [RecurringPayment] = []
    private(set) var error: String?
    private(set) var running = false
    private(set) var syncPending = false
    var controller: SendPaymentController { d.controller }

    init(dependencies: Dependencies) {
        d = dependencies
        d.store.protectPolicies(file: d.journal.file.appendingPathExtension("policies"), binding: d.binding)
        let journal = d.journal, binding = d.binding, now = d.now
        d.controller.persistRecurringEvidence = { attempt, generation, walletGeneration, _, message in
            guard FileManager.default.fileExists(atPath: journal.file.deletingLastPathComponent().path) else { throw NoWallet() }
            let owner = try binding()
            guard try binding() == owner else { throw NoWallet() }
            try journal.record(attempt, generation: generation, walletGeneration: walletGeneration, message: message, binding: owner, now: now())
        }
        if d.controller.state == .storageBlocked, d.controller.attempt?.recurringOccurrenceKey != nil { d.controller.reloadForWalletChange() }
    }
    private func readAdmission() throws -> RecurringStore.Binding {
        let directory = d.journal.file.deletingLastPathComponent()
        guard FileManager.default.fileExists(atPath: directory.path) else { throw NoWallet() }
        let owner = try d.binding()
        let generation = d.automationGeneration()
        guard FileManager.default.fileExists(atPath: directory.path), try d.binding() == owner,
              d.automationGeneration() == generation, executionBinding == nil || executionBinding == owner else { throw NoWallet() }
        return owner
    }
    private func storage<T: Sendable>(_ operation: @MainActor @Sendable () throws -> T) async throws -> T {
        try await d.mutation.withLock { @MainActor [self] in
            _ = try readAdmission()
            return try operation()
        }
    }
    func reload() async {
        do {
            let state = try await storage { [self] in try d.store.load() }
            schedules = state.schedules.values.sorted { ($0.nextRunAt ?? .greatestFiniteMagnitude) < ($1.nextRunAt ?? .greatestFiniteMagnitude) }
            syncPending = state.pending
        } catch is NoWallet { schedules = []; syncPending = false }
        catch { self.error = error.localizedDescription }
    }
    /// This wallet's settled occurrences, for Activity's recurring rows. The journal keeps
    /// each schedule's latest occurrence, so earlier ones read as ordinary sends.
    func historyLinks() async -> [HistoryMapping.RecurringLink] {
        let read = try? await d.mutation.withLock { @MainActor [self] () throws -> [HistoryMapping.RecurringLink] in
            let owner = try readAdmission()
            let schedules = try d.store.load().schedules
            return try d.journal.load().values.compactMap { entry in
                // Only this occurrence's own proven submission: an "already paid" outcome can name an earlier
                // manual payment, which must not read as recurring.
                guard entry.binding == owner, let terminal = entry.terminal, terminal.outcome == .succeeded,
                      terminal.attempt.alreadyPaid == nil, terminal.attempt.recurringOccurrenceKey == entry.key,
                      let receipt = terminal.attempt.receipt,
                      receipt.isProven(for: terminal.attempt.payment, knownHash: terminal.attempt.paymentHash),
                      let schedule = schedules[entry.scheduleID] else { return nil }
                let hash = receipt.paymentHash ?? terminal.attempt.paymentHash
                guard receipt.movementID != nil || hash != nil else { return nil }
                return .init(schedule: .init(scheduleID: schedule.id, name: schedule.label, interval: schedule.interval),
                             movementID: receipt.movementID, paymentHash: hash)
            }
        }
        return read ?? []
    }
    /// Join triggers. Settlement outlives the caller; new submissions respect its deadline.
    func execute(manual: Bool = false, approval: Review? = nil) async {
        while true {
            do { try BackgroundExecution.checkActive(); try ForegroundExecution.checkActiveNow() } catch { return }
            guard let pending = task else { break }
            await pending.work.value
            if RecurringSubmissionBudget.current == nil || pending.budget === RecurringSubmissionBudget.current { return }
            // Another admission may have been revoked; reread durable due work with our budget.
        }
        let work = Task {
            await run(manual: manual, approval: approval)
            task = nil
        }
        task = (work, RecurringSubmissionBudget.current)
        await work.value
    }
    private var executionBinding: RecurringStore.Binding?
    private func boundState() async throws -> RecurringStore.State {
        try await storage { [self] in try boundStateNow() }
    }
    private func boundStateNow() throws -> RecurringStore.State {
        try ForegroundExecution.checkActiveNow()
        try d.allowed()
        let owner = try d.binding()
        guard executionBinding == nil || executionBinding == owner else { throw NoWallet() }
        try d.store.checkAutomationAllowed()
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
        let notifications = try await storage { [self] in
            var notifications: [RecurringPayment] = []
            let entries: [String: RecurringJournal.Entry]
            do { entries = try d.journal.load() }
            catch {
                // A corrupt journal cannot identify which policies are safe in this wallet.
                for var schedule in state.schedules.values where !schedule.requiresPolicyApproval {
                    schedule.status = "needs_attention"
                    schedule.lastError = error.localizedDescription
                    schedule.issue = .init(code: .invalidJournal)
                    try d.store.upsert(schedule)
                }
                throw error
            }
            for var schedule in state.schedules.values where !schedule.unsupportedStorage && !schedule.requiresPolicyApproval {
                let current = try boundStateNow()
                guard current.binding == state.binding, current.schedules[schedule.id] == schedule else { continue }
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
                    || (legacyUncertain && schedule.issue != RecurringJournal.legacyReason) {
                    schedule.status = "needs_attention"
                    schedule.issue = legacyUncertain ? RecurringJournal.legacyReason : .init(code: .interrupted)
                    schedule.lastError = legacyUncertain ? RecurringJournal.legacyMessage : "Noah was interrupted while sending this payment. Check your transaction history before resuming."
                    // Retain the marker; ordinary pause/resume/edit cannot erase uncertainty.
                    try d.store.upsert(schedule)
                    notifications.append(schedule)
                }
            }
            return notifications
        }
        for schedule in notifications {
            if try await storage({ [self] in try d.store.load().schedules[schedule.id] }) == schedule {
                await d.notifications([], schedule)
            }
        }
    }
    private func run(manual: Bool, approval: Review?) async {
        running = true
        error = nil
        defer { executionBinding = nil; running = false }
        do {
            let (automationGeneration, durableGeneration) = try await d.mutation.withLock { @MainActor [self] in
                try ForegroundExecution.checkActiveNow()
                try d.allowed()
                let owner = try d.binding()
                let generation = d.automationGeneration()
                // Revalidate after waiting for lifecycle mutation, before touching authority.
                try d.allowed()
                guard try d.binding() == owner else { throw NoWallet() }
                let durableGeneration = try d.store.automationGeneration()
                try await d.ensureOpen()
                try ForegroundExecution.checkActiveNow()
                try d.allowed()
                guard try d.binding() == owner else { throw NoWallet() }
                executionBinding = owner
                return (generation, durableGeneration)
            }
            let recovery: [RecurringJournal.Entry]
            do { recovery = try await storage { [self] in Array(try d.journal.load().values) } }
            catch { try await recoverMarkers(boundState()); throw error }
            for entry in recovery where entry.terminal != nil && entry.released != true { try await settle(entry) }
            try await refreshDates()
            let initial = try await boundState()
            try await recoverMarkers(initial)
            for id in initial.schedules.keys.sorted() {
                if let approval, approval.schedule.id != id { continue }
                guard (try? ForegroundExecution.checkActiveNow()) != nil,
                      RecurringSubmissionBudget.available, RecurringExecutionBudget.available else { break }
                guard var schedule = try await boundState().schedules[id], !schedule.unsupportedStorage else { continue }
                let original = schedule
                guard schedule.validPolicy else { throw SendFailure.invalid }
                var planning = schedule
                if let approval {
                    guard schedule == approval.schedule, schedule.status == "needs_approval" else { continue }
                    planning.status = "active"
                }
                let plan = planning.plan(now: d.now(), zone: d.zone(), ignoreBackoff: manual)
                if plan.kind == "too_many_missed" {
                    schedule.status = "needs_attention"
                    schedule.issue = .init(code: .tooManyMissed)
                    schedule.lastError = "This schedule has more than 5,000 missed occurrences. Cancel it and create a schedule with a future first payment."
                    try await storage { [self, schedule] in
                        if try d.store.load().schedules[id] == original { try d.store.upsert(schedule) }
                    }
                    continue
                }
                if plan.kind == "complete" {
                    schedule.status = "completed"; schedule.nextRunAt = nil
                    try await storage { [self, schedule] in
                        if try d.store.load().schedules[id] == original { try d.store.upsert(schedule) }
                    }
                    continue
                }
                guard plan.kind == "pay", !d.controller.locked else { continue }
                try ForegroundExecution.checkActiveNow()
                try BackgroundExecution.checkActive()
                do {
                    let payment: SendPayment, quote: SendQuote
                    let fees: LightningSendFees?
                    if let approval {
                        guard plan == approval.plan else { continue }
                        (payment, quote, fees) = (approval.payment, approval.quote, nil)
                    } else { (payment, quote, fees) = try await d.quote(schedule) }
                    guard (try? ForegroundExecution.checkActiveNow()) != nil,
                          RecurringSubmissionBudget.available, RecurringExecutionBudget.available else { break }
                    let state = try await boundState()
                    try ForegroundExecution.checkActiveNow()
                    try BackgroundExecution.checkActive()
                    guard let latest = state.schedules[id], latest == schedule,
                          planning.plan(now: d.now(), zone: d.zone(), ignoreBackoff: manual) == plan,
                          quote.walletIdentity == state.binding?.wallet else { continue }
                    if approval == nil, let reason = RecurringFeePolicy.evaluate(amount: payment.amount, method: payment.method, quoteFee: quote.fee, schedule: fees).approvalIssue(quoteFee: quote.fee) {
                        schedule.status = "needs_approval"
                        schedule.issue = reason
                        schedule.lastError = RecurringFeePolicy.evaluate(amount: payment.amount, method: payment.method, quoteFee: quote.fee, schedule: fees).approvalMessage(quoteFee: quote.fee)
                        schedule.retryNotBefore = nil
                        schedule.updatedAt = d.now()
                        let updated = try await storage { [self, schedule] in
                            guard try d.store.load().schedules[id] == original else { return false }
                            try d.store.upsert(schedule)
                            return true
                        }
                        if updated { await d.notifications([], schedule) }
                        continue
                    }
                    // Automation never adopts a pre-existing Lightning checkpoint: no user fee review.
                    guard quote.checkpoint == nil else { throw SendFailure.lightningInProgress }
                    let entry = RecurringJournal.Entry(binding: state.binding!, scheduleID: id, plan: plan, markedAt: d.now(), policyRevision: schedule.nativeRevision, policy: schedule)
                    schedule.inFlight = .init(occurrenceIndex: plan.occurrenceIndex!, startedAt: d.now())
                    schedule.updatedAt = d.now()
                    let admitted = try await storage { [self, schedule] in
                        guard try boundStateNow().schedules[id] == original else { return false }
                        try ForegroundExecution.checkActiveNow()
                        try BackgroundExecution.checkActive()
                        try d.journal.save(entry)
                        try d.store.upsert(schedule)
                        return true
                    }
                    guard admitted else { continue }
                    let marked = schedule
                    let unattended = approval == nil
                    let store = d.store, binding = d.binding, allowed = d.allowed, generation = d.automationGeneration, now = d.now, zone = d.zone
                    await d.controller.submitAndWait(payment, quote: quote, recurringKey: entry.key) {
                        do {
                            try allowed()
                            guard try binding() == entry.binding, generation() == automationGeneration else { throw NoWallet() }
                            try store.checkAutomationAllowed()
                        } catch { throw SendNotSubmitted(reason: .recurringStopped) }
                        let state = try store.load()
                        // Remove only our marker; the persisted policy must still match below.
                        var currentPlan = marked
                        currentPlan.inFlight = nil
                        if !unattended { currentPlan.status = "active" }
                        guard RecurringSubmissionBudget.available, RecurringExecutionBudget.available,
                              currentPlan.plan(now: now(), zone: zone(), ignoreBackoff: manual) == entry.plan,
                              generation() == automationGeneration,
                              durableGeneration == (try store.automationGeneration()),
                              state.binding == (try binding()), state.binding == entry.binding,
                              state.schedules[id] == marked,
                              marked.amountSat <= RecurringPayment.maximumAmount,
                              marked.status == (unattended ? "active" : "needs_approval"),
                              !unattended || RecurringFeePolicy.evaluate(amount: payment.amount, method: payment.method, quoteFee: quote.fee, schedule: fees).autoPay else {
                            throw SendNotSubmitted(reason: .recurringStopped)
                        }
                    }
                    try await settle(entry, retainReceipt: approval != nil, notifyOriginal: true)
                } catch is CancellationError { break }
                catch is NoWallet { throw NoWallet() }
                catch {
                    // Only a failure before the independent marker exists is automatically retryable.
                    let entry = try await storage { [self] in try d.journal.load()[id] }
                    if let entry, !entry.resolved, entry.plan == plan,
                       entry.binding == initial.binding, entry.policyRevision == schedule.nativeRevision {
                        try await markUncertain(entry)
                    } else if let current = try await storage({ [self] in try d.store.load().schedules[id] }), current == schedule {
                        schedule.failure(plan, message: error.localizedDescription, retryable: true, now: d.now())
                        let updated = try await storage { [self, schedule] in
                            guard try d.store.load().schedules[id] == original else { return false }
                            try d.store.upsert(schedule)
                            return true
                        }
                        if updated, schedule.status == "active", schedule.consecutiveFailures == 1 || manual { await d.notifications([], schedule) }
                    }
                }
            }
            try await refreshDates()
            await syncServer()
            await d.refresh()
        } catch is NoWallet { return }
        catch is CancellationError { return }
        catch { self.error = error.localizedDescription }
        await reload()
        await d.notifications(schedules, nil)
    }
    private func markUncertain(_ entry: RecurringJournal.Entry, message: String? = nil) async throws {
        let stopped = try await storage { [self] () throws -> RecurringPayment? in
            guard let saved = try d.journal.load()[entry.scheduleID], !saved.resolved,
                  saved.binding == entry.binding, saved.plan == entry.plan, saved.policyRevision == entry.policyRevision,
                  saved.markedAt == entry.markedAt else { return nil }
            let state = try d.store.load()
            guard try d.binding() == entry.binding, state.binding == entry.binding,
                  var latest = state.schedules[entry.scheduleID], latest.nativeRevision == entry.policyRevision else { return nil }
            latest.status = "needs_attention"
            latest.issue = .init(code: .uncertain)
            latest.retryNotBefore = nil
            latest.lastError = message ?? "This payment may have been sent. Check its status before making another payment."
            try d.store.upsert(latest)
            return latest
        }
        if let stopped { await d.notifications([], stopped) }
    }
    private func settle(_ entry: RecurringJournal.Entry, retainReceipt: Bool = false, notifyOriginal: Bool = false) async throws {
        guard let evidence = try await storage({ [self] in
            try d.journal.load()[entry.scheduleID].flatMap { saved in
                saved.binding == entry.binding && saved.plan == entry.plan && saved.markedAt == entry.markedAt && saved.policyRevision == entry.policyRevision ? saved.terminal : nil
            }
        }) else { return }
        if evidence.outcome == .uncertain { try await markUncertain(entry, message: evidence.message); return }
        if try await storage({ [self] in try d.binding() == entry.binding }) {
            d.controller.releaseRecurring(attemptID: evidence.attempt.recurringAttemptID, expectedGeneration: evidence.generation, expectedWalletGeneration: evidence.walletGeneration, retainReceipt: retainReceipt)
        }
        let notification = try await storage { [self] in try applySettlement(entry, evidence: evidence, notifyOriginal: notifyOriginal) }
        if let notification { await d.notifications([], notification) }
    }
    private func applySettlement(_ entry: RecurringJournal.Entry, evidence: RecurringJournal.TerminalEvidence, notifyOriginal: Bool = false) throws -> RecurringPayment? {
        try d.journal.withLock {
            guard try d.binding() == entry.binding,
                  var saved = try d.journal.load()[entry.scheduleID], saved.terminal?.settlementID == evidence.settlementID else { return nil }
            if saved.released == true { return nil }
            let state = saved.historyApplied != true ? try d.store.settlementState() : nil
            if let state, state.binding != entry.binding { return nil }
            if !saved.resolved {
                saved.resolved = true; saved.consumed = evidence.paid
                try d.journal.save(saved)
            }
            var notification: RecurringPayment?
            if saved.historyApplied != true {
                guard let state else { return nil }
                if try !d.store.settlementApplied(entry.scheduleID, token: evidence.settlementID.uuidString),
                   var latest = state.schedules[entry.scheduleID], latest.nativeRevision == entry.policyRevision {
                    if evidence.outcome == .succeeded {
                        let stopped = latest
                        latest.status = "active"
                        latest.success(entry.plan, now: evidence.recordedAt, zone: d.zone())
                        if stopped.status != "active", stopped.status != "needs_approval", latest.status == "active" {
                            latest.status = stopped.status; latest.consecutiveFailures = stopped.consecutiveFailures
                            latest.lastError = stopped.lastError; latest.issue = stopped.issue; latest.retryNotBefore = stopped.retryNotBefore
                        }
                    } else {
                        latest.failure(entry.plan, message: evidence.paid ? "This invoice was already paid. This occurrence was not sent again. Check the recipient before resuming." : evidence.message ?? "No payment was submitted.", retryable: !evidence.paid, now: evidence.recordedAt, issue: .init(code: evidence.paid ? .alreadyPaid : .retryableFailure))
                    }
                    try d.store.upsert(latest, settlementKey: evidence.settlementID.uuidString)
                    notification = latest
                } else if notifyOriginal, evidence.outcome == .succeeded, var original = entry.policy, original.nativeRevision == entry.policyRevision {
                    original.status = "active"; original.success(entry.plan, now: evidence.recordedAt, zone: d.zone())
                    notification = original
                }
                saved.historyApplied = true; saved.policyApplied = true
                try d.journal.save(saved)
            }
            saved.released = true
            try d.journal.save(saved)
            return notification
        }
    }
    func checkStatus(_ id: String) async {
        guard !running else { return }
        do {
            guard let entry = try await storage({ [self] in try d.journal.load()[id] }) else { return }
            // Reconciliation can add exact evidence; settlement only reads the occurrence.
            if entry.terminal == nil || entry.terminal?.outcome == .uncertain { await d.controller.checkStatus() }
            try await settle(entry)
        } catch is NoWallet { return }
        catch { self.error = error.localizedDescription }
        await reload()
    }
    func review(_ id: String) async throws -> Review {
        guard !running, !d.controller.locked else { throw SendFailure.unresolved }
        try await d.mutation.withLock { @MainActor [self] in
            try d.allowed(); _ = try readAdmission(); try await d.ensureOpen()
        }
        guard let schedule = try await boundState().schedules[id], schedule.status == "needs_approval" else { throw SendFailure.invalid }
        var planning = schedule; planning.status = "active"
        let plan = planning.plan(now: d.now(), zone: d.zone(), ignoreBackoff: true)
        guard plan.kind == "pay" else { throw RecurringError.message("This payment is no longer due.") }
        let (payment, quote, _) = try await d.quote(schedule)
        guard try await boundState().schedules[id] == schedule else { throw SendFailure.invalid }
        return Review(schedule: schedule, plan: plan, payment: payment, quote: quote)
    }
    func save(_ input: RecurringPayment, editing: Bool) async throws {
        try await storage { [self] in
            var schedule = input
            let state = try boundStateNow()
            let reapproval = editing && state.schedules[schedule.id]?.requiresPolicyApproval == true
            if reapproval { try requireReapprovalSafe(schedule.id) }
            if editing {
                try rejectLegacyUncertainty(schedule.id)
                guard let old = state.schedules[schedule.id], (reapproval || old.inFlight == nil && old.status != "needs_attention") else { throw RecurringError.message("Check this payment's outcome before editing it.") }
            } else if state.schedules.count >= 50 { throw RecurringError.message("You've reached the maximum number of recurring payments.") }
            if reapproval { schedule.resume(now: d.now(), zone: d.zone()) }
            if editing { schedule.nativeRevision = UUID() }
            try schedule.validateInput(now: d.now())
            try d.store.upsert(schedule, reapproval: reapproval)
        }
        await reload()
        await syncServer()
        await d.notifications(schedules, nil)
    }
    func pause(_ id: String) async throws {
        try await storage { [self] in
            guard var schedule = try d.store.load().schedules[id], schedule.status != "completed" else { return }
            // A pause must not hide an uncertain outcome.
            if schedule.status != "needs_attention" { schedule.status = "paused" }
            schedule.updatedAt = d.now()
            try d.store.upsert(schedule)
        }
        await changed()
    }
    func resume(_ id: String) async throws {
        try await storage { [self] in
            _ = try boundStateNow()
            try rejectLegacyUncertainty(id)
            guard var schedule = try d.store.load().schedules[id], schedule.status != "completed" else { return }
            guard !schedule.unsupportedStorage else { throw RecurringError.message(RecurringPayment.unsupportedMessage) }
            guard schedule.amountSat <= RecurringPayment.maximumAmount else { throw RecurringError.message(RecurringPayment.amountLimitMessage) }
            let reapproval = schedule.requiresPolicyApproval
            if reapproval { try requireReapprovalSafe(id) }
            guard reapproval || schedule.inFlight == nil && schedule.status != "needs_attention" else { throw RecurringError.message("Check your transaction history, then confirm the destructive override before resuming.") }
            schedule.resume(now: d.now(), zone: d.zone())
            if reapproval { schedule.nativeRevision = UUID() }
            try d.store.upsert(schedule, reapproval: reapproval)
        }
        await changed()
    }
    private func requireReapprovalSafe(_ id: String) throws {
        let entry = try d.journal.load()[id]
        guard entry == nil || entry?.resolved == true else { throw RecurringError.message("Check this payment's outcome before approving it again.") }
        guard !d.controller.locked else { throw RecurringError.message("Acknowledge the current payment before approving it again.") }
    }
    func cancel(_ id: String) async throws {
        // Removing policy never removes either durable journal or unlocks an ambiguous Send.
        try await storage { [self] in try d.store.remove(id) }
        await changed()
    }
    func overrideAndResume(_ id: String, confirmed: Bool) async throws {
        guard confirmed, !running else { return }
        if let entry = try await storage({ [self] in try d.journal.load()[id] }),
           let terminal = entry.terminal, terminal.outcome != .uncertain { try await settle(entry) }
        let notification = try await storage { [self] () throws -> RecurringPayment? in
            guard confirmed, !running else { return nil }
            _ = try boundStateNow()
            guard var schedule = try d.store.load().schedules[id] else { return nil }
            guard !schedule.unsupportedStorage else { throw RecurringError.message(RecurringPayment.unsupportedMessage) }
            try rejectLegacyUncertainty(id)
            guard schedule.amountSat <= RecurringPayment.maximumAmount else { throw RecurringError.message(RecurringPayment.amountLimitMessage) }
            if let entry = try d.journal.load()[id], d.controller.attempt?.recurringOccurrenceKey == entry.key {
                d.controller.startNewPaymentAfterHistoryCheck(confirmed: true, confirmationText: Self.overrideConfirmation, warningText: Self.overrideWarning)
                guard !d.controller.locked else { throw RecurringError.message("Unable to save the override confirmation. Sending remains locked.") }
            }
            // Includes RN in-flight markers that predate Send. Audit before releasing the occurrence.
            try auditLegacyOverride(schedule)
            return try d.journal.withLock {
                // Clearing Send can repair its failed handoff. Settle that proof before any skip.
                var notification: RecurringPayment?
                if var entry = try d.journal.load()[id] {
                    guard try entry.binding == d.binding() else { throw NoWallet() }
                    if let evidence = entry.terminal, evidence.outcome != .uncertain {
                        notification = try applySettlement(entry, evidence: evidence)
                        guard try d.journal.load()[id]?.released == true else { throw SendFailure.unresolved }
                    } else {
                        entry.resolved = true; entry.consumed = true
                        if entry.terminal != nil { entry.historyApplied = true; entry.released = true }
                        try d.journal.save(entry)
                    }
                }
                guard let latest = try d.store.load().schedules[id] else { return notification }
                schedule = latest
                schedule.resume(now: d.now(), zone: d.zone())
                try d.store.upsert(schedule)
                return notification
            }
        }
        if let notification { await d.notifications([], notification) }
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
    private func refreshDates() async throws {
        try await storage { [self] in
            for var schedule in try boundStateNow().schedules.values where !schedule.unsupportedStorage && schedule.status != "completed" && !schedule.requiresPolicyApproval {
                let next = schedule.next(schedule.nextOccurrenceIndex, zone: d.zone())
                if next != schedule.nextRunAt {
                    schedule.nextRunAt = next
                    try d.store.upsert(schedule)
                }
            }
        }
    }
    func timeChanged() async {
        do { try await refreshDates(); await changed() }
        catch is NoWallet { return }
        catch { self.error = error.localizedDescription }
    }
    private func changed() async {
        await reload(); await syncServer(); await d.notifications(schedules, nil)
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
                try await refreshDates()
                let state = try await boundState()
                let entries = Self.entries(state.schedules.values.map { $0 })
                try await storage { [self] in try d.store.setPending(true) }
                try await d.sync(entries)
                try await refreshDates()
                let synced = try await storage { [self] in
                    guard Self.entries(try boundStateNow().schedules.values.map { $0 }) == entries else { return false }
                    try d.store.setPending(false)
                    return true
                }
                if synced { syncPending = false; return }
            }
        } catch is NoWallet { return }
        catch { syncPending = true; AppLog("recurring").w(#"Recurring wake schedule sync failed; retained for retry"#) }
    }
    static func entries(_ schedules: [RecurringPayment]) -> [Components.Schemas.RecurringPaymentScheduleEntry] {
        schedules.filter { $0.status == "active" && $0.inFlight == nil && $0.nextRunAt != nil }
            .sorted { $0.id < $1.id }.map { .init(nextRunAt: Int64(floor($0.nextRunAt! / 1000)), scheduleId: $0.id) }
    }
}
