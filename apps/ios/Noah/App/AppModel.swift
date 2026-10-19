import BarkBoltFFI
import Foundation
import Observation

/// App-wide state: what to show at launch and the open wallet.
@MainActor
@Observable
final class AppModel {
    enum Phase: Equatable {
        case launching
        case onboarding
        case wallet
        case blocked(LaunchState)
        /// An interrupted restore could not be settled; nothing was touched.
        case restoreIncomplete
    }

    private(set) var phase: Phase = .launching
    private(set) var summary: WalletSession.Summary?
    private(set) var walletError: String?
    private(set) var resumeOnboarding = false
    var isBalanceHidden: Bool {
        didSet { saveIsBalanceHidden() }
    }
    private(set) var amountUnit: BitcoinAmountUnit
    private(set) var preferredCurrency: String

    let variant = AppVariant.current
    let session = WalletSession.shared
    let server: ServerRegistration
    private let log = AppLog("app")
    private let settings = SettingsStorage.shared
    private let appVersion = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "unknown"
    @ObservationIgnored private let creator: WalletCreator
    @ObservationIgnored private var started = false
    @ObservationIgnored private var backupTriggers: (id: UUID, task: Task<Void, Never>)?

    init() {
        server = ServerRegistration(variant: .current, client: AppServices.shared.client)
        creator = WalletCreator(
            dependencies: .live(session: session, variant: variant, appVersion: appVersion))
        isBalanceHidden = settings.persistedState(PersistedPrivacyState.self, key: PersistedPrivacyState.key)?
            .isHomeBalanceHidden ?? false
        let currency = settings.persistedState(PersistedProfileState.self, key: PersistedProfileState.key)?.preferredCurrency ?? "USD"
        preferredCurrency = FiatCurrency.supported.contains(currency) ? currency : "USD"
        amountUnit = settings.persistedState(PersistedProfileState.self, key: PersistedProfileState.key)?
            .amountUnit ?? .bip177
    }

    func start() async {
        // `.task` can run again if the root view is rebuilt; launch once.
        guard !started else { return }
        started = true
        // An interrupted restore must be settled before the datadir and seed
        // say anything about which wallet this is.
        do {
            switch try await WalletRestorer.recoverInterruptedRestore(
                session: session, location: WalletDataLocation(variant: variant),
                seed: KeychainPassword.mnemonic(for: variant)) {
            case .nothingPending: break
            case .rolledBack: log.w("Undid an interrupted restore")
            case .completed: log.w("Finished an interrupted restore")
            case .waitingForKeychain:
                phase = .blocked(.secureStorageUnavailable)
                return
            }
        } catch {
            log.e("Couldn't settle an interrupted restore: \(Self.describe(error))")
            phase = .restoreIncomplete
            return
        }
        let state = LaunchState.current(variant: variant)
        switch state {
        case .existingWallet:
            resumeOnboarding = settings.persistedState(PersistedWalletState.self, key: PersistedWalletState.key)?.isInitialized != true
            phase = resumeOnboarding ? .onboarding : .wallet
            await openExistingWallet()
        case .onboarding:
            phase = .onboarding
        case .seedWithoutWalletData, .walletDataWithoutSeed, .secureStorageUnavailable:
            phase = .blocked(state)
        }
    }

    /// Onboarding "I accept": create (or resume) the wallet.
    func createWallet() async throws {
        try await creator.createOrResume()
        var wallet = settings.persistedState(PersistedWalletState.self, key: PersistedWalletState.key) ?? .init()
        wallet.isInitialized = false
        wallet.staticVtxoPubkey = try await session.peekPublicKey(index: 0)
        try settings.setPersistedState(wallet, key: PersistedWalletState.key, version: PersistedWalletState.version)
        // Registration failure must not hide the newly created recovery phrase.
        Task { await startServerServices() }
    }

    func currentMnemonicWords() -> [String] {
        guard case .found(let mnemonic) = KeychainPassword.mnemonic(for: variant).read() else { return [] }
        return mnemonic.split(separator: " ").map(String.init)
    }

    func finishOnboarding() async {
        var wallet = settings.persistedState(PersistedWalletState.self, key: PersistedWalletState.key) ?? .init()
        wallet.isInitialized = true
        wallet.isWalletLoaded = true
        try? settings.setPersistedState(wallet, key: PersistedWalletState.key, version: PersistedWalletState.version)
        phase = .wallet
        await refresh()
        await registerPush()
        await reconcileBackupTriggers()
    }

