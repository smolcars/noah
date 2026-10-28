import BarkBoltFFI
import Foundation
import HTTPTypes
@testable import Noah
import OpenAPIRuntime
import SQLite3
import Testing

/// Restore against a fake Noah server with a real bark snapshot. The open
/// check uses the real SDK opener (local regtest config) unless a test
/// swaps in one that fails or waits.
@Suite(.serialized)
struct WalletRestorerTests {
    // swiftlint:disable:next force_try - a static can't throw; bark's generator only fails without system randomness
    private static let previousSeed = try! generateMnemonic()
    private let root = FileManager.default.temporaryDirectory.appending(path: "restorer-\(UUID().uuidString)")
    private var deviceB: WalletDataLocation { WalletDataLocation(variant: .regtest, documentsDirectory: root.appending(path: "device-b")) }

    /// Serves list + download for one backup file.
    struct FakeServer: ClientTransport {
        let backupId: String
        let encrypted: URL
        var reportedSha256: String?
        var reportedSize: Int?

        func send(_: HTTPRequest, body _: HTTPBody?, baseURL _: URL, operationID: String) throws -> (HTTPResponse, HTTPBody?) {
            let data = try Data(contentsOf: encrypted)
            let info: [String: Any] = [
                "backup_id": backupId, "created_at": "2026-10-19T00:00:00Z",
                "encrypted_sha256": reportedSha256 ?? BackupArchive.sha256Hex(data), "encrypted_size": reportedSize ?? data.count,
                "format_version": 2,
            ]
            let json: Any
            switch operationID {
            case "list_backup_objects": json = [info]
            case "get_backup_object_download_url": json = ["backup": info, "download_url": encrypted.absoluteString]
            default: return (HTTPResponse(status: .notFound), nil)
            }
            var response = HTTPResponse(status: .ok)
            response.headerFields[.contentType] = "application/json"
            return (response, HTTPBody(try JSONSerialization.data(withJSONObject: json)))
        }
    }

    struct OpenRefused: Error {}

    /// Lets a test hold the open check until it has looked at the session.
    actor Gate {
        private var entered = false
        private var released = false
        private var enteredWaiters: [CheckedContinuation<Void, Never>] = []
        private var releaseWaiters: [CheckedContinuation<Void, Never>] = []

        func enter() async {
            entered = true
            enteredWaiters.forEach { $0.resume() }
            enteredWaiters = []
            guard !released else { return }
            await withCheckedContinuation { releaseWaiters.append($0) }
        }

        func waitUntilEntered() async {
            guard !entered else { return }
            await withCheckedContinuation { enteredWaiters.append($0) }
        }

        func release() {
            released = true
            releaseWaiters.forEach { $0.resume() }
            releaseWaiters = []
        }
    }

    /// Wallet created offline, sealed into a backup file.
    private func makeBackup(mnemonic: String) async throws -> URL {
        let location = WalletDataLocation(variant: .regtest, documentsDirectory: root.appending(path: "device-a"))
        try FileManager.default.createDirectory(at: location.directory, withIntermediateDirectories: true)
        let config = WalletConfiguration.base(for: .regtest).sdkConfig(userAgent: "test")
        let onchain = try await OnchainWallet.default(network: .regtest, mnemonic: mnemonic, config: config, datadir: location.directory.path)
        let wallet = try await Wallet.openWithOnchain(
            network: .regtest, mnemonicOrSeed: mnemonic, config: config,
            args: WalletOpenArgs(runDaemon: false, datadir: location.directory.path, createWithoutServer: true, skipRecovery: true),
            onchain: onchain)
        let snapshotURL = root.appending(path: "snapshot.sqlite")
        let snapshot = try await wallet.createSnapshot(destinationPath: snapshotURL.path)
        try await wallet.stopDaemonWait()
        return try BackupArchive.seal(
            snapshot: snapshotURL, manifest: BackupService.manifest(for: snapshot), mnemonic: mnemonic,
            to: root.appending(path: "backup.noahbackup"), workDirectory: root).url
    }

