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
    }

    private(set) var phase: Phase = .launching
    private(set) var summary: WalletSession.Summary?
    private(set) var walletError: String?
    var isBalanceHidden: Bool {
        didSet { saveIsBalanceHidden() }
    }
    private(set) var amountUnit: BitcoinAmountUnit

    let variant = AppVariant.current
    let session = WalletSession.shared
    private let log = AppLog("app")
    private let settings = SettingsStorage.shared
    private let appVersion = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "unknown"
    @ObservationIgnored private let creator: WalletCreator

    init() {
        creator = WalletCreator(
            dependencies: .live(session: session, variant: variant, appVersion: appVersion))
        isBalanceHidden = settings.persistedState(PersistedPrivacyState.self, key: PersistedPrivacyState.key)?
            .isHomeBalanceHidden ?? false
        amountUnit = settings.persistedState(PersistedProfileState.self, key: PersistedProfileState.key)?
            .amountUnit ?? .bip177
    }

    func start() async {
        // A restore interrupted mid-swap must not look like a fresh install.
        if SnapshotInstaller(datadir: WalletDataLocation(variant: variant).directory).recoverInterruptedInstall() {
            log.w("Recovered the wallet folder from an interrupted restore")
        }
        let state = LaunchState.current(variant: variant)
        switch state {
        case .existingWallet:
            phase = .wallet
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
    }

    func currentMnemonicWords() -> [String] {
        guard case .found(let mnemonic) = KeychainPassword.mnemonic(for: variant).read() else { return [] }
        return mnemonic.split(separator: " ").map(String.init)
    }

    func finishOnboarding() async {
        phase = .wallet
        await refresh()
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

    /// Error text safe for logs: SDK messages, never generated-client errors
    /// (their descriptions include request inputs and headers).
    private static func describe(_ error: Error) -> String {
        if let bark = error as? BarkError { return bark.message }
        return String(describing: type(of: error))
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
        }
    }

    private func saveIsBalanceHidden() {
        try? settings.setPersistedState(
            PersistedPrivacyState(isHomeBalanceHidden: isBalanceHidden),
            key: PersistedPrivacyState.key, version: PersistedPrivacyState.version)
    }
}
