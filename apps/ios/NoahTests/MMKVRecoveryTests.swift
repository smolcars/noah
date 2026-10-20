import Foundation
import MMKV
import Testing
@testable import Noah

@Suite(.serialized) @MainActor
struct MMKVRecoveryTests {
    static let id = SettingsStorage.instanceID
    static let fixture = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        .deletingLastPathComponent().deletingLastPathComponent().appending(path: "fixtures/rn-mmkv")

    static func copyFixture() throws -> URL {
        SettingsStorage.bootstrap()
        let root = FileManager.default.temporaryDirectory.appending(path: "mmkv-recovery-\(UUID())/mmkv")
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        for name in [id, id + ".crc"] {
            try FileManager.default.copyItem(at: fixture.appending(path: name), to: root.appending(path: name))
        }
        return root
    }
    static func close(_ root: URL) { MMKV(mmapID: id, rootPath: root.path)?.close() }
    static func clean(_ root: URL) { try? FileManager.default.removeItem(at: root.deletingLastPathComponent()) }
    static func corrupt(_ root: URL, mode: String) throws {
        let file = root.appending(path: id), crc = root.appending(path: id + ".crc")
        var data = try Data(contentsOf: file), meta = try Data(contentsOf: crc)
        // Invalidate both the current checksum and last-confirmed checksum to exercise the handler.
        meta[0] ^= 0xff
        for offset in 32..<40 { meta[offset] = 0xff }
        if mode == "length" { for offset in 0..<4 { data[offset] = 0xff; meta[28 + offset] = 0xff } }
        if mode == "truncate" {
            let size = (0..<4).reduce(0) { $0 | Int(meta[28 + $1]) << ($1 * 8) }
            data = data.prefix(4 + size - 16)
        }
        if mode == "empty" { data = Data(repeating: 0, count: data.count) }
        try data.write(to: file); try meta.write(to: crc)
    }
    static func rnKeys(_ store: SettingsStorage) -> [String] {
        RNStoreCodec.schemas.keys.filter { store.string(forKey: $0) != nil }.sorted()
    }

    @Test(arguments: ["crc", "length", "truncate"])
    func recoversCorruptRNFilesWithoutDiscarding(mode: String) throws {
        let root = try Self.copyFixture(); defer { Self.close(root); Self.clean(root) }
        try Self.corrupt(root, mode: mode)
        let original = try Data(contentsOf: root.appending(path: Self.id))
        let store = try SettingsStorage(rootPath: root.path)
        #expect(!Self.rnKeys(store).isEmpty)
        #expect(store.string(forKey: PersistedWalletState.key) != nil)
        let backup = MMKVUpgradeBackup(root: root, instanceID: Self.id)
        let exact = try Data(contentsOf: backup.directory.appending(path: Self.id)) == original
        #expect(exact)
    }

    @Test func restoresImmutableBackupOnceAndBlocksRepeatedLoss() throws {
        let root = try Self.copyFixture(); defer { Self.close(root); Self.clean(root) }
        let backup = MMKVUpgradeBackup(root: root, instanceID: Self.id)
        try backup.prepare()
        let original = try Data(contentsOf: root.appending(path: Self.id))
        try Self.corrupt(root, mode: "empty")
        let store = try SettingsStorage(rootPath: root.path)
        #expect(!Self.rnKeys(store).isEmpty && backup.restored)
        Self.close(root)
        try Self.corrupt(root, mode: "empty")
        #expect(throws: (any Error).self) { try SettingsStorage(rootPath: root.path) }
        let exact = try Data(contentsOf: backup.directory.appending(path: Self.id)) == original
        #expect(exact)
    }

    @Test func unrecoverableBackupBlocksInsteadOfOnboarding() throws {
        let root = try Self.copyFixture(); defer { Self.close(root); Self.clean(root) }
        try Self.corrupt(root, mode: "empty")
        #expect(throws: (any Error).self) { try SettingsStorage(rootPath: root.path) }
        let backup = MMKVUpgradeBackup(root: root, instanceID: Self.id)
        #expect(backup.restored)
        #expect(throws: (any Error).self) { try SettingsStorage(rootPath: root.path) }
    }