    /// Device B already has a wallet; returns a restorer for it.
    private func setUp(
        seed: FakeSeed, session: WalletSession? = nil, reportedSha256: String? = nil,
        reportedSize: Int? = nil) async throws -> (restorer: WalletRestorer, mnemonic: String) {
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let mnemonic = try generateMnemonic()
        let backup = try await makeBackup(mnemonic: mnemonic)
        try FileManager.default.createDirectory(at: deviceB.directory, withIntermediateDirectories: true)
        try Data("previous wallet".utf8).write(to: deviceB.database)
        let serverURL = try #require(URL(string: "http://fake"))
        let restorer = WalletRestorer(
            variant: .regtest, session: session ?? WalletSession(variant: .regtest, location: deviceB),
            location: deviceB, seed: seed,
            makeClient: { _ in
                Client(serverURL: serverURL,
                       transport: FakeServer(
                           backupId: "b1", encrypted: backup, reportedSha256: reportedSha256, reportedSize: reportedSize))
            })
        return (restorer, mnemonic)
    }

    @Test(.regtestStack) func failedOnboardingMarkerPreservesWalletAndAllowsRestoreRetry() async throws {
        defer { try? FileManager.default.removeItem(at: root) }
        let seed = FakeSeed(Self.previousSeed)
        var (restorer, mnemonic) = try await setUp(seed: seed)
        restorer.beginOnboarding = { throw SettingsStorage.OpenError() }
        await #expect(throws: SettingsStorage.OpenError.self) { try await restorer.restore(mnemonic: mnemonic) }
        expectPreviousWalletBack(seed)
        let settings = try SettingsStorage(rootPath: root.appending(path: "mmkv").path)
        restorer.beginOnboarding = { try settings.beginOnboarding(recoveryPhraseBackedUp: true) }
        _ = try await restorer.restore(mnemonic: mnemonic)
        #expect(settings.persistedState(PersistedWalletState.self, key: PersistedWalletState.key)?.isInitialized == false)
        #expect(seed.current == mnemonic)
    }

    @Test(.regtestStack) func restorePersistsUnfinishedOnboardingBeforeInstallingBackupOrPhrase() async throws {
        defer { try? FileManager.default.removeItem(at: root) }
        let settings = try SettingsStorage(rootPath: root.appending(path: "mmkv").path)
        let seed = FakeSeed(Self.previousSeed)
        var (restorer, mnemonic) = try await setUp(seed: seed)
        restorer.beginOnboarding = { try settings.beginOnboarding(recoveryPhraseBackedUp: true) }
        _ = try await restorer.restore(mnemonic: mnemonic) { step in
            if step == .loading || step == .finalizing {
                let reopened = try? SettingsStorage(rootPath: root.appending(path: "mmkv").path)
                #expect(reopened?.persistedState(PersistedWalletState.self, key: PersistedWalletState.key)?.isInitialized == false)
            }
        }
        let reopened = try SettingsStorage(rootPath: root.appending(path: "mmkv").path)
        #expect(reopened.persistedState(PersistedWalletState.self, key: PersistedWalletState.key)?.isInitialized == false)
    }

    private var deviceBWallet: String? {
        (try? Data(contentsOf: deviceB.database)).flatMap { String(bytes: $0, encoding: .utf8) }
    }

    private func expectPreviousWalletBack(_ seed: FakeSeed) {
        #expect(deviceBWallet == "previous wallet")
        #expect(seed.current == Self.previousSeed)
        expectNoRestoreLeftovers()
    }

    private func expectNoRestoreLeftovers(settingsPending: Bool = false) {
        let workspace = deviceB.restoreDirectory
        let leftovers = (try? FileManager.default.contentsOfDirectory(atPath: workspace.path)) ?? []
        #expect(leftovers == (settingsPending ? ["settings-pending"] : []), "Only pending settings completion may remain: \(leftovers)")
    }

    private struct UnavailableDeletionSeed: WalletDeletionSeed {
        func read() -> KeychainPassword.ReadResult { .unavailable(-25308) }
        func deleteWalletSeed() { Issue.record("Locked seed must not be deleted") }
    }

