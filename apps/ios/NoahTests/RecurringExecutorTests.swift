import BarkBoltFFI
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
        var fee: UInt64 = 0
        var method = "ark"
        var fees: LightningSendFees? = nil
        var feeFetches = 0
        var feeFetchFails = false
        var notified: [RecurringPayment] = []
        var reservation: (@MainActor (@escaping @MainActor @Sendable () async -> Void) async throws -> Void)?
        var preflight: (@MainActor () async throws -> Void)?
        var beforePay: (@MainActor () async throws -> Void)?
        var afterPay: (@MainActor () async throws -> Void)?
        var syncHook: (@MainActor ([Components.Schemas.RecurringPaymentScheduleEntry]) async throws -> Void)?
        var sentEntries: [[Components.Schemas.RecurringPaymentScheduleEntry]] = []
        var controller: SendPaymentController!
        var executor: RecurringExecutor!
        let now = 1_800_000_000_000.0
        let zone = Locked(TimeZone.current)
        var reminders: [RecurringPayment] = []
        let clock = Locked(1_800_000_000_000.0)
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
                    let fees = await RecurringRecipient.lightningFees { [self] in
                        try await fetchFees()
                    }
                    return await quoted(schedule, fees: fees)
                }, automationGeneration: { [settings] in settings.string(forKey: WalletLifecycle.automationStopKey) }, zone: { [zone] in zone.withLock { $0 } }, now: { [clock] in clock.withLock { $0 } }, sync: { [self] entries in try await sync(entries) },
                notifications: { [self] schedules, result in await notification(schedules, result) }))
        }
        func fetchFees() throws -> LightningSendFees? {
            feeFetches += 1
            if feeFetchFails { throw URLError(.notConnectedToInternet) }
            return fees
        }
        func quoted(_ schedule: RecurringPayment, fees: LightningSendFees) -> (SendPayment, SendQuote, LightningSendFees?) {
            (SendPayment(destination: schedule.destination, method: method, amount: schedule.amountSat),
             .init(amount: schedule.amountSat, fee: fee, total: schedule.amountSat + fee, walletIdentity: owner.withLock { $0.wallet }), fees)
        }
        func notification(_ schedules: [RecurringPayment], _ result: RecurringPayment?) {
            if let result { notified.append(result) } else { reminders = schedules }
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
    @Test func consumedMonthlyIndexSurvivesRollbackAndZoneChange() async throws {
        let h = try Harness()
        var schedule = RecurringScheduleTests.schedule(start: 1_772_337_600_000)
        schedule.destinationType = "ark"; schedule.destination = "ark-test-destination"
        h.zone.withLock { $0 = TimeZone(identifier: "America/New_York")! }
        h.clock.withLock { $0 = 1_774_753_200_000 }
        #expect(schedule.plan(now: h.clock.withLock { $0 }, zone: h.zone.withLock { $0 }).occurrenceIndex == 1)
        try h.store.upsert(schedule)
        await h.executor.execute()
        #expect(h.calls.withLock { $0 } == 1)
        try h.store.upsert(schedule)
        h.zone.withLock { $0 = .gmt }
        h.clock.withLock { $0 = 1_775_016_000_000 }
        #expect(schedule.plan(now: h.clock.withLock { $0 }, zone: .gmt).scheduledFor == 1_775_016_000_000)
        h.rebuild(); await h.executor.execute()
        #expect(h.calls.withLock { $0 } == 1)
        #expect(try h.store.load().schedules[schedule.id]?.status == "needs_attention")
    }

    @Test(arguments: [false, true]) func consumedIndexSurvivesDSTAndZoneRollback(fall: Bool) async throws {
        let h = try Harness()
        let start = ISO8601DateFormatter().date(from: fall ? "2026-10-31T16:00:00Z" : "2026-03-07T17:00:00Z")!.timeIntervalSince1970 * 1000
        var schedule = RecurringScheduleTests.schedule(start: start)
        schedule.interval = .init(unit: "day", every: 1)
        schedule.destinationType = "ark"; schedule.destination = "ark-test-destination"
        schedule.nextOccurrenceIndex = 1
        let ny = TimeZone(identifier: "America/New_York")!
        let from = fall ? TimeZone.gmt : ny, to = fall ? ny : TimeZone.gmt
        let paidAt = schedule.occurrence(1, zone: from), shifted = schedule.occurrence(1, zone: to)
        #expect(shifted - paidAt == 3_600_000)
        h.zone.withLock { $0 = from }; h.clock.withLock { $0 = paidAt }
        try h.store.upsert(schedule); await h.executor.execute()
        #expect(h.calls.withLock { $0 } == 1)
        try h.store.upsert(schedule)
        h.zone.withLock { $0 = to }; h.clock.withLock { $0 = shifted }
        h.rebuild(); await h.executor.execute()
        #expect(h.calls.withLock { $0 } == 1)
        #expect(try h.store.load().schedules[schedule.id]?.status == "needs_attention")
    }
    @Test func consumedIndexSurvivesZoneRollbackAcrossPolicyRevisions() async throws {
        let h = try Harness()
        var revisionA = RecurringScheduleTests.schedule(start: 1_772_337_600_000)
        revisionA.destinationType = "ark"; revisionA.destination = "ark-test-destination"
        revisionA.nativeRevision = UUID()
        h.zone.withLock { $0 = TimeZone(identifier: "America/New_York")! }
        h.clock.withLock { $0 = 1_774_753_200_000 }
        try h.store.upsert(revisionA); await h.executor.execute()
        var revisionB = revisionA
        revisionB.startAt = h.clock.withLock { $0 } + 1; revisionB.nextRunAt = revisionB.startAt
        try await h.executor.save(revisionB, editing: true)
        h.clock.withLock { $0 += 1 }; await h.executor.execute()
        #expect(h.calls.withLock { $0 } == 2)
        try h.store.upsert(revisionA)
        h.zone.withLock { $0 = .gmt }; h.clock.withLock { $0 = 1_775_016_000_000 }
        h.rebuild(); await h.executor.execute()
        #expect(h.calls.withLock { $0 } == 2)
        #expect(try h.store.load().schedules[revisionA.id]?.status == "needs_attention")
    }

    @Test func explicitEditCreatesNewPolicyWithoutReusingConsumedOccurrence() async throws {
        let h = try Harness(); let original = try h.add()
        await h.executor.execute()
        var edit = original
        edit.startAt = h.now + 1; edit.nextRunAt = h.now + 1; edit.amountSat = 1234
        h.clock.withLock { $0 += 1 }
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
    @Test func consumedWatermarkSurvivesLaterRevisionAndRollback() async throws {
        let h = try Harness(); let revisionA = try h.add()
        await h.executor.execute()
        var revisionB = revisionA
        revisionB.startAt = h.now + 1; revisionB.nextRunAt = h.now + 1
        try await h.executor.save(revisionB, editing: true)
        h.clock.withLock { $0 += 1 }
        await h.executor.execute()
        #expect(h.calls.withLock { $0 } == 2)
        try h.store.upsert(revisionA)
        h.rebuild(); await h.executor.execute()
        #expect(h.calls.withLock { $0 } == 2)
        #expect(try h.store.load().schedules[revisionA.id]?.status == "needs_attention")
    }
    @Test func legacyJournalMigratesConsumptionBeforeAttemptReplacement() throws {
        let h = try Harness(); let schedule = try h.add()
        let owner = h.owner.withLock { $0 }
        var old = RecurringJournal.Entry(binding: owner, scheduleID: schedule.id, plan: schedule.plan(now: h.now), markedAt: h.now)
        old.resolved = true; old.consumed = true
        try JSONEncoder().encode([schedule.id: old]).write(to: h.journal.file)
        _ = try h.journal.load()
        var new = old; new.consumed = nil; new.policyRevision = UUID()
        try h.journal.save(new)
        #expect(try h.journal.consumed(binding: owner, scheduleID: schedule.id, at: h.now, policyRevision: nil, occurrenceIndex: old.plan.occurrenceIndex!))
        var otherWallet = owner; otherWallet.instance = UUID()
        #expect(try !h.journal.consumed(binding: otherWallet, scheduleID: schedule.id, at: h.now, policyRevision: nil, occurrenceIndex: old.plan.occurrenceIndex!))
    }

    @Test(arguments: [false, true]) func oldJournalMigrationRetainsUnknownRevisionHistory(v1: Bool) async throws {
        let h = try Harness(); let schedule = try h.add()
        let owner = h.owner.withLock { $0 }
        try h.store.bind(owner)
        var old = RecurringJournal.Entry(binding: owner, scheduleID: schedule.id,
                                         plan: schedule.plan(now: h.now), markedAt: h.now, policyRevision: UUID())
        old.resolved = true; old.consumed = true
        let attempts = try JSONSerialization.jsonObject(with: JSONEncoder().encode([schedule.id: old]))
        let binding = try JSONSerialization.jsonObject(with: JSONEncoder().encode(owner))
        let bytes: Data
        if v1 {
            bytes = try JSONSerialization.data(withJSONObject: ["version": 1, "attempts": attempts,
                "consumed": [["binding": binding, "scheduleID": schedule.id, "through": h.now]]])
        } else { bytes = try JSONEncoder().encode([schedule.id: old]) }
        try bytes.write(to: h.journal.file)
        _ = try h.journal.load()
        let migrated = try #require(JSONSerialization.jsonObject(with: Data(contentsOf: h.journal.file)) as? [String: Any])
        #expect(migrated["version"] as? Int == 2)
        let indices = try #require(migrated["consumedIndices"] as? [[String: Any]])
        #expect(indices.first?["indices"] as? [Int] == [old.plan.occurrenceIndex!])
        #expect(indices.first?["policyRevision"] as? String == old.policyRevision?.uuidString)
        var replacement = old; replacement.consumed = nil; replacement.policyRevision = UUID()
        try h.journal.save(replacement)
        // No timestamp/index for this rolled-back revision survives in the old journal.
        h.clock.withLock { $0 += 10 * 86_400_000 }
        h.zone.withLock { $0 = .gmt }
        h.rebuild(); await h.executor.execute()
        #expect(h.calls.withLock { $0 } == 0)
        #expect(try h.store.load().schedules[schedule.id]?.status == "needs_attention")
        #expect(try h.journal.consumed(binding: owner, scheduleID: schedule.id, at: h.now + 1,
                                     policyRevision: old.policyRevision, occurrenceIndex: old.plan.occurrenceIndex!))
        var other = owner; other.instance = UUID()
        #expect(try !h.journal.consumed(binding: other, scheduleID: schedule.id, at: h.now + 1,
                                      policyRevision: old.policyRevision, occurrenceIndex: old.plan.occurrenceIndex!))
    }
    @Test func v2IndexScopeDoesNotBlockFreshRevisionOrWallet() throws {
        let h = try Harness(); let schedule = try h.add(); let owner = h.owner.withLock { $0 }
        var entry = RecurringJournal.Entry(binding: owner, scheduleID: schedule.id,
                                           plan: schedule.plan(now: h.now), markedAt: h.now, policyRevision: UUID())
        entry.resolved = true; entry.consumed = true
        try h.journal.save(entry)
        let index = entry.plan.occurrenceIndex!
        #expect(try h.journal.consumed(binding: owner, scheduleID: schedule.id, at: h.now + 1,
                                     policyRevision: entry.policyRevision, occurrenceIndex: index))
        #expect(try !h.journal.consumed(binding: owner, scheduleID: schedule.id, at: h.now + 1,
                                      policyRevision: UUID(), occurrenceIndex: index))
        #expect(try h.journal.consumed(binding: owner, scheduleID: schedule.id, at: h.now,
                                     policyRevision: UUID(), occurrenceIndex: 0))
        var other = owner; other.instance = UUID()
        #expect(try !h.journal.consumed(binding: other, scheduleID: schedule.id, at: h.now,
                                      policyRevision: entry.policyRevision, occurrenceIndex: index))
        entry.plan.occurrenceIndex = index + 1; entry.plan.scheduledFor = h.now + 1
        try h.journal.save(entry); try h.journal.save(entry)
        let json = try #require(JSONSerialization.jsonObject(with: Data(contentsOf: h.journal.file)) as? [String: Any])
        let indices = try #require(json["consumedIndices"] as? [[String: Any]])
        #expect(indices.first?["indices"] as? [Int] == [index, index + 1])
    }

    @Test(arguments: ["ark", "lnurl", "offer", "lightning"]) func variableOrExcessiveFeesNeedApprovalAndNeverConsume(method: String) async throws {
        let h = try Harness(); let schedule = try h.add()
        h.method = method; h.fee = 501 // F = 250; cap = 500 for 50,000 sats.
        await h.executor.execute(); await h.executor.execute(manual: true)
        #expect(h.calls.withLock { $0 } == 0)
        #expect(try h.store.load().schedules[schedule.id]?.status == "needs_approval")
        #expect(try h.journal.load().isEmpty)
        #expect(h.saved == nil)
        #expect(h.notified.count == 1)
        #expect(h.notified.first?.lastError?.contains(method == "ark" ? "0 sats" : "500 sats") == true)
    }
    @Test(arguments: ["lnurl", "offer", "lightning"]) func belowCapLightningPaysAutomaticallyExactlyOnce(method: String) async throws {
        let h = try Harness(); let schedule = try h.add(); h.method = method; h.fee = 250
        await h.executor.execute(); await h.executor.execute(manual: true)
        #expect(h.calls.withLock { $0 } == 1)
        #expect(try h.store.load().schedules[schedule.id]?.status == "active")
        #expect(try h.store.load().schedules[schedule.id]?.occurrencesPaid == 1)
        #expect(!h.notified.contains { $0.status == "needs_approval" })
    }
    @Test func scheduleAboveCapWithLowQuoteNeedsApproval() async throws {
        let h = try Harness(); let schedule = try h.add(); h.method = "offer"; h.fee = 1
        h.fees = .init(minFeeSats: 0, baseFeeSats: 0, ppmExpiryTable: [.init(expiryBlocksThreshold: 0, ppm: 50_000)])
        await h.executor.execute()
        #expect(h.calls.withLock { $0 } == 0)
        #expect(try h.journal.load().isEmpty && h.saved == nil)
        #expect(h.notified.count == 1)
        #expect(h.notified.first?.status == "needs_approval")
        #expect(h.notified.first?.lastError == "Ark's published Lightning fees for this amount can reach 2500 sats, above your automatic limit of 1000 sats. Review it to pay manually.")
        #expect(try h.store.load().schedules[schedule.id]?.lastError == h.notified.first?.lastError)
    }
    @Test func arkInfoFailureUsesFallbackCap() async throws {
        let h = try Harness(); _ = try h.add(); h.method = "offer"; h.fee = 501; h.feeFetchFails = true
        await h.executor.execute()
        #expect(h.feeFetches == 1 && h.calls.withLock { $0 } == 0)
        #expect(h.notified.first?.lastError == "The fee for this payment (501 sats) is above your automatic limit of 500 sats. Review it to pay manually.")
    }
    @Test func finalGuardUsesCapturedScheduleAndQuoteWithoutRefetching() async throws {
        let h = try Harness(); _ = try h.add(); h.method = "offer"; h.fee = 800
        h.fees = .init(minFeeSats: 0, baseFeeSats: 600, ppmExpiryTable: [])
        // Only the captured schedule/quote allows this fee; fallback or refetch blocks it.
        h.beforePay = { h.fees = .init(minFeeSats: 5000, baseFeeSats: 0, ppmExpiryTable: []); h.fee = 5000 }
        await h.executor.execute()
        #expect(h.feeFetches == 1 && h.calls.withLock { $0 } == 1)
        #expect(!h.notified.contains { $0.status == "needs_approval" })
    }
    @Test func manualApprovalUsesReviewedQuoteAndSettlesOccurrenceOnce() async throws {
        let h = try Harness(); let schedule = try h.add(); h.method = "lnurl"; h.fee = 501
        await h.executor.execute()
        let review = try await h.executor.review(schedule.id)
        #expect(review.quote.fee == 501)
        await h.executor.execute(manual: true, approval: review)
        await h.executor.execute(manual: true, approval: review)
        #expect(h.calls.withLock { $0 } == 1)
        #expect(try h.store.load().schedules[schedule.id]?.occurrencesPaid == 1)
        #expect(try h.journal.load()[schedule.id]?.consumed == true)
    }
    @Test func manualReviewCannotOutlivePause() async throws {
        let h = try Harness(); let schedule = try h.add(); h.method = "offer"; h.fee = 501
        await h.executor.execute()
        let review = try await h.executor.review(schedule.id)
        try await h.executor.pause(schedule.id)
        await h.executor.execute(manual: true, approval: review)
        #expect(h.calls.withLock { $0 } == 0)
    }
    @Test(arguments: [-1.0, 86_400_000.0]) func finalGuardRejectsClockJumpDuringPreparation(delta: Double) async throws {
        let h = try Harness(); let schedule = try h.add()
        h.beforePay = { h.clock.withLock { $0 += delta } }
        await h.executor.execute()
        #expect(h.calls.withLock { $0 } == 0)
        #expect(try h.journal.load()[schedule.id]?.resolved == true)
        #expect(try h.journal.load()[schedule.id]?.consumed != true)
        #expect(try h.store.load().schedules[schedule.id]?.inFlight == nil)
        #expect(try h.store.load().schedules[schedule.id]?.occurrencesPaid == 0)
        #expect(!h.controller.locked)
    }

    struct ZoneChange: Decodable {
        var fromZone: String; var toZone: String; var startAt: Double
        var interval: RecurringInterval; var index: Int; var cached: Double
    }
    @Test(arguments: [false, true]) func idlePassAndTimeZoneEventRefreshDurableWakeAndReminders(event: Bool) async throws {
        for fixture in try Fixtures.load("recurring-zone-change", input: ZoneChange.self, expected: Double.self) {
            let h = try Harness()
            var schedule = RecurringScheduleTests.schedule(start: fixture.input.startAt)
            schedule.destinationType = "ark"; schedule.destination = "ark-test"
            schedule.interval = fixture.input.interval; schedule.nextOccurrenceIndex = fixture.input.index
            schedule.nextRunAt = fixture.input.cached
            try h.store.upsert(schedule)
            h.clock.withLock { $0 = fixture.input.startAt + 1000 }
            h.zone.withLock { $0 = TimeZone(identifier: fixture.input.toZone)! }
            if event { await h.executor.timeChanged() } else { await h.executor.execute() }
            #expect(try h.store.load().schedules[schedule.id]?.nextRunAt == fixture.expected)
            #expect(h.sentEntries.last?.first?.nextRunAt == Int64(floor(fixture.expected / 1000)))
            #expect(h.reminders.first?.nextRunAt == fixture.expected)
            #expect(h.calls.withLock { $0 } == 0)
            #expect(try h.store.load().pending == false)
        }
    }

    @Test(arguments: [false, true]) func expiredPushBudgetNeverStartsSubmission(duringPreparation: Bool) async throws {
        let h = try Harness(); let schedule = try h.add()
        let gate = RecurringReservationTests.Gate()
        let hold: @MainActor @Sendable () async throws -> Void = { await gate.wait() }
        if duringPreparation { h.beforePay = hold } else { h.preflight = hold }
        let result = await AppDelegate.completeWithin(.seconds(2)) { await h.executor.execute() }
        #expect(result == .failed)
        #expect(h.calls.withLock { $0 } == 0)
        try #require(await gate.entered)
        await gate.release()
        await h.executor.execute() // Join settlement, without extending the expired worker's budget.
        #expect(h.calls.withLock { $0 } == 0)
        #expect(try h.store.load().schedules[schedule.id]?.inFlight == nil)
        #expect(try h.store.load().schedules[schedule.id]?.occurrencesPaid == 0)
        #expect(try h.store.load().schedules[schedule.id]?.status == "active")
        #expect(try h.journal.load()[schedule.id]?.consumed != true)
        #expect(!h.controller.locked)
    }
    @Test func paymentAlreadySubmittedSettlesAfterPushDeadline() async throws {
        let h = try Harness(); let schedule = try h.add()
        let gate = RecurringReservationTests.Gate()
        h.afterPay = { await gate.wait() }
        let result = await AppDelegate.completeWithin(.seconds(2)) { await h.executor.execute() }
        #expect(result == .failed)
        #expect(h.calls.withLock { $0 } == 1)
        try #require(await gate.entered)
        await gate.release()
        await h.executor.execute()
        #expect(h.calls.withLock { $0 } == 1)
        #expect(try h.store.load().schedules[schedule.id]?.occurrencesPaid == 1)
        #expect(try h.journal.load()[schedule.id]?.consumed == true)
    }

    @Test func clockJumpBehindBackoffStopsEvenWhenSameOccurrenceIsDue() async throws {
        let h = try Harness(); var schedule = try h.add()
        schedule.startAt -= 5000; schedule.retryNotBefore = h.now - 1000
        try h.store.upsert(schedule)
        h.beforePay = { h.clock.withLock { $0 -= 2000 } }
        await h.executor.execute()
        #expect(h.calls.withLock { $0 } == 0)
        #expect(try h.journal.load()[schedule.id]?.consumed != true)
        #expect(!h.controller.locked)
    }

}