    @Test func missingPairPreventsMMKVOpenAndRetriesNextLaunch() throws {
        let root = try Self.copyFixture(); defer { Self.close(root); Self.clean(root) }
        let crc = root.appending(path: Self.id + ".crc"), bytes = try Data(contentsOf: crc)
        try FileManager.default.removeItem(at: crc)
        #expect(throws: (any Error).self) { try SettingsStorage(rootPath: root.path) }
        #expect(!FileManager.default.fileExists(atPath: crc.path))
        try bytes.write(to: crc)
        let store = try SettingsStorage(rootPath: root.path)
        #expect(!Self.rnKeys(store).isEmpty)
    }

    @Test func interruptedRestoreFinishesBeforeMapping() throws {
        let root = try Self.copyFixture(); defer { Self.close(root); Self.clean(root) }
        let backup = MMKVUpgradeBackup(root: root, instanceID: Self.id)
        try backup.prepare()
        try Data([1]).write(to: backup.directory.appending(path: "restore-pending"))
        try Self.corrupt(root, mode: "empty")
        let store = try SettingsStorage(rootPath: root.path)
        #expect(!Self.rnKeys(store).isEmpty && backup.restored)
        #expect(!FileManager.default.fileExists(atPath: backup.directory.appending(path: "restore-pending").path))
    }

    @Test func deliberateDeletionDoesNotResurrectTheBackup() throws {
        let root = try Self.copyFixture(); defer { Self.close(root); Self.clean(root) }
        let store = try SettingsStorage(rootPath: root.path)
        try store.removeAllDurably(); Self.close(root)
        let reopened = try SettingsStorage(rootPath: root.path)
        #expect(Self.rnKeys(reopened).isEmpty)
        #expect(try !MMKVUpgradeBackup(root: root, instanceID: Self.id).hasContent)
    }

    @Test func missingWalletSettingsBlockExistingWalletButAnExplicitIncompleteStoreCanResume() throws {
        let root = try Self.copyFixture(); defer { Self.close(root); Self.clean(root) }
        let store = try SettingsStorage(rootPath: root.path)
        store.remove(forKey: PersistedWalletState.key)
        #expect(throws: (any Error).self) { try store.requireCompatibleRNStores(walletDataExists: true) }
        try store.requireCompatibleRNStores(walletDataExists: false)
        try store.setPersistedState(PersistedWalletState(isInitialized: false), key: PersistedWalletState.key, version: 0)
        try store.requireCompatibleRNStores(walletDataExists: true)
        #expect(store.persistedState(PersistedWalletState.self, key: PersistedWalletState.key)?.isInitialized == false)
    }
}

/// Run alone in a separate test process; never replaces the handler in the ordinary suite.
@Suite(.enabled(if: ProcessInfo.processInfo.environment["NOAH_MMKV_DISCARD_DEMO"] == "1")) @MainActor
struct MMKVDiscardDemonstrationTests {
    private final class Discard: NSObject, MMKVHandler {
        func onMMKVCRCCheckFail(_ mmapID: String) -> MMKVRecoverStrategic { .onErrorDiscard }
        func onMMKVFileLengthError(_ mmapID: String) -> MMKVRecoverStrategic { .onErrorDiscard }
        func mmkvLog(with level: MMKVLogLevel, file: UnsafePointer<CChar>!, line: Int32,
                     func funcname: UnsafePointer<CChar>!, message: String!) {}
    }
    @Test func defaultStrategyDiscardsTheSameCorruptRNBytes() throws {
        let root = try MMKVRecoveryTests.copyFixture(); defer { MMKVRecoveryTests.close(root); MMKVRecoveryTests.clean(root) }
        try MMKVRecoveryTests.corrupt(root, mode: "crc")
        MMKV.register(Discard())
        let mmkv = try #require(MMKV(mmapID: SettingsStorage.instanceID, rootPath: root.path))
        #expect(!RNStoreCodec.schemas.keys.contains { mmkv.contains(key: $0) })
    }
}
