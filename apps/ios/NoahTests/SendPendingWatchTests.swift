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

    @Test func nothingToWatchEndsAtOnce() async {
        let subject = pending.retry.controller(pending.retry.bark(), .init())
        var slept = 0
        await SendPendingWatch.run(controller: subject, pendingHashes: { Set<String>() }, sleep: { _ in slept += 1 })
        #expect(slept == 0)
    }
}
