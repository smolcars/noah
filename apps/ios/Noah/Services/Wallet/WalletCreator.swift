import BarkBoltFFI
import Foundation

/// Create-or-resume for onboarding ("I accept" on the beta screen), ported
/// from `createWallet` in `client/src/lib/walletApi.ts`:
///
/// - seed and database present: resume that wallet, never generate a seed;
/// - only one of them present, or the Keychain unreadable: stop, touch nothing;
/// - neither: create a fresh wallet; if that attempt fails, undo only what
///   it created so the next attempt can retry;
/// - overlapping calls share one attempt;
/// - a fresh attempt holds a `WalletSession.Reservation` from its final
///   check through seed write, creation and any cleanup, so a restore or
///   recovery can't run in between and its files are never removed.
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
        var mutation: WalletMutationGate = .shared
        var readSeed: @Sendable () -> KeychainPassword.ReadResult
        var saveSeed: @Sendable (String) throws -> Void
        var deleteSeed: @Sendable (_ onlyIfValueIs: String) -> Void
        /// Anything in the datadir (RN `walletDataExists`).
        var walletDataExists: @Sendable () -> Bool
        var datadirExists: @Sendable () -> Bool
        /// Removes what a failed fresh attempt created. The datadir was
        /// absent or empty before the attempt, so everything in it is ours.
        /// Returns false if anything could not be removed.
        var removeAttemptFiles: @Sendable (_ datadirExisted: Bool) -> Bool
        var generateMnemonic: @Sendable () throws -> String
        var openExisting: @Sendable (_ mnemonic: String) async throws -> Void
        var reserve: @Sendable () async throws -> WalletSession.Reservation
        var release: @Sendable (WalletSession.Reservation) async -> Void
        /// Leaves the wallet open (ending the reservation) or fails with the
        /// reservation still held and nothing running on the files.
        var createNew: @Sendable (WalletSession.Reservation, _ mnemonic: String) async throws -> Void
        var beginOnboarding: @Sendable () throws -> Void = {}
    }

    private enum Plan: Equatable {
        case resume(seed: String)
        case fresh
    }

    private let dependencies: Dependencies
    private var inFlight: Task<Void, Error>?

    init(dependencies: Dependencies) {
        self.dependencies = dependencies
    }

    func createOrResume() async throws {
        if let inFlight {
            try await inFlight.value
            return
        }
        let attempt = Task { try await dependencies.mutation.withLock { try await self.run() } }
        inFlight = attempt
        defer { inFlight = nil }
        try await attempt.value
    }

    private func run() async throws {
        if case .resume(let seed) = try plan() {
            try await dependencies.openExisting(seed)
            return
        }
        let reservation = try await dependencies.reserve()
        do {
            try await createFresh(holding: reservation)
        } catch {
            // No-op once the new wallet is open.
            await dependencies.release(reservation)
            throw error
        }
    }

    private func plan() throws -> Plan {
        let seed: String?
        switch dependencies.readSeed() {
        case .unavailable(let status):
            if status == errSecDecode { throw StoredSeed.Failure.invalid }
            if status == errSecDuplicateItem { throw StoredSeed.Failure.conflict }
            throw Failure.secureStorageUnavailable(status)
        case .found(let value):
            seed = try StoredSeed.normalized(value)
        case .notFound:
            seed = nil
        }
        switch (seed, dependencies.walletDataExists()) {
        case (let seed?, true): return .resume(seed: seed)
        case (_?, false), (nil, true): throw Failure.incompleteExistingData
        case (nil, false): return .fresh
        }
    }

    private func createFresh(holding reservation: WalletSession.Reservation) async throws {
        // Checked again under the reservation: a restore may have finished
        // since the first look.
        guard try plan() == .fresh else { throw WalletSession.OpenError.busy }
        let datadirExisted = dependencies.datadirExists()
        try dependencies.beginOnboarding()
        let mnemonic = try dependencies.generateMnemonic()
        _ = try StoredSeed.normalized(mnemonic)
        try dependencies.saveSeed(mnemonic)
        do {
            try await dependencies.createNew(reservation, mnemonic)
        } catch WalletSession.OpenError.teardownFailed {
            // The wallet is still open on its files: they and the seed stay.
            throw WalletSession.OpenError.teardownFailed
        } catch {
            // Only drop the new seed once its wallet files are really gone;
            // otherwise keep it so the leftover data stays recoverable.
            if dependencies.removeAttemptFiles(datadirExisted) {
                dependencies.deleteSeed(mnemonic)
            }
            throw error
        }
    }
}

extension WalletCreator.Dependencies {
    static func live(session: WalletSession, variant: AppVariant, appVersion: String, settings: SettingsStorage = .shared) -> Self {
        let seedItem = KeychainPassword.mnemonic(for: variant)
        let location = WalletDataLocation(variant: variant)
        return Self(
            readSeed: {
                guard !FileManager.default.fileExists(atPath: location.deletionMarker.path) else {
                    return .unavailable(errSecInteractionNotAllowed)
                }
                let stored = seedItem.read()
                guard (try? StoredSeed.requireUsable(stored)) != nil else { return stored }
                guard !location.hasMultipleDatabases else { return .unavailable(errSecDuplicateItem) }
                do { try location.removeDiscardedDeletionData() }
                catch { return .unavailable(errSecIO) }
                return stored
            },
            saveSeed: { try seedItem.save($0) },
            deleteSeed: { seedItem.delete(onlyIfValueIs: $0) },
            walletDataExists: { location.hasAnyData },
            datadirExists: { FileManager.default.fileExists(atPath: location.directory.path) },
            removeAttemptFiles: { datadirExisted in
                let fileManager = FileManager.default
                do {
                    if !datadirExisted {
                        if fileManager.fileExists(atPath: location.directory.path) {
                            try fileManager.removeItem(at: location.directory)
                        }
                    } else {
                        for name in try fileManager.contentsOfDirectory(atPath: location.directory.path) {
                            try fileManager.removeItem(at: location.directory.appending(path: name))
                        }
                    }
                    return true
                } catch {
                    return false
                }
            },
            generateMnemonic: { try BarkBoltFFI.generateMnemonic() },
            openExisting: { try await session.ensureOpen(mnemonic: $0, runDaemon: true, appVersion: appVersion) },
            reserve: { try await session.reserve() },
            release: { await session.release($0) },
            createNew: { try await session.createNew($0, mnemonic: $1, runDaemon: true, appVersion: appVersion) },
            beginOnboarding: { try settings.beginOnboarding(recoveryPhraseBackedUp: false) })
    }
}
