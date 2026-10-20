import BarkBoltFFI
import Foundation
import Observation
import UIKit

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
    private(set) var isRestoring = false
    private(set) var restoreStep: WalletRestorer.Step?
    private(set) var restoreIssue: RestoreIssue?
    var isBalanceHidden: Bool {
        didSet { saveIsBalanceHidden() }
    }
    private(set) var amountUnit: BitcoinAmountUnit
    private(set) var preferredCurrency: String
    /// Settings → suspend: nothing opens the wallet until it is resumed.
    private(set) var isWalletSuspended: Bool
    var localeIdentifier = LegacyLocale.read() ?? "en"

    func updateProfile(_ change: (inout PersistedProfileState) -> Void) throws {
        var profile = settings.persistedState(PersistedProfileState.self, key: PersistedProfileState.key) ?? .init()
        change(&profile)
        try settings.setPersistedState(profile, key: PersistedProfileState.key, version: PersistedProfileState.version)
        amountUnit = profile.amountUnit
        preferredCurrency = profile.preferredCurrency ?? "USD"
    }

    func setLanguage(_ code: String) {
        UserDefaults.standard.set(code, forKey: LegacyLocale.key)
        localeIdentifier = code
    }

    func backupSettingsChanged() async { await reconcileBackupTriggers() }

    let variant = AppVariant.current
    let session = WalletSession.shared
    let versionCheck = AppVersionCheck()
    let pushPermission = PushPermissionModel()
    let server: ServerRegistration
    private let log = AppLog("app")
    private let settings = SettingsStorage.shared
    private let appVersion = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "unknown"
    @ObservationIgnored private let creator: WalletCreator
    @ObservationIgnored private var recurringForeground: RecurringForegroundRunner?
    @ObservationIgnored private var started = false
    @ObservationIgnored private var backupTriggers: (id: UUID, task: Task<Void, Never>)?
    @ObservationIgnored private let lifecycle = WalletLifecycle(dependencies: .live())

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
        isWalletSuspended = settings.persistedState(PersistedWalletState.self, key: PersistedWalletState.key)?
            .isWalletSuspended == true
        recurringForeground = RecurringForegroundRunner { [weak self] in
            guard let self, phase == .wallet, !isWalletSuspended, !pushPermission.blocksWallet(initialized: true) else { return }
            await RecurringExecutor.shared.execute()
        }
    }

    func start() async {
        // `.task` can run again if the root view is rebuilt; launch once.
        guard !started else { return }
        started = true
        do { try settings.requireCompatibleRNStores() }
        catch { phase = .blocked(.incompatibleStorage); return }
        let initialState = LaunchState.current(variant: variant)
        if initialState == .invalidSeed || initialState == .seedConflict {
            phase = .blocked(initialState)
            return
        }
        if UIApplication.shared.applicationState == .active { recurringForeground?.start() }
        // An interrupted restore must be settled before the datadir and seed
        // say anything about which wallet this is.
        do {
            switch try await AppServices.shared.restoreRecovery.settle() {
            case .nothingPending: break
            case .rolledBack: log.w("Undid an interrupted restore")
            case .completed: log.w("Finished an interrupted restore")
            case .waitingForKeychain:
                phase = .blocked(.secureStorageUnavailable)
                return
            }
        } catch {
            log.e("Couldn't settle an interrupted restore: \(Self.describe(error))")
            walletError = (error as? WalletLifecycle.Failure)?.errorDescription
            if walletError == nil, FileManager.default.fileExists(atPath: WalletDataLocation(variant: variant).deletionMarker.path) {
                walletError = "Noah couldn't finish deleting your wallet. Restart Noah to try again."
            }
            phase = .restoreIncomplete
            return
        }
        isWalletSuspended = settings.persistedState(PersistedWalletState.self, key: PersistedWalletState.key)?.isWalletSuspended == true
        let state = LaunchState.current(variant: variant)
        switch state {
        case .existingWallet:
            resumeOnboarding = settings.persistedState(PersistedWalletState.self, key: PersistedWalletState.key)?.isInitialized != true
            await openExistingWallet()
            if phase == .launching { phase = resumeOnboarding ? .onboarding : .wallet }
        case .onboarding:
            phase = .onboarding
        case .seedWithoutWalletData, .walletDataWithoutSeed, .secureStorageUnavailable, .invalidSeed, .seedConflict, .incompatibleStorage:
            phase = .blocked(state)
        }
    }

    /// Onboarding "I accept": create (or resume) the wallet.
    func createWallet() async throws {
        do { try await creator.createOrResume() }
        catch {
            if let failure = error as? StoredSeed.Failure {
                phase = .blocked(failure == .invalid ? .invalidSeed : .seedConflict)
            }
            throw error
        }
        var wallet = settings.persistedState(PersistedWalletState.self, key: PersistedWalletState.key) ?? .init()
        wallet.isInitialized = false
        wallet.staticVtxoPubkey = try await session.peekPublicKey(index: 0)
        try settings.setPersistedState(wallet, key: PersistedWalletState.key, version: PersistedWalletState.version)
        // Registration failure must not hide the newly created recovery phrase.
        Task { await startServerServices() }
    }

    func currentMnemonicWords() -> [String] {
        guard case .found(let value) = KeychainPassword.mnemonic(for: variant).read(),
              let mnemonic = try? StoredSeed.normalized(value) else { return [] }
        return mnemonic.split(separator: " ").map(String.init)
    }

    /// Both the Welcome entry and reinstall recovery use this closed-session flow.
    func restoreWallet(phrase: String? = nil) async {
        guard !isRestoring, restoreIssue?.requiresRestart != true else { return }
        guard phase == .onboarding || phase == .blocked(.seedWithoutWalletData) else { return }
        isRestoring = true
        restoreIssue = nil
        defer { isRestoring = false; restoreStep = nil }
        var committed = false
        do {
            let mnemonic = try phrase ?? AppServices.shared.readMnemonic()
            guard (try? StoredSeed.normalized(mnemonic)) != nil else {
                restoreIssue = .invalidPhrase
                return
            }
            let location = WalletDataLocation(variant: variant)
            // Restoring over data belongs to a separate, explicit recovery flow.
            guard !location.hasAnyData else { throw WalletSession.OpenError.walletDatabaseExists }
            let variant = self.variant
            let restorer = WalletRestorer(
                variant: variant, session: session, location: location,
                seed: KeychainPassword.mnemonic(for: variant),
                makeClient: { ServerClient.makeRestore(variant: variant, mnemonic: $0) })
            let outcome = try await restorer.restore(mnemonic: mnemonic) { [weak self] step in
                Task { @MainActor in
                    if self?.isRestoring == true { self?.restoreStep = step }
                }
            }
            committed = true
            try await RestoreCompletion(
                loadWalletState: { self.settings.persistedState(PersistedWalletState.self, key: PersistedWalletState.key) ?? .init() },
                saveWalletState: { try self.settings.setPersistedState($0, key: PersistedWalletState.key, version: PersistedWalletState.version) },
                seedRestored: { await AppServices.shared.backupCoordinator.seedRestored(snapshotSha256: $0, backupId: $1) },
                recover: { try await AppServices.shared.restoreRecovery.settle() },
                openWallet: {
                    await AppServices.shared.tokens.invalidate()
                    self.resumeOnboarding = false
                    self.phase = .wallet
                    await self.openExistingWallet()
                }).finish(outcome)
        } catch {
            log.e("Restore failed: \(SafeErrorDescription.of(error))")
            if let failure = error as? StoredSeed.Failure {
                phase = .blocked(failure == .invalid ? .invalidSeed : .seedConflict)
            }
            restoreIssue = committed ? .committed : .from(error)
        }
    }

    func finishOnboarding() async {
        var wallet = settings.persistedState(PersistedWalletState.self, key: PersistedWalletState.key) ?? .init()
        wallet.isInitialized = true
        wallet.isWalletLoaded = true
        try? settings.setPersistedState(wallet, key: PersistedWalletState.key, version: PersistedWalletState.version)
        phase = .wallet
        await pushPermission.refresh()
        guard !pushPermission.blocksWallet(initialized: true) else { return }
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
        guard !isWalletSuspended else { return }
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
            // Identity checks must finish before exposing wallet/settings actions.
            if phase == .launching { phase = resumeOnboarding ? .onboarding : .wallet }
        } catch {
            if let failure = error as? StoredSeed.Failure {
                phase = .blocked(failure == .invalid ? .invalidSeed : .seedConflict)
                return
            }
            log.e("Failed to open wallet: \(Self.describe(error))")
            walletError = String(localized: "Couldn't open your wallet.")
            return
        }
        if phase == .wallet { await pushPermission.refresh() }
        await reconcileBackupTriggers()
        await startServerServices()
    }

    // MARK: Danger zone (Settings)

    /// RN `useSuspendWallet(true)`.
    func suspendWallet() async throws {
        try await lifecycle.suspend()
        isWalletSuspended = true
        summary = nil
        await reconcileBackupTriggers()
    }

    /// RN `useSuspendWallet(false)`.
    func resumeWallet() async throws {
        try lifecycle.resume()
        isWalletSuspended = false
        await openExistingWallet()
    }

    /// RN `useDeleteWallet`: back to onboarding with nothing left behind.
    func deleteWallet() async throws {
        if let running = backupTriggers {
            running.task.cancel()
            backupTriggers = nil
        }
        do { try await lifecycle.delete() }
        catch {
            isWalletSuspended = settings.persistedState(PersistedWalletState.self, key: PersistedWalletState.key)?.isWalletSuspended == true
            if !isWalletSuspended { await openExistingWallet() }
            throw error
        }
        summary = nil
        walletError = nil
        isWalletSuspended = false
        resumeOnboarding = false
        server.reload()
        phase = .onboarding
    }

    /// RN `resetAndReRegisterWithServer`.
    func reRegisterWithServer() async throws {
        try server.resetRegistration()
        try await server.ensureRegistered()
        await reconcileBackupTriggers()
        await registerPush()
    }

    // MARK: Backups (RN `useBackupCoordinator`)

    /// In the wallet, backups switched on, initialized and not suspended.
    private var canCoordinateBackups: Bool {
        guard phase == .wallet, !pushPermission.blocksWallet(initialized: true), !isWalletSuspended, server.state.isBackupEnabled == true else { return false }
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
        if UIApplication.shared.applicationState == .active { recurringForeground?.start() }
        if phase == .wallet {
            let wasBlocked = pushPermission.blocksWallet(initialized: true)
            await pushPermission.refresh()
            await reconcileBackupTriggers()
            guard !pushPermission.blocksWallet(initialized: true) else { return }
            if wasBlocked { await startServerServices() }
            try? await server.refreshMailboxIfNeeded()
            await RecurringExecutor.shared.execute()
            await refresh()
        }
        await reconcileBackupTriggers()
        guard canCoordinateBackups else { return }
        await AppServices.shared.backupCoordinator.schedule(.appForeground, immediate: true)
    }

    func resolvePushPermission(request: Bool) async {
        await pushPermission.retry(request: request)
        guard phase == .wallet, !pushPermission.blocksWallet(initialized: true) else { return }
        await startServerServices()
    }

    private func startServerServices() async {
        if phase == .wallet {
            await pushPermission.refresh()
            guard !pushPermission.blocksWallet(initialized: true) else { return }
        }
        do {
            try await server.ensureRegistered()
            // Registration may have switched backups on.
            await reconcileBackupTriggers()
            try await server.reportLastLogin()
            try? await server.refreshMailboxIfNeeded()
        } catch {
            log.w("Server setup failed; it can be retried from onboarding or Home")
            return
        }
        await registerPush()
        guard !pushPermission.blocksWallet(initialized: phase == .wallet) else { return }
        startBackupTriggers()
        await RecurringExecutor.shared.execute()
    }

    /// Like RN's `usePushNotifications`: only once onboarding is finished and
    /// the server knows this wallet, so the prompt never interrupts onboarding.
    private func registerPush() async {
        guard phase == .wallet, server.state.isRegisteredWithServer == true else { return }
        do {
            let outcome = try await PushRegistration.shared.register()
            await pushPermission.refresh()
            switch outcome {
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
