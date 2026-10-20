import Foundation
import Testing
@testable import Noah

@MainActor struct RecurringOnboardingTests {
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
            ensureOpen: { if stopped.withLock({ $0 }) { throw AppServices.WalletSuspended() } }, binding: binding,
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
