import Foundation

/// Process-wide services shared by the UI, push handlers and background
/// work: one wallet session, one token provider (one login at a time), one
/// server client.
final class AppServices: Sendable {
    static let shared = AppServices(variant: .current)

    static let configurationAccess = WalletMutationGate()

    private let foregroundSync = ForegroundWalletSync()

    let variant: AppVariant
    let session: WalletSession
    let tokens: AuthTokenProvider
    let client: Client
    let seed: KeychainPassword
    /// One backup at a time for UI, push and background work.
    let backupCoordinator: BackupCoordinator
    /// Foreground launch recovery; headless jobs defer pending journals/markers.
    let restoreRecovery: RestoreRecovery

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
        let coordinator = BackupCoordinator(dependencies: .live(service: backups, session: session))
        backupCoordinator = coordinator
        let location = WalletDataLocation(variant: variant)
        let lifecycle = WalletLifecycle(dependencies: .init(
            session: session, location: location, seed: seed,
            authToken: KeychainPassword.serverAuthToken(for: variant), settings: .shared,
            scratch: .shared, appGroup: variant.appGroup,
            deregister: { _ = try await client.deregister().ok },
            settleBackground: { try await coordinator.settleForDeletion(timeout: $0) }))
        restoreRecovery = RestoreRecovery {
            try await lifecycle.recoverRestoreAndDeletionLocked {
                try await WalletRestorer.recoverInterruptedRestore(session: session, location: location, seed: seed)
            }
        }
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

    /// Read-only admission: recovery and seed migration belong to foreground launch.
    struct RestoreNotSettled: Error {}

    func ensureWalletOpen() async throws {
        let mnemonic = try HeadlessWalletGate.mnemonic(
            location: WalletDataLocation(variant: variant), settings: .shared, readSeed: { self.seed.read() })
        try Task.checkCancellation()
        try await session.ensureOpen(mnemonic: mnemonic, runDaemon: WalletSession.backgroundOwner == nil, appVersion: appVersion)
        try Task.checkCancellation()
    }

    func withBackgroundWallet<T: Sendable>(_ operation: @escaping @Sendable () async throws -> T) async throws -> T {
        try await Self.configurationAccess.withLock { [self] in
            try await withBackgroundWalletLocked(operation)
        }
    }

    private func withBackgroundWalletLocked<T: Sendable>(_ operation: @escaping @Sendable () async throws -> T) async throws -> T {
        await WalletBackgroundActivity.shared.begin()
        do {
            let result = try await session.withBackgroundAccess(operation, settle: { [self] in await backupCoordinator.settle() })
            await WalletBackgroundActivity.shared.end()
            return result
        } catch {
            await WalletBackgroundActivity.shared.end()
            throw error
        }
    }

    /// Called by the app-wide foreground timer; never opens or recovers a wallet.
    func syncInForeground() async {
        do {
            try await foregroundSync.run { [self] in
                try await ForegroundExecution.checkActive()
                try BackgroundExecution.checkActive()
                let location = WalletDataLocation(variant: variant)
                let wallet = SettingsStorage.shared.persistedState(PersistedWalletState.self, key: PersistedWalletState.key)
                guard wallet?.isInitialized != false, wallet?.isWalletSuspended != true,
                      !location.restorePending, !FileManager.default.fileExists(atPath: location.deletionMarker.path),
                      await session.isOpen else { return }
                await WalletBackgroundActivity.shared.begin()
                do { try await session.sync(includingLightningClaims: true) }
                catch { await WalletBackgroundActivity.shared.end(); throw error }
                await WalletBackgroundActivity.shared.end()
            }
        } catch { /* A later foreground tick retries; no secrets or provider errors are logged. */ }
    }

