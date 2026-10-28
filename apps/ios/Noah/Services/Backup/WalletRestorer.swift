import BarkBoltFFI
import Foundation

/// Restore from the user's recovery phrase, ported from `restoreWallet` and
/// `BackupService.restoreBackup` in `client/src/lib/backupService.ts`:
/// newest valid v2 backup first (legacy v1 when the server has none),
/// validated against its manifest, then swapped in and committed through a
/// `RestoreTransaction` while the wallet session is reserved. The seed is
/// written last; any failure before that puts the previous wallet back and
/// leaves the previous seed untouched.
struct WalletRestorer: Sendable {
    enum Failure: Error, Equatable {
        case wrongNetwork
        case identityMismatch
        case noUsableBackup
        case unsupportedFormat(Int32)
        /// The download doesn't match the size or hash the server recorded.
        case downloadMismatch
        /// The Keychain can't be read; nothing was changed.
        case secureStorageUnavailable
        /// The previous wallet could not be put back; launch recovery retries.
        case rollbackFailed
        /// A seed write failed and the Keychain can't be read to tell whether
        /// it landed; launch recovery settles it once it can.
        case seedStateUnknown
    }

    enum Step: Sendable {
        case fetching, validating, loading, finalizing
    }

    /// Same cap as the server's `MAX_BACKUP_OBJECT_SIZE_BYTES`.
    static let maxEncryptedBytes: Int64 = 256 * 1024 * 1024

    var mutation: WalletMutationGate = .shared
    let variant: AppVariant
    let session: WalletSession
    let location: WalletDataLocation
    let seed: any SecretItem
    /// Authenticated client for `mnemonic` whose token is never persisted.
    let makeClient: @Sendable (_ mnemonic: String) -> Client
    var walletChanged: @Sendable () async -> Void = { await SendPaymentController.shared.reloadForWalletChange() }

    var beginOnboarding: @Sendable () throws -> Void = {}

    struct Outcome: Equatable, Sendable {
        let backupId: String?
        let snapshotSha256: String?
        /// The restore is committed (seed saved), but dropping the previous
        /// wallet and the journal failed. The wallet won't open until
        /// `recoverInterruptedRestore` finishes it (it never rolls back a
        /// committed restore); the next launch does that too.
        var cleanupPending = false
    }

    private var expectedNetwork: String { variant == .mainnet ? "bitcoin" : variant.rawValue }

    /// Restores into a closed session and leaves it closed; open the wallet
    /// afterwards as usual.
    func restore(mnemonic: String, progress: @Sendable (Step) -> Void = { _ in }) async throws -> Outcome {
        try await mutation.withLock { try await restoreLocked(mnemonic: mnemonic, progress: progress) }
    }

    private func restoreLocked(mnemonic: String, progress: @Sendable (Step) -> Void) async throws -> Outcome {
        _ = try StoredSeed.normalized(mnemonic)
        do { try StoredSeed.requireUsable(seed.read()) }
        catch is KeychainPassword.WriteError { throw Failure.secureStorageUnavailable }
        guard !location.hasMultipleDatabases else { throw StoredSeed.Failure.conflict }
        guard !FileManager.default.fileExists(atPath: location.deletionMarker.path) else {
            throw WalletSession.OpenError.restorePending
        }
        if session === WalletSession.shared { try RecurringStore().protectLegacyBeforeReplacement() }
        let reservation = try await session.reserve()
        do {
            let outcome = try await restore(mnemonic: mnemonic, holding: reservation, progress: progress)
            await walletChanged()
            await session.release(reservation)
            return outcome
        } catch {
            await session.release(reservation)
            throw error
        }
    }

    /// Settles a restore interrupted by a crash. Run at launch before the
    /// launch state is read.
    static func recoverInterruptedRestore(
        session: WalletSession, location: WalletDataLocation, seed: any SecretItem) async throws -> RestoreTransaction.Recovery {
        // Without a journal there is nothing to swap: drop abandoned plaintext
        // without reserving, so a launch opening the wallet right after its own
        // recovery never finds the session busy. A journal only appears under a
        // restore's reservation, which `unlessReserved` respects.
        guard location.restorePending else {
            let transaction = RestoreTransaction(location: location)
            try await session.unlessReserved { try transaction.removeScratch() }
            return .nothingPending
        }
        let reservation: WalletSession.Reservation
        do {
            reservation = try await session.reserve()
        } catch WalletSession.OpenError.busy where !location.restorePending {
            // Opened (e.g. by a silent push before the UI started): nothing
            // can be pending, since opening refuses while it is. Decrypted
            // leftovers of a restore that died before its journal still go,
            // unless a restore holds the session right now.
            let transaction = RestoreTransaction(location: location)
            try await session.unlessReserved { try transaction.removeScratch() }
            return .nothingPending
        }
        let result = Result { try RestoreTransaction(location: location).recover(seed: seed) }
        if case .success(.completed) = result, session === WalletSession.shared {
            await SendPaymentController.shared.reloadForWalletChange()
        }
        await session.release(reservation)
        return try result.get()
    }

