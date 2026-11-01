@testable import Noah
import Testing

/// A cold launch and each return to the front read Home, and wait for Home's history read, before the foreground pass: its
/// on-chain sync holds the on-chain wallet for its whole Esplora round trip, and Home's balance and history would wait for it.
@MainActor
struct ForegroundCatchUpTests {
    typealias Gate = RecurringReservationTests.Gate

    @Test func homeIsReadBeforeThePassAndAgainAfterIt() async {
        var calls: [String] = []
        await ForegroundCatchUp(home: { calls.append("home") }, history: { calls.append("history") }, pass: { calls.append("pass") }) {
            calls.append("after the pass")
            return true
        }.run()
        #expect(calls == ["home", "history", "pass", "after the pass", "home"])
    }

    /// The wallet closed or the app left during the pass: nothing reads it again.
    @Test func homeIsNotReadAgainOnceTheWalletIsGone() async {
        var calls: [String] = []
        await ForegroundCatchUp(home: { calls.append("home") }, history: {}, pass: { calls.append("pass") }) { false }.run()
        #expect(calls == ["home", "pass"])
    }

    /// Home's history read (`HistoryModel`, which reads on its own) starting late and still running after Home's read:
    /// the pass waits for it.
    @Test func aDelayedHistoryReadKeepsThePassFromStarting() async throws {
        let session = WalletSession(), late = Gate(), reading = Gate()
        let baseline = await session.historyReadsFinished
        let calls = RecurringExecutorTests.Locked<[String]>([])
        let catchUp = Task {
            await ForegroundCatchUp(home: { calls.withLock { $0.append("home") } }, history: {
                await session.waitForHistoryReads(finishedSince: baseline, within: .seconds(30))
            }, pass: { calls.withLock { $0.append("pass") } }) { false }.run()
        }
        let history = Task {
            await late.wait()
            await session.historyReadStarted()
            await reading.wait()
            await session.historyReadFinished()
        }
        try await waitUntil { await late.entered }
        await yieldABit()
        #expect(calls.withLock { $0 } == ["home"], "no history read yet")
        await late.release()
        try await waitUntil { await reading.entered }
        await yieldABit()
        #expect(calls.withLock { $0 } == ["home"], "the read is running")
        await reading.release()
        await history.value
        await catchUp.value
        #expect(calls.withLock { $0 } == ["home", "pass"])
    }

    /// A return only waits for a read already running; a history that never loads holds the pass for the limit at most.
    @Test func thePassWaitsForHistoryOnlyWhileAReadRunsAndAtMostTheLimit() async {
        let session = WalletSession()
        var started = ContinuousClock.now
        await session.waitForHistoryReads(finishedSince: nil, within: .seconds(30))
        #expect(started.duration(to: .now) < .seconds(5), "nothing running: no wait")
        await session.historyReadStarted()
        started = ContinuousClock.now
        await session.waitForHistoryReads(finishedSince: nil, within: .milliseconds(200))
        #expect(started.duration(to: .now) >= .milliseconds(200))
        await session.historyReadFinished()
        let baseline = await session.historyReadsFinished
        started = ContinuousClock.now
        await session.waitForHistoryReads(finishedSince: baseline, within: .milliseconds(200))
        #expect(started.duration(to: .now) >= .milliseconds(200), "a launch waits for a read after the open, up to the limit")
        #expect(started.duration(to: .now) < .seconds(5))
    }

    private func waitUntil(_ condition: @MainActor () async -> Bool) async throws {
        let deadline = ContinuousClock.now + .seconds(5)
        while !(await condition()), ContinuousClock.now < deadline { await Task.yield() }
        try #require(await condition())
    }

    /// Long enough for the catch-up to reach the pass if nothing held it (the wait rechecks every 50 ms).
    private func yieldABit() async { try? await Task.sleep(for: .milliseconds(300)) }
}