    @Test(.regtestStack, arguments: [true, false])
    func stagedDeletionUnlockRestoreAndDeleteUsesRealRestorePipeline(pending: Bool) async throws {
        defer { try? FileManager.default.removeItem(at: root) }
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let mnemonic = try generateMnemonic()
        let backup = try await makeBackup(mnemonic: mnemonic)
        try FileManager.default.createDirectory(at: deviceB.directory, withIntermediateDirectories: true)
        try Data("previous wallet".utf8).write(to: deviceB.database)
        let seed = KeychainPassword(service: "com.noah.tests.restore-r5.\(UUID().uuidString)", account: "seed")
        let token = KeychainPassword(service: "com.noah.tests.restore-r5.\(UUID().uuidString)", account: "token")
        defer { seed.remove(); token.remove() }
        try seed.save(mnemonic)
        try deviceB.writeDeletionIdentity(seed: seed.read())
        try FileManager.default.moveItem(at: deviceB.directory, to: deviceB.deletionDirectory)
        let session = WalletSession(variant: .regtest, location: deviceB)
        let mutation = WalletMutationGate()
        let settings = try SettingsStorage(rootPath: root.appending(path: "mmkv").path, instanceID: UUID().uuidString)
        let dependencies = WalletLifecycle.Dependencies(session: session, location: deviceB, seed: seed, authToken: token,
                                                        settings: settings, scratch: ScratchSpace(root: root.appending(path: "scratch")), appGroup: nil,
                                                        mutation: mutation, deregister: {}, walletChanged: {})
        var lockedDependencies = dependencies
        lockedDependencies.seed = UnavailableDeletionSeed()
        let locked = WalletLifecycle(dependencies: lockedDependencies)
        #expect(try await locked.recoverDeletion() == .waitingForKeychain)
        #expect(FileManager.default.fileExists(atPath: deviceB.deletionMarker.path))
        #expect(FileManager.default.fileExists(atPath: deviceB.deletionDirectory.path))
        let lifecycle = WalletLifecycle(dependencies: dependencies)
        if pending { try await lifecycle.recoverDeletion() }
        else { try FileManager.default.removeItem(at: deviceB.deletionMarker) } // Older app left staging and seed.
        let serverURL = try #require(URL(string: "http://fake"))
        let restorer = WalletRestorer(mutation: mutation, variant: .regtest, session: session, location: deviceB, seed: seed,
                                      makeClient: { _ in
                                          Client(serverURL: serverURL, transport: FakeServer(backupId: "b1", encrypted: backup))
                                      },
                                      walletChanged: {})
        _ = try await restorer.restore(mnemonic: mnemonic)
        #expect(deviceB.databaseExists); #expect(seed.read() == .found(mnemonic))
        #expect(!FileManager.default.fileExists(atPath: deviceB.deletionDirectory.path))
        try await lifecycle.delete()
        #expect(seed.read() == .notFound)
        #expect(!deviceB.databaseExists)
        #expect(!FileManager.default.fileExists(atPath: deviceB.deletionMarker.path))
    }

