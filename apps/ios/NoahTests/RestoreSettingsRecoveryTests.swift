import Darwin
import Foundation
import Testing
@testable import Noah

private let restoreSettingsCheckpoints = ["journal", "previousMoved", "installed", "committing", "seed", "markerRemoved", "previousRemoved", "journalRemoved", "flags", "markerOnly", "stateOnly", "corrupt"]
    + (1...6).map { "initializing\($0)" } + (1...9).map { "settling\($0)" }

struct RestoreSettingsRecoveryTests {
    @Test(arguments: [false, true])
    func explicitResumeRetriesSettingsCompletion(stillFailing: Bool) async throws {
        let fm = FileManager.default
        let root = fm.temporaryDirectory.appending(path: "restore-resume-\(UUID())")
        let preferences = root.appending(path: "preferences")
        let settings = try SettingsStorage(rootPath: preferences.appending(path: "mmkv").path)
        let location = WalletDataLocation(variant: .regtest, documentsDirectory: root)
        let tx = RestoreTransaction(location: location)
        defer { chmod(preferences.path, 0o700); try? fm.removeItem(at: root) }
        try tx.prepare()
        try fm.createDirectory(at: tx.staged, withIntermediateDirectories: true)
        try Data("committed".utf8).write(to: tx.staged.appending(path: "db.sqlite"))
        try tx.begin(seed: "committed seed"); try tx.markCommitting(); try tx.finish()
        #expect(chmod(preferences.path, 0o500) == 0)
        tx.completeSettings(settings)
        #expect(tx.settingsPending)
        #expect(chmod(preferences.path, 0o700) == 0)
        let item = KeychainPassword(service: "com.noah.tests.restore-resume.\(UUID())", account: "noah")
        let session = WalletSession(variant: .regtest, location: location)
        let lifecycle = WalletLifecycle(dependencies: .init(session: session, location: location, seed: item,
            authToken: item, settings: settings, scratch: ScratchSpace(root: root.appending(path: "scratch")),
            appGroup: nil, deregister: {}))
        try await lifecycle.suspend()
        if stillFailing { #expect(chmod(preferences.path, 0o500) == 0) }
        do {
            try lifecycle.resume()
            #expect(!stillFailing)
            #expect(!tx.settingsPending)
            #expect(settings.persistedState(PersistedWalletState.self, key: PersistedWalletState.key)?.isWalletSuspended == false)
            #expect(RecurringAutomationAuthority(directory: location.directory).allowed())
        } catch {
            #expect(stillFailing)
            #expect(error.localizedDescription.contains("settings can't be saved yet"))
            #expect(tx.settingsPending)
            #expect(!RecurringAutomationAuthority(directory: location.directory).allowed())
            #expect(await !session.isOpen)
        }
    }

    @MainActor @Test func transactionSettlesBeforeFailedPreferenceCompletion() async throws {
        let fm = FileManager.default
        let root = fm.temporaryDirectory.appending(path: "restore-completion-\(UUID())")
        let preferences = root.appending(path: "preferences")
        let settings = try SettingsStorage(rootPath: preferences.appending(path: "mmkv").path)
        let location = WalletDataLocation(variant: .regtest, documentsDirectory: root)
        let tx = RestoreTransaction(location: location)
        try tx.prepare()
        try fm.createDirectory(at: tx.staged, withIntermediateDirectories: true)
        try Data("committed".utf8).write(to: tx.staged.appending(path: "db.sqlite"))
        try tx.begin(seed: "committed seed"); try tx.markCommitting()
        #expect(chmod(preferences.path, 0o500) == 0)
        defer { chmod(preferences.path, 0o700); try? fm.removeItem(at: root) }
        let completion = RestoreCompletion(loadWalletState: { .init() }, saveWalletState: {
            try settings.setPersistedState($0, key: PersistedWalletState.key, version: 0)
        }, seedRestored: { _, _ in }, recover: { try tx.recover(seed: FakeSeed("committed seed")) }, openWallet: {})
        await #expect(throws: (any Error).self) {
            try await completion.finish(.init(backupId: nil, snapshotSha256: nil, cleanupPending: true))
        }
        #expect(!location.restorePending)
        #expect(try String(contentsOf: location.database, encoding: .utf8) == "committed")
    }

    @Test(arguments: restoreSettingsCheckpoints)
    func interruptedRestoreSettlesWithFailedSettings(step: String) async throws {
        try await exercise(step: step, root: FileManager.default.temporaryDirectory.appending(path: "restore-settings-\(UUID())"))
    }

    // Host runs stage, terminates the test host, then runs recover in a fresh process.
    @Test(.enabled(if: FileManager.default.fileExists(atPath: URL.documentsDirectory.appending(path: "restore-settings-control").path)))
    func processDeathRestoreWithFailedSettings() async throws {
        let control = try String(contentsOf: URL.documentsDirectory.appending(path: "restore-settings-control"), encoding: .utf8).split(separator: " ").map(String.init)
        try #require(control.count == 2)
        try await exercise(step: control[0], root: URL.documentsDirectory.appending(path: "restore-settings-process"), mode: control[1])
    }