    private func restore(
        mnemonic: String, holding reservation: WalletSession.Reservation, progress: @Sendable (Step) -> Void) async throws -> Outcome {
        let transaction = RestoreTransaction(location: location)
        if try transaction.recover(seed: seed) == .waitingForKeychain { throw Failure.secureStorageUnavailable }
        if case .unavailable = seed.read() { throw Failure.secureStorageUnavailable }
        try transaction.prepare()
        do {
            progress(.fetching)
            let outcome = try await fetchAndStage(mnemonic: mnemonic, transaction: transaction, progress: progress)
            do {
                try beginOnboarding()
                try transaction.begin(seed: mnemonic)
                progress(.loading)
                try await session.verify(reservation, mnemonic: mnemonic, appVersion: "restore")
                progress(.finalizing)
                try transaction.markCommitting()
            } catch {
                try undo(transaction)
                throw error
            }
            try commitSeed(mnemonic, transaction: transaction)
            // Seed saved: the restore stands, whatever happens to the cleanup.
            var committed = outcome
            if (try? transaction.finish()) == nil && (try? transaction.finish()) == nil {
                AppLog("restore").e("Restore committed but its cleanup failed; it will finish on the next attempt")
                committed.cleanupPending = true
            }
            return committed
        } catch {
            try? transaction.removeScratch()
            throw error
        }
    }

    /// The seed write is the commit point. A failed write is checked against
    /// what the Keychain now holds before anything is undone.
    private func commitSeed(_ mnemonic: String, transaction: RestoreTransaction) throws {
        do {
            try seed.save(mnemonic)
        } catch {
            switch seed.read() {
            case .found(let value) where value == mnemonic:
                return
            case .found, .notFound:
                try undo(transaction)
                throw error
            case .unavailable:
                throw Failure.seedStateUnknown
            }
        }
    }

    private func undo(_ transaction: RestoreTransaction) throws {
        // No journal: `begin` failed before recording anything, nothing moved.
        guard transaction.isPending else { return }
        do {
            try transaction.undo()
        } catch {
            throw Failure.rollbackFailed
        }
    }

    /// Leaves the restored datadir in `transaction.staged`.
    private func fetchAndStage(
        mnemonic: String, transaction: RestoreTransaction, progress: @Sendable (Step) -> Void) async throws -> Outcome {
        let client = makeClient(mnemonic)
        let backups: [Components.Schemas.BackupObjectInfo]
        switch try await client.listBackupObjects() {
        case .ok(let response):
            backups = try response.body.json
        case .undocumented(statusCode: 404, _):
            backups = []
        case let other:
            _ = try other.ok // throws with the server's response
            backups = []
        }
        if backups.isEmpty {
            return try await stageLegacy(mnemonic: mnemonic, client: client, transaction: transaction)
        }

        var firstError: Error?
        for backup in backups {
            guard backup.formatVersion == Int32(BackupArchive.formatVersion) else {
                firstError = firstError ?? Failure.unsupportedFormat(backup.formatVersion)
                continue
            }
            do {
                return try await stageV2(
                    backup.backupId, mnemonic: mnemonic, client: client, transaction: transaction, progress: progress)
            } catch {
                try? transaction.removeScratch()
                try? FileManager.default.createDirectory(at: transaction.work, withIntermediateDirectories: false)
                firstError = firstError ?? error
                AppLog("restore").w("A retained backup failed validation, trying an older one")
            }
        }
        throw firstError ?? Failure.noUsableBackup
    }

