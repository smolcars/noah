import Foundation

/// Settings' danger-zone actions, ported from RN `useSuspendWallet` and
/// `useDeleteWallet`, run through the session's lifecycle rules.
struct WalletLifecycle: Sendable {
    enum Failure: LocalizedError, Equatable {
        /// An interrupted restore must be settled first (relaunch).
        case waitingForSecureStorage
        case restorePending
        case restoreSettingsUnavailable
        case temporaryFiles
        case backupBusy

        var errorDescription: String? {
            switch self {
            case .waitingForSecureStorage: "Waiting for secure storage. Unlock your device and try again."
            case .backupBusy: "Noah is still finishing a backup. Try again in a moment."
            case .temporaryFiles: "Noah couldn't remove temporary wallet files. Restart Noah and try again."
            case .restorePending: "Restart Noah to settle the pending restore before deleting."
            // swiftlint:disable:next line_length - one literal: translations.mjs reads copy as a single string
            case .restoreSettingsUnavailable: "Restored wallet settings can't be saved yet. Try Resume again when storage is available. Recurring payments remain stopped."
            }
        }
    }

    struct Dependencies: Sendable {
        var session: WalletSession
        var location: WalletDataLocation
        var seed: any WalletDeletionSeed
        var authToken: KeychainPassword
        var settings: SettingsStorage
        var scratch: ScratchSpace
        var appGroup: String?
        var mutation: WalletMutationGate = .shared
        var drainTimeout: Duration = .seconds(30)
        /// Best effort, like RN (`/v0/deregister`).
        var deregister: @Sendable () async throws -> Void
        var settleBackground: @Sendable (Duration) async throws -> Void = { _ in }
        var walletChanged: @Sendable () async -> Void = { await SendPaymentController.shared.reloadForWalletChange() }
        var persistAutomationStop: (@Sendable (String) throws -> Void)?
    }

    static let automationStopKey = "native-recurring-automation-stop-generation"

    private final class StopLatch: @unchecked Sendable {
        let lock = NSLock()
        var failed: [ObjectIdentifier: SettingsStorage] = [:]
    }
    private static let stopLatch = StopLatch()
    static func automationAllowed(settings: SettingsStorage) -> Bool {
        stopLatch.lock.withLock { stopLatch.failed[ObjectIdentifier(settings)] == nil }
    }

    let dependencies: Dependencies

    /// The flag is persisted first, so nothing (UI, push, background work)
    /// opens the wallet again; then the wallet is closed, after an open
    /// already in progress. If closing fails the flag is put back.
    func suspend() async throws {
        try stopAutomation()
        do {
            try await dependencies.session.closeSettled(beforeClose: { try setSuspended(true) })
        } catch {
            try? setSuspended(false)
            throw error
        }
    }

    /// Clears the flag; the caller then opens the wallet as at launch.
    func resume() throws {
        let transaction = RestoreTransaction(location: dependencies.location)
        transaction.completeSettings(dependencies.settings)
        guard !transaction.settingsPending else { throw Failure.restoreSettingsUnavailable }
        try authority()?.resume()
        try setSuspended(false)
    }

    /// Finish a committed deletion before launch inspects the seed and datadir.
    @discardableResult
    func recoverDeletion() async throws -> RestoreTransaction.Recovery {
        try await dependencies.mutation.withLock { try await recoverDeletionLocked() }
    }