    @MainActor @Test(.regtestStack) func sendReceiptIsInvalidatedByRealDeleteAndDifferentSeedRestore() async throws {
        defer { try? FileManager.default.removeItem(at: root) }
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let replacementSeed = try generateMnemonic()
        let backup = try await makeBackup(mnemonic: replacementSeed)
        let session = WalletSession(variant: .regtest, location: deviceB) { mnemonic, datadir, _, _, create in
            let config = WalletConfiguration.base(for: .regtest).sdkConfig(userAgent: "send-identity-test")
            let onchain = try await OnchainWallet.default(network: .regtest, mnemonic: mnemonic, config: config, datadir: datadir)
            let wallet = try await Wallet.openWithOnchain(network: .regtest, mnemonicOrSeed: mnemonic, config: config,
                                                          args: WalletOpenArgs(
                                                              runDaemon: false,
                                                              datadir: datadir,
                                                              createIfNotExists: create,
                                                              createWithoutServer: true,
                                                              skipRecovery: true), onchain: onchain)
            return .init(wallet: wallet, onchain: onchain)
        }
        let oldSeed = try generateMnemonic()
        let seed = KeychainPassword(service: "com.noah.tests.send-replace.\(UUID().uuidString)", account: "noah")
        let token = KeychainPassword(service: "com.noah.tests.send-token.\(UUID().uuidString)", account: "noah")
        defer { seed.remove(); token.remove() }
        try seed.save(oldSeed)
        try await session.createNew(mnemonic: oldSeed, runDaemon: false, appVersion: "test")
        let oldFingerprint = try #require(try await session.summary()?.fingerprint)
        var dependencies = SendPaymentController.Dependencies.live(session: session, variant: .regtest, location: deviceB)
        dependencies.prepare = { _, _ in .init(movementIDs: []) }
        dependencies.pay = { _, _, _ in .init(amount: 1000, proof: .submitReturnedArkSuccess) }
        let controller = SendPaymentController(dependencies: dependencies)
        controller.submit(.init(destination: "ark", method: "ark", amount: 1000), quote: .init(amount: 1000, fee: 0, total: 1000))
        try await SendPaymentTests.waitUntil { controller.state != .submitting }
        #expect(controller.attempt?.receipt?.proof == .submitReturnedArkSuccess)
        let settings = try SettingsStorage(rootPath: root.appending(path: "mmkv").path, instanceID: UUID().uuidString)
        let lifecycle = WalletLifecycle(dependencies: .init(session: session, location: deviceB, seed: seed, authToken: token,
                                                            settings: settings, scratch: ScratchSpace(root: root.appending(path: "scratch")), appGroup: nil,
                                                            deregister: {}, walletChanged: { await controller.reloadForWalletChange() }))
        try await lifecycle.delete()
        #expect(controller.state == .ready && controller.attempt == nil)
        let serverURL = try #require(URL(string: "http://fake"))
        let restorer = WalletRestorer(variant: .regtest, session: session, location: deviceB, seed: seed,
                                      makeClient: { _ in Client(
                                          serverURL: serverURL,
                                          transport: FakeServer(backupId: "new", encrypted: backup)) },
                                      walletChanged: { await controller.reloadForWalletChange() })
        _ = try await restorer.restore(mnemonic: replacementSeed)
        try await session.openExisting(mnemonic: replacementSeed, runDaemon: false, appVersion: "test")
        #expect(try await session.summary()?.fingerprint != oldFingerprint)
        #expect(seed.read() == .found(replacementSeed))
        #expect(controller.state == .ready && controller.attempt == nil)
        try await session.close()
    }

    @MainActor @Test(.regtestStack) func sendReservationExcludesReplacementAndAnotherCreatorDespiteCancellation() async throws {
        defer { try? FileManager.default.removeItem(at: root) }
        let mnemonic = try generateMnemonic()
        let backup = try await makeBackup(mnemonic: mnemonic)
        let session = WalletSession(variant: .regtest, location: deviceB)
        let serverURL = try #require(URL(string: "http://fake"))
        let restorer = WalletRestorer(variant: .regtest, session: session, location: deviceB, seed: FakeSeed(nil),
                                      makeClient: { _ in
                                          Client(serverURL: serverURL, transport: FakeServer(backupId: "b", encrypted: backup))
                                      })
        _ = try await restorer.restore(mnemonic: mnemonic)
        try await session.openExisting(mnemonic: mnemonic, runDaemon: false, appVersion: "test")
        let gate = Gate()
        let submitting = Task { try await session.withSendReservation { await gate.enter() } }
        await gate.waitUntilEntered()
        submitting.cancel()
        await #expect(throws: WalletSession.OpenError.busy) { try await session.closeSettled() }
        await #expect(throws: WalletSession.OpenError.busy) { try await session.reserve() }
        await #expect(throws: WalletSession.OpenError.busy) { try await session.withSendReservation { Issue.record("Second creator") } }
        await gate.release()
        try await submitting.value
        try await session.close()
        let reservation = try await session.reserve()
        await session.release(reservation)
    }

    @Test(.regtestStack) func failedOpenPutsThePreviousWalletBack() async throws {
        defer { try? FileManager.default.removeItem(at: root) }
        let seed = FakeSeed(Self.previousSeed)
        let session = WalletSession(variant: .regtest, location: deviceB) { _, _, _, _, _ in throw OpenRefused() }
        let (restorer, mnemonic) = try await setUp(seed: seed, session: session)

        await #expect(throws: OpenRefused.self) { try await restorer.restore(mnemonic: mnemonic) }
        expectPreviousWalletBack(seed)
    }