    private func stageV2(
        _ backupId: String, mnemonic: String, client: Client, transaction: RestoreTransaction,
        progress: @Sendable (Step) -> Void) async throws -> Outcome {
        let download = try await client.getBackupObjectDownloadUrl(body: .json(.init(backupId: backupId))).ok.body.json
        guard download.backup.encryptedSize > 0, download.backup.encryptedSize <= Self.maxEncryptedBytes else {
            throw Failure.downloadMismatch
        }
        let encrypted = transaction.work.appending(path: "backup.noahbackup")
        let unpacked = transaction.work.appending(path: "unpacked", directoryHint: .isDirectory)
        try await downloadFile(download.downloadUrl, to: encrypted, limit: download.backup.encryptedSize)
        let data = try Data(contentsOf: encrypted, options: .alwaysMapped)
        guard Int64(data.count) == download.backup.encryptedSize,
              BackupArchive.sha256Hex(data) == download.backup.encryptedSha256.lowercased()
        else { throw Failure.downloadMismatch }

        progress(.validating)
        let opened = try BackupArchive.open(encrypted: encrypted, mnemonic: mnemonic, into: unpacked)
        let manifest = opened.manifest
        guard manifest.network == expectedNetwork else { throw Failure.wrongNetwork }
        let validated = try await validateWalletSnapshot(
            path: opened.snapshot.path,
            expected: .init(network: manifest.network, walletFingerprint: manifest.walletFingerprint, serverPubkey: manifest.serverPubkey))
        guard validated.sha256 == manifest.snapshotSha256,
              Int(validated.schemaVersion) == manifest.barkSchemaVersion,
              validated.serverPubkey == manifest.serverPubkey,
              validated.mailboxPubkey == manifest.mailboxPubkey
        else { throw Failure.identityMismatch }

        // The restored datadir holds only db.sqlite, as in the RN app.
        try FileManager.default.createDirectory(at: transaction.staged, withIntermediateDirectories: false)
        try FileManager.default.moveItem(at: opened.snapshot, to: transaction.staged.appending(path: "db.sqlite"))
        return Outcome(backupId: download.backup.backupId, snapshotSha256: manifest.snapshotSha256)
    }

    /// Legacy v1 (the RN app deleted the current data first; here it goes
    /// through the same transaction as v2).
    private func stageLegacy(mnemonic: String, client: Client, transaction: RestoreTransaction) async throws -> Outcome {
        let download = try await client.getDownloadUrl(body: .json(.init(backupVersion: nil))).ok.body.json
        let base64File = transaction.work.appending(path: "legacy-backup.txt")
        // base64 text of an envelope no larger than the server's object cap.
        try await downloadFile(download.downloadUrl, to: base64File, limit: Self.maxEncryptedBytes / 3 * 4 + 1024)
        let text = try Data(contentsOf: base64File, options: .alwaysMapped)
        try BackupArchive.openLegacy(base64: text, mnemonic: mnemonic, variant: variant, into: transaction.staged)
        return Outcome(backupId: nil, snapshotSha256: nil)
    }

    private func downloadFile(_ url: String, to destination: URL, limit: Int64) async throws {
        guard let source = URL(string: url) else { throw URLError(.badURL) }
        do {
            try await BoundedDownload.fetch(URLRequest(url: source, timeoutInterval: 60), to: destination, limit: limit)
        } catch is BoundedDownload.TooLarge {
            throw Failure.downloadMismatch
        }
    }
}

/// One foreground launch recovery at a time. Creation, restore and deletion use
/// the same mutation gate, so recovery checks only after those mutations settle.
/// Headless jobs defer recovery because it may change wallet files or secrets.
actor RestoreRecovery {
    private let mutation: WalletMutationGate
    private let recover: @Sendable () async throws -> RestoreTransaction.Recovery
    private var tail: Task<RestoreTransaction.Recovery, any Error>?

    init(mutation: WalletMutationGate = .shared, recover: @escaping @Sendable () async throws -> RestoreTransaction.Recovery) {
        self.mutation = mutation
        self.recover = recover
    }

    /// Lifecycle → recovery → session; do not call settle() from inside this gate.
    func withSettled<T: Sendable>(_ operation: @escaping @Sendable () async throws -> T) async throws -> T {
        try await mutation.withLock { [self] in
            let result = try await recover()
            guard result != .waitingForKeychain else { throw WalletLifecycle.Failure.waitingForSecureStorage }
            return try await operation()
        }
    }

    func settle() async throws -> RestoreTransaction.Recovery {
        let previous = tail
        let pass = Task { [recover] in
            _ = await previous?.result
            return try await mutation.withLock { try await recover() }
        }
        tail = pass
        return try await pass.value
    }
}
