import BarkBoltFFI
import Foundation
import SQLite3
import Testing

@testable import Noah

struct WalletSessionTests {
    private let documents = FileManager.default.temporaryDirectory.appending(path: "session-\(UUID().uuidString)")

    private var location: WalletDataLocation { WalletDataLocation(variant: .regtest, documentsDirectory: documents) }

    /// Minimal stand-in for a bark database: only `bark_properties`.
    private func writeBarkProperties(network: String) throws {
        try FileManager.default.createDirectory(at: location.directory, withIntermediateDirectories: true)
        var db: OpaquePointer?
        #expect(sqlite3_open(location.database.path, &db) == SQLITE_OK)
        defer { sqlite3_close(db) }
        let sql = """
            CREATE TABLE bark_properties (id TEXT PRIMARY KEY, network TEXT NOT NULL, fingerprint TEXT NOT NULL);
            INSERT INTO bark_properties (id, network, fingerprint) VALUES ('1', '\(network)', 'abcd1234');
            """
        #expect(sqlite3_exec(db, sql, nil, nil, nil) == SQLITE_OK)
    }

    /// Same database in WAL mode, closed cleanly: no -wal or -shm left behind.
    private func writeClosedWALDatabase(network: String) throws {
        try writeBarkProperties(network: network)
        var db: OpaquePointer?
        #expect(sqlite3_open(location.database.path, &db) == SQLITE_OK)
        #expect(sqlite3_exec(db, "PRAGMA journal_mode=WAL; INSERT INTO bark_properties VALUES ('2', 'x', 'y'); DELETE FROM bark_properties WHERE id = '2';", nil, nil, nil) == SQLITE_OK)
        sqlite3_close(db)
        #expect(!FileManager.default.fileExists(atPath: location.database.path + "-wal"))
        #expect(!FileManager.default.fileExists(atPath: location.database.path + "-shm"))
    }

    /// Opener that never reaches the SDK; it waits until released, then fails.
    private actor Gate {
        private var waiters: [CheckedContinuation<Void, Never>] = []
        private(set) var calls = 0
        func enter() async {
            calls += 1
            await withCheckedContinuation { waiters.append($0) }
        }
        func release() { waiters.forEach { $0.resume() }; waiters = [] }
    }

    private struct OpenerFailed: Error {}

    private func session(gate: Gate? = nil) -> WalletSession {
        WalletSession(variant: .regtest, location: location) { _, _, _, _, _ in
            if let gate { await gate.enter() }
            throw OpenerFailed()
        }
    }

