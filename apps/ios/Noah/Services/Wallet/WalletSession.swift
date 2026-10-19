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
        /// The database is not a bark wallet (checked read-only before bark
        /// touches it).
        case notABarkWallet
        /// The database belongs to another network than this build variant.
        case networkMismatch(found: String)
        /// Another open or close is in progress, or a wallet is already open.
        case busy
        /// `createNew` found an existing database; it never overwrites one.
        case walletDatabaseExists
    }

    /// Opener seam so lifecycle rules can be tested without the SDK.
    /// `create` allows bark to create a new wallet in an empty datadir.
    typealias Opener = @Sendable (
        _ mnemonic: String, _ datadir: String, _ runDaemon: Bool, _ appVersion: String, _ create: Bool
    ) async throws -> Handles

    struct Handles: @unchecked Sendable {
        let wallet: Wallet
        let onchain: OnchainWallet
    }

    private enum State {
        case closed, opening, open(Handles), closing
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
    private let opener: Opener
    private var state = State.closed

    init(variant: AppVariant = .current, location: WalletDataLocation? = nil, opener: Opener? = nil) {
        self.variant = variant
        self.location = location ?? WalletDataLocation(variant: variant)
        self.opener = opener ?? Self.sdkOpener(variant: variant)
    }

    /// Opens the wallet the React Native app (or a previous native run)
    /// created. Refuses to run if the database is absent, is not a bark
    /// wallet, or belongs to another network.
    func openExisting(mnemonic: String, runDaemon: Bool, appVersion: String) async throws {
        guard case .closed = state else { throw OpenError.busy }
        guard location.databaseExists else { throw OpenError.walletDatabaseMissing }
        let properties: WalletDatabase.Properties
        do {
            properties = try WalletDatabase.properties(at: location.database)
        } catch {
            throw OpenError.notABarkWallet
        }
        let expectedNetwork = WalletConfiguration.base(for: variant).networkName
        guard properties.network == expectedNetwork else {
            throw OpenError.networkMismatch(found: properties.network)
        }

        try await open(mnemonic: mnemonic, runDaemon: runDaemon, appVersion: appVersion, create: false)
    }

    /// Creates a new wallet. Only valid when no database exists yet.
    func createNew(mnemonic: String, runDaemon: Bool, appVersion: String) async throws {
        guard case .closed = state else { throw OpenError.busy }
        guard !location.databaseExists else { throw OpenError.walletDatabaseExists }
        try FileManager.default.createDirectory(at: location.directory, withIntermediateDirectories: true)
        try await open(mnemonic: mnemonic, runDaemon: runDaemon, appVersion: appVersion, create: true)
    }

    private func open(mnemonic: String, runDaemon: Bool, appVersion: String, create: Bool) async throws {
        state = .opening
        do {
            let handles = try await opener(mnemonic, location.directory.path, runDaemon, appVersion, create)
            state = .open(handles)
        } catch {
            state = .closed
            throw error
        }
    }

    var isOpen: Bool {
        if case .open = state { true } else { false }
    }

    func summary() async throws -> Summary? {
        guard case .open(let handles) = state else { return nil }
        let wallet = handles.wallet
        let onchain = handles.onchain
        return Summary(
            network: try await wallet.network(),
            fingerprint: wallet.fingerprint(),
            spendableSats: try await wallet.balance().spendableSats,
            onchainTotalSats: try await onchain.balance().totalSats,
            movementCount: try await wallet.history().count)
    }

    func close() async throws {
        switch state {
        case .closed:
            return
        case .opening, .closing:
            throw OpenError.busy
        case .open(let handles):
            state = .closing
            do {
                try await handles.wallet.stopDaemonWait()
            } catch {
                state = .open(handles)
                throw error
            }
            state = .closed
        }
    }

    private static func sdkOpener(variant: AppVariant) -> Opener {
        { mnemonic, datadir, runDaemon, appVersion, create in
            let configuration = WalletConfiguration.base(for: variant)
            let config = configuration.sdkConfig(
                userAgent: WalletConfiguration.userAgent(variant: variant, version: appVersion))
            let onchain = try await OnchainWallet.default(
                network: configuration.network, mnemonic: mnemonic, config: config, datadir: datadir)
            let wallet = try await Wallet.openWithOnchain(
                network: configuration.network,
                mnemonicOrSeed: mnemonic,
                config: config,
                args: WalletOpenArgs(runDaemon: runDaemon, datadir: datadir, createIfNotExists: create),
                onchain: onchain)
            return Handles(wallet: wallet, onchain: onchain)
        }
    }
}
