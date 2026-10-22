import Foundation
import Synchronization
import Testing
@testable import Noah

/// Home's Renew rechecks eligibility at submission, under the sync flight and inside the
/// journal's operation, so a tip that moves while execution waits can't refresh an
/// expired VTXO.
@Suite(.timeLimit(.minutes(1)))
struct RenewSubmissionTests {
    final class Shared<Value: Sendable>: Sendable {
        private let value: Mutex<Value>
        init(_ value: Value) { self.value = Mutex(value) }
        var current: Value { value.withLock { $0 } }
        func set(_ new: Value) { value.withLock { $0 = new } }
    }

    /// A wallet read the eligibility check makes before submitting.
    enum Read: Sendable { case expiring, tip }
    struct ReadFailed: Error {}

    static let quote = OnchainQuote(action: .refresh, walletID: "wallet", ids: ["a"], amount: 20_000, fee: 0,
                                    chainFee: 0, confirmed: 0)
    static let coin = HomeRenewal.Coin(id: "a", sats: 20_000, expiryHeight: 1_010, spendable: true)

    private func journal() throws -> (OnchainOperationJournal, URL) {
        let directory = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return (OnchainOperationJournal(file: directory.appending(path: "attempt.json")), directory)
    }

    private func submit(_ journal: OnchainOperationJournal, flight: WalletSync, tip: Shared<UInt32>,
                        coins: [HomeRenewal.Coin] = [coin], failing: Read? = nil, submitted: Shared<Int>) async throws -> String {
        try await WalletSession.submitRenewal(
            Self.quote, threshold: 144, journal: journal, flight: flight,
            requote: { Self.quote },
            expiring: { _ in
                if failing == .expiring { throw ReadFailed() }
                return coins
            },
            tip: {
                if failing == .tip { throw ReadFailed() }
                return tip.current
            },
            refresh: { _ in
                submitted.set(submitted.current + 1)
                return OnchainReceipt(message: "submitted")
            })
    }

    @Test func expiryWhileWaitingForTheLockSubmitsNothing() async throws {
        let (journal, directory) = try journal()
        defer { try? FileManager.default.removeItem(at: directory) }
        let flight = WalletSync()
        let tip = Shared<UInt32>(1_000)
        let submitted = Shared(0)
        // A sync holds the flight while Renew's execution waits for it.
        let (gate, release) = AsyncStream<Void>.makeStream()
        let sync = Task { try await flight.serialized { for await _ in gate { break } } }
        try await Task.sleep(for: .milliseconds(100))
        let renewal = Task { try await submit(journal, flight: flight, tip: tip, submitted: submitted) }
        try await Task.sleep(for: .milliseconds(100))
        #expect(submitted.current == 0)
        // That sync moves the tip past the VTXO's expiry, then releases.
        tip.set(1_010)
        release.yield(())
        release.finish()
        try await sync.value
        await #expect(throws: OnchainFailure.changed) { try await renewal.value }
        #expect(submitted.current == 0)
        // Refused before submission: no attempt is left to block a later renewal.
        #expect(try await journal.loadAll().isEmpty)
    }

    @Test func eligibleCoinsSubmitOnce() async throws {
        let (journal, directory) = try journal()
        defer { try? FileManager.default.removeItem(at: directory) }
        let submitted = Shared(0)
        let message = try await submit(journal, flight: WalletSync(), tip: Shared<UInt32>(1_000), submitted: submitted)
        #expect(message == "submitted" && submitted.current == 1)
        #expect(try await journal.loadAll().first?.receipt?.message == "submitted")
    }

    @Test(arguments: [
        HomeRenewal.Coin(id: "a", sats: 20_000, expiryHeight: 1_010, spendable: false), // locked
        HomeRenewal.Coin(id: "b", sats: 20_000, expiryHeight: 1_010, spendable: true),  // no longer in the set
    ])
    func coinsThatStoppedBeingEligibleSubmitNothing(coin: HomeRenewal.Coin) async throws {
        let (journal, directory) = try journal()
        defer { try? FileManager.default.removeItem(at: directory) }
        let submitted = Shared(0)
        await #expect(throws: OnchainFailure.changed) {
            try await submit(journal, flight: WalletSync(), tip: Shared<UInt32>(1_000), coins: [coin], submitted: submitted)
        }
        #expect(submitted.current == 0)
        #expect(try await journal.loadAll().isEmpty)
    }

    @Test(arguments: [Read.expiring, .tip])
    func aFailedReadSubmitsNothingAndLeavesNothingBehind(read: Read) async throws {
        let (journal, directory) = try journal()
        defer { try? FileManager.default.removeItem(at: directory) }
        let flight = WalletSync()
        let submitted = Shared(0)
        await #expect(throws: OnchainFailure.changed) {
            try await submit(journal, flight: flight, tip: Shared<UInt32>(1_000), failing: read, submitted: submitted)
        }
        #expect(submitted.current == 0)
        #expect(try await journal.loadAll().isEmpty)
        // No uncertain attempt is left to block the next Renew of the same VTXOs as busy.
        let message = try await submit(journal, flight: flight, tip: Shared<UInt32>(1_000), submitted: submitted)
        #expect(message == "submitted" && submitted.current == 1)
    }
}
