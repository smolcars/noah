import Foundation
@testable import Noah
import Testing

@MainActor struct RecurringWriteFailureTests {
    @Test(arguments: ["pauseQuote", "cancelQuote", "editQuote", "pauseSubmit", "cancelSubmit"])
    func failedPolicyWriteRevokesPendingPayment(action: String) async throws {
        let h = try RecurringExecutorTests.Harness(); let schedule = try h.add()
        defer { try? FileManager.default.removeItem(at: h.directory) }
        var failedBytes: String?
        let fail: @MainActor () async throws -> Void = {
            failedBytes = h.settings.string(forKey: RecurringStore.key)
            h.storeWriteFails.withLock { $0 = true }
            await #expect(throws: (any Error).self) {
                if action.hasPrefix("pause") { try await h.executor.pause(schedule.id) }
                else if action.hasPrefix("cancel") { try await h.executor.cancel(schedule.id) }
                else {
                    var edit = schedule; edit.startAt = h.now; edit.nextRunAt = h.now; edit.amountSat += 1
                    try await h.executor.save(edit, editing: true)
                }
            }
            // The storage recovers, but a read of the old policy cannot undo the stop intent.
            h.storeWriteFails.withLock { $0 = false }
        }
        if action.hasSuffix("Quote") { h.preflight = fail } else { h.beforePay = fail }
        await h.executor.execute()
        #expect(h.calls.withLock { $0 } == 0)
        #expect(h.settings.string(forKey: RecurringStore.key) == failedBytes)
        #expect(throws: (any Error).self) { try h.store.checkAutomationAllowed() }
        #expect(throws: (any Error).self) { try h.store.load() }
        #expect(throws: (any Error).self) { try h.store.setPending(false) }
        h.preflight = nil; h.beforePay = nil
        await h.executor.execute()
        #expect(h.calls.withLock { $0 } == 0)
        // A fresh process may trust durable state; the failed store itself stays latched.
        let reopened = RecurringStore(settings: h.settings)
        try reopened.checkAutomationAllowed()
        #expect(try reopened.load().schedules[schedule.id] != nil)
        #expect(try h.journal.load()[schedule.id]?.consumed != true)
    }

    @Test(arguments: [false, true])
    func failedLifecycleStopWriteRevokesSubmission(delete: Bool) async throws {
        let h = try RecurringExecutorTests.Harness(); let schedule = try h.add()
        defer { try? FileManager.default.removeItem(at: h.directory) }
        let location = WalletDataLocation(variant: .regtest, documentsDirectory: h.directory)
        let session = WalletSession(variant: .regtest, location: location)
        let fail = RecurringExecutorTests.Locked(true), settings = h.settings
        let lifecycle = WalletLifecycle(dependencies: .init(
            session: session, location: location,
            seed: KeychainPassword(service: "recurring-stop-\(UUID())", account: "test"),
            authToken: KeychainPassword(service: "recurring-stop-token-\(UUID())", account: "test"),
            settings: h.settings, scratch: ScratchSpace(root: h.directory.appending(path: "scratch")),
            appGroup: nil, mutation: WalletMutationGate(), deregister: {}, walletChanged: {},
            persistAutomationStop: { generation in
                if fail.withLock({ $0 }) { throw CocoaError(.fileWriteUnknown) }
                try settings.setDurably(generation, forKey: WalletLifecycle.automationStopKey)
            }))
        h.beforePay = {
            await #expect(throws: (any Error).self) {
                if delete { try await lifecycle.delete() } else { try await lifecycle.suspend() }
            }
            #expect(h.settings.string(forKey: WalletLifecycle.automationStopKey) == nil)
            #expect(!WalletLifecycle.automationAllowed(settings: h.settings))
        }
        await h.executor.execute()
        #expect(h.calls.withLock { $0 } == 0)
        #expect(try h.journal.load()[schedule.id]?.consumed != true)
        h.beforePay = nil
        await h.executor.execute()
        #expect(h.calls.withLock { $0 } == 0)
        // A successful durable stop may clear the latch; resume alone cannot.
        try lifecycle.resume()
        #expect(!WalletLifecycle.automationAllowed(settings: h.settings))
        fail.withLock { $0 = false }
        try await lifecycle.suspend()
        #expect(WalletLifecycle.automationAllowed(settings: h.settings))
        #expect(h.settings.string(forKey: WalletLifecycle.automationStopKey) != nil)
    }
}
