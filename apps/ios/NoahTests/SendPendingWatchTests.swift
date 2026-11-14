import BarkBoltFFI
import Foundation
@testable import Noah
import Testing

/// The watch behind Send's pending result and the background window: it only reads bark's pending list and asks for
/// status checks, and stops when the attempt is no longer paying.
@MainActor struct SendPendingWatchTests {
    let pending: SendLightningPendingTests

    init() throws { pending = try SendLightningPendingTests() }

    /// Counts the seconds the watch slept and the reads it made.
    @MainActor final class Clock {
        var slept = 0, reads = 0
        var onSleep: (Int) -> Void = { _ in }
    }

    @Test func aCheckFollowsTheSendLeavingBarksPendingList() async throws {
        let bark = pending.paying()
        let subject = pending.retry.controller(bark, .init())
        try await pending.retry.send(subject)
        let clock = Clock(), hash = pending.hash
        // bark settles it during the third second; nothing drives it meanwhile.
        clock.onSleep = { second in
            if second == 3 { bark.checkpoint = nil; bark.finish("successful", effective: -1079) }
        }
        await SendPendingWatch.run(controller: subject, pendingHashes: { @MainActor in
            clock.reads += 1
            return bark.checkpoint == nil ? [] : [hash]
        }, sleep: { _ in clock.slept += 1; clock.onSleep(clock.slept) })
        guard case .succeeded = subject.state else { Issue.record("Expected Sent, got \(subject.state)"); return }
        // It stopped by itself once the payment ended, and the pay call ran once.
        #expect(clock.slept == 4 && clock.reads == 4)
        #expect(bark.calls == 1)
    }

    @Test func theBackgroundWindowAlsoChecksOnATimer() async throws {
        let bark = pending.paying()
        let subject = pending.retry.controller(bark, .init())
        try await pending.retry.send(subject)
        let clock = Clock(), hash = pending.hash
        var checks = 0
        // The pending list never changes (say the mailbox is down), but bark's records do: only a check finds out.
        clock.onSleep = { second in
            if second == 12 { bark.checkpoint = nil; bark.finish("failed", effective: 0); checks = second }
        }
        await SendPendingWatch.run(controller: subject, driveEvery: 10, pendingHashes: { Set([hash]) }, sleep: { _ in
            clock.slept += 1
            clock.onSleep(clock.slept)
        })
        #expect(subject.state == .failed)
        // Found at the 20 s check, not at 12 s.
        #expect(checks == 12 && clock.slept == 21)
    }

    /// A push's check waits out a check already under way (one the Send screen or the background window started): it
    /// reports what that check found, not the paying state from before it, and stops waiting once cancelled.
    @Test(.timeLimit(.minutes(1))) func aPushCheckWaitsForACheckUnderWay() async throws {
        let bark = pending.paying(), storage = SendPaymentTests.Storage()
        let (release, open) = AsyncStream<Void>.makeStream()
        defer { open.finish() }
        let subject = SendPaymentController(dependencies: .init(
            prepare: { _, _ in await bark.prepare() },
            pay: { try await bark.pay($0, $1, $2) },
            reconcile: { attempt in
                for await _ in release { break }
                return await bark.reconcile(attempt)
            },
            read: { storage.attempt }, write: { try storage.write($0) }))
        try await pending.retry.send(subject)
        let earlier = Task { await subject.checkStatus() }
        try await SendPaymentTests.waitUntil { subject.checking }
        // bark settles it while that check is under way.
        bark.checkpoint = nil
        bark.finish("successful", effective: -1079)

        // Cancelled (the push's deadline), it stops waiting and checks nothing itself.
        let clock = Clock()
        let cancelled = Task { try await SendPendingWatch.checkOnce(controller: subject) { _ in clock.slept += 1; await Task.yield() } }
        try await SendPaymentTests.waitUntil { clock.slept > 0 }
        cancelled.cancel()
        await #expect(throws: CancellationError.self) { try await cancelled.value }
        #expect(subject.checking && subject.lightningSendPending)

        // Otherwise it waits for that check to end and reports what it found.
        clock.slept = 0
        let finished = try await SendPendingWatch.checkOnce(controller: subject) { _ in
            clock.slept += 1
            if clock.slept == 1 { open.finish() }
            await Task.yield()
        }
        await earlier.value
        #expect(finished && clock.slept > 0)
        guard case .succeeded = subject.state else { Issue.record("Expected Sent, got \(subject.state)"); return }
        #expect(bark.calls == 1)
    }

    @Test func nothingToWatchEndsAtOnce() async {
        let subject = pending.retry.controller(pending.retry.bark(), .init())
        var slept = 0
        await SendPendingWatch.run(controller: subject, pendingHashes: { Set<String>() }, sleep: { _ in slept += 1 })
        #expect(slept == 0)
    }
}
