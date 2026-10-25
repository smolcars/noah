import BarkBoltFFI
import Foundation
import SQLite3
import Testing
@testable import Noah

@MainActor struct HeadlessWalletTests {
    private let phrase = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"

    @Test(arguments: ["restore", "settingsCompletion", "deletion", "suspended", "uninitialized", "missing", "noSeed", "locked", "invalid", "conflict"])
    func unsafeStateNeverChangesSeedOrFiles(state: String) throws {
        SettingsStorage.bootstrap()
        let directory = FileManager.default.temporaryDirectory.appending(path: "headless-\(UUID())")
        defer { try? FileManager.default.removeItem(at: directory) }
        let location = WalletDataLocation(variant: .regtest, documentsDirectory: directory)
        let storageID = UUID().uuidString
        let settings = try SettingsStorage(rootPath: directory.appending(path: "mmkv").path, instanceID: storageID)
        try FileManager.default.createDirectory(at: location.directory, withIntermediateDirectories: true)
        let database = Data("existing database stays unchanged".utf8)
        if state != "missing" { try database.write(to: location.database) }
        var wallet = PersistedWalletState(isInitialized: true, isWalletSuspended: false)
        if state == "suspended" { wallet.isWalletSuspended = true }
        if state == "uninitialized" { wallet.isInitialized = false }
        try settings.setPersistedState(wallet, key: PersistedWalletState.key, version: PersistedWalletState.version)
        var marker: URL?
        if state == "restore" {
            try FileManager.default.createDirectory(at: location.restoreDirectory, withIntermediateDirectories: true)
            marker = location.restoreDirectory.appending(path: RestoreTransaction.journalName)
        } else if state == "settingsCompletion" {
            try FileManager.default.createDirectory(at: location.restoreDirectory, withIntermediateDirectories: true)
            marker = RestoreTransaction(location: location).settingsCompletion
        } else if state == "deletion" { marker = location.deletionMarker }
        if let marker { try Data("pending".utf8).write(to: marker) }
        let seed: KeychainPassword.ReadResult = switch state {
        case "noSeed": .notFound
        case "locked": .unavailable(-25308)
        case "invalid": .found("invalid")
        case "conflict": .unavailable(-25299)
        default: .found(phrase)
        }
        #expect(throws: (any Error).self) {
            _ = try HeadlessWalletGate.mnemonic(location: location, settings: settings, readSeed: {
                return seed
            })
        }
        if state == "missing" { #expect(!location.databaseExists) }
        else { #expect(try Data(contentsOf: location.database) == database) }
        if let marker { #expect(try Data(contentsOf: marker) == Data("pending".utf8)) }
    }

    @Test func admissionReadsOriginalSeedWithoutNormalizationWrite() throws {
        SettingsStorage.bootstrap()
        let directory = FileManager.default.temporaryDirectory.appending(path: "headless-read-\(UUID())")
        defer { try? FileManager.default.removeItem(at: directory) }
        let location = WalletDataLocation(variant: .regtest, documentsDirectory: directory)
        let settings = try SettingsStorage(rootPath: directory.appending(path: "mmkv").path, instanceID: UUID().uuidString)
        try FileManager.default.createDirectory(at: location.directory, withIntermediateDirectories: true)
        try Data().write(to: location.database)
        try settings.setPersistedState(PersistedWalletState(isInitialized: true), key: PersistedWalletState.key, version: 0)
        let original = "  " + phrase.uppercased() + "\n"
        #expect(try HeadlessWalletGate.mnemonic(location: location, settings: settings, readSeed: { .found(original) }) == original)
    }

    @Test func jobOwnershipExcludesForegroundAndOtherJobsUntilCancelledSettlement() async throws {
        let session = WalletSession(variant: .regtest)
        let workGate = RecurringReservationTests.Gate(), settleGate = RecurringReservationTests.Gate()
        let work = Task {
            try await session.withBackgroundAccess({ await workGate.wait() }, settle: { await settleGate.wait() })
        }
        let limit = ContinuousClock.now + .seconds(5)
        while !(await workGate.entered), ContinuousClock.now < limit { await Task.yield() }
        try #require(await workGate.entered)
        work.cancel()
        await #expect(throws: WalletSession.OpenError.busy) { _ = try await session.reserve() }
        await #expect(throws: WalletSession.OpenError.busy) { try await session.withBackgroundAccess {} }
        await #expect(throws: WalletSession.OpenError.busy) {
            try await session.ensureOpen(mnemonic: "unused", runDaemon: true, appVersion: "test")
        }
        await workGate.release()
        while !(await settleGate.entered), ContinuousClock.now < limit { await Task.yield() }
        try #require(await settleGate.entered)
        await #expect(throws: WalletSession.OpenError.busy) { _ = try await session.reserve() }
        await settleGate.release()
        try await work.value
        let reservation = try await session.reserve()
        await session.release(reservation)
    }

    @Test func reservationExcludesBackgroundEntryAndOwnerCanUseSession() async throws {
        let session = WalletSession(variant: .regtest)
        let held = try await session.reserve()
        await #expect(throws: WalletSession.OpenError.busy) { try await session.withBackgroundAccess {} }
        await session.release(held)
        try await session.withBackgroundAccess {
            let reservation = try await session.reserve()
            await #expect(throws: WalletSession.OpenError.busy) {
                try await session.createNew(reservation, mnemonic: "unused", runDaemon: false, appVersion: "test")
            }
            await session.release(reservation)
        }
    }

