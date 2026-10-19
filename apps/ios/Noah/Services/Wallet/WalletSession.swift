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
        /// An interrupted restore has not been settled (`RestoreTransaction`).
        case restorePending
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
        case closed
        case opening(Task<Handles, Error>)
        case open(Handles)
        case closing
        /// A restore is replacing the wallet files; everyone else sees `busy`.
        case replacing(UUID)
    }

    /// Exclusive hold on a closed session while a restore swaps the wallet
    /// files. Only its holder may check the new files (`verifyReplacement`).
    struct Replacement: Sendable, Equatable {
        fileprivate let id: UUID
    }

    /// The one session per app process. bark-bolt-ffi locks the datadir, so a
    /// second session on the same wallet would fail; UI, push handlers,
    /// background tasks and App Intents all share this one.
    static let shared = WalletSession()

    struct Summary: Equatable, Sendable {
        let network: Network
        let fingerprint: String
        let balance: WalletBalance
        let movementCount: Int

        var spendableSats: UInt64 { balance.ark.spendable }
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
        guard !location.restorePending else { throw OpenError.restorePending }
        try checkExistingDatabase()
        try await open(mnemonic: mnemonic, runDaemon: runDaemon, appVersion: appVersion, create: false)
    }

    /// Read-only checks before bark touches the database.
    private func checkExistingDatabase() throws {
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
    }

    /// Reserves the closed session for a restore. Opens, closes and wallet
    /// calls from anyone else fail (or see no wallet) until `endReplacement`.
    func beginReplacement() throws -> Replacement {
        guard case .closed = state else { throw OpenError.busy }
        let replacement = Replacement(id: UUID())
        state = .replacing(replacement.id)
        return replacement
    }

    /// Opens the swapped-in files with `mnemonic`, without the daemon, and
    /// closes them again: proof the restore is usable before the seed is
    /// committed. Nothing else can reach this wallet in between.
    func verifyReplacement(_ replacement: Replacement, mnemonic: String, appVersion: String) async throws {
        guard case .replacing(let id) = state, id == replacement.id else { throw OpenError.busy }
        try checkExistingDatabase()
        let handles = try await opener(mnemonic, location.directory.path, false, appVersion, false)
        try await handles.wallet.stopDaemonWait()
    }

    func endReplacement(_ replacement: Replacement) {
        guard case .replacing(let id) = state, id == replacement.id else { return }
        state = .closed
    }

    /// Opens the existing wallet unless it is already open (RN's
    /// `loadWalletIfNeeded`). Still rejects overlapping open/close.
    /// Waits for an open already in progress instead of failing.
    func ensureOpen(mnemonic: String, runDaemon: Bool, appVersion: String) async throws {
        switch state {
        case .open:
            return
        case .opening(let inFlight):
            _ = try await inFlight.value
        case .closing, .replacing:
            throw OpenError.busy
        case .closed:
            try await openExisting(mnemonic: mnemonic, runDaemon: runDaemon, appVersion: appVersion)
        }
    }

    /// Creates a new wallet. Only valid when no database exists yet.
    func createNew(mnemonic: String, runDaemon: Bool, appVersion: String) async throws {
        guard case .closed = state else { throw OpenError.busy }
        guard !location.restorePending else { throw OpenError.restorePending }
        guard !location.databaseExists else { throw OpenError.walletDatabaseExists }
        try FileManager.default.createDirectory(at: location.directory, withIntermediateDirectories: true)
        try await open(mnemonic: mnemonic, runDaemon: runDaemon, appVersion: appVersion, create: true)
        // RN creation persists key 0 before peeking its registration address.
        // On failure the creator closes this session before rolling back its files.
        guard case .open(let handles) = state else { throw OpenError.busy }
        _ = try await handles.wallet.deriveStoreNextKeypair()
    }

    private func open(mnemonic: String, runDaemon: Bool, appVersion: String, create: Bool) async throws {
        let opener = self.opener
        let datadir = location.directory.path
        let attempt = Task { try await opener(mnemonic, datadir, runDaemon, appVersion, create) }
        state = .opening(attempt)
        do {
            state = .open(try await attempt.value)
        } catch {
            state = .closed
            throw error
        }
    }

    var isOpen: Bool {
        if case .open = state { true } else { false }
    }

    func peekPublicKey(index: UInt32) async throws -> String {
        guard case .open(let handles) = state else { throw OpenError.walletDatabaseMissing }
        return try await handles.wallet.peekPublicKey(index: index)
    }

    /// Stable registration address; never advances the wallet's address index.
    func peekAddress(index: UInt32) async throws -> String {
        guard case .open(let handles) = state else { throw OpenError.walletDatabaseMissing }
        return try await handles.wallet.peekAddress(index: index)
    }

    func summary() async throws -> Summary? {
        guard case .open(let handles) = state else { return nil }
        let wallet = handles.wallet
        let onchain = handles.onchain
        let ark = try await wallet.balance()
        let chain = try await onchain.balance()
        return Summary(
            network: try await wallet.network(),
            fingerprint: wallet.fingerprint(),
            balance: WalletBalance(
                // bark-bolt-ffi 0.2.0 drops needs_refresh, pending_arkoor_send
                // and pending_offboard from bark's Balance; they read as 0
                // until the SDK exposes them (FFI handoff item 12).
                ark: .init(
                    spendable: ark.spendableSats,
                    needsRefresh: 0,
                    pendingLightningSend: ark.pendingLightningSendSats,
                    claimableLightningReceive: ark.claimableLightningReceiveSats,
                    pendingInRound: ark.pendingInRoundSats,
                    pendingExit: ark.pendingExitSats,
                    pendingBoard: ark.pendingBoardSats,
                    pendingArkoorSend: 0,
                    pendingOffboard: 0),
                onchain: .init(confirmed: chain.confirmedSats, pending: chain.pendingSats, total: chain.totalSats)),
            movementCount: try await wallet.history().count)
    }

    enum SessionError: Error, Equatable {
        case notOpen
    }

    /// Consistent plaintext SQLite export of the open wallet (backups).
    func createSnapshot(at destination: URL) async throws -> WalletSnapshotInfo {
        guard case .open(let handles) = state else { throw SessionError.notOpen }
        return try await handles.wallet.createSnapshot(destinationPath: destination.path)
    }

    private func openHandles() throws -> Handles {
        guard case .open(let handles) = state else { throw SessionError.notOpen }
        return handles
    }

    /// Background maintenance the server asks for (`maintenanceDelegated`).
    func maintenanceDelegated() async throws {
        try await openHandles().wallet.maintenanceDelegated()
    }

    /// BOLT11 invoice for a forwarded Lightning address payment.
    func bolt11Invoice(amountSats: UInt64) async throws -> String {
        try await openHandles().wallet.bolt11Invoice(amountSats: amountSats, description: nil).invoice
    }

    /// Claims pending Lightning receives without waiting (RN `wait: false`).
    func claimLightningReceives() async throws {
        _ = try await openHandles().wallet.tryClaimAllLightningReceives(wait: false)
    }

    /// Blocks until the soonest VTXO expiry, as the widget shows it.
    func closestExpiryBlocks() async throws -> Int? {
        let handles = try openHandles()
        let vtxos = try await handles.wallet.vtxos()
        guard !vtxos.isEmpty else { return nil }
        let tip = Int(try await handles.onchain.tipHeight())
        return vtxos.map { Int($0.expiryHeight) - tip }.min()
    }

    /// Syncs onchain then Ark state, like `syncWallet` minus Lightning claims.
    func sync() async throws {
        guard case .open(let handles) = state else { return }
        _ = try await handles.onchain.sync()
        try await handles.wallet.sync()
    }

    func close() async throws {
        switch state {
        case .closed:
            return
        case .opening, .closing, .replacing:
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
