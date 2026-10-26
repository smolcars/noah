import Foundation
import MMKV
import Testing
@testable import Noah

/// RN bytes are an immutable import source, never a native settings backend.
@Suite(.serialized) @MainActor
struct MMKVRecoveryTests {
    static let id = SettingsStorage.instanceID
    static let fixture = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        .deletingLastPathComponent().deletingLastPathComponent().appending(path: "fixtures/rn-mmkv")
    static func copyFixture() throws -> URL {
        let root = FileManager.default.temporaryDirectory.appending(path: "native-import-\(UUID())/mmkv")
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        for name in [id, id + ".crc"] {
            try FileManager.default.copyItem(at: fixture.appending(path: name), to: root.appending(path: name))
        }
        return root
    }
    static func close(_ root: URL) {} // SettingsStorage holds no MMKV handle.
    static func clean(_ root: URL) { try? FileManager.default.removeItem(at: root.deletingLastPathComponent()) }
    static func corrupt(_ root: URL, mode: String) throws {
        let crc = root.appending(path: id + ".crc")
        var data = try Data(contentsOf: crc)
        if mode == "length" { for i in 28..<32 { data[i] = 255 } } else { data[0] ^= 255 }
        try data.write(to: crc)
    }
    static func writeRN(_ root: URL, values: [String: String]) throws {
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        MMKV.initialize(rootDir: root.path, logLevel: .none)
        let rn = try #require(MMKV(mmapID: id, rootPath: root.path))
        rn.clearAll()
        for (key, raw) in values { rn.set(raw, forKey: key) }
        rn.sync(); rn.close()
    }
    private func bytes(_ root: URL) throws -> [Data] {
        try [Self.id, Self.id + ".crc"].map { try Data(contentsOf: root.appending(path: $0)) }
    }
    @Test func importsRealRNBytesAndNativeWritesNeverTouchLiveFiles() throws {
        let root = try Self.copyFixture(); defer { Self.clean(root) }
        let before = try bytes(root)
        let store = try SettingsStorage(rootPath: root.path)
        #expect(store.persistedState(PersistedWalletState.self, key: PersistedWalletState.key)?.isInitialized == true)
        #expect(try RecurringStore(settings: store).load().schedules.isEmpty)
        #expect(try !store.nativeFile.load().recurringNoticePending)
        for i in 0..<20 { try store.setDurably(String(i), forKey: "native-counter") }
        #expect(try bytes(root) == before)
        let backup = MMKVUpgradeBackup(root: root, instanceID: Self.id)
        #expect(try bytes(backup.directory) == before)
        #expect(try SettingsStorage(rootPath: root.path).string(forKey: "native-counter") == "19")
    }
    @Test func existingSnapshotWinsOverLaterLiveRNChanges() throws {
        let root = try Self.copyFixture(); defer { Self.clean(root) }
        try MMKVUpgradeBackup(root: root, instanceID: Self.id).prepare()
        try Self.writeRN(root, values: [PersistedWalletState.key: #"{"state":{"isInitialized":false},"version":0}"#])
        let live = try bytes(root)
        let imported = try SettingsStorage(rootPath: root.path)
        #expect(imported.persistedState(PersistedWalletState.self, key: PersistedWalletState.key)?.isInitialized == true)
        #expect(try bytes(root) == live)
    }

    @Test func damagedSnapshotImportsNothingAndDoesNotRecover() throws {
        for mode in ["crc", "length"] {
            let root = try Self.copyFixture(); defer { Self.clean(root) }
            try Self.corrupt(root, mode: mode)
            let before = try bytes(root)
            let store = try SettingsStorage(rootPath: root.path)
            #expect(try store.nativeFile.load().values == [EsploraSettings.historyUnavailableKey: "1"])
            #expect(try store.takeUpgradeNotices() == [SettingsStorage.settingsNotice])
            #expect(try bytes(root) == before)
        }
    }
    @Test func freshInstallCreatesNoMMKVAndDeletionNeverReimports() throws {
        let root = FileManager.default.temporaryDirectory.appending(path: "native-fresh-\(UUID())/mmkv")
        defer { Self.clean(root) }
        let store = try SettingsStorage(rootPath: root.path)
        #expect(!FileManager.default.fileExists(atPath: root.path))
        #expect(try store.takeUpgradeNotices().isEmpty)
        try Self.writeRN(root, values: [PersistedWalletState.key: #"{"state":{"isInitialized":false},"version":0}"#])
        #expect(try SettingsStorage(rootPath: root.path).string(forKey: PersistedWalletState.key) == nil)
        try store.removeAllDurably()
        #expect(!FileManager.default.fileExists(atPath: root.appending(path: Self.id).path))
        #expect(!FileManager.default.fileExists(atPath: MMKVUpgradeBackup(root: root, instanceID: Self.id).directory.path))
        try store.setPersistedState(PersistedWalletState(isInitialized: true), key: PersistedWalletState.key, version: 0)
        let reopened = try SettingsStorage(rootPath: root.path)
        #expect(reopened.persistedState(PersistedWalletState.self, key: PersistedWalletState.key)?.isInitialized == true)
        #expect(reopened.nativeFile.exists)
    }
    @Test func noticeIsShownOnceAndDamagedNativeFileUsesDefaultsWithoutReimport() throws {
        let root = try Self.copyFixture(); defer { Self.clean(root) }
        try Self.writeRN(root, values: [RecurringStore.key: #"{"state":{"schedules":{"old":{"id":"old"}}},"version":0}"#])
        let store = try SettingsStorage(rootPath: root.path)
        #expect(try store.takeUpgradeNotices().contains(SettingsStorage.recurringNotice))
        #expect(try SettingsStorage(rootPath: root.path).takeUpgradeNotices().isEmpty)
        try Data("damaged generation".utf8).write(to: store.nativeFile.file)
        let reset = try SettingsStorage(rootPath: root.path)
        #expect(reset.string(forKey: PersistedWalletState.key) == nil)
        #expect(try reset.takeUpgradeNotices() == [SettingsStorage.settingsNotice])
        #expect(try reset.takeUpgradeNotices().isEmpty)
    }
    @Test func sharedSettingsLaunchPolicyAndBackgroundImport() throws {
        struct Input: Decodable { let wallet: String }
        struct Expected: Decodable { let outcome: String; let settingsNotice: Bool }
        let cases = try Fixtures.load("mmkv-launch-state", input: Input.self, expected: Expected.self)
        #expect(cases.count == 8)
        for item in cases {
            let root = FileManager.default.temporaryDirectory.appending(path: "native-launch-\(UUID())/mmkv")
            defer { Self.clean(root) }
            let raw: String? = switch item.input.wallet {
            case "initialized", "damaged": #"{"state":{"isInitialized":true},"version":0}"#
            case "unfinished": #"{"state":{"isInitialized":false},"version":0}"#
            case "undecodable": "broken"
            case "missingFlag": #"{"state":{},"version":0}"#
            default: nil
            }
            if item.input.wallet != "absent" {
                try Self.writeRN(root, values: raw.map { [PersistedWalletState.key: $0] } ?? [:])
                if item.input.wallet == "damaged" { try Self.corrupt(root, mode: "crc") }
            }
            // Same importer is used on a push launch; no foreground acknowledgement exists.
            let settings = try SettingsStorage(rootPath: root.path)
            let wallet = settings.persistedState(PersistedWalletState.self, key: PersistedWalletState.key)
            #expect((wallet?.isInitialized == false ? "onboarding" : "existingWallet") == item.expected.outcome, "\(item.name)")
            #expect(try settings.nativeFile.load().settingsNoticePending == item.expected.settingsNotice, "\(item.name)")
            let location = WalletDataLocation(variant: .regtest, documentsDirectory: root.deletingLastPathComponent())
            try FileManager.default.createDirectory(at: location.directory, withIntermediateDirectories: true)
            try Data("wallet DB unaffected".utf8).write(to: location.database)
            let seed = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
            if item.expected.outcome == "existingWallet" {
                #expect(try HeadlessWalletGate.mnemonic(location: location, settings: settings, readSeed: { .found(seed) }) == seed)
            } else {
                #expect(throws: AppServices.WalletSuspended.self) { try HeadlessWalletGate.mnemonic(location: location, settings: settings, readSeed: { .found(seed) }) }
            }
        }
    }
    @Test func recurringImportSyncsEmptyOnlyAfterAdmissionAndRetriesFailure() async throws {
        let h = try RecurringExecutorTests.Harness()
        defer { try? FileManager.default.removeItem(at: h.directory.deletingLastPathComponent()) }
        let imported = SettingsStorage.importValues([RecurringStore.key: #"{"state":{"schedules":{"old":{"id":"old"}}},"version":0}"#])
        try h.settings.setDurably(try #require(imported.values[RecurringStore.key]), forKey: RecurringStore.key)
        h.stopped.withLock { $0 = true }
        await h.executor.execute()
        #expect(h.sentEntries.isEmpty)
        h.stopped.withLock { $0 = false }
        h.syncHook = { _ in throw SettingsStorage.OpenError() }
        await h.executor.execute()
        #expect(try h.store.load().pending)
        h.syncHook = nil
        await h.executor.execute()
        #expect(!h.sentEntries.isEmpty && h.sentEntries.allSatisfy { $0.isEmpty })
        #expect(try !h.store.load().pending)
        #expect(h.calls.withLock { $0 } == 0)
    }

    @Test func concurrentBackgroundAndForegroundImportPublishOnce() async throws {
        let root = try Self.copyFixture(); defer { Self.clean(root) }
        try Self.writeRN(root, values: [RecurringStore.key: #"{"state":{"schedules":{"old":{"id":"old"}}},"version":0}"#])
        let stores = try await withThrowingTaskGroup(of: SettingsStorage.self) { group in
            for _ in 0..<8 { group.addTask { try SettingsStorage(rootPath: root.path) } }
            var results: [SettingsStorage] = []
            for try await store in group { results.append(store) }
            return results
        }
        var notices = 0
        for store in stores { if try !store.takeUpgradeNotices().isEmpty { notices += 1 } }
        #expect(notices == 1)
    }

    @Test func importsEachValidKeyIndependentlyAndSkipsAllRNRecurring() throws {
        let wallet = #"{"state":{"isInitialized":true},"version":0}"#
        let state = SettingsStorage.importValues(["wallet-storage": wallet, "privacy-storage": "broken", "unknown-RN-key": "raw-string", RecurringStore.key: "broken"])
        #expect(state.values["wallet-storage"] == wallet)
        #expect(state.values["privacy-storage"] == nil)
        #expect(state.values["unknown-RN-key"] == "raw-string")
        #expect(state.values[RecurringStore.key] == SettingsStorage.emptyRecurring)
        #expect(state.settingsNoticePending && state.recurringNoticePending)
    }
}
