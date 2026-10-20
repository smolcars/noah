import BarkBoltFFI
import Foundation
import Testing

@testable import Noah

/// Mirrors `client/tests/integration/walletCreation.test.js`.
struct WalletCreatorTests {
    /// In-memory seed store, datadir and wallet calls.
    final class Fake: @unchecked Sendable {
        let lock = NSLock()
        var seed: KeychainPassword.ReadResult = .notFound
        var database = false
        var datadir = false
        var generated: [String] = []
        var opened: [String] = []
        var created: [String] = []
        var createFailure: Error?
        var openFailure: Error?
        var createGate: CheckedContinuation<Void, Never>?
        var holdCreate = false
        var cleanupFails = false
        /// Real session, only for its reservations (the opener is never used).
        let session = WalletSession(
            variant: .regtest,
            location: WalletDataLocation(
                variant: .regtest,
                documentsDirectory: FileManager.default.temporaryDirectory.appending(path: "creator-\(UUID().uuidString)"))
        ) { _, _, _, _, _ in throw Boom() }

        func withLock<T>(_ body: () throws -> T) rethrows -> T { lock.lock(); defer { lock.unlock() }; return try body() }

        func dependencies() -> WalletCreator.Dependencies {
            WalletCreator.Dependencies(
                readSeed: { self.withLock { self.seed } },
                saveSeed: { value in self.withLock { self.seed = .found(value) } },
                deleteSeed: { value in self.withLock { if self.seed == .found(value) { self.seed = .notFound } } },
                walletDataExists: { self.withLock { self.database } },
                datadirExists: { self.withLock { self.datadir } },
                removeAttemptFiles: { existed in
                    self.withLock {
                        if self.cleanupFails { return false }
                        self.database = false
                        if !existed { self.datadir = false }
                        return true
                    }
                },
                generateMnemonic: {
                    self.withLock {
                        let value = self.generated.isEmpty ? WalletCreatorTests.first : WalletCreatorTests.second
                        self.generated.append(value)
                        return value
                    }
                },
                openExisting: { value in
                    if let failure = self.withLock({ self.openFailure }) { throw failure }
                    self.withLock { self.opened.append(value) }
                },
                reserve: { try await self.session.reserve() },
                release: { await self.session.release($0) },
                createNew: { _, value in
                    if self.withLock({ self.holdCreate }) {
                        await withCheckedContinuation { continuation in self.withLock { self.createGate = continuation } }
                    }
                    try self.withLock {
                        self.datadir = true
                        self.database = true
                        if let failure = self.createFailure { throw failure }
                        self.created.append(value)
                    }
                })
        }
    }

    private static let first = try! generateMnemonic()
    private static let second = try! generateMnemonic()
    struct Boom: Error {}

    @Test func acceptingAgainKeepsTheOriginalSeedAndCreatesOnce() async throws {
        let fake = Fake()
        let creator = WalletCreator(dependencies: fake.dependencies())
        try await creator.createOrResume()
        try await creator.createOrResume()
        #expect({ fake.generated == [Self.first] }())
        #expect({ fake.created == [Self.first] }())
        #expect({ fake.opened == [Self.first] }())
        #expect({ fake.seed == .found(Self.first) }())
    }

    @Test func resumesExistingWalletWithoutGeneratingASeed() async throws {
        let fake = Fake()
        fake.seed = .found(Self.first)
        fake.database = true
        try await WalletCreator(dependencies: fake.dependencies()).createOrResume()
        #expect(fake.generated.isEmpty)
        #expect({ fake.opened == [Self.first] }())
    }

    @Test func failedResumePreservesSeedAndData() async {
        let fake = Fake()
        fake.seed = .found(Self.first)
        fake.database = true
        fake.openFailure = Boom()
        await #expect(throws: Boom.self) { try await WalletCreator(dependencies: fake.dependencies()).createOrResume() }
        #expect({ fake.seed == .found(Self.first) }())
        #expect(fake.database)
        #expect(fake.generated.isEmpty)
    }

