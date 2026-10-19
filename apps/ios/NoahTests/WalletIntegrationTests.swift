import BarkBoltFFI
import Foundation
import Testing

@testable import Noah

/// Real bark-bolt-ffi wallet against the regtest Ark server, in a temporary
/// datadir with an in-memory seed. Enabled with `TEST_RUNNER_NOAH_SERVER_IT=1`.
@Suite(.enabled(if: ProcessInfo.processInfo.environment["NOAH_SERVER_IT"] == "1"))
struct WalletIntegrationTests {
    final class MemorySeed: @unchecked Sendable {
        let lock = NSLock()
        var value: String?
    }

    @Test func createThenResumeAgainSucceeds() async throws {
        let documents = FileManager.default.temporaryDirectory.appending(path: "wallet-it-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: documents) }
        let location = WalletDataLocation(variant: .regtest, documentsDirectory: documents)
        let session = WalletSession(variant: .regtest, location: location)
        let seed = MemorySeed()

        let creator = WalletCreator(dependencies: .init(
            readSeed: { seed.lock.withLock { seed.value.map { .found($0) } ?? .notFound } },
            saveSeed: { value in seed.lock.withLock { seed.value = value } },
            deleteSeed: { value in seed.lock.withLock { if seed.value == value { seed.value = nil } } },
            walletDataExists: { location.hasAnyData },
            datadirExists: { FileManager.default.fileExists(atPath: location.directory.path) },
            removeAttemptFiles: { _ in (try? FileManager.default.removeItem(at: location.directory)) != nil },
            generateMnemonic: { try generateMnemonic() },
            openExisting: { try await session.ensureOpen(mnemonic: $0, runDaemon: false, appVersion: "it") },
            reserve: { try await session.reserve() },
            release: { await session.release($0) },
            createNew: { try await session.createNew($0, mnemonic: $1, runDaemon: false, appVersion: "it") }))

        try await creator.createOrResume()
        #expect(location.databaseExists)
        // RN creates key 0 before registration; a fresh native wallet must too.
        let registrationAddress = try await session.peekAddress(index: 0)
        #expect(!registrationAddress.isEmpty)
        try await creator.createOrResume()  // already open: resumes instead of failing

        let summary = try #require(try await session.summary())
        #expect(summary.network == .regtest)
        #expect(summary.balance.totalSats == 0)
        try await session.close()

        // A closed session reopens the same wallet from disk.
        try await creator.createOrResume()
        #expect(try await session.summary()?.fingerprint == summary.fingerprint)
        #expect(try await session.peekAddress(index: 0) == registrationAddress)
        try await session.close()
    }
}