    @Test(.regtestStack) func sessionIsReservedForTheWholeRestore() async throws {
        defer { try? FileManager.default.removeItem(at: root) }
        let seed = FakeSeed(Self.previousSeed)
        let gate = Gate()
        let session = WalletSession(variant: .regtest, location: deviceB) { _, _, _, _, _ in
            await gate.enter()
            throw OpenRefused()
        }
        let (restorer, mnemonic) = try await setUp(seed: seed, session: session)

        let restore = Task { try await restorer.restore(mnemonic: mnemonic) }
        await gate.waitUntilEntered()
        // Mid-restore: the new files are in place but nobody else can reach them.
        #expect(deviceBWallet != "previous wallet")
        await #expect(throws: WalletSession.OpenError.busy) {
            try await session.ensureOpen(mnemonic: Self.previousSeed, runDaemon: false, appVersion: "test")
        }
        await #expect(throws: WalletSession.OpenError.busy) { try await session.reserve() }
        await #expect(throws: WalletSession.OpenError.busy) { try await session.close() }
        #expect(try await session.summary() == nil)
        // Even a caller with a separate mutation gate cannot bypass the session reservation.
        var competing = restorer
        competing.mutation = WalletMutationGate()
        await #expect(throws: WalletSession.OpenError.busy) { try await competing.restore(mnemonic: mnemonic) }

