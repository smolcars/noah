import Foundation
import Synchronization
import Testing
@testable import Noah

/// Exit and VTXO pages reload when the wallet reports a change (a renew, an exit step, arriving funds),
/// not at their next minute, and a change that lands during a load isn't dropped.
@MainActor
struct OnchainChangesTests {
    final class Reads: Sendable { let count = Mutex(0) }
    @MainActor final class Gate { var allowed = true; var asked = 0 }
    nonisolated static let overview = OnchainOverview(walletID: "w", vtxos: [], exits: [], confirmed: 0, tip: 100, pendingTotal: 0)

    @Test func aChangeReloadsAtOnceUnlessThePageHoldsAReview() async throws {
        let reads = Reads(), gate = Gate()
        let (changes, change) = AsyncStream<Void>.makeStream()
        var dependencies = OnchainModelTests().dependencies(.init())
        dependencies.overview = { reads.count.withLock { $0 += 1 }; return Self.overview }
        dependencies.changes = { changes }
        let model = OnchainModel(dependencies: dependencies)
        let observer = Task { await model.observeChanges { gate.asked += 1; return gate.allowed } }
        defer { observer.cancel() }
        change.yield()
        try await SendPaymentTests.waitUntil { reads.count.withLock { $0 } == 1 }
        // The page's own guard (a review open, busy, in the background) skips it, as the minute loop does.
        gate.allowed = false
        change.yield()
        try await SendPaymentTests.waitUntil { gate.asked == 2 }
        gate.allowed = true
        change.yield()
        try await SendPaymentTests.waitUntil { reads.count.withLock { $0 } == 2 }
        #expect(gate.asked == 3)
    }

    @Test func aLoadAskedForDuringALoadRunsAgainAfterIt() async throws {
        let reads = Reads()
        let (release, open) = AsyncStream<Void>.makeStream()
        var dependencies = OnchainModelTests().dependencies(.init())
        dependencies.overview = {
            // The first read waits for the test, as a slow read would while the wallet commits.
            if reads.count.withLock({ $0 += 1; return $0 }) == 1 { for await _ in release { break } }
            return Self.overview
        }
        let model = OnchainModel(dependencies: dependencies)
        let first = Task { await model.load() }
        try await SendPaymentTests.waitUntil { reads.count.withLock { $0 } == 1 }
        await model.load()
        #expect(reads.count.withLock { $0 } == 1)
        open.yield()
        await first.value
        #expect(reads.count.withLock { $0 } == 2 && model.overview != nil)
        await model.load()
        #expect(reads.count.withLock { $0 } == 3)
    }
}
