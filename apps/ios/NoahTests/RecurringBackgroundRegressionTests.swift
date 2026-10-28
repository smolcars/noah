import Foundation
@testable import Noah
import Testing
import UIKit

@MainActor struct RecurringBackgroundRegressionTests {
    private func waitUntil(_ condition: @MainActor () -> Bool) async throws {
        let deadline = ContinuousClock.now + .seconds(5)
        while !condition(), ContinuousClock.now < deadline { await Task.yield() }
        try #require(condition())
    }

    @Test(arguments: ["quote", "prepare"])
    func backgroundDuringQuoteMustRevokeTimerSubmission(stage: String) async throws {
        let h = try RecurringExecutorTests.Harness(); _ = try h.add()
        defer { try? FileManager.default.removeItem(at: h.directory) }
        let minutes = RecurringForegroundRunnerTests.Minutes(), center = NotificationCenter()
        var release: CheckedContinuation<Void, Never>?
        var quoteEntered = false
        let block: @MainActor () async -> Void = {
            quoteEntered = true
            await withCheckedContinuation { release = $0 }
        }
        if stage == "quote" { h.preflight = block } else { h.beforePay = block }
        let runner = RecurringForegroundRunner(center: center, sleep: { try await minutes.sleep($0) }) {
            await h.executor.execute()
        }
        defer { runner.stop() }
        runner.start()
        try await waitUntil { minutes.waiting }
        minutes.tick()
        try await waitUntil { quoteEntered && release != nil }
        center.post(name: UIScene.willDeactivateNotification, object: nil)
        release?.resume()
        try await waitUntil { !h.executor.running }
        #expect(h.calls.withLock { $0 } == 0)
        #expect(try h.journal.load().values.allSatisfy { $0.consumed != true })
        h.preflight = nil; h.beforePay = nil
        runner.start()
        try await waitUntil { minutes.waiting }
        h.clock.withLock { $0 += 86_400_000 }
        minutes.tick()
        try await waitUntil { h.calls.withLock { $0 } == 1 && !h.executor.running }
        runner.stop()
    }

    @Test func deactivationAfterSubmissionStillSettlesOnce() async throws {
        let h = try RecurringExecutorTests.Harness(); let schedule = try h.add()
        defer { try? FileManager.default.removeItem(at: h.directory) }
        let minutes = RecurringForegroundRunnerTests.Minutes(), center = NotificationCenter()
        var release: CheckedContinuation<Void, Never>?
        h.afterPay = { await withCheckedContinuation { release = $0 } }
        let runner = RecurringForegroundRunner(center: center, sleep: { try await minutes.sleep($0) }) {
            await h.executor.execute()
        }
        defer { runner.stop() }
        runner.start()
        try await waitUntil { minutes.waiting }
        minutes.tick()
        try await waitUntil { release != nil }
        center.post(name: UIScene.willDeactivateNotification, object: nil)
        release?.resume()
        try await waitUntil { !h.executor.running }
        #expect(h.calls.withLock { $0 } == 1)
        #expect(try h.journal.load()[schedule.id]?.consumed == true)
        #expect(try h.store.load().schedules[schedule.id]?.occurrencesPaid == 1)
        await h.executor.execute()
        #expect(h.calls.withLock { $0 } == 1)
    }
}
