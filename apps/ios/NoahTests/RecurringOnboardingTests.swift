import Foundation
@testable import Noah
import Testing

@MainActor struct RecurringOnboardingTests {
    // swiftlint:disable:next discouraged_optional_boolean - nil is a third case: no saved wallet state at all
    @Test(arguments: [nil, false, true] as [Bool?]) func liveAdmissionSkipsWelcomeThenRunsTheCreatedWallet(initialized: Bool?) async throws {
        let h = try RecurringExecutorTests.Harness()
        defer { try? FileManager.default.removeItem(at: h.directory) }
        let location = WalletDataLocation(variant: .regtest, documentsDirectory: h.directory.appending(path: "welcome"))
        try h.settings.setPersistedState(PersistedWalletState(isInitialized: initialized), key: PersistedWalletState.key, version: 0)
        let owner = h.owner
        let binding: @Sendable () throws -> RecurringStore.Binding = {
            guard location.databaseExists else { throw CocoaError(.fileNoSuchFile) }
            return owner.withLock { $0 }
        }
        var d = RecurringExecutor.Dependencies.live(settings: h.settings, controller: h.controller, location: location)
        d.binding = binding
        d.ensureOpen = {}
        d.quote = { schedule in await h.quoted(schedule, fees: RecurringFeePolicy.fallback) }
        d.now = { 1000 }
        d.sync = { _ in }; d.notifications = { _, _ in }; d.refresh = {}
        let store = d.store, journal = d.journal
        let executor = RecurringExecutor(dependencies: d)
        await executor.timeChanged() // AppDelegate clock/timezone callbacks on Welcome.
        await executor.execute() // Foreground timer, push and BGTask share this entry.
        #expect(executor.error == nil)
        #expect(!FileManager.default.fileExists(atPath: location.directory.path))
        #expect(h.calls.withLock { $0 } == 0)
        try FileManager.default.createDirectory(at: location.directory, withIntermediateDirectories: true)
        await executor.timeChanged() // A directory alone is not a wallet database.
        #expect(!FileManager.default.fileExists(atPath: location.directory.appending(path: ".noah-recurring-automation.json.initialized").path))
        try Data().write(to: location.database)
        if initialized == false {
            #expect(throws: (any Error).self) { try d.allowed() }
            try h.settings.setPersistedState(PersistedWalletState(isInitialized: true), key: PersistedWalletState.key, version: 0)
        }
        try d.allowed() // Missing initialization still admits an existing wallet.
        try store.bind(binding())
        #expect(RecurringAutomationAuthority(directory: location.directory).allowed())
        var schedule = RecurringScheduleTests.schedule(start: 1000)
        schedule.destinationType = "ark"
        try store.upsert(schedule)
        await executor.timeChanged(); await executor.execute(); await executor.execute(manual: true)
        #expect(h.calls.withLock { $0 } == 1)
        #expect(try store.load().schedules[schedule.id]?.occurrencesPaid == 1)
        #expect(try journal.load()[schedule.id]?.consumed == true)
        #expect(executor.error == nil)
    }

    @Test func preOnboardingTriggerMustNotPoisonTheNextWalletAuthority() async throws {
        let h = try RecurringExecutorTests.Harness()
        defer { try? FileManager.default.removeItem(at: h.directory) }
        let directory = h.directory.appending(path: "future-wallet")
        let owner = h.owner, stopped = h.stopped
        stopped.withLock { $0 = true }
        let binding: @Sendable () throws -> RecurringStore.Binding = {
            guard FileManager.default.fileExists(atPath: directory.path) else { throw CocoaError(.fileNoSuchFile) }
            return owner.withLock { $0 }
        }
        let store = RecurringStore(settings: h.settings)
        let journal = RecurringJournal(file: directory.appending(path: "journal.json"))
        store.protectPolicies(file: journal.file.appendingPathExtension("policies"), sync: { _ in }, binding: binding)
        let executor = RecurringExecutor(dependencies: .init(store: store, journal: journal, controller: h.controller,
                                                             ensureOpen: { if stopped.withLock({ $0 }) { throw AppServices.WalletSuspended() } },
                                                             binding: binding,
                                                             allowed: { if stopped.withLock({ $0 }) { throw AppServices.WalletSuspended() } },
                                                             quote: { _ in throw SendFailure.invalid }))
        await executor.timeChanged(); await executor.execute()
        #expect(!FileManager.default.fileExists(atPath: directory.path))
        #expect(h.calls.withLock { $0 } == 0)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        stopped.withLock { $0 = false }
        try store.bind(binding())
        #expect(RecurringAutomationAuthority(directory: directory).allowed())
        #expect(try store.automationGeneration() != nil)
    }
}
