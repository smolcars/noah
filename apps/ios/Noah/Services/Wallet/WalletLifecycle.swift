import Foundation
import WidgetKit

/// Settings' danger-zone actions, ported from RN `useSuspendWallet` and
/// `useDeleteWallet`, run through the session's lifecycle rules.
struct WalletLifecycle: Sendable {
    enum Failure: Error, Equatable {
        /// An interrupted restore must be settled first (relaunch).
        case restorePending
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

    /// Removes this wallet from the device: deregisters (best effort),
    /// closes the wallet, then under a reservation deletes its files, and
    /// only after them its seed, so a crash in between leaves a seed that
    /// can be restored from backup rather than wallet data without a seed.
    /// Then local state (MMKV, sign-in token, widget data) is cleared.
    func delete() async throws {
        let log = AppLog("walletLifecycle")
        do {
            try await dependencies.deregister()
        } catch {
            log.w("Deregistration failed while deleting the wallet: \(SafeErrorDescription.of(error))")
        }
        try await dependencies.session.closeSettled()
        let reservation = try await dependencies.session.reserve()
        do {
            try deleteLocalWallet()
            await dependencies.session.release(reservation)
        } catch {
            await dependencies.session.release(reservation)
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
        dependencies.scratch.sweepAbandoned()
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
            deregister: { _ = try await services.client.deregister().ok })
    }
}