    @Test func backgroundOpenNeverCreatesOrStartsDaemonEvenIfCallerRequestsIt() async throws {
        let directory = FileManager.default.temporaryDirectory.appending(path: "headless-open-\(UUID())")
        defer { try? FileManager.default.removeItem(at: directory) }
        let location = WalletDataLocation(variant: .regtest, documentsDirectory: directory)
        try FileManager.default.createDirectory(at: location.directory, withIntermediateDirectories: true)
        var database: OpaquePointer?
        try #require(sqlite3_open(location.database.path, &database) == SQLITE_OK)
        try #require(sqlite3_exec(database, "CREATE TABLE bark_properties (network TEXT, fingerprint TEXT); INSERT INTO bark_properties VALUES ('regtest', 'test');", nil, nil, nil) == SQLITE_OK)
        sqlite3_close(database)
        struct Opened: Error {}
        let session = WalletSession(variant: .regtest, location: location) { _, _, daemon, _, create in
            #expect(!daemon); #expect(!create)
            throw Opened()
        }
        await #expect(throws: Opened.self) {
            try await session.withBackgroundAccess {
                try await session.ensureOpen(mnemonic: "unused", runDaemon: true, appVersion: "test")
            }
        }
        #expect(!(await session.isOpen))
        let held = try await session.reserve()
        await session.release(held)
    }

    @Test(.regtestStack) func cancelledBackgroundOpenClosesRealHandlesAfterSettlement() async throws {
        let directory = FileManager.default.temporaryDirectory.appending(path: "headless-real-\(UUID())")
        defer { try? FileManager.default.removeItem(at: directory) }
        let location = WalletDataLocation(variant: .regtest, documentsDirectory: directory)
        let session = WalletSession(variant: .regtest, location: location) { mnemonic, datadir, daemon, _, create in
            #expect(!daemon)
            let config = WalletConfiguration.base(for: .regtest).sdkConfig(userAgent: "test")
            let onchain = create
                ? try await OnchainWallet.default(network: .regtest, mnemonic: mnemonic, config: config, datadir: datadir)
                : try await OnchainWallet.openExisting(network: .regtest, mnemonic: mnemonic, config: config, datadir: datadir)
            let wallet = try await Wallet.openWithOnchain(network: .regtest, mnemonicOrSeed: mnemonic, config: config,
                args: WalletOpenArgs(runDaemon: false, datadir: datadir, createIfNotExists: create,
                                     createWithoutServer: true, skipRecovery: true), onchain: onchain)
            return .init(wallet: wallet, onchain: onchain)
        }
        // Foreground setup only. The tested background branch must open this existing DB.
        let mnemonic = try generateMnemonic()
        try await session.createNew(mnemonic: mnemonic, runDaemon: false, appVersion: "test")
        try await session.close()
        let gate = RecurringReservationTests.Gate()
        let work = Task {
            try await session.withBackgroundAccess {
                try await session.ensureOpen(mnemonic: mnemonic, runDaemon: false, appVersion: "test")
                await Task { await gate.wait() }.value
            }
        }
        let limit = ContinuousClock.now + .seconds(5)
        while !(await gate.entered), ContinuousClock.now < limit { await Task.yield() }
        try #require(await gate.entered)
        #expect(await session.isOpen)
        work.cancel()
        await #expect(throws: WalletSession.OpenError.busy) { try await session.close() }
        await gate.release()
        try await work.value
        #expect(!(await session.isOpen))
        try await session.ensureOpen(mnemonic: mnemonic, runDaemon: false, appVersion: "test")
        #expect(try await session.summary() != nil)
        try await session.close()
    }


    @Test func sharedActivityCoversJobAndCancelledSettlement() async throws {
        let services = AppServices(variant: .regtest, session: WalletSession(variant: .regtest))
        let gate = RecurringReservationTests.Gate()
        let work = Task { try await services.withBackgroundWallet { await gate.wait() } }
        let limit = ContinuousClock.now + .seconds(5)
        while !(await gate.entered), ContinuousClock.now < limit { await Task.yield() }
        try #require(await gate.entered)
        #expect(await WalletBackgroundActivity.shared.isRunning)
        work.cancel()
        #expect(await WalletBackgroundActivity.shared.isRunning)
        await gate.release()
        try await work.value
        #expect(!(await WalletBackgroundActivity.shared.isRunning))
    }

    struct OpenedAfterExpiry: Error {}

    @Test(arguments: ["budget", "deadline", "cancellation"], [true, false])
    func expiredShieldedTaskCannotStartWalletOpen(expiry: String, ensure: Bool) async throws {
        let directory = FileManager.default.temporaryDirectory.appending(path: "review-open-\(UUID())")
        defer { try? FileManager.default.removeItem(at: directory) }
        let location = WalletDataLocation(variant: .regtest, documentsDirectory: directory)
        try FileManager.default.createDirectory(at: location.directory, withIntermediateDirectories: true)
        var database: OpaquePointer?
        try #require(sqlite3_open(location.database.path, &database) == SQLITE_OK)
        try #require(sqlite3_exec(database, "CREATE TABLE bark_properties (network TEXT, fingerprint TEXT); INSERT INTO bark_properties VALUES ('regtest', 'test');", nil, nil, nil) == SQLITE_OK)
        sqlite3_close(database)
        let recorder = PushJobsTests.Recorder()
        let session = WalletSession(variant: .regtest, location: location) { _, _, _, _, _ in
            recorder.add("native open")
            throw OpenedAfterExpiry()
        }
        let budget = RecurringSubmissionBudget()
        await RecurringSubmissionBudget.$current.withValue(budget) {
            await #expect(throws: CancellationError.self) {
                try await session.withBackgroundAccess {
                    // Model OS revocation after ownership, before the actor admits the open.
                    if expiry == "budget" { budget.revoke() }
                    try await RecurringExecutionBudget.$deadline.withValue(expiry == "deadline" ? .now - .seconds(1) : nil) {
                        try await Task {
                            if expiry == "cancellation" { withUnsafeCurrentTask { $0?.cancel() } }
                            if ensure { try await session.ensureOpen(mnemonic: "unused", runDaemon: false, appVersion: "test") }
                            else { try await session.openExisting(mnemonic: "unused", runDaemon: false, appVersion: "test") }
                        }.value
                    }
                }
            }
        }
        #expect(recorder.all.isEmpty)
        let reservation = try await session.reserve()
        await session.release(reservation)
    }

}
