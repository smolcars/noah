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

    /// Returning to the foreground catches the wallet up: a sync, then the balance read.
    /// Leaving the foreground starts a catch-up and that read finishes it. Home's first
    /// payment waits for it, so what it imports doesn't play as live. In memory only.
    struct CatchUp: Equatable {
        var started = 0
        var finished = 0
        /// `summaryRevision` just before the finished catch-up's balance read: any later
        /// revision was read after its sync.
        var summaryRevision = 0

        var pending: Bool { finished < started }

        /// An older return finishing late leaves a newer one pending.
        mutating func finish(_ started: Int, summaryRevision: Int) {
            guard started > finished else { return }
            finished = started
            self.summaryRevision = summaryRevision
        }

        /// The sync is done, a balance read after it has landed, and history has been reloaded since.
        func isCaughtUp(summaryRevision: Int, historyReloadedFor reloaded: Int) -> Bool {
            !pending && summaryRevision > self.summaryRevision && reloaded == finished
        }
    }

    private(set) var phase: Phase = .launching
    private(set) var summaryRevision = 0
    private(set) var catchUp = CatchUp()
    private(set) var summary: WalletSession.Summary?
    private(set) var walletSessionRevision = 0
    private(set) var walletError: String?
    private(set) var customServerUnresponsive = false
    private(set) var switchingToDefaultServer = false
    @ObservationIgnored private var customOpenWait: EsploraOpenWait?
    private(set) var resumeOnboarding = false
    /// Home's "Back up your wallet" prompt (`PersistedRecoveryPhraseBackup`).
    private(set) var needsRecoveryPhraseBackup = false
    /// Onboarding stays on screen after a restore succeeds until its "Ready" moment ends.
    private(set) var holdsOnboardingAfterRestore = false
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
    var localeIdentifier = LegacyLocale.resolved()

    func updateProfile(_ change: (inout PersistedProfileState) -> Void) throws {
        var profile = settings.persistedState(PersistedProfileState.self, key: PersistedProfileState.key) ?? .init()
        change(&profile)
        try settings.setPersistedState(profile, key: PersistedProfileState.key, version: PersistedProfileState.version)
        amountUnit = profile.amountUnit
        preferredCurrency = profile.currency
    }

    func setLanguage(_ code: String) {
        UserDefaults.standard.set(code, forKey: LegacyLocale.key)
        localeIdentifier = LegacyLocale.resolved()
    }

    /// Closing a session ends its streams, even when its wallet identity stays the same.
    func walletConfigurationChanged() async {
        if let running = backupTriggers {
            running.task.cancel()
            await running.task.value
            backupTriggers = nil
        }
        walletSessionRevision += 1
        if await session.isOpen {
            await refresh()
            await reconcileBackupTriggers()
        }
    }

    func backupSettingsChanged() async { await reconcileBackupTriggers() }

    let variant = AppVariant.current
    let session = WalletSession.shared
    let versionCheck = AppVersionCheck()
    let pushPermission: PushPermissionModel
    let walletLock: WalletLock
    let paymentLink = PaymentLinkRoute()
    var paymentLinkGate: PaymentLinkRoute.Gate {
        .init(phase: phase, onboarding: holdsOnboardingAfterRestore,
              gated: isWalletSuspended || pushPermission.blocksWallet(initialized: true) || (phase == .launching && customServerUnresponsive),
              settling: !pushPermission.settled, locked: walletLock.policy.locked)
    }
    let server: ServerRegistration
    private let log = AppLog("app")
    private let settings: SettingsStorage
    private let appVersion = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "unknown"
    @ObservationIgnored private let creator: WalletCreator
    @ObservationIgnored private var recurringForeground: RecurringForegroundRunner?
    @ObservationIgnored private var foregroundActivation: RecurringSubmissionBudget?
    @ObservationIgnored private var started = false
    @ObservationIgnored private var starting = false
    @ObservationIgnored private let readLaunchState: () -> LaunchState
    @ObservationIgnored private let settleRestore: @MainActor () async throws -> RestoreTransaction.Recovery
    @ObservationIgnored private var backupTriggers: (id: UUID, task: Task<Void, Never>)?
    @ObservationIgnored private let lifecycle = WalletLifecycle(dependencies: .live())

    init(settings: SettingsStorage = .shared, readLaunchState: @escaping () -> LaunchState = { .current() },
         settleRestore: @escaping @MainActor () async throws -> RestoreTransaction.Recovery = { try await AppServices.shared.restoreRecovery.settle() },
         pushPermission: PushPermissionModel = PushPermissionModel()) {
        self.settings = settings
        self.pushPermission = pushPermission
        walletLock = WalletLock(settings: settings)
        self.readLaunchState = readLaunchState
        self.settleRestore = settleRestore
        server = ServerRegistration(variant: .current, settings: settings, client: AppServices.shared.client)
        creator = WalletCreator(
            dependencies: .live(session: session, variant: variant, appVersion: appVersion, settings: settings))
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
            await AppServices.shared.syncInForeground()
            guard !Task.isCancelled, phase == .wallet, !isWalletSuspended, await session.isOpen else { return }
            await refresh()
            guard (try? await ForegroundExecution.checkActive()) != nil else { return }
            await RecurringExecutor.shared.execute()
        }
    }

    func start() async {
        // `.task` can run again if the root view is rebuilt; launch once.
        guard !started, !starting else { return }
        starting = true
        defer { starting = false }
        await session.waitForBackgroundAccess()
        started = true
        let initialState = readLaunchState()
        if initialState == .invalidSeed || initialState == .seedConflict {
            phase = .blocked(initialState)
            return
        }
        if UIApplication.shared.applicationState == .active { recurringForeground?.start() }
        // An interrupted restore must be settled before the datadir and seed
        // say anything about which wallet this is.
        do {
            switch try await settleRestore() {
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
        RestoreTransaction(location: WalletDataLocation(variant: variant)).completeSettings(settings)
        isWalletSuspended = settings.persistedState(PersistedWalletState.self, key: PersistedWalletState.key)?.isWalletSuspended == true
        reloadRecoveryPhraseBackup()
        let state = readLaunchState()
        switch state {
        case .existingWallet:
            resumeOnboarding = Self.resumesOnboarding(settings.persistedState(PersistedWalletState.self, key: PersistedWalletState.key))
            await openExistingWallet()
            if phase == .launching && !customServerUnresponsive { phase = resumeOnboarding ? .onboarding : .wallet }
        case .onboarding:
            phase = .onboarding
        case .seedWithoutWalletData, .walletDataWithoutSeed, .secureStorageUnavailable, .invalidSeed, .seedConflict:
            phase = .blocked(state)
        }
    }

    /// Seed plus wallet data opens Home even when settings are missing or
    /// damaged; only a readable store that says onboarding never finished
    /// resumes it (at Name).
    nonisolated static func resumesOnboarding(_ wallet: PersistedWalletState?) -> Bool {
        wallet?.isInitialized == false
    }

    /// Marks onboarding unfinished before a restore installs anything. Only a
    /// phrase the user typed counts as backed up; reinstall recovery from the
    /// stored seed leaves the backup prompt as it was.
    nonisolated static func beginRestore(_ settings: SettingsStorage, typedPhrase: Bool) throws {
        try settings.beginOnboarding(recoveryPhraseBackedUp: typedPhrase ? true : nil)
    }

    /// Onboarding "Got it": create (or resume) the wallet.
    func createWallet() async throws {
        do { try await creator.createOrResume() }
        catch {
            if let failure = error as? StoredSeed.Failure {
                phase = .blocked(failure == .invalid ? .invalidSeed : .seedConflict)
            }
            throw error
        }
        var wallet = settings.persistedState(PersistedWalletState.self, key: PersistedWalletState.key) ?? .init()
        wallet.staticVtxoPubkey = try await session.peekPublicKey(index: 0)
        try settings.setPersistedState(wallet, key: PersistedWalletState.key, version: PersistedWalletState.version)
        // Registration failure must not hide the newly created recovery phrase.
        Task { await startServerServices() }
    }

    /// The user confirmed they wrote the phrase down after viewing it.
    func confirmRecoveryPhraseBackedUp() throws {
        try settings.setPersistedState(PersistedRecoveryPhraseBackup(isBackedUp: true),
                                       key: PersistedRecoveryPhraseBackup.key, version: PersistedRecoveryPhraseBackup.version)
        reloadRecoveryPhraseBackup()
    }

    private func reloadRecoveryPhraseBackup() {
        // Missing or unreadable never hides the prompt.
        needsRecoveryPhraseBackup = PersistedRecoveryPhraseBackup.read(from: settings).needsBackup
    }

    /// Onboarding's restore finished its closing moment; show the wallet.
    func endOnboardingAfterRestore() { holdsOnboardingAfterRestore = false }

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
        defer {
            isRestoring = false; restoreStep = nil
            if phase != .wallet { holdsOnboardingAfterRestore = false }
        }
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
                makeClient: { ServerClient.makeRestore(variant: variant, mnemonic: $0) },
                beginOnboarding: { [settings] in try Self.beginRestore(settings, typedPhrase: phrase != nil) })
            let outcome = try await restorer.restore(mnemonic: mnemonic) { [weak self] step in
                Task { @MainActor in
                    if self?.isRestoring == true { self?.restoreStep = step }
                }
            }
            committed = true
            try await RestoreCompletion(
                loadWalletState: { self.settings.persistedState(PersistedWalletState.self, key: PersistedWalletState.key) ?? .init() },
                saveWalletState: { _ in
                    self.settings.completeRestore()
                    RestoreTransaction(location: WalletDataLocation(variant: variant)).completeSettings(self.settings)
                },
                seedRestored: { await AppServices.shared.backupCoordinator.seedRestored(snapshotSha256: $0, backupId: $1) },
                recover: { try await AppServices.shared.restoreRecovery.settle() },
                openWallet: {
                    await AppServices.shared.tokens.invalidate()
                    self.resumeOnboarding = false
                    self.reloadRecoveryPhraseBackup()
                    self.holdsOnboardingAfterRestore = true
                    self.phase = .wallet
                    await self.openExistingWallet()
                }).finish(outcome)
        } catch {
            log.e("Restore failed: \(SafeErrorDescription.forLogging(error))")
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
        reloadRecoveryPhraseBackup()
        phase = .wallet
        await pushPermission.refresh()
        guard !pushPermission.blocksWallet(initialized: true) else { return }
        await refresh()
        await registerPush()
        await reconcileBackupTriggers()
    }

    @ObservationIgnored private var summaryRefresh = UUID()

    func refresh() async {
        let generation = UUID()
        summaryRefresh = generation
        let location = WalletDataLocation(variant: variant)
        func checkStage() throws {
            try BackgroundExecution.checkActive()
            try ForegroundExecution.checkActiveNow()
            guard summaryRefresh == generation, ForegroundExecution.isActive, !isWalletSuspended,
                  !location.restorePending, !FileManager.default.fileExists(atPath: location.deletionMarker.path),
                  settings.persistedState(PersistedWalletState.self, key: PersistedWalletState.key)?.isWalletSuspended != true
            else { throw CancellationError() }
        }
        do {
            guard let latest = try await ForegroundWidgetRefresh.read(
                summary: { try await session.summary() }, expiry: { try await session.closestExpiryBlocks() },
                checkStage: checkStage,
                publish: { WidgetDataWriter.write(balance: $0, closestExpiryBlocks: $1, variant: variant) }) else {
                // Not open (e.g. the first open failed): try again rather than
                // clearing the error with nothing to show.
                await openExistingWallet()
                return
            }
            summary = latest
            summaryRevision += 1
            walletError = nil
        } catch is CancellationError { return }
        catch {
            log.e("Failed to read wallet summary: \(Self.describe(error))")
            walletError = L10n.text("Couldn't load your balance. Pull down to try again.")
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
        SafeErrorDescription.forLogging(error)
    }

    func useDefaultEsploraServer() {
        switchingToDefaultServer = true
        // Persist the choice now so a restart can recover even if native never returns.
        settings.useDefaultEsplora(for: variant)
        customOpenWait?.chooseDefault()
    }

    private func customOpenFinished(_ success: Bool) async {
        customServerUnresponsive = false; switchingToDefaultServer = false; customOpenWait = nil
        if success { await openExistingWallet(); await walletConfigurationChanged() }
        else { walletError = L10n.text("Couldn't open your wallet. Please try again."); phase = .wallet }
    }

    private func openExistingWallet() async {
        guard !isWalletSuspended, !customServerUnresponsive else { return }
        guard case .found(let mnemonic) = KeychainPassword.mnemonic(for: variant).read() else {
            phase = .blocked(.secureStorageUnavailable)
            return
        }
        do {
            let wait = EsploraOpenWait()
            customOpenWait = wait
            try await AppServices.shared.openWalletInForeground(mnemonic: mnemonic, wait: wait) { [weak self] success in
                await self?.customOpenFinished(success)
            }
            customOpenWait = nil
            if let latest = try await session.summary() {
                summary = latest
                summaryRevision += 1
                walletError = nil
            }
            // Identity checks must finish before exposing wallet/settings actions.
            if phase == .launching && !customServerUnresponsive { phase = resumeOnboarding ? .onboarding : .wallet }
        } catch is EsploraOpenWait.Timeout {
            customServerUnresponsive = customOpenWait != nil
            return
        } catch {
            customOpenWait = nil
            if let failure = error as? StoredSeed.Failure {
                phase = .blocked(failure == .invalid ? .invalidSeed : .seedConflict)
                return
            }
            log.e("Failed to open wallet: \(Self.describe(error))")
            walletError = L10n.text("Couldn't open your wallet. Please try again.")
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
        walletLock.reset()
        summary = nil
        walletError = nil
        isWalletSuspended = false
        resumeOnboarding = false
        needsRecoveryPhraseBackup = false
        server.reload()
        phase = .onboarding
    }

    /// RN `resetAndReRegisterWithServer`.
    func reRegisterWithServer() async throws {
        guard !isWalletSuspended else { throw BackupCoordinator.NotReady() }
        try await server.reRegister(loadWallet: {
            try await AppServices.shared.openWalletInForeground(mnemonic: AppServices.shared.readMnemonic(),
                wait: EsploraOpenWait(), lateCompletion: { _ in })
        }, registerPush: {
            try await PushRegistration.shared.register().requireCompleted()
        })
        await pushPermission.refresh()
        await reconcileBackupTriggers()
    }

    // MARK: Backups (RN `useBackupCoordinator`)

    /// In the wallet, backups switched on, initialized and not suspended.
    private var canCoordinateBackups: Bool {
        guard phase == .wallet, !pushPermission.blocksWallet(initialized: true), !isWalletSuspended, server.state.isBackupEnabled == true else { return false }
        let wallet = settings.persistedState(PersistedWalletState.self, key: PersistedWalletState.key)
        return wallet?.isInitialized != false && wallet?.isWalletSuspended != true
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
        // This session observer outlives the foreground activation that creates it.
        let task = Task.detached { @MainActor @Sendable [weak self] in
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

    /// Unavailable is retryable; it must never become an absent seed/default wallet.
    func retrySecureStorage() async {
        guard phase == .blocked(.secureStorageUnavailable), !starting else { return }
        started = false
        phase = .launching
        await start()
    }

    func appBecameInactive() {
        summaryRefresh = UUID()
        foregroundActivation?.revoke()
        recurringForeground?.stop()
        catchUp.started += 1
    }

    func appBecameActive() async {
        await session.appBecameActive()
        guard UIApplication.shared.applicationState == .active else { return }
        foregroundActivation?.revoke()
        let budget = RecurringSubmissionBudget()
        foregroundActivation = budget
        defer {
            budget.revoke()
            if foregroundActivation === budget { foregroundActivation = nil }
        }
        let started = catchUp.started
        await ForegroundExecution.run(budget: budget) { await refreshOnForeground(catchingUp: started) }
    }

    private func refreshOnForeground(catchingUp started: Int) async {
        await session.waitForBackgroundAccess()
        guard RecurringSubmissionBudget.available, UIApplication.shared.applicationState == .active else { return }
        var synced = summaryRevision
        RestoreTransaction(location: WalletDataLocation(variant: variant)).completeSettings(settings)
        if phase == .wallet, !isWalletSuspended, !(await session.isOpen) { await openExistingWallet() }
        await retrySecureStorage()
        if UIApplication.shared.applicationState == .active { recurringForeground?.start() }
        if phase == .wallet {
            let wasBlocked = pushPermission.blocksWallet(initialized: true)
            await pushPermission.refresh()
            await reconcileBackupTriggers()
            guard !pushPermission.blocksWallet(initialized: true) else { return }
            if wasBlocked { await startServerServices() }
            try? await server.refreshMailboxIfNeeded()
            await AppServices.shared.syncInForeground()
            await RecurringExecutor.shared.execute()
            synced = summaryRevision
            await refresh()
        }
        catchUp.finish(started, summaryRevision: synced)
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
        await ForegroundExecution.run { await RecurringExecutor.shared.execute() }
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
