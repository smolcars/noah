import Foundation
@testable import Noah
import Testing

@MainActor struct RecurringAdmissionRaceTests {
    @Test(arguments: [false, true])
    func deletionAfterAdmissionMustNotPoisonNewWallet(replacement: Bool) async throws {
        let h = try RecurringExecutorTests.Harness()
        defer { try? FileManager.default.removeItem(at: h.directory) }
        let location = WalletDataLocation(variant: .regtest, documentsDirectory: h.directory.appending(path: "race"))
        try FileManager.default.createDirectory(at: location.directory, withIntermediateDirectories: true)
        try Data().write(to: location.database)
        var d = RecurringExecutor.Dependencies.live(settings: h.settings, controller: h.controller, location: location)
        let owner = h.owner
        d.binding = {
            guard location.databaseExists else { throw CocoaError(.fileNoSuchFile) }
            return owner.withLock { $0 }
        }
        d.automationGeneration = {
            // Model deletion completing after allowed(), before the authority read.
            try? FileManager.default.removeItem(at: location.directory)
            RecurringAutomationAuthority.deleted(directory: location.directory)
            if replacement {
                try? FileManager.default.createDirectory(at: location.directory, withIntermediateDirectories: true)
                try? Data().write(to: location.database)
                owner.withLock { $0.instance = UUID() }
            }
            return nil
        }
        d.ensureOpen = {}; d.sync = { _ in }; d.notifications = { _, _ in }; d.refresh = {}
        let executor = RecurringExecutor(dependencies: d)
        await executor.execute()
        #expect(executor.error == nil)
        #expect(!FileManager.default.fileExists(atPath: location.directory.appending(path: ".noah-recurring-automation.json").path))
        try FileManager.default.createDirectory(at: location.directory, withIntermediateDirectories: true)
        try Data().write(to: location.database)
        #expect(
            RecurringAutomationAuthority(directory: location.directory).allowed(),
            "A completed deletion must not let old work poison the replacement wallet")
    }
    @Test func waitingTriggerRevalidatesAfterDeletionReleasesLifecycleGate() async throws {
        let h = try RecurringExecutorTests.Harness()
        defer { try? FileManager.default.removeItem(at: h.directory) }
        let location = WalletDataLocation(variant: .regtest, documentsDirectory: h.directory.appending(path: "waiting"))
        try FileManager.default.createDirectory(at: location.directory, withIntermediateDirectories: true)
        try Data().write(to: location.database)
        let mutation = WalletMutationGate()
        let entered = RecurringExecutorTests.Locked(false)
        let release = AsyncStream<Void>.makeStream()
        let deletion = Task {
            try await mutation.withLock {
                entered.withLock { $0 = true }
                var iterator = release.stream.makeAsyncIterator()
                _ = await iterator.next()
                try FileManager.default.removeItem(at: location.directory)
                RecurringAutomationAuthority.deleted(directory: location.directory)
            }
        }
        while !entered.withLock({ $0 }) { await Task.yield() }
        var d = RecurringExecutor.Dependencies.live(settings: h.settings, controller: h.controller, location: location)
        let owner = h.owner, bindings = RecurringExecutorTests.Locked(0)
        d.binding = {
            bindings.withLock { $0 += 1 }
            guard location.databaseExists else { throw CocoaError(.fileNoSuchFile) }
            return owner.withLock { $0 }
        }
        d.ensureOpen = {}; d.sync = { _ in }; d.notifications = { _, _ in }; d.refresh = {}
        d.mutation = mutation
        let executor = RecurringExecutor(dependencies: d)
        let trigger = Task { await executor.execute() }
        while await mutation.waitingCount == 0 { await Task.yield() }
        #expect(bindings.withLock { $0 } == 0)
        release.continuation.yield(()); release.continuation.finish()
        try await deletion.value
        await trigger.value
        #expect(executor.error == nil)
        #expect(h.calls.withLock { $0 } == 0)
        #expect(!FileManager.default.fileExists(atPath: location.directory.path))
        try FileManager.default.createDirectory(at: location.directory, withIntermediateDirectories: true)
        try Data().write(to: location.database)
        #expect(RecurringAutomationAuthority(directory: location.directory).allowed())
    }
}
