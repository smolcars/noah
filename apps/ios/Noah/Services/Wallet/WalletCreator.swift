import BarkBoltFFI
import Foundation

/// Create-or-resume for onboarding ("I accept" on the beta screen), ported
/// from `createWallet` in `client/src/lib/walletApi.ts`:
///
/// - seed and database present: resume that wallet, never generate a seed;
/// - only one of them present, or the Keychain unreadable: stop, touch nothing;
/// - neither: create a fresh wallet; if that attempt fails, undo only what
///   it created so the next attempt can retry;
/// - overlapping calls share one attempt.
///
/// Unlike the RN app the seed is saved before the wallet files are created,
/// so a crash in between leaves a restorable seed rather than wallet data
/// whose seed the user never saw.
actor WalletCreator {
    enum Failure: Error, Equatable {
        /// Seed without wallet data or the reverse. The user has to restore.
        case incompleteExistingData
        case secureStorageUnavailable(OSStatus)
    }

    struct Dependencies: Sendable {
        var readSeed: @Sendable () -> KeychainPassword.ReadResult
        var saveSeed: @Sendable (String) throws -> Void
        var deleteSeed: @Sendable (_ onlyIfValueIs: String) -> Void
        var walletDatabaseExists: @Sendable () -> Bool
        var datadirExists: @Sendable () -> Bool
        /// Removes what a failed fresh attempt created: the whole datadir if
        /// it did not exist before, otherwise only the database files.
        var removeAttemptFiles: @Sendable (_ datadirExisted: Bool) -> Void
        var generateMnemonic: @Sendable () throws -> String
        var openExisting: @Sendable (_ mnemonic: String) async throws -> Void
        var createNew: @Sendable (_ mnemonic: String) async throws -> Void
        var close: @Sendable () async -> Void
    }

    private let dependencies: Dependencies
    private var inFlight: Task<Void, Error>?

    init(dependencies: Dependencies) {
        self.dependencies = dependencies
    }

    func createOrResume() async throws {
        if let inFlight {
            return try await inFlight.value
        }
        let attempt = Task { try await run() }
        inFlight = attempt
        defer { inFlight = nil }
        try await attempt.value
    }

    private func run() async throws {
        let seed: String?
        switch dependencies.readSeed() {
        case .unavailable(let status):
            throw Failure.secureStorageUnavailable(status)
        case .found(let value) where !value.isEmpty:
            seed = value
        case .found, .notFound:
            seed = nil
        }

        switch (seed, dependencies.walletDatabaseExists()) {
        case (let seed?, true):
            try await dependencies.openExisting(seed)
            return
        case (_?, false), (nil, true):
            throw Failure.incompleteExistingData
        case (nil, false):
            break
        }

        let datadirExisted = dependencies.datadirExists()
        let mnemonic = try dependencies.generateMnemonic()
        try dependencies.saveSeed(mnemonic)
        do {
            try await dependencies.createNew(mnemonic)
        } catch {
            await dependencies.close()
            dependencies.removeAttemptFiles(datadirExisted)
            dependencies.deleteSeed(mnemonic)
            throw error
        }
    }
}

extension WalletCreator.Dependencies {
    static func live(session: WalletSession, variant: AppVariant, appVersion: String) -> Self {
        let seedItem = KeychainPassword.mnemonic(for: variant)
        let location = WalletDataLocation(variant: variant)
        return Self(
            readSeed: { seedItem.read() },
            saveSeed: { try seedItem.save($0) },
            deleteSeed: { seedItem.delete(onlyIfValueIs: $0) },
            walletDatabaseExists: { location.databaseExists },
            datadirExists: { FileManager.default.fileExists(atPath: location.directory.path) },
            removeAttemptFiles: { datadirExisted in
                let fileManager = FileManager.default
                if !datadirExisted {
                    try? fileManager.removeItem(at: location.directory)
                    return
                }
                for name in ["db.sqlite", "db.sqlite-wal", "db.sqlite-shm", "LOCK"] {
                    try? fileManager.removeItem(at: location.directory.appending(path: name))
                }
            },
            generateMnemonic: { try BarkBoltFFI.generateMnemonic() },
            openExisting: { try await session.openExisting(mnemonic: $0, runDaemon: true, appVersion: appVersion) },
            createNew: { try await session.createNew(mnemonic: $0, runDaemon: true, appVersion: appVersion) },
            close: { try? await session.close() })
    }
}