    /// Called by launch and push recovery while RestoreRecovery owns the mutation gate.
    func recoverRestoreAndDeletionLocked(
        recoverRestore: @Sendable () async throws -> RestoreTransaction.Recovery) async throws -> RestoreTransaction.Recovery {
        // Deletion refuses a restore journal, and restore refuses a deletion marker.
        // Coexistence proves the marker is stale, even if its seed tag matches.
        // Drop it durably before restore removes its journal: a crash after rollback
        // must not reauthorize deletion of the previous wallet on the next launch.
        if dependencies.location.restorePending {
            if FileManager.default.fileExists(atPath: dependencies.location.deletionMarker.path) {
                if try dependencies.location.deletionStatus(seed: dependencies.seed.read()) == .waitingForKeychain {
                    return .waitingForKeychain
                }
                try discardDeletionMarker()
            } else {
                // Retry a prior marker unlink whose directory sync failed.
                try RestoreTransaction.fullSync(dependencies.location.directory.deletingLastPathComponent())
            }
        }
        if !FileManager.default.fileExists(atPath: dependencies.location.deletionMarker.path) {
            try dependencies.location.removeDiscardedDeletionData()
        }
        let result = try await recoverRestore()
        guard result != .waitingForKeychain else { return result }
        if try await recoverDeletionLocked() == .waitingForKeychain { return .waitingForKeychain }
        return result
    }

    /// Called by RestoreRecovery while it owns the same mutation gate.
    @discardableResult
    func recoverDeletionLocked() async throws -> RestoreTransaction.Recovery {
        let location = dependencies.location
        if FileManager.default.fileExists(atPath: location.deletionMarker.path) {
            switch try location.deletionStatus(seed: dependencies.seed.read()) {
            case .waitingForKeychain: return .waitingForKeychain
            case .mismatched: try discardDeletionMarker()
            case .matching:
                try await deleteLocked()
                return .nothingPending
            }
        }
        try location.removeDiscardedDeletionData()
        return .nothingPending
    }

    private func discardDeletionMarker() throws {
        AppLog("walletLifecycle").w("Discarding deletion marker for a different or unidentifiable wallet")
        let location = dependencies.location
        try FileManager.default.removeItem(at: location.deletionMarker)
        try RestoreTransaction.fullSync(location.directory.deletingLastPathComponent())
    }

    /// Drain operations and clean temporary files before committing deletion.
    /// Once the marker is durable, files precede secrets and relaunch finishes any interruption.
    func delete() async throws {
        try stopAutomation()
        try await dependencies.mutation.withLock { try await deleteLocked() }
    }

    private func stopAutomation() throws {
        try Self.stopLatch.lock.withLock {
            let settings = dependencies.settings
            do {
                try authority()?.stop()
                let generation = UUID().uuidString
                if let persist = dependencies.persistAutomationStop { try persist(generation) }
                else { try settings.setDurably(generation, forKey: Self.automationStopKey) }
                Self.stopLatch.failed.removeValue(forKey: ObjectIdentifier(settings))
            } catch {
                // A failed stop must beat a pending submission even when durable state is unchanged.
                Self.stopLatch.failed[ObjectIdentifier(settings)] = settings
                throw error
            }
        }
    }

    private func deleteLocked() async throws {
        try stopAutomation()
        let wasSuspended = dependencies.settings.persistedState(PersistedWalletState.self, key: PersistedWalletState.key)?.isWalletSuspended == true
        try setSuspended(true)
        var ownsScratch = false
        defer { if ownsScratch { dependencies.scratch.finishWalletDeletion() } }
        do {
            let deadline = ContinuousClock.now + dependencies.drainTimeout
            try await dependencies.settleBackground(dependencies.drainTimeout)
            try await dependencies.scratch.drainForWalletDeletion(timeout: ContinuousClock.now.duration(to: deadline))
            ownsScratch = true
            try await dependencies.session.closeSettled()
            let reservation = try await dependencies.session.reserve()
            do {
                guard !dependencies.location.restorePending else { throw Failure.restorePending }
                do { try dependencies.scratch.sweepForWalletDeletion() }
                catch { throw Failure.temporaryFiles }
                let marker = dependencies.location.deletionMarker
                let seed = dependencies.seed.read()
                if FileManager.default.fileExists(atPath: marker.path) {
                    switch try dependencies.location.deletionStatus(seed: seed) {
                    case .waitingForKeychain: throw Failure.waitingForSecureStorage
                    case .mismatched: try discardDeletionMarker()
                    case .matching: break
                    }
                }
                if !FileManager.default.fileExists(atPath: marker.path) {
                    if case .unavailable = seed { throw Failure.waitingForSecureStorage }
                    try dependencies.location.writeDeletionIdentity(seed: seed)
                    let handle = try FileHandle(forWritingTo: marker)
                    try handle.synchronize()
                    try handle.close()
                    try RestoreTransaction.fullSync(marker.deletingLastPathComponent())
                }
                do { try await dependencies.deregister() }
                catch { AppLog("walletLifecycle").w("Deregistration failed while deleting the wallet") }
                try deleteLocalWallet()
                await dependencies.walletChanged()
                try FileManager.default.removeItem(at: marker)
                try RestoreTransaction.fullSync(marker.deletingLastPathComponent())
                await dependencies.session.release(reservation)
            } catch {
                await dependencies.session.release(reservation)
                throw error
            }
        } catch {
            if !FileManager.default.fileExists(atPath: dependencies.location.deletionMarker.path) {
                try? setSuspended(wasSuspended)
            }
            throw error
        }
    }