    func refresh() async {
        do {
            guard let latest = try await session.summary() else {
                // Not open (e.g. the first open failed): try again rather than
                // clearing the error with nothing to show.
                await openExistingWallet()
                return
            }
            summary = latest
            walletError = nil
        } catch {
            log.e("Failed to read wallet summary: \(Self.describe(error))")
            walletError = String(localized: "Couldn't load your balance.")
        }
    }

    func sync() async {
        guard await session.isOpen else {
            await openExistingWallet()
            return
        }
        do {
            try await session.sync()
        } catch {
            log.w("Wallet sync failed: \(Self.describe(error))")
        }
        await refresh()
    }

    private static func describe(_ error: Error) -> String {
        SafeErrorDescription.of(error)
    }

    private func openExistingWallet() async {
        guard case .found(let mnemonic) = KeychainPassword.mnemonic(for: variant).read() else {
            phase = .blocked(.secureStorageUnavailable)
            return
        }
        do {
            try await session.ensureOpen(mnemonic: mnemonic, runDaemon: true, appVersion: appVersion)
            if let latest = try await session.summary() {
                summary = latest
                walletError = nil
            }
        } catch {
            log.e("Failed to open wallet: \(Self.describe(error))")
            walletError = String(localized: "Couldn't open your wallet.")
            return
        }
        await reconcileBackupTriggers()
        await startServerServices()
    }

    // MARK: Backups (RN `useBackupCoordinator`)

    /// In the wallet, backups switched on, initialized and not suspended.
    private var canCoordinateBackups: Bool {
        guard phase == .wallet, server.state.isBackupEnabled == true else { return false }
        let wallet = settings.persistedState(PersistedWalletState.self, key: PersistedWalletState.key)
        return wallet?.isInitialized == true && wallet?.isWalletSuspended != true
    }

    /// Starts or stops automatic backups whenever eligibility may have
    /// changed (wallet opened, onboarding finished, registration switched
    /// backups on, foreground), like RN's reactive effect.
    private func reconcileBackupTriggers() async {
        if canCoordinateBackups {
            startBackupTriggers()
        } else if let running = backupTriggers {
            running.task.cancel()
            backupTriggers = nil
            await AppServices.shared.backupCoordinator.cancelScheduled()
        }
    }

    /// Back up after committed wallet changes (debounced); at once on the
    /// first event and after a resync. Ends when the session closes.
    private func startBackupTriggers() {
        guard backupTriggers == nil, canCoordinateBackups else { return }
        let coordinator = AppServices.shared.backupCoordinator
        let session = self.session
        let id = UUID()
        let task = Task { [weak self] in
            defer { if self?.backupTriggers?.id == id { self?.backupTriggers = nil } }
            var resubscribe = true
            while resubscribe, !Task.isCancelled {
                resubscribe = false
                do {
                    for try await event in try await session.stateChanges() {
                        switch event.reason {
                        case "databaseChanged":
                            await coordinator.schedule(.databaseChanged)
                        case "resyncRequired":
                            await coordinator.schedule(.resyncRequired, immediate: true)
                            resubscribe = true
                        default:
                            await coordinator.schedule(.startup, immediate: true)
                        }
                        if resubscribe { break }
                    }
                } catch {
                    if Task.isCancelled { return }
                    self?.log.w("Couldn't watch wallet changes for backups: \(Self.describe(error))")
                    await coordinator.schedule(.startup, immediate: true)
                }
            }
        }
        backupTriggers = (id, task)
    }

    func appBecameActive() async {
        await reconcileBackupTriggers()
        guard canCoordinateBackups else { return }
        await AppServices.shared.backupCoordinator.schedule(.appForeground, immediate: true)
    }

    private func startServerServices() async {
        do {
            try await server.ensureRegistered()
            // Registration may have switched backups on.
            await reconcileBackupTriggers()
            try await server.reportLastLogin()
        } catch {
            log.w("Server setup failed; it can be retried from onboarding or Home")
            return
        }
        await registerPush()
    }

    /// Like RN's `usePushNotifications`: only once onboarding is finished and
    /// the server knows this wallet, so the prompt never interrupts onboarding.
    private func registerPush() async {
        guard phase == .wallet, server.state.isRegisteredWithServer == true else { return }
        do {
            switch try await PushRegistration.shared.register() {
            case .registered: break
            case .permissionDenied: log.i("Push permission denied; skipping push registration")
            case .deviceNotSupported: log.i("Push registration unavailable on this device")
            }
        } catch {
            log.w("Push registration failed: \(Self.describe(error))")
        }
    }

    private func saveIsBalanceHidden() {
        try? settings.setPersistedState(
            PersistedPrivacyState(isHomeBalanceHidden: isBalanceHidden),
            key: PersistedPrivacyState.key, version: PersistedPrivacyState.version)
    }
}
