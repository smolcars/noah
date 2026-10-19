import BarkBoltFFI
import Foundation

/// Owns the bark wallet handles. Every wallet operation in the app (UI,
/// push, background tasks, backups, App Intents) must go through this actor
/// so loads, closes and replacements never overlap.
actor WalletSession {
    enum OpenError: Error, Equatable {
        /// No `db.sqlite` in the datadir. Opening would create a new wallet,
        /// which must never happen implicitly.
        case walletDatabaseMissing
        case alreadyOpen
    }

    struct Summary: Equatable, Sendable {
        let network: Network
        let fingerprint: String
        let spendableSats: UInt64
        let onchainTotalSats: UInt64
        let movementCount: Int
    }

    private let variant: AppVariant
    private let location: WalletDataLocation
    private var wallet: Wallet?
    private var onchain: OnchainWallet?

    init(variant: AppVariant = .current, location: WalletDataLocation? = nil) {
        self.variant = variant
        self.location = location ?? WalletDataLocation(variant: variant)
    }

    /// Opens the wallet the React Native app (or a previous native run)
    /// created. Refuses to run if the database is absent.
    func openExisting(mnemonic: String, runDaemon: Bool, appVersion: String) async throws {
        guard wallet == nil else { throw OpenError.alreadyOpen }
        guard location.databaseExists else { throw OpenError.walletDatabaseMissing }

        let configuration = WalletConfiguration.base(for: variant)
        let config = configuration.sdkConfig(
            userAgent: WalletConfiguration.userAgent(variant: variant, version: appVersion))
        let datadir = location.directory.path

        let onchain = try await OnchainWallet.default(
            network: configuration.network, mnemonic: mnemonic, config: config, datadir: datadir)
        let wallet = try await Wallet.openWithOnchain(
            network: configuration.network,
            mnemonicOrSeed: mnemonic,
            config: config,
            args: WalletOpenArgs(runDaemon: runDaemon, datadir: datadir, createIfNotExists: false),
            onchain: onchain)
        self.onchain = onchain
        self.wallet = wallet
    }

    func summary() async throws -> Summary? {
        guard let wallet, let onchain else { return nil }
        return Summary(
            network: try await wallet.network(),
            fingerprint: wallet.fingerprint(),
            spendableSats: try await wallet.balance().spendableSats,
            onchainTotalSats: try await onchain.balance().totalSats,
            movementCount: try await wallet.history().count)
    }

    func close() async throws {
        guard let wallet else { return }
        try await wallet.stopDaemonWait()
        self.wallet = nil
        onchain = nil
    }
}