    func switchEsploraEndpoint(_ input: String?) async throws {
        let endpoint: String?
        if let input { endpoint = try await EsploraEndpoint.validate(input, variant: variant) }
        else if let defaultEndpoint = WalletConfiguration.base(for: variant).esplora {
            endpoint = try await EsploraEndpoint.validate(defaultEndpoint, variant: variant)
        } else { endpoint = nil }
        guard variant != .regtest else { throw EsploraEndpoint.Failure(message: "Regtest wallets use Bitcoin Core instead of Esplora.") }
        let candidate = EsploraSettings.Selection(endpoint: endpoint == WalletConfiguration.base(for: variant).esplora ? nil : endpoint,
            variant: variant.rawValue)
        // Background owners finish before acquiring the lifecycle gate; new jobs wait here.
        try await Self.configurationAccess.withLock { [self] in
            try await restoreRecovery.withSettled { [self] in
                guard await session.isOpen,
                      SettingsStorage.shared.persistedState(PersistedWalletState.self, key: PersistedWalletState.key)?.isWalletSuspended != true else {
                    throw EsploraEndpoint.Failure(message: "Open your wallet before changing the endpoint.")
                }
                let mnemonic = try readMnemonic()
                let store = EsploraSettings()
                let prepared = try store.prepare(candidate)
                let reservation = try await session.reserveForConfigurationChange()
                try await EsploraOpenWait().run(operation: { [self] wait in
                    do {
                        try await store.change(to: candidate, prepared: prepared, commitCandidate: { try wait.commit($0) }) { [self] selection, commit in
                            try await EsploraOpening.$selection.withValue(selection) {
                                try await session.reopen(reservation, mnemonic: mnemonic, appVersion: appVersion, commit: commit)
                            }
                        }
                        await session.release(reservation)
                    } catch {
                        await session.release(reservation)
                        throw error
                    }
                }, lateCompletion: { _ in
                    NotificationCenter.default.post(name: Self.esploraReopened, object: nil)
                })
            }
        }
    }

    static let esploraReopened = Notification.Name("noah.esplora-reopened")

    /// Foreground only: timeout offers a default selection while the worker keeps the datadir reserved.
    func openWalletInForeground(mnemonic: String, wait: EsploraOpenWait,
                                lateCompletion: @escaping @Sendable (Bool) async -> Void) async throws {
        let store = EsploraSettings()
        guard variant != .regtest, let endpoint = try await store.resolve(for: variant), !(await session.isOpen) else {
            try await session.ensureOpen(mnemonic: mnemonic, runDaemon: true, appVersion: appVersion)
            return
        }
        let selection = EsploraSettings.Selection(endpoint: endpoint, variant: variant.rawValue)
        try await Self.configurationAccess.withLock { [self] in
            try await restoreRecovery.withSettled { [self] in
                let reservation = try await session.reserve()
                try await wait.run(operation: { [self] wait in
                    do {
                        do {
                            try await EsploraOpening.$selection.withValue(selection) {
                                try await session.reopen(reservation, mnemonic: mnemonic, appVersion: appVersion, commit: { try wait.commit({}) })
                            }
                        } catch {
                            guard wait.timedOut else { throw error }
                            await wait.waitForDefault()
                            let fallback = EsploraSettings.Selection(variant: variant.rawValue)
                            SettingsStorage.shared.useDefaultEsplora(for: variant)
                            try await EsploraOpening.$selection.withValue(fallback) {
                                try await session.reopen(reservation, mnemonic: mnemonic, appVersion: appVersion, commit: {})
                            }
                        }
                        await session.release(reservation)
                    } catch {
                        await session.release(reservation)
                        throw error
                    }
                }, lateCompletion: lateCompletion)
            }
        }
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
                guard wallet?.isInitialized != false, wallet?.isWalletSuspended != true else { return false }
                guard requireEnabled else { return true }
                return settings.persistedState(PersistedServerState.self, key: PersistedServerState.key)?.isBackupEnabled == true
            },
            loadState: { settings.persistedState(PersistedBackupState.self, key: PersistedBackupState.key) ?? .init() },
            saveState: { try? settings.setPersistedState($0, key: PersistedBackupState.key, version: PersistedBackupState.version) })
    }
}
