import Foundation
import Testing
@testable import Noah

@MainActor struct RecurringProtectedReadRaceTests {
    @Test func reloadCannotPoisonReplacementAuthority() async throws {
        let h = try RecurringExecutorTests.Harness()
        defer { try? FileManager.default.removeItem(at: h.directory) }
        _ = try h.add()
        let store = RecurringStore(settings: h.settings)
        let directory = h.directory, owner = h.owner
        let armed = RecurringExecutorTests.Locked(true)
        store.protectPolicies(file: h.journal.file.appendingPathExtension("policies"), sync: { _ in }, binding: {
            let binding = owner.withLock { $0 }
            if armed.withLock({ value in let old = value; value = false; return old }) {
                // A concurrent lifecycle deletion completes after binding resolution.
                try FileManager.default.removeItem(at: directory)
                RecurringAutomationAuthority.deleted(directory: directory)
            }
            return binding
        })
        let executor = RecurringExecutor(dependencies: .init(store: store, journal: h.journal, controller: h.controller,
            ensureOpen: {}, binding: { owner.withLock { $0 } }, allowed: {}, quote: { _ in throw SendFailure.invalid }))
        await executor.reload()
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        #expect(RecurringAutomationAuthority(directory: directory, sync: { _ in }).allowed(), "An old reload must not latch replacement authority off")
    }
    @Test(arguments: ["reload", "status", "pause", "cancel", "resume", "save", "sync", "clock"], [false, true])
    func staleReadSkipsDeletedOrReplacedWallet(path: String, replacement: Bool) async throws {
        let h = try RecurringExecutorTests.Harness()
        defer { try? FileManager.default.removeItem(at: h.directory) }
        let schedule = try h.add()
        let store = RecurringStore(settings: h.settings)
        let directory = h.directory, owner = h.owner
        let armed = RecurringExecutorTests.Locked(true)
        let binding: @Sendable () throws -> RecurringStore.Binding = {
            let previous = owner.withLock { $0 }
            if armed.withLock({ value in let old = value; value = false; return old }) {
                try FileManager.default.removeItem(at: directory)
                RecurringAutomationAuthority.deleted(directory: directory)
                if replacement {
                    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
                    owner.withLock { $0.instance = UUID() }
                }
            }
            return previous
        }
        store.protectPolicies(file: h.journal.file.appendingPathExtension("policies"), sync: { _ in }, binding: binding)
        let executor = RecurringExecutor(dependencies: .init(store: store, journal: h.journal, controller: h.controller,
            ensureOpen: { Issue.record("Read paths must not open the wallet") }, binding: binding,
            allowed: {}, quote: { _ in throw SendFailure.invalid }))
        do {
            switch path {
            case "reload": await executor.reload()
            case "status": await executor.checkStatus(schedule.id)
            case "pause": try await executor.pause(schedule.id)
            case "cancel": try await executor.cancel(schedule.id)
            case "resume": try await executor.resume(schedule.id)
            case "save": try await executor.save(schedule, editing: true)
            case "sync": await executor.syncServer()
            default: await executor.timeChanged()
            }
        } catch is RecurringExecutor.NoWallet {} // Explicit mutations may report their vanished wallet.
        #expect(executor.error == nil)
        #expect(h.calls.withLock { $0 } == 0)
        #expect(!FileManager.default.fileExists(atPath: directory.appending(path: ".noah-recurring-automation.json").path))
        #expect(!FileManager.default.fileExists(atPath: h.journal.file.appendingPathExtension("policies").path))
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        #expect(RecurringAutomationAuthority(directory: directory, sync: { _ in }).allowed())
    }

    @Test func reloadWaitsForLifecycleDeletion() async throws {
        let h = try RecurringExecutorTests.Harness()
        defer { try? FileManager.default.removeItem(at: h.directory) }
        let mutation = WalletMutationGate()
        let entered = RecurringExecutorTests.Locked(false)
        let bindings = RecurringExecutorTests.Locked(0)
        let release = AsyncStream<Void>.makeStream()
        let directory = h.directory, owner = h.owner
        let deletion = Task {
            try await mutation.withLock {
                entered.withLock { $0 = true }
                var iterator = release.stream.makeAsyncIterator()
                _ = await iterator.next()
                try FileManager.default.removeItem(at: directory)
                RecurringAutomationAuthority.deleted(directory: directory)
            }
        }
        while !entered.withLock({ $0 }) { await Task.yield() }
        let executor = RecurringExecutor(dependencies: .init(store: h.store, journal: h.journal, controller: h.controller,
            ensureOpen: { Issue.record("Read paths must not open the wallet") }, binding: {
                bindings.withLock { $0 += 1 }
                return owner.withLock { $0 }
            }, allowed: {}, quote: { _ in throw SendFailure.invalid }, mutation: mutation))
        let read = Task { await executor.reload() }
        while await mutation.waitingCount == 0 { await Task.yield() }
        #expect(bindings.withLock { $0 } == 0)
        release.continuation.yield(()); release.continuation.finish()
        try await deletion.value; await read.value
        #expect(executor.error == nil)
        #expect(!FileManager.default.fileExists(atPath: directory.path))
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        #expect(RecurringAutomationAuthority(directory: directory, sync: { _ in }).allowed())
    }

    @Test func reloadRevalidatesStopGenerationBeforeAuthority() async throws {
        let h = try RecurringExecutorTests.Harness()
        defer { try? FileManager.default.removeItem(at: h.directory) }
        let generation = RecurringExecutorTests.Locked(0), owner = h.owner
        let executor = RecurringExecutor(dependencies: .init(store: h.store, journal: h.journal, controller: h.controller,
            ensureOpen: { Issue.record("Read paths must not open the wallet") }, binding: { owner.withLock { $0 } },
            allowed: {}, quote: { _ in throw SendFailure.invalid }, automationGeneration: {
                generation.withLock { value in value += 1; return String(value) }
            }))
        await executor.reload()
        #expect(executor.error == nil)
        #expect(!FileManager.default.fileExists(atPath: h.directory.appending(path: ".noah-recurring-automation.json").path))
    }

}