    private func deleteLocalWallet() throws {
        let fileManager = FileManager.default
        let location = dependencies.location
        guard !location.restorePending else { throw Failure.restorePending }
        if fileManager.fileExists(atPath: location.directory.path) {
            // Also finishes an older app's committed marker over abandoned staging.
            if fileManager.fileExists(atPath: location.deletionDirectory.path) {
                try fileManager.removeItem(at: location.deletionDirectory)
            }
            // Rename before recursive removal: recovery never sees a partial instance identity.
            try fileManager.moveItem(at: location.directory, to: location.deletionDirectory)
            try RestoreTransaction.fullSync(location.directory.deletingLastPathComponent())
        }
        if fileManager.fileExists(atPath: location.deletionDirectory.path) {
            try fileManager.removeItem(at: location.deletionDirectory)
        }
        if fileManager.fileExists(atPath: location.restoreDirectory.path) {
            try fileManager.removeItem(at: location.restoreDirectory)
        }
        try RestoreTransaction.fullSync(location.directory.deletingLastPathComponent())
        RecurringAutomationAuthority.deleted(directory: location.directory)
        try dependencies.seed.deleteWalletSeed()
        dependencies.authToken.remove()
        try dependencies.settings.removeAllDurably()
        if let appGroup = dependencies.appGroup { WidgetDataWriter.clear(appGroup: appGroup) }
    }

    private func authority() -> RecurringAutomationAuthority? {
        guard FileManager.default.fileExists(atPath: dependencies.location.directory.path) else { return nil }
        return RecurringAutomationAuthority(directory: dependencies.location.directory)
    }
    private func setSuspended(_ suspended: Bool) throws {
        try authority()?.suspension(suspended)
        let settings = dependencies.settings
        var wallet = settings.persistedState(PersistedWalletState.self, key: PersistedWalletState.key) ?? .init()
        wallet.isWalletSuspended = suspended
        do { try settings.setPersistedState(wallet, key: PersistedWalletState.key, version: PersistedWalletState.version) }
        catch { try? authority()?.stop(); throw error }
    }
}

extension WalletLifecycle.Dependencies {
    static func live(services: AppServices = .shared) -> Self {
        Self(
            session: services.session,
            location: WalletDataLocation(variant: services.variant),
            seed: services.seed,
            authToken: KeychainPassword.serverAuthToken(for: services.variant),
            settings: .shared,
            scratch: .shared,
            appGroup: services.variant.appGroup,
            deregister: { _ = try await services.client.deregister().ok },
            settleBackground: { try await services.backupCoordinator.settleForDeletion(timeout: $0) })
    }
}

/// Injectable secure-storage reads include locked/unreadable seed recovery.
protocol WalletDeletionSeed: Sendable {
    func read() -> KeychainPassword.ReadResult
    func deleteWalletSeed() throws
}

extension KeychainPassword: WalletDeletionSeed {}