    private func exercise(step: String, root: URL, mode: String = "all") async throws {
        let fm = FileManager.default
        let location = WalletDataLocation(variant: .regtest, documentsDirectory: root)
        let preferences = root.appending(path: "preferences")
        let settingsRoot = preferences.appending(path: "mmkv")
        let file = URL(fileURLWithPath: settingsRoot.path + ".native-settings.json")
        let tx = RestoreTransaction(location: location)
        let phrase = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        let committed = !["journal", "previousMoved", "installed", "committing"].contains(step)
        if mode != "recover" {
            try fm.createDirectory(at: preferences, withIntermediateDirectories: true)
            try SettingsStorage(rootPath: settingsRoot.path).beginOnboarding(recoveryPhraseBackedUp: true)
            try fm.createDirectory(at: location.directory, withIntermediateDirectories: true)
            try Data("old".utf8).write(to: location.database)
            try tx.prepare()
            try fm.createDirectory(at: tx.staged, withIntermediateDirectories: true)
            try Data("committed".utf8).write(to: tx.staged.appending(path: "db.sqlite"))
            try tx.begin(seed: phrase)
            if step == "journal" || step == "previousMoved" {
                try fm.moveItem(at: location.directory, to: tx.staged)
                if step == "journal" { try fm.moveItem(at: tx.previous, to: location.directory) }
            }
            if !["journal", "previousMoved", "installed"].contains(step) { try tx.markCommitting() }
            if ["markerRemoved", "previousRemoved"].contains(step) {
                try fm.removeItem(at: location.directory.appending(path: RestoreTransaction.markerName))
                if step == "previousRemoved" { try fm.removeItem(at: tx.previous) }
            }
            if step == "journalRemoved" || step == "flags" { try tx.finish() }
            // A readable unfinished flag must also be overridden when its completion write fails.
            if step != "flags" { try Data("damaged".utf8).write(to: file); #expect(chmod(file.path, 0) == 0) }
            #expect(chmod(preferences.path, 0o500) == 0)
            if ["markerOnly", "stateOnly", "corrupt"].contains(step) {
                let authority = RecurringAutomationAuthority(directory: location.directory)
                try authority.stop()
                switch step {
                case "markerOnly": try fm.removeItem(at: authority.file)
                case "stateOnly": try fm.removeItem(at: authority.marker)
                default: try Data("{".utf8).write(to: authority.file)
                }
            }
            if step.hasPrefix("initializing") || step.hasPrefix("settling") {
                let initializing = step.hasPrefix("initializing")
                let boundary = try #require(Int(step.dropFirst(initializing ? 12 : 8)))
                let count = RecurringExecutorTests.Locked(0)
                let sync: @Sendable (URL) throws -> Void = { target in
                    try RestoreTransaction.fullSync(target)
                    guard count.withLock({ $0 += 1; return $0 }) == boundary else { return }
                    if mode == "stage" {
                        try Data(step.utf8).write(to: root.appending(path: "ready"))
                        while true { Thread.sleep(forTimeInterval: 1) } // Host kills at the actual durable-write boundary.
                    }
                    throw CocoaError(.fileWriteUnknown)
                }
                if initializing { _ = try? RecurringAutomationAuthority(directory: location.directory, sync: sync).stop() }
                else { try? tx.finish(sync: sync) }
                #expect(count.withLock { $0 } == boundary)
            }
        }
        if mode == "stage" {
            try Data(step.utf8).write(to: root.appending(path: "ready"))
            // The host terminates this process with the on-disk checkpoint intact.
            while true { try await Task.sleep(for: .seconds(60)) }
        }
        defer {
            chmod(preferences.path, 0o700); chmod(file.path, 0o600)
            try? fm.removeItem(at: root)
        }
        let settings = try SettingsStorage(rootPath: settingsRoot.path)
        let session = WalletSession(variant: .regtest, location: location)
        let result = try await WalletRestorer.recoverInterruptedRestore(session: session, location: location,
            seed: FakeSeed(committed ? phrase : "old seed"))
        #expect(result == (["journalRemoved", "flags", "settling9"].contains(step) ? .nothingPending : committed ? .completed : .rolledBack))
        tx.completeSettings(settings)
        #expect(!location.restorePending)
        #expect(try String(contentsOf: location.database, encoding: .utf8) == (committed ? "committed" : "old"))
        if committed {
            #expect(settings.persistedState(PersistedWalletState.self, key: PersistedWalletState.key)?.isInitialized == true)
            #expect(tx.settingsPending)
            let authority = RecurringAutomationAuthority(directory: location.directory)
            #expect(!authority.allowed())
            try authority.suspension(false) // Valid STOPPED state, rather than a corrupt file rejecting execution.
            #expect(!authority.allowed())
            #expect(throws: (any Error).self) { try settings.requireHealthy() }
            #expect(throws: (any Error).self) {
                _ = try HeadlessWalletGate.mnemonic(location: location, settings: settings, readSeed: { .found(phrase) })
            }
            if step != "flags" { #expect(try settings.takeUpgradeNotices() == [SettingsStorage.settingsNotice]) }
            #expect(try settings.takeUpgradeNotices().isEmpty)
            #expect(chmod(preferences.path, 0o700) == 0); #expect(chmod(file.path, 0o600) == 0)
            let reopened = try SettingsStorage(rootPath: settingsRoot.path)
            tx.completeSettings(reopened)
            #expect(!tx.settingsPending)
            #expect(!RecurringAutomationAuthority(directory: location.directory).allowed())
            try reopened.requireHealthy()
            #expect(reopened.persistedState(PersistedWalletState.self, key: PersistedWalletState.key)?.isInitialized == true)
        }
    }
}
