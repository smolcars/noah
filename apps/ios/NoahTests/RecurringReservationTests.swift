import BarkBoltFFI
import Foundation
import Testing
@testable import Noah

@MainActor struct RecurringReservationTests {
    actor Gate {
        var entered = false
        private var continuation: CheckedContinuation<Void, Never>?
        func wait() async { entered = true; await withCheckedContinuation { continuation = $0 } }
        func release() { continuation?.resume(); continuation = nil }
    }
    private func waitUntil(_ condition: @MainActor () async -> Bool) async throws {
        let deadline = ContinuousClock.now + .seconds(5)
        while !(await condition()), ContinuousClock.now < deadline { await Task.yield() }
        try #require(await condition())
    }
    @Test func exclusiveSendDrainsWorkExcludesOtherCallersAndSurvivesCancellation() async throws {
        let documents = FileManager.default.temporaryDirectory.appending(path: "recurring-reservation-\(UUID())")
        defer { try? FileManager.default.removeItem(at: documents) }
        let session = WalletSession(variant: .regtest, location: .init(variant: .regtest, documentsDirectory: documents))
        try await session.createNew(mnemonic: generateMnemonic(), runDaemon: false, appVersion: "test")
        let previous = Gate(), critical = Gate()
        let counted = Task { try await session.withOpenWallet { _ in await previous.wait() } }
        try await waitUntil { await previous.entered }
        var nestedResult: Int?
        let exclusive = Task {
            try await session.withSendReservation({
                await critical.wait()
                nestedResult = try? await session.withOpenWallet { _ in 42 }
            }, exclusive: true)
        }
        try await waitUntil {
            do { try await session.withOpenWallet { _ in () }; return false }
            catch WalletSession.OpenError.busy { return true }
            catch { return false }
        }
        #expect(await !critical.entered)
        await previous.release(); try await counted.value
        try await waitUntil { await critical.entered }
        await #expect(throws: WalletSession.OpenError.busy) { try await session.withOpenWallet { _ in () } }
        await #expect(throws: WalletSession.OpenError.busy) { try await session.closeSettled() }
        exclusive.cancel()
        await critical.release(); try await exclusive.value
        #expect(nestedResult == 42)
        try await session.withOpenWallet { _ in () }
        try await session.closeSettled()
    }
}
