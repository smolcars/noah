import Foundation
import WidgetKit

/// Settings' danger-zone actions, ported from RN `useSuspendWallet` and
/// `useDeleteWallet`, run through the session's lifecycle rules.
struct WalletLifecycle: Sendable {
    enum Failure: LocalizedError, Equatable {
        /// An interrupted restore must be settled first (relaunch).
        case restorePending
        case temporaryFiles

        var errorDescription: String? {
            switch self {
            case .temporaryFiles: "Noah couldn't remove temporary wallet files. Restart Noah and try again."
            case .restorePending: "Restart Noah to settle the pending restore before deleting."
            }
        }
    }

    struct Dependencies: Sendable {
        var session: WalletSession
        var location: WalletDataLocation
        var seed: KeychainPassword
        var authToken: KeychainPassword
        var settings: SettingsStorage
        var scratch: ScratchSpace
        var appGroup: String?
        /// Best effort, like RN (`/v0/deregister`).
        var deregister: @Sendable () async throws -> Void
        var settleBackground: @Sendable () async -> Void = {}
    }

    let dependencies: Dependencies

    /// The flag is persisted first, so nothing (UI, push, background work)
    /// opens the wallet again; then the wallet is closed, after an open
    /// already in progress. If closing fails the flag is put back.
    func suspend() async throws {
        try setSuspended(true)
        do {
            try await dependencies.session.closeSettled()
        } catch {
            try? setSuspended(false)
            throw error
        }
    }

    /// Clears the flag; the caller then opens the wallet as at launch.
    func resume() throws {
        try setSuspended(false)
    }

    /// Finish a committed deletion before launch inspects the seed and datadir.
    func recoverDeletion() async throws {
        if FileManager.default.fileExists(atPath: dependencies.location.deletionMarker.path) {
            try await delete()
        }
    }

    /// Drain operations and clean temporary files before committing deletion.
    /// Once the marker is durable, files precede secrets and relaunch finishes any interruption.
    func delete() async throws {
        let wasSuspended = dependencies.settings.persistedState(PersistedWalletState.self, key: PersistedWalletState.key)?.isWalletSuspended == true
        try setSuspended(true)
        var ownsScratch = false
        defer { if ownsScratch { dependencies.scratch.finishWalletDeletion() } }
        do {
            await dependencies.settleBackground()
            try await dependencies.scratch.drainForWalletDeletion()
            ownsScratch = true
            try await dependencies.session.closeSettled()
            let reservation = try await dependencies.session.reserve()
            do {
                guard !dependencies.location.restorePending else { throw Failure.restorePending }
                do { try dependencies.scratch.sweepForWalletDeletion() }
                catch { throw Failure.temporaryFiles }
                let marker = dependencies.location.deletionMarker
                if !FileManager.default.fileExists(atPath: marker.path) {
                    try Data([1]).write(to: marker, options: .atomic)
                    let handle = try FileHandle(forWritingTo: marker)
                    try handle.synchronize()
                    try handle.close()
                    try RestoreTransaction.fullSync(marker.deletingLastPathComponent())
                }
                do { try await dependencies.deregister() }
                catch { AppLog("walletLifecycle").w("Deregistration failed while deleting the wallet") }
                try deleteLocalWallet()
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
            try fileManager.removeItem(at: location.directory)
        }
        if fileManager.fileExists(atPath: location.restoreDirectory.path) {
            try fileManager.removeItem(at: location.restoreDirectory)
        }
        try RestoreTransaction.fullSync(location.directory.deletingLastPathComponent())
        try dependencies.seed.deleteWalletSeed()
        dependencies.authToken.remove()
        dependencies.settings.removeAll()
        if let appGroup = dependencies.appGroup, let defaults = UserDefaults(suiteName: appGroup) {
            defaults.removeObject(forKey: "widgetBalance")
            WidgetCenter.shared.reloadAllTimelines()
        }
    }

    private func setSuspended(_ suspended: Bool) throws {
        let settings = dependencies.settings
        var wallet = settings.persistedState(PersistedWalletState.self, key: PersistedWalletState.key) ?? .init()
        wallet.isWalletSuspended = suspended
        try settings.setPersistedState(wallet, key: PersistedWalletState.key, version: PersistedWalletState.version)
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
            settleBackground: { await services.backupCoordinator.settle() })
    }
}