    @Test func refusesMissingDatabaseWithoutCreatingAnything() async {
        await #expect(throws: WalletSession.OpenError.walletDatabaseMissing) {
            try await session().openExisting(mnemonic: "unused", runDaemon: false, appVersion: "test")
        }
        #expect(!FileManager.default.fileExists(atPath: documents.path))
    }

    @Test func refusesEmptyDatabaseAndLeavesItUntouched() async throws {
        try FileManager.default.createDirectory(at: location.directory, withIntermediateDirectories: true)
        try Data().write(to: location.database)
        await #expect(throws: WalletSession.OpenError.notABarkWallet) {
            try await session().openExisting(mnemonic: "unused", runDaemon: false, appVersion: "test")
        }
        let size = try FileManager.default.attributesOfItem(atPath: location.database.path)[.size] as? Int
        #expect(size == 0)
    }

    @Test func refusesOtherNetworkBeforeOpening() async throws {
        try writeBarkProperties(network: "signet")
        let gate = Gate()
        await #expect(throws: WalletSession.OpenError.networkMismatch(found: "signet")) {
            try await session(gate: gate).openExisting(mnemonic: "unused", runDaemon: false, appVersion: "test")
        }
        #expect(await gate.calls == 0)
    }

    /// The v0.3.0 SDK opens these; Noah's own preflight must not reject them first.
    @Test func cleanlyClosedWALDatabaseReachesTheOpener() async throws {
        defer { try? FileManager.default.removeItem(at: documents) }
        try writeClosedWALDatabase(network: "regtest")
        #expect(try WalletDatabase.properties(at: location.database) == .init(network: "regtest", fingerprint: "abcd1234"))
        #expect(!FileManager.default.fileExists(atPath: location.database.path + "-shm"), "the check created no sidecar")

        let gate = Gate()
        let session = session(gate: gate)
        let open = Task { try await session.openExisting(mnemonic: "unused", runDaemon: false, appVersion: "test") }
        while await gate.calls == 0 { await Task.yield() }
        await gate.release()
        await #expect(throws: OpenerFailed.self) { try await open.value }

        let reservation = try await session.reserve()
        let verify = Task { try await session.verify(reservation, mnemonic: "unused", appVersion: "test") }
        while await gate.calls < 2 { await Task.yield() }
        await gate.release()
        await #expect(throws: OpenerFailed.self) { try await verify.value }
        await session.release(reservation)
    }

    @Test func liveWALDatabaseIsReadWithItsLog() throws {
        defer { try? FileManager.default.removeItem(at: documents) }
        try writeBarkProperties(network: "regtest")
        var writer: OpaquePointer?
        #expect(sqlite3_open(location.database.path, &writer) == SQLITE_OK)
        defer { sqlite3_close(writer) }
        // Committed only to the WAL while the writer stays open.
        #expect(sqlite3_exec(writer, "PRAGMA journal_mode=WAL; PRAGMA wal_autocheckpoint=0; UPDATE bark_properties SET network = 'signet';", nil, nil, nil) == SQLITE_OK)
        #expect(FileManager.default.fileExists(atPath: location.database.path + "-wal"))
        #expect(try WalletDatabase.properties(at: location.database).network == "signet")
    }

    @Test func rejectsOverlappingOpenAndCloseThenRecovers() async throws {
        try writeBarkProperties(network: "regtest")
        let gate = Gate()
        let session = session(gate: gate)

        let first = Task { try await session.openExisting(mnemonic: "unused", runDaemon: false, appVersion: "test") }
        while await gate.calls == 0 { await Task.yield() }

        await #expect(throws: WalletSession.OpenError.busy) {
            try await session.openExisting(mnemonic: "unused", runDaemon: false, appVersion: "test")
        }
        await #expect(throws: WalletSession.OpenError.busy) { try await session.close() }

        await gate.release()
        await #expect(throws: OpenerFailed.self) { try await first.value }
        #expect(try await session.summary() == nil)

        // A failed open returns to closed: the next attempt reaches the opener.
        let second = Task { try await session.openExisting(mnemonic: "unused", runDaemon: false, appVersion: "test") }
        while await gate.calls < 2 { await Task.yield() }
        await gate.release()
        await #expect(throws: OpenerFailed.self) { try await second.value }
    }

    /// Real bark wallet, created without a server so the test runs offline.
    private func offlineSession(beforeOpen: @escaping @Sendable () async throws -> Void = {}) -> WalletSession {
        WalletSession(variant: .regtest, location: location) { mnemonic, datadir, _, _, create in
            try await beforeOpen()
            let config = WalletConfiguration.base(for: .regtest).sdkConfig(userAgent: "test")
            let onchain = try await OnchainWallet.default(network: .regtest, mnemonic: mnemonic, config: config, datadir: datadir)
            let wallet = try await Wallet.openWithOnchain(
                network: .regtest, mnemonicOrSeed: mnemonic, config: config,
                args: WalletOpenArgs(
                    runDaemon: false, datadir: datadir, createIfNotExists: create,
                    createWithoutServer: true, skipRecovery: true),
                onchain: onchain)
            return WalletSession.Handles(wallet: wallet, onchain: onchain)
        }
    }

    /// Exits read the daemon's clock: every path that starts bark's daemon sets it, every stop clears it.
    /// A cold launch with a custom Esplora opens through `reopen`.
    @Test(.regtestStack, .timeLimit(.minutes(1))) func theDaemonClockFollowsEveryOpenAndClose() async throws {
        defer { try? FileManager.default.removeItem(at: documents) }
        let session = offlineSession()
        let mnemonic = try generateMnemonic()
        try await session.createNew(mnemonic: mnemonic, runDaemon: true, appVersion: "test")
        #expect(await session.daemonSince != nil)
        let reservation = try await session.reserveForConfigurationChange()
        #expect(await session.daemonSince == nil)
        try await session.reopen(reservation, mnemonic: mnemonic, appVersion: "test", commit: {})
        #expect(await session.daemonSince != nil)
        try await session.close()
        #expect(await session.daemonSince == nil)
        try await session.ensureOpen(mnemonic: mnemonic, runDaemon: false, appVersion: "test")
        #expect(await session.daemonSince == nil)
        try await session.close()
        // A configuration that fails to commit stops the daemon again.
        let failing = try await session.reserveForConfigurationChange()
        await #expect(throws: OpenerFailed.self) {
            try await session.reopen(failing, mnemonic: mnemonic, appVersion: "test", commit: { throw OpenerFailed() })
        }
        #expect(await session.daemonSince == nil)
        await session.release(failing)
    }

    @Test(.regtestStack, .timeLimit(.minutes(1))) func silentEsploraReleasesUIGateButKeepsReservationUntilRollback() async throws {
        defer { try? FileManager.default.removeItem(at: documents) }
        for lateSuccess in [false, true] {
            let server = try LocalEsploraServer(.silent)
            defer { server.stop() }
            let candidate = EsploraSettings.Selection(endpoint: server.url, variant: "signet")
            let previous = EsploraSettings.Selection(variant: "signet")
            let session = offlineSession {
                if EsploraOpening.selection == candidate {
                    do { _ = try await URLSession.shared.data(from: URL(string: server.url)!) }
                    catch { if !lateSuccess { throw error } }
                }
            }
            let mnemonic = try generateMnemonic()
            // Each iteration uses a fresh wallet directory.
            try await session.createNew(mnemonic: mnemonic, runDaemon: false, appVersion: "test")
            let store = EsploraSettings(storage: try SettingsStorage(rootPath: documents.appending(path: "settings").path))
            try store.save(.init(active: previous))
            let prepared = try store.prepare(candidate)
            let reservation = try await session.reserveForConfigurationChange()
            let gate = WalletMutationGate()
            let wait = EsploraOpenWait()
            let completion = AsyncStream.makeStream(of: Bool.self)
            await #expect(throws: EsploraOpenWait.Timeout.self) {
                try await gate.withLock {
                    try await wait.run(timeout: .milliseconds(300), operation: { wait in
                        do {
                            try await store.change(to: candidate, prepared: prepared, commitCandidate: { try wait.commit($0) }) { selection, commit in
                                try await EsploraOpening.$selection.withValue(selection) {
                                    try await session.reopen(reservation, mnemonic: mnemonic, appVersion: "test", commit: commit)
                                }
                            }
                            await session.release(reservation)
                        } catch { await session.release(reservation); throw error }
                    }, lateCompletion: { success in completion.continuation.yield(success); completion.continuation.finish() })
                }
            }
            try #require(wait.timedOut)
            #expect(await gate.withLock { true })
            #expect(await session.unlessReserved {} == false)
            await #expect(throws: WalletSession.OpenError.busy) {
                try await session.ensureOpen(mnemonic: mnemonic, runDaemon: false, appVersion: "test")
            }
            server.stop()
            #expect(await completion.stream.first(where: { _ in true }) == false)
            #expect(await session.isOpen)
            #expect(try store.record()?.active == previous)
            try await session.close()
            try FileManager.default.removeItem(at: location.directory)
        }
    }

    @Test(.regtestStack, .timeLimit(.minutes(1))) func launchDefaultWaitsForNativeAndReopensDespiteStorageFailure() async throws {
        defer { try? FileManager.default.removeItem(at: documents) }
        let candidate = EsploraSettings.Selection(endpoint: "https://custom.invalid/api", variant: "signet")
        let fallback = EsploraSettings.Selection(variant: "signet")
        let native = Gate()
        let session = offlineSession {
            if EsploraOpening.selection == candidate { await native.enter(); throw OpenerFailed() }
        }
        let mnemonic = try generateMnemonic()
        try await session.createNew(mnemonic: mnemonic, runDaemon: false, appVersion: "test")
        let fingerprint = try await session.summary()?.fingerprint
        try await session.close()
        let reservation = try await session.reserve()
        let wait = EsploraOpenWait()
        let completion = AsyncStream.makeStream(of: Bool.self)
        let storage = EsploraSettings(read: { _ in nil }, write: { _, _ in throw OpenerFailed() })
        await #expect(throws: EsploraOpenWait.Timeout.self) {
            try await wait.run(timeout: .milliseconds(100), operation: { wait in
                do {
                    do {
                        try await EsploraOpening.$selection.withValue(candidate) {
                            try await session.reopen(reservation, mnemonic: mnemonic, appVersion: "test", commit: { try wait.commit({}) })
                        }
                    } catch {
                        guard wait.timedOut else { throw error }
                        await wait.waitForDefault()
                        try? storage.save(.init(active: fallback))
                        try await EsploraOpening.$selection.withValue(fallback) {
                            try await session.reopen(reservation, mnemonic: mnemonic, appVersion: "test", commit: {})
                        }
                    }
                    await session.release(reservation)
                } catch { await session.release(reservation); throw error }
            }, lateCompletion: { success in completion.continuation.yield(success); completion.continuation.finish() })
        }
        try #require(wait.timedOut)
        wait.chooseDefault()
        #expect(await session.isOpen == false)
        #expect(await session.unlessReserved {} == false)
        while await native.calls == 0 { await Task.yield() }
        await native.release()
        #expect(await completion.stream.first(where: { _ in true }) == true)
        #expect(await session.isOpen)
        #expect(try await session.summary()?.fingerprint == fingerprint)
        try await session.close()
    }

    @Test(.regtestStack) func esploraReloadRefusesInFlightMoneyOperations() async throws {
        defer { try? FileManager.default.removeItem(at: documents) }
        let session = offlineSession()
        try await session.createNew(mnemonic: try generateMnemonic(), runDaemon: false, appVersion: "test")
        for operation in ["send", "board", "exit", "recurring"] {
            let gate = Gate()
            let work = Task {
                if operation == "send" || operation == "recurring" {
                    try await session.withSendReservation({ await gate.enter() }, exclusive: operation == "recurring")
                } else {
                    try await session.withOpenWallet { _ in await gate.enter() }
                }
            }
            while await gate.calls == 0 { await Task.yield() }
            await #expect(throws: EsploraEndpoint.Failure.self) { try await session.reserveForConfigurationChange() }
            #expect(await session.isOpen)
            await gate.release(); try await work.value
        }
        let reservation = try await session.reserveForConfigurationChange()
        await session.release(reservation)
    }

    @Test func backgroundWalletWaitsDuringEsploraReload() async throws {
        let session = WalletSession(variant: .regtest, location: location)
        let services = AppServices(variant: .regtest, session: session)
        let gate = Gate()
        let changing = Task { await AppServices.configurationAccess.withLock { await gate.enter() } }
        while await gate.calls == 0 { await Task.yield() }
        let background = Task { try await services.withBackgroundWallet { true } }
        while await AppServices.configurationAccess.waitingCount == 0 { await Task.yield() }
        #expect(await gate.calls == 1)
        await gate.release(); await changing.value
        #expect(try await background.value)
    }

    @Test(.regtestStack) func esploraReopenKeepsReservationThroughDurableCommit() async throws {
        defer { try? FileManager.default.removeItem(at: documents) }
        let session = offlineSession()
        let mnemonic = try generateMnemonic()
        try await session.createNew(mnemonic: mnemonic, runDaemon: false, appVersion: "test")
        let reservation = try await session.reserveForConfigurationChange()
        let reopening = Task {
            try await session.reopen(reservation, mnemonic: mnemonic, appVersion: "test", commit: {})
        }
        try await reopening.value
        #expect(await session.isOpen)
        await session.release(reservation)
        #expect(await session.isOpen)
        try await session.close()
    }

    @Test(.regtestStack) func esploraCommitFailureKeepsReservationForRollback() async throws {
        defer { try? FileManager.default.removeItem(at: documents) }
        let session = offlineSession()
        let mnemonic = try generateMnemonic()
        try await session.createNew(mnemonic: mnemonic, runDaemon: false, appVersion: "test")
        let reservation = try await session.reserveForConfigurationChange()
        await #expect(throws: OpenerFailed.self) {
            try await session.reopen(reservation, mnemonic: mnemonic, appVersion: "test") { throw OpenerFailed() }
        }
        #expect(await session.isOpen == false)
        #expect(await session.unlessReserved {} == false)
        try await session.reopen(reservation, mnemonic: mnemonic, appVersion: "test", commit: {})
        #expect(await session.isOpen)
        try await session.close()
    }

    @Test(.regtestStack) func cancelledOrExpiredTasksCannotAdmitWalletCalls() async throws {
        defer { try? FileManager.default.removeItem(at: documents) }
        let session = offlineSession()
        try await session.createNew(mnemonic: try generateMnemonic(), runDaemon: false, appVersion: "test")
        let gate = Gate()
        let cancelled = Task {
            await gate.enter()
            return try await session.withOpenWallet { _ in true }
        }
        while await gate.calls == 0 { await Task.yield() }
        cancelled.cancel()
        await gate.release()
        await #expect(throws: CancellationError.self) { try await cancelled.value }
        let budget = RecurringSubmissionBudget()
        budget.revoke()
        try await RecurringSubmissionBudget.$current.withValue(budget) {
            // Independent tasks inherit expiry, but not caller cancellation.
            await #expect(throws: CancellationError.self) {
                try await Task { try await session.withOpenWallet { _ in true } }.value
            }
        }
        await RecurringExecutionBudget.$deadline.withValue(ContinuousClock.now - .seconds(1)) {
            await #expect(throws: CancellationError.self) {
                try await session.withOpenWallet { _ in true }
            }
        }
        try await session.close()
    }

    @Test(.regtestStack) func closeWaitsForWalletCallsStillRunning() async throws {
        defer { try? FileManager.default.removeItem(at: documents) }
        let session = offlineSession()
        try await session.createNew(mnemonic: try generateMnemonic(), runDaemon: false, appVersion: "test")
        let gate = Gate()
        let call = Task { try await session.withOpenWallet { _ in await gate.enter() } }
        while await gate.calls == 0 { await Task.yield() }
        // The caller gives up; like Rust work under BoltFFI, the call goes on.
        call.cancel()

        let closing = Task { try await session.close() }
        for _ in 0..<50 { await Task.yield() }
        await #expect(throws: WalletSession.SessionError.notOpen) { try await session.peekAddress(index: 0) }
        await #expect(throws: WalletSession.OpenError.busy) { try await session.reserve() }

        await gate.release()
        try await closing.value
        let reservation = try await session.reserve()
        await session.release(reservation)
    }

    @Test(.regtestStack, .timeLimit(.minutes(1))) func repeatedReceiveObserverTeardown() async throws {
        for _ in 0..<50 {
            try await closeStopsReceiveObserversAndWaitsForThem()
        }
    }

    @Test(.regtestStack, .timeLimit(.minutes(1))) func repeatedPendingObserversStopOnCloseAndConsumerCancellation() async throws {
        defer { try? FileManager.default.removeItem(at: documents) }
        for iteration in 0..<25 {
            let session = offlineSession()
            try await session.createNew(mnemonic: try generateMnemonic(), runDaemon: false, appVersion: "test")
            let completions = try await session.receiveCompletions(for:
                ReceiveRequest(arkAddress: "unused-ark-address", onchainAddress: "unused-onchain-address"))
            let changes = try await session.stateChanges()
            let receiveReader = Task {
                do { for try await _ in completions {} }
                catch is CancellationError {}
            }
            let changeReader = Task {
                do { for try await _ in changes {} }
                catch is CancellationError {}
            }
            // Exercise pending SDK waits as well as the immediate-close registration gap.
            try await Task.sleep(for: .milliseconds(10))
            if iteration.isMultiple(of: 2) { receiveReader.cancel(); changeReader.cancel() }
            let closing = Task { try await session.close() }
            if iteration.isMultiple(of: 3) { closing.cancel() }
            try await closing.value
            try await receiveReader.value
            try await changeReader.value
            let reservation = try await session.reserve()
            await session.release(reservation)
            try FileManager.default.removeItem(at: documents)
        }
    }

    @Test(.regtestStack, .timeLimit(.minutes(1))) func closeStopsReceiveObserversAndWaitsForThem() async throws {
        defer { try? FileManager.default.removeItem(at: documents) }
        let session = offlineSession()
        try await session.createNew(mnemonic: try generateMnemonic(), runDaemon: false, appVersion: "test")
        // Offline wallet: no server for real addresses, and none are needed to wait.
        let request = ReceiveRequest(arkAddress: "unused-ark-address", onchainAddress: "unused-onchain-address")
        let completions = try await session.receiveCompletions(for: request)
        let reader = Task {
            var received: [ReceiveCompletion] = []
            do {
                for try await completion in completions { received.append(completion) }
            } catch is CancellationError {
                // Closing cancels the native observer; both normal finish and cancellation are valid.
            }
            return received
        }
        try await session.close()
        // The observer ended with the session: no completion, stream finished.
        #expect(try await reader.value.isEmpty)
        let reservation = try await session.reserve()
        await session.release(reservation)
    }

    @Test func reservationKeepsEveryoneElseOut() async throws {
        defer { try? FileManager.default.removeItem(at: documents) }
        try writeBarkProperties(network: "regtest")
        let gate = Gate()
        let session = session(gate: gate)
        let reservation = try await session.reserve()
        await #expect(throws: WalletSession.OpenError.busy) {
            try await session.ensureOpen(mnemonic: "unused", runDaemon: false, appVersion: "test")
        }
        await #expect(throws: WalletSession.OpenError.busy) { try await session.reserve() }
        await #expect(throws: WalletSession.OpenError.busy) { try await session.close() }
        #expect(await gate.calls == 0)

        await session.release(reservation)
        let open = Task { try await session.ensureOpen(mnemonic: "unused", runDaemon: false, appVersion: "test") }
        while await gate.calls == 0 { await Task.yield() }
        await gate.release()
        await #expect(throws: OpenerFailed.self) { try await open.value }
    }

    @Test func ensureOpenWaitsForAnOpenInProgress() async throws {
        try writeBarkProperties(network: "regtest")
        let gate = Gate()
        let session = session(gate: gate)
        let first = Task { try await session.openExisting(mnemonic: "unused", runDaemon: false, appVersion: "test") }
        while await gate.calls == 0 { await Task.yield() }

        // Joins the in-flight open (and its failure) instead of reporting busy.
        let joined = Task { try await session.ensureOpen(mnemonic: "unused", runDaemon: false, appVersion: "test") }
        for _ in 0..<50 { await Task.yield() }
        await gate.release()
        await #expect(throws: OpenerFailed.self) { try await first.value }
        await #expect(throws: OpenerFailed.self) { try await joined.value }
        #expect(await gate.calls == 1)
    }
}
