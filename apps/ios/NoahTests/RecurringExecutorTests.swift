import Foundation
import Testing
@testable import Noah

@MainActor struct RecurringExecutorTests {
    final class Locked<Value>: @unchecked Sendable {
        private let lock = NSLock()
        private var value: Value
        init(_ value: Value) { self.value = value }
        func withLock<T>(_ body: (inout Value) throws -> T) rethrows -> T { try lock.withLock { try body(&value) } }
    }
    @MainActor final class Harness {
        let settings: SettingsStorage
        let store: RecurringStore
        let directory: URL
        let journal: RecurringJournal
        let owner: Locked<RecurringStore.Binding>
        let stopped = Locked(false)
        let calls = Locked(0)
        var saved: SendPaymentController.Attempt?
        var writeReceiptFails = false
        var ambiguous = false
        var reservation: (@MainActor (@escaping @MainActor @Sendable () async -> Void) async throws -> Void)?
        var preflight: (@MainActor () async throws -> Void)?
        var beforePay: (@MainActor () async throws -> Void)?
        var afterPay: (@MainActor () async throws -> Void)?
        var syncHook: (@MainActor ([Components.Schemas.RecurringPaymentScheduleEntry]) async throws -> Void)?
        var sentEntries: [[Components.Schemas.RecurringPaymentScheduleEntry]] = []
        var controller: SendPaymentController!
        var executor: RecurringExecutor!
        let now = 1_800_000_000_000.0
        init() throws {
            SettingsStorage.bootstrap()
            directory = FileManager.default.temporaryDirectory.appending(path: "recurring-executor-\(UUID())")
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            settings = try .init(rootPath: directory.appending(path: "mmkv").path, instanceID: UUID().uuidString)
            store = .init(settings: settings)
            journal = .init(file: directory.appending(path: "journal.json"))
            owner = Locked(.init(wallet: .init(fingerprint: "test", network: "regtest"), instance: UUID()))
            rebuild()
        }
        func rebuild() {
            let owner = owner, stopped = stopped, calls = calls
            controller = SendPaymentController(dependencies: .init(
                prepare: { [self] _, _ in
                    try await beforePrepare()
                    return .init(movementIDs: [], walletIdentity: owner.withLock { $0.wallet })
                },
                pay: { _, _, _ in Issue.record("Recurring must use guarded Send submission"); throw SendFailure.unresolved },
                reconcile: { _ in .uncertain },
                read: { [self] in saved },
                write: { [self] in
                    if writeReceiptFails && $0?.receipt != nil { throw CocoaError(.fileWriteUnknown) }
                    saved = $0
                },
                identity: { owner.withLock { $0.wallet } },
                withRecurringReservation: { [self] work in try await reserve(work) },
                guardedPay: { [self] payment, _, _, guardPolicy in
                    try await beforePayment()
                    try guardPolicy()
                    calls.withLock { $0 += 1 }
                    try await afterPayment()
                    if await isAmbiguous() { throw URLError(.timedOut) }
                    return .init(amount: payment.amount, proof: .submitReturnedArkSuccess)
                }))
            executor = .init(dependencies: .init(store: store, journal: journal, controller: controller,
                ensureOpen: {}, binding: { owner.withLock { $0 } }, allowed: {
                    if stopped.withLock({ $0 }) { throw AppServices.WalletSuspended() }
                }, quote: { [self] schedule in
                    try await beforeQuote()
                    return (SendPayment(destination: schedule.destination, method: "ark", amount: schedule.amountSat),
                            .init(amount: schedule.amountSat, fee: 0, total: schedule.amountSat, walletIdentity: owner.withLock { $0.wallet }))
                }, automationGeneration: { [settings] in settings.string(forKey: WalletLifecycle.automationStopKey) }, now: { [now] in now }, sync: { [self] entries in try await sync(entries) }))
        }
        func reserve(_ work: @escaping @MainActor @Sendable () async -> Void) async throws {
            if let reservation { try await reservation(work) } else { await work() }
        }
        func beforePrepare() async throws {}
        func beforeQuote() async throws { try await preflight?() }
        func beforePayment() async throws { try await beforePay?() }
        func afterPayment() async throws { try await afterPay?() }
        func isAmbiguous() -> Bool { ambiguous }
        func sync(_ entries: [Components.Schemas.RecurringPaymentScheduleEntry]) async throws { sentEntries.append(entries); try await syncHook?(entries) }
        func add() throws -> RecurringPayment {
            var schedule = RecurringScheduleTests.schedule(start: now - 4 * 86_400_000)
            schedule.destinationType = "ark"; schedule.destination = "ark-test-destination"
            schedule.interval = .init(unit: "day", every: 1)
            try store.upsert(schedule)
            return schedule
        }
        func marker(_ schedule: RecurringPayment, mmkv: Bool) throws {
            let binding = owner.withLock { $0 }
            try store.bind(binding)
            let plan = schedule.plan(now: now)
            try journal.save(.init(binding: binding, scheduleID: schedule.id, plan: plan, markedAt: now))
            if mmkv {
                var schedule = schedule; schedule.inFlight = .init(occurrenceIndex: plan.occurrenceIndex!, startedAt: now)
                try store.upsert(schedule)
            }
        }
    }
    @Test func pushAndForegroundJoinAndMissedPaymentsNeverStack() async throws {
        let h = try Harness(); let schedule = try h.add()
        async let push: Void = h.executor.execute()
        async let foreground: Void = h.executor.execute()
        _ = await (push, foreground)
        await h.executor.execute()
        #expect(h.calls.withLock { $0 } == 1)
        let saved = try #require(h.store.load().schedules[schedule.id])
        #expect(saved.occurrencesPaid == 1 && saved.nextOccurrenceIndex == 5)
        #expect(saved.runs.map(\.status) == ["success", "skipped", "skipped", "skipped", "skipped"])
    }
    @Test(arguments: [false, true]) func crashAfterMarkerBeforePayNeverRetries(mmkvWritten: Bool) async throws {
        let h = try Harness(); let schedule = try h.add()
        try h.marker(schedule, mmkv: mmkvWritten)
        h.rebuild()
        await h.executor.execute(); await h.executor.execute(manual: true)
        #expect(h.calls.withLock { $0 } == 0)
        #expect(try h.store.load().schedules[schedule.id]?.status == "needs_attention")
        await #expect(throws: (any Error).self) { try await h.executor.resume(schedule.id) }
    }
    @Test func crashAfterPayBeforeReceiptNeverRetries() async throws {
        let h = try Harness(); let schedule = try h.add(); h.ambiguous = true
        await h.executor.execute()
        #expect(h.calls.withLock { $0 } == 1 && h.saved?.receipt == nil)
        h.rebuild()
        await h.executor.execute(); await h.executor.execute(manual: true)
        #expect(h.calls.withLock { $0 } == 1)
        #expect(h.controller.state == .uncertain)
        #expect(try h.store.load().schedules[schedule.id]?.status == "needs_attention")
    }
    @Test func crashWithUnsavedSendReceiptRetainsRecurringMarker() async throws {
        let h = try Harness(); let schedule = try h.add(); h.ambiguous = true
        await h.executor.execute()
        #expect(h.calls.withLock { $0 } == 1)
        let bytes = try Data(contentsOf: h.journal.file)
        h.rebuild(); await h.executor.execute()
        #expect(try Data(contentsOf: h.journal.file) == bytes)
        #expect(try h.store.load().schedules[schedule.id]?.inFlight != nil)
    }
    @Test(arguments: ["pause", "cancel", "edit", "suspend", "replace", "restore"]) func changeDuringPreflightStopsPayment(action: String) async throws {
        let h = try Harness(); let schedule = try h.add()
        h.preflight = {
            switch action {
            case "pause": try await h.executor.pause(schedule.id)
            case "cancel": try await h.executor.cancel(schedule.id)
            case "edit": var edit = schedule; edit.amountSat += 1; try h.store.upsert(edit)
            case "suspend": h.stopped.withLock { $0 = true }
            case "replace": h.owner.withLock { $0.wallet.fingerprint = "replacement" }
            default: h.owner.withLock { $0.instance = UUID() }
            }
        }
        await h.executor.execute()
        #expect(h.calls.withLock { $0 } == 0)
        if action == "cancel" { #expect(try h.store.load().schedules[schedule.id] == nil) }
    }
    @Test(arguments: ["pause", "cancel", "edit", "suspend", "replace", "restore"]) func finalGuardAfterPreparationStopsPayment(action: String) async throws {
        let h = try Harness(); let schedule = try h.add()
        h.beforePay = {
            switch action {
            case "pause": try await h.executor.pause(schedule.id)
            case "cancel": try await h.executor.cancel(schedule.id)
            case "edit": var edit = try #require(h.store.load().schedules[schedule.id]); edit.amountSat += 1; try h.store.upsert(edit)
            case "suspend": h.stopped.withLock { $0 = true }
            case "replace": h.owner.withLock { $0.wallet.fingerprint = "replacement" }
            default: h.owner.withLock { $0.instance = UUID() }
            }
        }
        await h.executor.execute()
        #expect(h.calls.withLock { $0 } == 0)
        if action == "cancel" { #expect(try h.store.load().schedules[schedule.id] == nil) }
    }
    @Test func suspendedAndDeletedWalletCannotRunExistingSchedules() async throws {
        let h = try Harness(); _ = try h.add(); h.stopped.withLock { $0 = true }
        await h.executor.execute(); #expect(h.calls.withLock { $0 } == 0)
        h.settings.removeAll(); h.owner.withLock { $0.instance = UUID(); $0.wallet.fingerprint = "new-wallet" }
        h.stopped.withLock { $0 = false }
        await h.executor.execute(); #expect(h.calls.withLock { $0 } == 0)
    }
    @Test func replacingOrRestoringWalletRetainsButDisablesOldPolicies() async throws {
        let h = try Harness(); let schedule = try h.add()
        try h.store.bind(h.owner.withLock { $0 })
        h.owner.withLock { $0.instance = UUID() }
        await h.executor.execute()
        #expect(h.calls.withLock { $0 } == 0)
        #expect(try h.store.load().schedules[schedule.id] != nil)
        #expect(h.executor.error != nil)
    }
    @Test func replacementWalletCanCreateFreshPolicyAfterCancellingOldOnes() async throws {
        let h = try Harness(); var schedule = try h.add()
        try h.store.bind(h.owner.withLock { $0 })
        h.owner.withLock { $0.instance = UUID() }
        try await h.executor.cancel(schedule.id)
        schedule.id = UUID().uuidString; schedule.startAt = h.now; schedule.nextRunAt = h.now
        try await h.executor.save(schedule, editing: false)
        await h.executor.execute()
        #expect(h.calls.withLock { $0 } == 1)
    }
    @Test func unresolvedSendBlocksOtherSchedulesAndOverrideSkipsPastDue() async throws {
        let h = try Harness(); let schedule = try h.add(); h.ambiguous = true
        await h.executor.execute()
        await #expect(throws: (any Error).self) { try await h.executor.resume(schedule.id) }
        try await h.executor.overrideAndResume(schedule.id, confirmed: true)
        await h.executor.execute()
        #expect(h.calls.withLock { $0 } == 1)
        #expect(try h.store.load().schedules[schedule.id]?.nextOccurrenceIndex == 5)
        #expect(FileManager.default.fileExists(atPath: h.directory.appending(path: "noah-recurring-overrides.json").path))
    }
    @Test(arguments: ["pause", "cancel", "edit"]) func userChangesWhileNativePaymentFinishesNeverReenableOrRecreatePolicy(action: String) async throws {
        let h = try Harness(); let original = try h.add()
        h.afterPay = {
            if action == "pause" { try await h.executor.pause(original.id) }
            if action == "cancel" { try await h.executor.cancel(original.id) }
            if action == "edit" {
                var edit = original; edit.startAt = h.now; edit.nextRunAt = h.now
                await #expect(throws: (any Error).self) { try await h.executor.save(edit, editing: true) }
            }
        }
        await h.executor.execute()
        #expect(h.calls.withLock { $0 } == 1)
        let saved = try h.store.load().schedules[original.id]
        if action == "cancel" { #expect(saved == nil) }
        else { #expect(saved?.occurrencesPaid == 1 && saved?.status == (action == "pause" ? "paused" : "active")) }
        #expect(!h.controller.locked)
    }
    @Test func consumedJournalBlocksAnOlderMMKVSnapshot() async throws {
        let h = try Harness(); let original = try h.add()
        await h.executor.execute()
        #expect(h.calls.withLock { $0 } == 1)
        #expect(try h.journal.load()[original.id]?.consumed == true)
        try h.store.upsert(original) // Simulated stale persistence after a crash.
        h.rebuild(); await h.executor.execute()
        #expect(h.calls.withLock { $0 } == 1)
        #expect(try h.store.load().schedules[original.id]?.status == "needs_attention")
    }
    @Test func explicitEditCreatesNewPolicyWithoutReusingConsumedOccurrence() async throws {
        let h = try Harness(); let original = try h.add()
        await h.executor.execute()
        var edit = original
        edit.startAt = h.now; edit.nextRunAt = h.now; edit.amountSat = 1234
        try await h.executor.save(edit, editing: true)
        #expect(try h.store.load().schedules[original.id]?.nativeRevision != nil)
        await h.executor.execute()
        #expect(h.calls.withLock { $0 } == 2)
    }
    @Test func corruptJournalStopsAllExecutionWithoutChangingBytes() async throws {
        let h = try Harness(); _ = try h.add()
        try Data("broken journal".utf8).write(to: h.journal.file)
        await h.executor.execute()
        #expect(h.calls.withLock { $0 } == 0)
        #expect(try String(contentsOf: h.journal.file, encoding: .utf8) == "broken journal")
        #expect(h.executor.error != nil)
    }
    @Test func cancelDoesNotUnlockUncertainSend() async throws {
        let h = try Harness(); let schedule = try h.add(); h.ambiguous = true
        await h.executor.execute()
        try await h.executor.cancel(schedule.id)
        #expect(h.controller.locked)
        #expect(try h.store.load().schedules[schedule.id] == nil)
        #expect(try h.journal.load()[schedule.id] != nil)
    }
    @Test func syncSerializesLatestListAndKeepsFailurePending() async throws {
        let h = try Harness(); var schedule = try h.add()
        h.syncHook = { _ in
            if h.sentEntries.count == 1 { schedule.status = "paused"; try h.store.upsert(schedule) }
        }
        await h.executor.syncServer()
        #expect(h.sentEntries.count == 2 && h.sentEntries.last?.isEmpty == true)
        #expect(try h.store.load().pending == false)
        h.syncHook = { _ in throw URLError(.notConnectedToInternet) }
        await h.executor.syncServer()
        #expect(try h.store.load().pending)
    }
}
