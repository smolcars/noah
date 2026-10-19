import Foundation

/// Process-wide services shared by the UI, push handlers and background
/// work: one wallet session, one token provider (one login at a time), one
/// server client.
final class AppServices: Sendable {
    static let shared = AppServices(variant: .current)

    let variant: AppVariant
    let session: WalletSession
    let tokens: AuthTokenProvider
    let client: Client
    let seed: KeychainPassword
    /// One backup at a time for UI, push and background work.
    let backupCoordinator: BackupCoordinator

    init(variant: AppVariant, session: WalletSession = .shared) {
        self.variant = variant
        self.session = session
        let tokens = AuthTokenProvider(dependencies: .live(variant: variant))
        let client = ServerClient.make(variant: variant, tokens: tokens)
        let seed = KeychainPassword.mnemonic(for: variant)
        self.tokens = tokens
        self.client = client
        self.seed = seed
        let backups = BackupService(session: session, client: client, readMnemonic: {
            guard case .found(let mnemonic) = seed.read() else { throw SeedUnavailable() }
            return mnemonic
        })
        backupCoordinator = BackupCoordinator(dependencies: .live(service: backups, session: session))
    }

    var appVersion: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "unknown"
    }

    struct SeedUnavailable: Error {}

    func readMnemonic() throws -> String {
        guard case .found(let mnemonic) = seed.read() else { throw SeedUnavailable() }
        return mnemonic
    }

    struct WalletSuspended: Error {}

    /// Opens the wallet if needed (RN `loadWalletIfNeeded`), never while the
    /// user has suspended it.
    struct RestoreNotSettled: Error {}

    func ensureWalletOpen() async throws {
        let wallet = SettingsStorage.shared.persistedState(PersistedWalletState.self, key: PersistedWalletState.key)
        guard wallet?.isWalletSuspended != true else { throw WalletSuspended() }
        // A background (push) launch never runs AppModel.start: settle an interrupted
        // restore here too, instead of failing every job until the app is opened.
        let location = WalletDataLocation(variant: variant)
        if location.restorePending,
            try await WalletRestorer.recoverInterruptedRestore(session: session, location: location, seed: seed)
                == .waitingForKeychain
        {
            throw RestoreNotSettled()
        }
        try await session.ensureOpen(mnemonic: readMnemonic(), runDaemon: true, appVersion: appVersion)
    }

    var backupService: BackupService {
        BackupService(session: session, client: client, readMnemonic: { [self] in try readMnemonic() })
    }
}

extension BackupCoordinator.Dependencies {
    static func live(service: BackupService, session: WalletSession, settings: SettingsStorage = .shared) -> Self {
        Self(
            perform: { last, onUploadRequired in
                try await service.performBackup(lastUploadedSnapshotSha256: last, onUploadRequired: onUploadRequired)
            },
            canBackup: { requireEnabled in
                // RN `canBackup`: initialized, loaded (open) and not suspended;
                // automatic backups also need the user's backup setting.
                guard await session.isOpen else { return false }
                let wallet = settings.persistedState(PersistedWalletState.self, key: PersistedWalletState.key)
                guard wallet?.isInitialized == true, wallet?.isWalletSuspended != true else { return false }
                guard requireEnabled else { return true }
                return settings.persistedState(PersistedServerState.self, key: PersistedServerState.key)?.isBackupEnabled == true
            },
            loadState: { settings.persistedState(PersistedBackupState.self, key: PersistedBackupState.key) ?? .init() },
            saveState: { try? settings.setPersistedState($0, key: PersistedBackupState.key, version: PersistedBackupState.version) })
    }
}
