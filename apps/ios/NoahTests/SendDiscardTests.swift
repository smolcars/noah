import BarkBoltFFI
import Foundation
import Testing

@testable import Noah

struct SendDiscardTests {
    actor ReservedInputs {
        var failures: Int
        let cancellation: Bool
        var calls = 0
        var reserved = true
        var sawCallerCancellation = false
        var entered = false
        var gate: CheckedContinuation<Void, Never>?
        var suspended = false
        init(failures: Int, cancellation: Bool = false) {
            self.failures = failures
            self.cancellation = cancellation
        }
        func suspend() { suspended = true }
        func resume() { suspended = false; gate?.resume(); gate = nil }
        func discard() async throws {
            calls += 1
            entered = true
            if suspended { await withCheckedContinuation { gate = $0 } }
            sawCallerCancellation = sawCallerCancellation || Task.isCancelled
            try Task.checkCancellation()
            if failures > 0 {
                failures -= 1
                if cancellation { throw CancellationError() }
                throw CocoaError(.fileWriteUnknown)
            }
            reserved = false
        }
    }

    @Test(arguments: [false, true])
    func cancelledCallerStillReleasesAfterFirstDiscardFailure(cancellation: Bool) async throws {
        let session = WalletSession(variant: .regtest)
        let fake = ReservedInputs(failures: 1, cancellation: cancellation)
        await fake.suspend()
        let quote = await SendPreparedTests.quote
        await session.retainSendReview(SendPreparedTests.payment, quote: quote, discard: { try await fake.discard() })
        let caller = Task { await session.discardSend(quote) }
        let deadline = ContinuousClock.now.advanced(by: .seconds(5))
        while await !fake.entered, ContinuousClock.now < deadline { await Task.yield() }
        #expect(await fake.entered)
        caller.cancel()
        #expect(await session.sendPreparations.count == 1)
        #expect(await fake.reserved)
        await #expect(throws: SendFailure.feeChanged) { try await session.claimSendReview(SendPreparedTests.payment, quote: quote) }
        await fake.resume()
        await caller.value
        #expect(await !fake.reserved)
        #expect(await !fake.sawCallerCancellation)
        #expect(await fake.calls == 2)
        #expect(await session.sendPreparations.isEmpty)
    }

    @Test(.regtestStack) func exhaustedRetriesRetainOwnershipAndNextWalletActivityReleases() async throws {
        let documents = FileManager.default.temporaryDirectory.appending(path: "send-r7-discard-\(UUID())")
        defer { try? FileManager.default.removeItem(at: documents) }
        let session = WalletSession(variant: .regtest, location: .init(variant: .regtest, documentsDirectory: documents))
        try await session.createNew(mnemonic: generateMnemonic(), runDaemon: false, appVersion: "test")
        let fake = ReservedInputs(failures: 3)
        let quote = await SendPreparedTests.quote
        await session.retainSendReview(SendPreparedTests.payment, quote: quote, discard: { try await fake.discard() })
        await session.discardSend(quote)
        #expect(await fake.reserved)
        #expect(await fake.calls == 3)
        #expect(await session.sendPreparations.count == 1)
        // Real session activity triggers another bounded cleanup pass before the wallet call.
        _ = try await session.peekPublicKey(index: 0)
        #expect(await !fake.reserved)
        #expect(await fake.calls == 4)
        #expect(await session.sendPreparations.isEmpty)
        try await session.close()
    }

    @Test(.regtestStack) func closeFailureRetainsSessionAndPreparationUntilCleanupSucceeds() async throws {
        let documents = FileManager.default.temporaryDirectory.appending(path: "send-r7-close-\(UUID())")
        defer { try? FileManager.default.removeItem(at: documents) }
        let session = WalletSession(variant: .regtest, location: .init(variant: .regtest, documentsDirectory: documents))
        try await session.createNew(mnemonic: generateMnemonic(), runDaemon: false, appVersion: "test")
        let fake = ReservedInputs(failures: 3)
        let quote = await SendPreparedTests.quote
        await session.retainSendReview(SendPreparedTests.payment, quote: quote, discard: { try await fake.discard() })
        await #expect(throws: SendFailure.unresolved) { try await session.close() }
        #expect(await session.isOpen)
        #expect(await session.sendPreparations.count == 1)
        #expect(await fake.reserved)
        try await session.close()
        #expect(await !session.isOpen)
        #expect(await session.sendPreparations.isEmpty)
        #expect(await !fake.reserved)
    }
}
