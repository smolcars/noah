import BarkBoltFFI
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

    /// A commit after the page's first read began but before bark's subscription existed has no event of its own:
    /// the subscription's `initial` stands for it, and brings exactly one load after that first read.
    @Test func aChangeBeforeTheSubscriptionStartedReloadsOnceAfterTheFirstRead() async throws {
        let reads = Reads(), gate = Gate()
        let (release, open) = AsyncStream<Void>.makeStream()
        var dependencies = OnchainModelTests().dependencies(.init())
        dependencies.overview = {
            if reads.count.withLock({ $0 += 1; return $0 }) == 1 { for await _ in release { break } }
            return Self.overview
        }
        dependencies.changes = {
            OnchainModel.Dependencies.changes(subscribe: { AsyncThrowingStream { $0.yield(.init(sequence: 0, reason: "initial")) } },
                                              journal: AsyncStream { _ in })
        }
        let model = OnchainModel(dependencies: dependencies)
        let first = Task { await model.load() }
        try await SendPaymentTests.waitUntil { reads.count.withLock { $0 } == 1 }
        let observer = Task { await model.observeChanges { gate.asked += 1; return true } }
        defer { observer.cancel() }
        try await SendPaymentTests.waitUntil { gate.asked == 1 }
        open.yield()
        await first.value
        #expect(reads.count.withLock { $0 } == 2)
        try await Task.sleep(for: .milliseconds(200))
        #expect(reads.count.withLock { $0 } == 2 && gate.asked == 1)
    }

    /// bark ends a subscription after `resyncRequired`; the new one's `initial` covers the same gap.
    @Test func aResubscriptionsInitialEventReloads() async throws {
        let reads = Reads(), subscriptions = Reads()
        let (second, secondEvents) = AsyncThrowingStream<StateChangeEvent, Error>.makeStream()
        var dependencies = OnchainModelTests().dependencies(.init())
        dependencies.overview = { reads.count.withLock { $0 += 1 }; return Self.overview }
        dependencies.changes = {
            OnchainModel.Dependencies.changes(subscribe: {
                guard subscriptions.count.withLock({ $0 += 1; return $0 }) == 1 else { return second }
                return AsyncThrowingStream { $0.yield(.init(sequence: 1, reason: "resyncRequired")); $0.finish() }
            }, journal: AsyncStream { _ in })
        }
        let model = OnchainModel(dependencies: dependencies)
        let observer = Task { await model.observeChanges { true } }
        defer { observer.cancel() }
        try await SendPaymentTests.waitUntil { reads.count.withLock { $0 } == 1 && subscriptions.count.withLock { $0 } == 2 }
        secondEvents.yield(.init(sequence: 0, reason: "initial"))
        try await SendPaymentTests.waitUntil { reads.count.withLock { $0 } == 2 }
    }

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

    /// A retained receipt's status check that finds nothing new neither saves nor announces, so reloads on
    /// journal changes settle: one real change ("confirmed" recorded) gives one more load, not a loop.
    @Test(.timeLimit(.minutes(1))) func unchangedStatusChecksSettle() async throws {
        let file = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: file) }
        let journal = OnchainOperationJournal(file: file)
        _ = try await journal.perform(BoardPreparedTests.quote) { OnchainReceipt(message: "Submitted", txid: "exact", onchainFee: 1543) }
        let reads = WalletSession.OnchainAttemptReads(walletID: "wallet", transactionIDs: { [] }, vtxoState: { _ in "" },
                                                      exitState: { _ in nil }, transactionStatus: { _ in .confirmed(123) })
        let calls = OnchainModelTests.Calls()
        var dependencies = OnchainModelTests().dependencies(calls)
        dependencies.attempt = { try await journal.loadAll() }
        dependencies.check = { try await WalletSession.checkOnchainAttempt($0, journal: journal, reads: reads) }
        let changes = journal.changes.stream()
        dependencies.changes = { changes }
        let model = OnchainModel(dependencies: dependencies)
        let observer = Task { await model.observeChanges { true } }
        defer { observer.cancel() }
        await model.load()
        try await Task.sleep(for: .milliseconds(500))
        let settled = await calls.reads
        #expect(settled <= 3)
        try await Task.sleep(for: .milliseconds(300))
        #expect(await calls.reads == settled)
        #expect(try await journal.load()?.receipt?.chainStatus == .confirmed(123))
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

    /// After an app switch the foreground sync holds the wallet for a moment: the page keeps what it showed, shows no
    /// error, and reads once the wallet is ready. One unavailable for a real reason is still cleared and says so.
    @Test func aBrieflyBusyWalletKeepsTheLastStateAndReadsOnceReady() async {
        let calls = OnchainModelTests.Calls(), settles = Mutex(true)
        var dependencies = OnchainModelTests().dependencies(calls)
        dependencies.settle = {
            guard settles.withLock({ $0 }) else { return false }
            await calls.setReady(true)
            return true
        }
        let model = OnchainModel(dependencies: dependencies)
        await model.load()
        await calls.setReady(false)
        await model.load()
        #expect(await calls.reads == 2)
        #expect(model.overview != nil && model.error == nil)
        settles.withLock { $0 = false }
        await calls.setReady(false)
        await model.load()
        #expect(await calls.reads == 2)
        #expect(model.overview == nil && model.error == OnchainFailure.unavailable.errorDescription)
    }

    /// A sync that starts during a read doesn't wipe the page: it reads again once the wallet is ready.
    @Test func aSyncStartingDuringAReadReadsAgainInsteadOfClearing() async {
        let calls = OnchainModelTests.Calls()
        var dependencies = OnchainModelTests().dependencies(calls)
        dependencies.overview = {
            let overview = await calls.read()
            if await calls.reads == 1 { await calls.setReady(false) }
            return overview
        }
        dependencies.settle = { await calls.setReady(true); return true }
        let model = OnchainModel(dependencies: dependencies)
        await model.load()
        #expect(await calls.reads == 2)
        #expect(model.overview != nil && model.error == nil)
    }

    @Test func onlyABusyOrOpeningWalletSettles() {
        #expect(ExitRules.settling(initialized: true, loaded: true, suspended: false, background: true))
        #expect(ExitRules.settling(initialized: true, loaded: false, suspended: false, background: false))
        #expect(!ExitRules.settling(initialized: true, loaded: false, suspended: true, background: true))
        #expect(!ExitRules.settling(initialized: false, loaded: false, suspended: false, background: false))
        #expect(!ExitRules.settling(initialized: true, loaded: true, suspended: false, background: false))
    }
}