    @Test(arguments: ["seed", "data"])
    func preservesInconsistentStorage(existing: String) async {
        let fake = Fake()
        if existing == "seed" { fake.seed = .found(Self.first) } else { fake.database = true }
        await #expect(throws: WalletCreator.Failure.incompleteExistingData) {
            try await WalletCreator(dependencies: fake.dependencies()).createOrResume()
        }
        #expect(fake.generated.isEmpty)
        #expect({ existing == "seed" ? fake.seed == .found(Self.first) : fake.database }())
    }

    @Test func unreadableKeychainTouchesNothing() async {
        let fake = Fake()
        fake.seed = .unavailable(-25308)
        await #expect(throws: WalletCreator.Failure.secureStorageUnavailable(-25308)) {
            try await WalletCreator(dependencies: fake.dependencies()).createOrResume()
        }
        #expect(fake.generated.isEmpty)
    }

    @Test func failedFreshAttemptIsUndoneSoTheNextOneCanRetry() async throws {
        let fake = Fake()
        fake.createFailure = Boom()
        let creator = WalletCreator(dependencies: fake.dependencies())
        await #expect(throws: Boom.self) { try await creator.createOrResume() }
        #expect({ fake.seed == .notFound }(), "the new seed was stored, then removed")
        #expect(!fake.database && !fake.datadir)

        fake.withLock { fake.createFailure = nil }
        try await creator.createOrResume()
        #expect({ fake.generated == [Self.first, Self.second] }())
        #expect({ fake.seed == .found(Self.second) }())
    }

    @Test func overlappingAcceptancesShareOneAttempt() async throws {
        let fake = Fake()
        fake.holdCreate = true
        let creator = WalletCreator(dependencies: fake.dependencies())
        let first = Task { try await creator.createOrResume() }
        while fake.withLock({ fake.createGate == nil }) { await Task.yield() }
        let second = Task { try await creator.createOrResume() }
        for _ in 0..<50 { await Task.yield() }
        fake.withLock { fake.createGate?.resume(); fake.createGate = nil }
        try await first.value
        try await second.value
        #expect({ fake.generated == [Self.first] }())
        #expect({ fake.created == [Self.first] }())
    }

    @Test func freshCreationWaitsOutAReservationWithoutTouchingAnything() async throws {
        let fake = Fake()
        // A restore (or launch recovery) holds the session.
        let restore = try await fake.session.reserve()
        await #expect(throws: WalletSession.OpenError.busy) {
            try await WalletCreator(dependencies: fake.dependencies()).createOrResume()
        }
        #expect(fake.generated.isEmpty)
        #expect({ fake.seed == .notFound }())
        #expect(!fake.datadir)
        await fake.session.release(restore)
    }

    @Test func freshCreationHoldsTheSessionUntilItsCleanupIsDone() async throws {
        let fake = Fake()
        fake.holdCreate = true
        fake.createFailure = Boom()
        let creator = WalletCreator(dependencies: fake.dependencies())
        let attempt = Task { try await creator.createOrResume() }
        while fake.withLock({ fake.createGate == nil }) { await Task.yield() }

        // Mid-creation: a restore can't start.
        await #expect(throws: WalletSession.OpenError.busy) { try await fake.session.reserve() }
        fake.withLock { fake.createGate?.resume(); fake.createGate = nil }
        await #expect(throws: Boom.self) { try await attempt.value }

        // Cleanup finished under the reservation, then released it.
        #expect({ fake.seed == .notFound }())
        #expect(!fake.database && !fake.datadir)
        let next = try await fake.session.reserve()
        await fake.session.release(next)
    }

    @Test func keepsTheNewSeedWhenCleanupFails() async {
        let fake = Fake()
        fake.createFailure = Boom()
        fake.cleanupFails = true
        await #expect(throws: Boom.self) { try await WalletCreator(dependencies: fake.dependencies()).createOrResume() }
        #expect({ fake.seed == .found(Self.first) }(), "leftover wallet files must keep their seed")
        #expect(fake.database)
    }
}