        await gate.release()
        await #expect(throws: OpenRefused.self) { try await restore.value }
        expectPreviousWalletBack(seed)
        #expect(await !session.isOpen)
    }

    @Test(.regtestStack) func cancellingDuringTheOpenCheckWaitsForTheNativeWork() async throws {
        defer { try? FileManager.default.removeItem(at: root) }
        let seed = FakeSeed(Self.previousSeed)
        let gate = Gate()
        let sawCancellation = FakeSeed(nil)
        // BoltFFI answers a cancelled Swift caller early while Rust goes on. The
        // session runs the opener in its own task, so the opener must never see
        // the caller's cancellation, and the restore must wait for it.
        let session = WalletSession(variant: .regtest, location: deviceB) { _, _, _, _, _ in
            await withTaskCancellationHandler {
                await gate.enter()
            } onCancel: {
                try? sawCancellation.save("cancelled")
            }
            throw OpenRefused()
        }
        let (restorer, mnemonic) = try await setUp(seed: seed, session: session)

        let restore = Task { try await restorer.restore(mnemonic: mnemonic) }
        await gate.waitUntilEntered()
        restore.cancel()
        for _ in 0..<50 { await Task.yield() }
        // Still mid-check: nothing was swapped back underneath the opener.
        #expect(deviceBWallet != "previous wallet")
        #expect(deviceB.restorePending)
        await #expect(throws: WalletSession.OpenError.busy) { try await session.reserve() }

        #expect(sawCancellation.current == nil)

        await gate.release()
        await #expect(throws: OpenRefused.self) { try await restore.value }
        expectPreviousWalletBack(seed)
    }

    @Test(.regtestStack) func downloadLargerThanTheServerRecordIsCutOff() async throws {
        defer { try? FileManager.default.removeItem(at: root) }
        let seed = FakeSeed(Self.previousSeed)
        let (restorer, mnemonic) = try await setUp(seed: seed, reportedSize: 64)

        await #expect(throws: WalletRestorer.Failure.downloadMismatch) { try await restorer.restore(mnemonic: mnemonic) }
        expectPreviousWalletBack(seed)
    }

    @Test(.regtestStack) func launchRecoveryWithTheWalletAlreadyOpeningIsNotAFailure() async throws {
        defer { try? FileManager.default.removeItem(at: root) }
        let seed = FakeSeed(Self.previousSeed)
        let gate = Gate()
        let session = WalletSession(variant: .regtest, location: deviceB) { _, _, _, _, _ in
            await gate.enter()
            throw OpenRefused()
        }
        _ = try await setUp(seed: seed, session: session)
        try makeBarkProperties(at: deviceB)

        // A restore died while unpacking, before its journal: plaintext left.
        let transaction = RestoreTransaction(location: deviceB)
        try transaction.prepare()
        try Data("decrypted".utf8).write(to: transaction.work.appending(path: "db.sqlite"))

        // A silent push started opening the wallet before the UI launched.
        let pushOpen = Task { try await session.ensureOpen(mnemonic: Self.previousSeed, runDaemon: false, appVersion: "test") }
        await gate.waitUntilEntered()
        #expect(try await WalletRestorer.recoverInterruptedRestore(session: session, location: deviceB, seed: seed) == .nothingPending)
        #expect(!FileManager.default.fileExists(atPath: transaction.work.path), "decrypted leftovers are swept anyway")
        await gate.release()
        _ = try? await pushOpen.value
    }

    /// Review finding: UI start and a push job (or two push jobs) recovering
    /// at once must both settle, not fail on each other's reservation.
    @Test func concurrentLaunchRecoveriesWaitForEachOther() async throws {
        let session = WalletSession(variant: .regtest, location: deviceB) { _, _, _, _, _ in throw OpenRefused() }
        let gate = Gate()
        let recovery = RestoreRecovery {
            let hold = try await session.reserve()
            await gate.enter()
            await session.release(hold)
            return .nothingPending
        }
        let first = Task { try await recovery.settle() }
        await gate.waitUntilEntered()
        let second = Task { try await recovery.settle() }
        try await Task.sleep(for: .milliseconds(20))
        await gate.release()
        #expect(try await first.value == .nothingPending)
        #expect(try await second.value == .nothingPending)
    }

    /// Review finding: a queued no-journal recovery must not reserve the
    /// session another launch is opening.
    @Test(.regtestStack) func recoveryWithoutAJournalNeverBlocksAnOpen() async throws {
        defer { try? FileManager.default.removeItem(at: root) }
        let seed = FakeSeed(Self.previousSeed)
        let session = WalletSession(variant: .regtest, location: deviceB) { _, _, _, _, _ in throw OpenRefused() }
        _ = try await setUp(seed: seed, session: session)
        try makeBarkProperties(at: deviceB)
        let transaction = RestoreTransaction(location: deviceB)
        try transaction.prepare()
        let count = 5000
        for index in 0..<count {
            try Data("plain".utf8).write(to: transaction.work.appending(path: "plain-\(index)"))
        }
        let samples = stride(from: 0, to: count, by: 250).map { transaction.work.appending(path: "plain-\($0)").path }
        let location = deviceB
        let recovery = Task.detached {
            try await WalletRestorer.recoverInterruptedRestore(session: session, location: location, seed: seed)
        }
        // Open while the recovery is deleting the leftovers: its critical window.
        let deadline = ContinuousClock.now + .seconds(10)
        while samples.allSatisfy({ FileManager.default.fileExists(atPath: $0) }), ContinuousClock.now < deadline {
            await Task.yield()
        }
        do {
            try await session.ensureOpen(mnemonic: Self.previousSeed, runDaemon: false, appVersion: "test")
            Issue.record("the refusing opener should have thrown")
        } catch {
            #expect(error is OpenRefused, "the open must get past the session, not find it busy: \(error)")
        }
        #expect(try await recovery.value == .nothingPending)
        #expect(!FileManager.default.fileExists(atPath: transaction.work.path))
    }

    @Test func launchRecoveryLeavesAnActiveRestoresFilesAlone() async throws {
        defer { try? FileManager.default.removeItem(at: root) }
        let session = WalletSession(variant: .regtest, location: deviceB) { _, _, _, _, _ in throw OpenRefused() }
        let transaction = RestoreTransaction(location: deviceB)
        try transaction.prepare()
        try Data("downloading".utf8).write(to: transaction.work.appending(path: "backup.noahbackup"))

        let restoreHold = try await session.reserve()
        #expect(try await WalletRestorer.recoverInterruptedRestore(
            session: session, location: deviceB, seed: FakeSeed("seed")) == .nothingPending)
        #expect(FileManager.default.fileExists(atPath: transaction.work.path))
        await session.release(restoreHold)
    }

    /// Enough of a bark database to pass the session's read-only preflight.
    private func makeBarkProperties(at location: WalletDataLocation) throws {
        try? FileManager.default.removeItem(at: location.database)
        var db: OpaquePointer?
        guard sqlite3_open(location.database.path, &db) == SQLITE_OK else { throw OpenRefused() }
        defer { sqlite3_close(db) }
        let sql = """
        CREATE TABLE bark_properties (id TEXT PRIMARY KEY, network TEXT NOT NULL, fingerprint TEXT NOT NULL);
        INSERT INTO bark_properties (id, network, fingerprint) VALUES ('1', 'regtest', 'abcd1234');
        """
        guard sqlite3_exec(db, sql, nil, nil, nil) == SQLITE_OK else { throw OpenRefused() }
    }

    @Test(.regtestStack) func restoreCommitsTheSeedAndLeavesTheSessionReopenable() async throws {
        defer { try? FileManager.default.removeItem(at: root) }
        let seed = FakeSeed(Self.previousSeed)
        let session = WalletSession(variant: .regtest, location: deviceB)
        let (restorer, mnemonic) = try await setUp(seed: seed, session: session)

        let outcome = try await restorer.restore(mnemonic: mnemonic)
        #expect(outcome.backupId == "b1")
        #expect(seed.current == mnemonic)
        #expect(deviceBWallet != "previous wallet")
        expectNoRestoreLeftovers(settingsPending: true)
        // The open check released the datadir lock.
        #expect(await !session.isOpen)
        try await session.ensureOpen(mnemonic: mnemonic, runDaemon: false, appVersion: "test")
        try await session.close()
    }

    @Test(.regtestStack) func failedSeedWritePutsThePreviousWalletBack() async throws {
        defer { try? FileManager.default.removeItem(at: root) }
        let seed = FakeSeed(Self.previousSeed)
        seed.failWrites()
        let (restorer, mnemonic) = try await setUp(seed: seed)

        await #expect(throws: KeychainPassword.WriteError.self) { try await restorer.restore(mnemonic: mnemonic) }
        expectPreviousWalletBack(seed)
    }

    @Test(.regtestStack) func seedWriteThatLandedDespiteAnErrorCommits() async throws {
        defer { try? FileManager.default.removeItem(at: root) }
        let seed = FakeSeed(Self.previousSeed)
        seed.failWrites(landing: true)
        let (restorer, mnemonic) = try await setUp(seed: seed)

        _ = try await restorer.restore(mnemonic: mnemonic)
        #expect(seed.current == mnemonic)
        #expect(deviceBWallet != "previous wallet")
        expectNoRestoreLeftovers(settingsPending: true)
    }

    @Test(.regtestStack) func unknownSeedStateIsLeftForLaunchRecovery() async throws {
        defer { try? FileManager.default.removeItem(at: root) }
        let seed = FakeSeed(Self.previousSeed)
        seed.failWrites(thenLock: true)
        let session = WalletSession(variant: .regtest, location: deviceB)
        let (restorer, mnemonic) = try await setUp(seed: seed, session: session)

        await #expect(throws: WalletRestorer.Failure.seedStateUnknown) { try await restorer.restore(mnemonic: mnemonic) }
        #expect(deviceB.restorePending)
        await #expect(throws: WalletSession.OpenError.restorePending) {
            try await session.ensureOpen(mnemonic: Self.previousSeed, runDaemon: false, appVersion: "test")
        }
        // Next launch, still locked: wait. Unlocked, old seed: undo.
        #expect(try await WalletRestorer.recoverInterruptedRestore(session: session, location: deviceB, seed: seed) == .waitingForKeychain)
        seed.makeUnavailable(false)
        #expect(try await WalletRestorer.recoverInterruptedRestore(session: session, location: deviceB, seed: seed) == .rolledBack)
        expectPreviousWalletBack(seed)
    }

    @Test(.regtestStack) func unreadableKeychainChangesNothing() async throws {
        defer { try? FileManager.default.removeItem(at: root) }
        let seed = FakeSeed(Self.previousSeed)
        seed.makeUnavailable()
        let (restorer, mnemonic) = try await setUp(seed: seed)

        await #expect(throws: WalletRestorer.Failure.secureStorageUnavailable) { try await restorer.restore(mnemonic: mnemonic) }
        seed.makeUnavailable(false)
        expectPreviousWalletBack(seed)
    }

    @Test(.regtestStack) func downloadThatDoesNotMatchTheServerRecordIsRejected() async throws {
        defer { try? FileManager.default.removeItem(at: root) }
        let seed = FakeSeed(Self.previousSeed)
        let (restorer, mnemonic) = try await setUp(seed: seed, reportedSha256: String(repeating: "0", count: 64))

        await #expect(throws: WalletRestorer.Failure.downloadMismatch) { try await restorer.restore(mnemonic: mnemonic) }
        expectPreviousWalletBack(seed)
    }
}
