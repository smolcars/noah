import BarkBoltFFI
import Foundation
import MMKV
@testable import Noah
import Testing

@MainActor struct RecurringExecutorTests {
    @Test(arguments: ["pause", "cancel", "edit"])
    func unpaidPolicyRollbackCannotResurrectPauseCancelOrEdit(action: String) async throws {
        let h = try Harness(); var original = try h.add()
        defer { try? FileManager.default.removeItem(at: h.directory) }
        original.startAt = h.now; original.nextRunAt = h.now
        try h.store.upsert(original)
        await h.executor.timeChanged() // First native load, without submitting money.
        let active = try #require(h.settings.string(forKey: RecurringStore.key))
        switch action {
        case "pause": try await h.executor.pause(original.id)
        case "cancel": try await h.executor.cancel(original.id)
        default:
            original.amountSat = 500; original.destination = "new-recipient"
            try await h.executor.save(original, editing: true)
        }
        try h.settings.setDurably(active, forKey: RecurringStore.key)
        h.rebuild() // New executor, same independent durable evidence.
        await h.executor.execute(); await h.executor.execute(manual: true)
        #expect(h.calls.withLock { $0 } == 0)
        #expect(try h.store.load().schedules[original.id]?.status == "needs_attention")
        #expect(try h.journal.load().isEmpty)
    }

    @Test func rollbackDuringFinalPreparationCannotBypassDurablePause() async throws {
        let h = try Harness(); let policy = try h.add()
        defer { try? FileManager.default.removeItem(at: h.directory) }
        h.beforePay = {
            let markedActive = try #require(h.settings.string(forKey: RecurringStore.key))
            try await h.executor.pause(policy.id)
            try h.settings.setDurably(markedActive, forKey: RecurringStore.key)
        }
        await h.executor.execute()
        #expect(h.calls.withLock { $0 } == 0)
        #expect(try h.store.load().schedules[policy.id]?.status == "needs_attention")
    }
    @Test func resumeSkipSurvivesRollbackToOriginalActivePolicy() async throws {
        let h = try Harness(); let policy = try h.add()
        defer { try? FileManager.default.removeItem(at: h.directory) }
        let active = try #require(h.settings.string(forKey: RecurringStore.key))
        try await h.executor.pause(policy.id); try await h.executor.resume(policy.id)
        try h.settings.setDurably(active, forKey: RecurringStore.key)
        await h.executor.execute(manual: true)
        #expect(h.calls.withLock { $0 } == 0)
        #expect(try h.store.load().schedules[policy.id]?.status == "needs_attention")
    }

    @Test func recoveredMMKVRollbackWaitsForHealthyStorageAndCannotPayTwice() async throws {
        let h = try Harness(); _ = try h.add()
        defer { try? FileManager.default.removeItem(at: h.directory) }
        try h.store.bind(h.owner.withLock { $0 })
        await h.executor.execute()
        #expect(h.calls.withLock { $0 } == 1)
        h.stopped.withLock { $0 = true }
        await h.executor.execute()
        #expect(h.calls.withLock { $0 } == 1)
        // Damaged RN settings import defaults, and never admit the older RN policy.
        let root = try MMKVRecoveryTests.copyFixture()
        defer { MMKVRecoveryTests.clean(root) }
        try MMKVRecoveryTests.corrupt(root, mode: "crc")
        h.settings = try SettingsStorage(rootPath: root.path)
        try h.settings.requireCompatibleRNStores()
        h.store = RecurringStore(settings: h.settings)
        h.rebuild()
        // Reopening alone cannot authorize execution; the launch/storage gate still has to clear.
        await h.executor.execute()
        #expect(h.calls.withLock { $0 } == 1)
        h.stopped.withLock { $0 = false }
        await h.executor.execute(); await h.executor.execute()
        #expect(h.calls.withLock { $0 } == 1)
        #expect(try h.store.load().schedules.isEmpty)
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
    @Test func statusCheckDuringSubmissionDoesNotStopSuccessfulSchedule() async throws {
        let h = try Harness(); let schedule = try h.add()
        defer { try? FileManager.default.removeItem(at: h.directory) }
        h.afterPay = {
            #expect(h.executor.running && h.controller.state == .submitting)
            await h.executor.reload() // Opening the recurring screen during submission.
            let sending = try #require(h.executor.schedules.first { $0.id == schedule.id })
            #expect(sending.inFlight != nil && sending.status == "active")
            let journal = try Data(contentsOf: h.journal.file)
            await h.executor.checkStatus(schedule.id)
            #expect(try h.store.load().schedules[schedule.id] == sending)
            #expect(try Data(contentsOf: h.journal.file) == journal)
            #expect(h.statusChecks == 0)
        }
        await h.executor.execute()
        await h.executor.execute() // Reopening the screen reuses the recorded occurrence.
        let saved = try #require(h.store.load().schedules[schedule.id])
        #expect(h.calls.withLock { $0 } == 1)
        #expect(saved.status == "active" && saved.occurrencesPaid == 1 && saved.nextOccurrenceIndex == 5)
        #expect(saved.inFlight == nil && saved.lastError == nil)
        #expect(saved.runs.count(where: { $0.status == "success" }) == 1)
        #expect(try h.journal.load()[schedule.id]?.resolved == true)
        #expect(try h.journal.load()[schedule.id]?.consumed == true)
        #expect(!h.notified.contains { $0.status == "needs_attention" })
        #expect(!h.controller.locked)
    }
    @Test func settledUncertainAttemptStillAllowsStatusCheck() async throws {
        let h = try Harness(); let schedule = try h.add(); h.ambiguous = true
        defer { try? FileManager.default.removeItem(at: h.directory) }
        await h.executor.execute()
        #expect(!h.executor.running && h.controller.state == .uncertain)
        #expect(try h.store.load().schedules[schedule.id]?.status == "needs_attention")
        let checks = h.statusChecks
        h.resolution = .paid(.init(amount: schedule.amountSat, proof: .submitReturnedArkSuccess))
        await h.executor.checkStatus(schedule.id)
        #expect(h.statusChecks == checks + 1 && h.calls.withLock { $0 } == 1)
        #expect(try h.store.load().schedules[schedule.id]?.occurrencesPaid == 1)
        #expect(try h.store.load().schedules[schedule.id]?.inFlight == nil)
        #expect(try h.journal.load()[schedule.id]?.resolved == true)
        #expect(try h.journal.load()[schedule.id]?.consumed == true)
        #expect(!h.controller.locked)
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
}
