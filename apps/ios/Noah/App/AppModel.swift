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
    let session = WalletSession()
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
            summary = try await session.summary()
            walletError = nil
        } catch {
            log.e("Failed to read wallet summary: \(error)")
            walletError = String(localized: "Couldn't load your balance.")
        }
    }

    func sync() async {
        do {
            try await session.sync()
        } catch {
            log.w("Wallet sync failed: \(error)")
        }
        await refresh()
    }

    private func openExistingWallet() async {
        guard case .found(let mnemonic) = KeychainPassword.mnemonic(for: variant).read() else {
            phase = .blocked(.secureStorageUnavailable)
            return
        }
        do {
            try await session.openExisting(mnemonic: mnemonic, runDaemon: true, appVersion: appVersion)
            await refresh()
        } catch {
            log.e("Failed to open wallet: \(error)")
            walletError = String(localized: "Couldn't open your wallet.")
        }
    }

    private func saveIsBalanceHidden() {
        try? settings.setPersistedState(
            PersistedPrivacyState(isHomeBalanceHidden: isBalanceHidden),
            key: PersistedPrivacyState.key, version: PersistedPrivacyState.version)
    }
}
