import BarkBoltFFI
import Foundation

/// Owns the bark wallet handles. Every wallet operation in the app (UI,
/// push, background tasks, backups, App Intents) must go through this actor
/// so loads, closes and replacements never overlap.
///
/// Native work is never abandoned halfway: BoltFFI answers a cancelled Swift
/// caller with `CancellationError` while the Rust work keeps running, so
/// opens, wallet calls and teardown run in their own tasks that the session
/// waits for. `close` waits for wallet calls still running, and a
/// `Reservation` (restore, creation, launch recovery) starts from a fully
/// closed session.
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
        /// Another open, close or reservation is in progress, or a wallet is
        /// already open.
        case busy
        /// `createNew` found an existing database; it never overwrites one.
        case walletDatabaseExists
        /// An interrupted restore has not been settled (`RestoreTransaction`).
        case restorePending
        /// A new wallet failed after opening and could not be shut down, so
        /// it was left open on its files rather than having them removed.
        case teardownFailed
    }

    enum SessionError: Error, Equatable {
        case notOpen
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
        /// Held by a restore, a wallet creation or launch recovery; everyone
        /// else sees `busy`.
        case reserved(UUID)
    }

    /// Exclusive hold on a closed session while the wallet files or seed are
    /// created or replaced. Only its holder may open the wallet
    /// (`verify`, `createNew`) until `release`.
    struct Reservation: Sendable, Equatable {
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
    var sendPreparations: [String: SendPreparation] = [:]
    var boardPreparations: [String: BoardPreparation] = [:]
    private var state = State.closed
    private var runningCalls = 0
    private var sendReserved = false
    private var exclusiveSend = false
    @TaskLocal private static var sendWork = false
    private var drainWaiters: [CheckedContinuation<Void, Never>] = []
    private var observers: [UUID: Observer] = [:]

    /// Long-lived readers (receive completion, state changes). Not counted
    /// calls: `close` stops them first, then waits for them to end.
    private struct Observer {
        let task: Task<Void, Never>
        /// Releases the reader's pending native wait.
        let stop: @Sendable () -> Void
    }

    init(variant: AppVariant = .current, location: WalletDataLocation? = nil, opener: Opener? = nil) {
        self.variant = variant
        self.location = location ?? WalletDataLocation(variant: variant)
        self.opener = opener ?? Self.sdkOpener(variant: variant)
    }

    // MARK: Lifecycle

    /// Opens the wallet the React Native app (or a previous native run)
    /// created. Refuses to run if the database is absent, is not a bark
    /// wallet, or belongs to another network.
    func openExisting(mnemonic: String, runDaemon: Bool, appVersion: String) async throws {
        guard case .closed = state else { throw OpenError.busy }
        guard !location.restorePending else { throw OpenError.restorePending }
        try checkExistingDatabase()
        let opener = self.opener
        let datadir = location.directory.path
        // The attempt settles the state itself before anyone awaiting it
        // resumes, so `ensureOpen` joiners and `closeSettled` never see a
        // finished open still marked as opening.
        let attempt = Task {
            do {
                let handles = try await opener(mnemonic, datadir, runDaemon, appVersion, false)
                state = .open(handles)
                return handles
            } catch {
                state = .closed
                throw error
            }
        }
        state = .opening(attempt)
        _ = try await attempt.value
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
        case .closing, .reserved:
            throw OpenError.busy
        case .closed:
            try await openExisting(mnemonic: mnemonic, runDaemon: runDaemon, appVersion: appVersion)
        }
    }

    /// Stops new wallet calls, waits for the running ones, then stops the
    /// daemon. Only then is the session closed.
    func close() async throws {
        guard !sendReserved else { throw OpenError.busy }
        switch state {
        case .closed:
            return
        case .opening, .closing, .reserved:
            throw OpenError.busy
        case .open(let handles):
            state = .closing
            await stopObservers()
            await callsDrained()
            do {
                try await discardSendReviews()
                try await discardBoardReviews()
                try await Task { try await handles.wallet.stopDaemonWait() }.value
            } catch {
                state = .open(handles)
                throw error
            }
            state = .closed
        }
    }

    /// `close`, after waiting for an open already in progress (suspend,
    /// delete). Still `busy` while a reservation is held.
    func closeSettled() async throws {
        if case .opening(let attempt) = state { _ = try? await attempt.value }
        try await close()
    }

    var isOpen: Bool {
        if case .open = state { true } else { false }
    }

    /// Reserves the closed session. Opens, closes, wallet calls and other
    /// reservations fail (or see no wallet) until `release`.
    func reserve() throws -> Reservation {
        guard case .closed = state else { throw OpenError.busy }
        let reservation = Reservation(id: UUID())
        state = .reserved(reservation.id)
        return reservation
    }

    func release(_ reservation: Reservation) {
        guard case .reserved(let id) = state, id == reservation.id else { return }
        state = .closed
    }

    /// Runs `body` unless a reservation is held. It runs on the actor without
    /// suspending, so no reservation can start while it does. Returns
    /// whether it ran.
    @discardableResult
    func unlessReserved(_ body: @Sendable () throws -> Void) rethrows -> Bool {
        if case .reserved = state { return false }
        try body()
        return true
    }

    /// Opens the datadir's wallet with `mnemonic`, without the daemon, and
    /// shuts it again: proof a restored wallet is usable before its seed is
    /// committed. Finishes even if the caller is cancelled, so the files are
    /// never swapped back while bark is still on them.
    func verify(_ reservation: Reservation, mnemonic: String, appVersion: String) async throws {
        try checkHeld(reservation)
        try checkExistingDatabase()
        let opener = self.opener
        let datadir = location.directory.path
        try await Task {
            let handles = try await opener(mnemonic, datadir, false, appVersion, false)
            try await handles.wallet.stopDaemonWait()
        }.value
    }

    /// Creates a new wallet in an empty datadir and leaves it open, which
    /// ends the reservation. If it fails, the reservation is still held and
    /// nothing is running on the files, so the caller can remove what the
    /// attempt created before anyone else gets in.
    func createNew(_ reservation: Reservation, mnemonic: String, runDaemon: Bool, appVersion: String) async throws {
        try checkHeld(reservation)
        guard !location.restorePending else { throw OpenError.restorePending }
        guard !location.databaseExists else { throw OpenError.walletDatabaseExists }
        try FileManager.default.createDirectory(at: location.directory, withIntermediateDirectories: true)
        if self === WalletSession.shared { try RecurringStore().protectLegacyBeforeReplacement() }
        let opener = self.opener
        let datadir = location.directory.path
        let handles = try await Task { try await opener(mnemonic, datadir, runDaemon, appVersion, true) }.value
        do {
            // RN creation persists key 0 before peeking its registration address.
            _ = try await Task { try await handles.wallet.deriveStoreNextKeypair() }.value
        } catch {
            do {
                try await Task { try await handles.wallet.stopDaemonWait() }.value
            } catch {
                state = .open(handles)
                throw OpenError.teardownFailed
            }
            throw error
        }
        if self === WalletSession.shared { await SendPaymentController.shared.reloadForWalletChange() }
        state = .open(handles)
    }

    /// `createNew` under its own reservation (integration tests).
    func createNew(mnemonic: String, runDaemon: Bool, appVersion: String) async throws {
        let reservation = try reserve()
        do {
            try await createNew(reservation, mnemonic: mnemonic, runDaemon: runDaemon, appVersion: appVersion)
        } catch {
            release(reservation)
            throw error
        }
    }

    private func checkHeld(_ reservation: Reservation) throws {
        guard case .reserved(let id) = state, id == reservation.id else { throw OpenError.busy }
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

    // MARK: Wallet calls

    /// Runs `body` on the open wallet as a counted call: `close` waits for
    /// it, and it finishes even if the caller is cancelled. Long-lived
    /// observers must not use this, or `close` would wait for them forever.
    func withOpenWallet<T: Sendable>(_ body: @escaping @Sendable (Handles) async throws -> T) async throws -> T {
        guard case .open(let handles) = state else { throw SessionError.notOpen }
        guard !exclusiveSend || Self.sendWork else { throw OpenError.busy }
        runningCalls += 1
        defer { callFinished() }
        await retrySendDiscards()
        await retryBoardDiscards()
        return try await Task { try await body(handles) }.value
    }

    /// Covers inspection, durable journal writes and progression; excludes replacement and other Send creators.
    func withSendReservation(_ body: @escaping @MainActor @Sendable () async -> Void, exclusive: Bool = false) async throws {
        guard isOpen, !sendReserved else { throw OpenError.busy }
        sendReserved = true
        exclusiveSend = exclusive
        defer { sendReserved = false; exclusiveSend = false }
        // Automation drains counted work and excludes new callers through the same Send hold.
        if exclusive { await callsDrained() }
        await Self.$sendWork.withValue(true) { await Task { await body() }.value }
    }

    private func callFinished() {
        runningCalls -= 1
        guard runningCalls == 0 else { return }
        let waiters = drainWaiters
        drainWaiters = []
        waiters.forEach { $0.resume() }
    }

    private func callsDrained() async {
        guard runningCalls > 0 else { return }
        await withCheckedContinuation { drainWaiters.append($0) }
    }

    func peekPublicKey(index: UInt32) async throws -> String {
        try await withOpenWallet { try await $0.wallet.peekPublicKey(index: index) }
    }

    /// Stable registration address; never advances the wallet's address index.
    func peekAddress(index: UInt32) async throws -> String {
        try await withOpenWallet { try await $0.wallet.peekAddress(index: index) }
    }

    func summary() async throws -> Summary? {
        guard isOpen else { return nil }
        return try await withOpenWallet { handles in
            let wallet = handles.wallet
            let ark = try await wallet.balance()
            let chain = try await handles.onchain.balance()
            return Summary(
                network: try await wallet.network(),
                fingerprint: wallet.fingerprint(),
                balance: WalletBalance(
                    ark: .init(
                        spendable: ark.spendableSats,
                        needsRefresh: ark.needsRefreshSats,
                        pendingLightningSend: ark.pendingLightningSendSats,
                        claimableLightningReceive: ark.claimableLightningReceiveSats,
                        pendingInRound: ark.pendingInRoundSats,
                        pendingExit: ark.pendingExitSats,
                        pendingBoard: ark.pendingBoardSats,
                        pendingArkoorSend: ark.pendingArkoorSendSats,
                        pendingOffboard: ark.pendingOffboardSats,
                        pending: ark.pendingSats,
                        total: ark.totalSats),
                    onchain: .init(confirmed: chain.confirmedSats, pending: chain.pendingSats, total: chain.totalSats)),
                movementCount: try await wallet.history().count)
        }
    }

    /// Both reads stay counted until completion; no observer holds a wallet call open.
    func transactionHistory() async throws -> [HistoryTransaction] {
        try await withOpenWallet { handles in
            let movements = try await handles.wallet.history().filter(HistoryMapping.include).map { HistoryMapping.transaction($0) }
            let chain = try await handles.onchain.transactions().filter { $0.balanceChangeSats != 0 }.map { HistoryMapping.transaction($0) }
            return movements + chain
        }
    }

    /// Consistent plaintext SQLite export of the open wallet (backups).
    func createSnapshot(at destination: URL) async throws -> WalletSnapshotInfo {
        try await withOpenWallet { try await $0.wallet.createSnapshot(destinationPath: destination.path) }
    }

    /// Background maintenance the server asks for (`maintenanceDelegated`).
    func maintenanceDelegated() async throws {
        try await withOpenWallet { try await $0.wallet.maintenanceDelegated() }
    }

    /// BOLT11 invoice for a forwarded Lightning address payment.
    func bolt11Invoice(amountSats: UInt64) async throws -> String {
        try await withOpenWallet { try await $0.wallet.bolt11Invoice(amountSats: amountSats, description: nil).invoice }
    }

    /// Claims pending Lightning receives without waiting (RN `wait: false`).
    func claimLightningReceives() async throws {
        try await withOpenWallet { _ = try await $0.wallet.tryClaimAllLightningReceives(wait: false) }
    }

    /// Blocks until the soonest VTXO expiry, as the widget shows it.
    func closestExpiryBlocks() async throws -> Int? {
        try await withOpenWallet { handles in
            let vtxos = try await handles.wallet.vtxos()
            guard !vtxos.isEmpty else { return nil }
            let tip = Int(try await handles.onchain.tipHeight())
            return vtxos.map { Int($0.expiryHeight) - tip }.min()
        }
    }

    // MARK: Receive

    /// Fresh Ark and onchain addresses, kept paired while an amount is edited.
    func receiveRequest() async throws -> ReceiveRequest {
        try await withOpenWallet { handles in
            let ark = try await handles.wallet.newAddress()
            let onchain = try await handles.onchain.newAddress()
            return ReceiveRequest(arkAddress: ark, onchainAddress: onchain)
        }
    }

    func receiveInvoice(for request: ReceiveRequest, amountSat: Int64, description: String) async throws -> ReceiveRequest {
        let description = description.trimmingCharacters(in: .whitespacesAndNewlines)
        try ReceiveRequest.validate(amountSat: amountSat, description: description)
        let invoice = try await withOpenWallet { handles in
            try await handles.wallet.bolt11Invoice(
                amountSats: UInt64(amountSat), description: description.isEmpty ? nil : description)
        }
        var updated = request
        updated.amountSat = amountSat
        updated.description = description
        updated.lightningInvoice = invoice.invoice
        updated.paymentHash = invoice.paymentHash
        return updated
    }

    /// Finishes with the payment of `request`, like RN's per-address and
    /// per-hash subscriptions (the FFI has one general stream; it is
    /// filtered here). Subscribes before reading history, so a payment made
    /// during setup can't fall in a gap. The observer belongs to the
    /// session: `close` stops it and waits for it.
    func receiveCompletions(for request: ReceiveRequest) throws -> AsyncThrowingStream<ReceiveCompletion, Error> {
        guard case .open(let handles) = state else { throw SessionError.notOpen }
        let holder = handles.wallet.notifications()
        let id = UUID()
        let (stream, continuation) = AsyncThrowingStream<ReceiveCompletion, Error>.makeStream()
        let task = Task.detached { [weak self] in
            do {
                var completion = try await Self.reconcileReceive(handles, request: request)
                while completion == nil, !Task.isCancelled {
                    guard let notification = try await holder.nextNotification() else { break }
                    switch notification {
                    case .movementCreated(let movement), .movementUpdated(let movement):
                        completion = ReceiveCompletion.matching(movement, request: request)
                    case .channelLagging:
                        completion = try await Self.reconcileReceive(handles, request: request)
                    }
                }
                if let completion { continuation.yield(completion) }
                continuation.finish()
            } catch {
                continuation.finish(throwing: error)
            }
            await self?.observerEnded(id)
        }
        observers[id] = Observer(task: task, stop: { holder.cancelNextNotificationWait() })
        continuation.onTermination = { _ in
            task.cancel()
            holder.cancelNextNotificationWait()
        }
        return stream
    }

    /// Every committed database change (`initial`, `databaseChanged`,
    /// `resyncRequired`), for scheduling backups. Session-owned like the
    /// receive observer: `close` stops it and waits for it.
    func stateChanges() throws -> AsyncThrowingStream<StateChangeEvent, Error> {
        guard case .open(let handles) = state else { throw SessionError.notOpen }
        let subscription = try handles.wallet.stateChanges()
        let id = UUID()
        let (stream, continuation) = AsyncThrowingStream<StateChangeEvent, Error>.makeStream()
        let task = Task.detached { [weak self] in
            do {
                while !Task.isCancelled, let event = try await subscription.nextChange() {
                    continuation.yield(event)
                }
                continuation.finish()
            } catch {
                continuation.finish(throwing: error)
            }
            subscription.stop()
            await self?.observerEnded(id)
        }
        observers[id] = Observer(task: task, stop: { subscription.stop() })
        continuation.onTermination = { _ in
            task.cancel()
            subscription.stop()
        }
        return stream
    }

    /// History read run to completion even if the observer is cancelled.
    private static func reconcileReceive(_ handles: Handles, request: ReceiveRequest) async throws -> ReceiveCompletion? {
        let movements = try await Task { try await handles.wallet.history() }.value
        return movements.lazy.compactMap { ReceiveCompletion.matching($0, request: request) }.first
    }

    private func observerEnded(_ id: UUID) {
        observers.removeValue(forKey: id)
    }

    private func stopObservers() async {
        let running = Array(observers.values)
        for observer in running {
            observer.task.cancel()
            observer.stop()
        }
        for observer in running { await observer.task.value }
    }

    /// Syncs onchain then Ark state, like `syncWallet` minus Lightning claims.
    func sync() async throws {
        guard isOpen else { return }
        try await withOpenWallet { handles in
            _ = try await handles.onchain.sync()
            try await handles.wallet.sync()
        }
    }

    private static func sdkOpener(variant: AppVariant) -> Opener {
        { mnemonic, datadir, runDaemon, appVersion, create in
            let configuration = WalletConfiguration.base(for: variant)
            let config = configuration.sdkConfig(
                userAgent: WalletConfiguration.userAgent(variant: variant, version: appVersion))
            // Existing wallets go through the SDK's read-only identity check
            // (properties, network, seed fingerprint) before anything writes;
            // only creation may use the creating factory.
            let onchain = create
                ? try await OnchainWallet.default(
                    network: configuration.network, mnemonic: mnemonic, config: config, datadir: datadir)
                : try await OnchainWallet.openExisting(
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
