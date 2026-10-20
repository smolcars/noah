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
        if mode == "truncate" {
            // Recovery retains keys, but a partial JSON envelope must still block startup.
            #expect(throws: (any Error).self) { try SettingsStorage(rootPath: root.path) }
        } else {
            let store = try SettingsStorage(rootPath: root.path)
            #expect(!Self.rnKeys(store).isEmpty)
            #expect(store.string(forKey: PersistedWalletState.key) != nil)
        }
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

    @Test func nativeSuspensionIsNeverReplacedByTheOriginalSnapshot() throws {
        let root = try Self.copyFixture(); defer { Self.close(root); Self.clean(root) }
        let store = try SettingsStorage(rootPath: root.path)
        var wallet = try #require(store.persistedState(PersistedWalletState.self, key: PersistedWalletState.key))
        wallet.isWalletSuspended = true
        try store.setPersistedState(wallet, key: PersistedWalletState.key, version: 0)
        Self.close(root)
        try Self.corrupt(root, mode: "empty")
        #expect(throws: (any Error).self) { try SettingsStorage(rootPath: root.path) }
        #expect(!MMKVUpgradeBackup(root: root, instanceID: Self.id).restored)
    }

    @Test(arguments: ["undecodable", "missing"])
    func recurringEnvelopeAndAdmittedKeyLossBlock(mode: String) throws {
        let root = try Self.copyFixture(); defer { Self.close(root); Self.clean(root) }
        if mode == "missing" {
            let store = try SettingsStorage(rootPath: root.path)
            try store.setDurably(#"{"state":{"schedules":{},"serverSyncPending":false},"version":0}"#, forKey: RecurringStore.key)
        }
        let raw = try #require(MMKV(mmapID: Self.id, rootPath: root.path))
        if mode == "missing" { raw.removeValue(forKey: RecurringStore.key) }
        else { raw.set(Data([0xff]), forKey: RecurringStore.key) }
        raw.sync(); raw.close()
        #expect(throws: (any Error).self) { try SettingsStorage(rootPath: root.path).requireCompatibleRNStores() }
    }

    @Test func unsupportedRecurringRowsRemainVisibleAndStopped() throws {
        let root = try Self.copyFixture(); defer { Self.close(root); Self.clean(root) }
        let raw = try #require(MMKV(mmapID: Self.id, rootPath: root.path))
        raw.set(#"{"state":{"schedules":{"future":{"label":"Saved rule","amountSat":1e99}},"serverSyncPending":false},"version":0}"#, forKey: RecurringStore.key)
        raw.sync(); raw.close()
        let store = try SettingsStorage(rootPath: root.path)
        try store.requireCompatibleRNStores()
        let row = try #require(RecurringStore(settings: store).load().schedules["future"])
        #expect(row.unsupportedStorage && row.status == "needs_attention")
    }

    @Test func laterCRCRecoveryBlocksEvenWhenRNKeysSurvive() throws {
        let root = try Self.copyFixture(); defer { Self.close(root); Self.clean(root) }
        _ = try SettingsStorage(rootPath: root.path)
        Self.close(root); try Self.corrupt(root, mode: "crc")
        #expect(throws: (any Error).self) { try SettingsStorage(rootPath: root.path) }
    }

    @Test func laterMissingWalletKeyBlocksInsteadOfResumingOnboarding() throws {
        let root = try Self.copyFixture(); defer { Self.close(root); Self.clean(root) }
        _ = try SettingsStorage(rootPath: root.path)
        let raw = try #require(MMKV(mmapID: Self.id, rootPath: root.path))
        raw.removeValue(forKey: PersistedWalletState.key); raw.sync(); raw.close()
        #expect(throws: (any Error).self) { try SettingsStorage(rootPath: root.path) }
    }

    @Test func presentButUndecodableWalletValueBlocksValidationAndWrites() throws {
        let root = try Self.copyFixture(); defer { Self.close(root); Self.clean(root) }
        let file = root.appending(path: Self.id)
        var bytes = try Data(contentsOf: file)
        let value = try #require(bytes.range(of: Data(#"{"state":{"isInitialized"#.utf8), options: .backwards))
        bytes[value.lowerBound + 3] = 0xff
        try bytes.write(to: file)
        try Self.corrupt(root, mode: "crc")
        let raw = try #require(MMKV(mmapID: Self.id, rootPath: root.path))
        #expect(raw.contains(key: PersistedWalletState.key))
        #expect(raw.string(forKey: PersistedWalletState.key) == nil)
        raw.close()
        #expect(throws: (any Error).self) { try SettingsStorage(rootPath: root.path) }
    }

    @Test func backupDirectoriesAreExcludedFromSystemBackup() throws {
        let root = try Self.copyFixture(); defer { Self.close(root); Self.clean(root) }
        let backup = MMKVUpgradeBackup(root: root, instanceID: Self.id)
        try backup.prepare()
        #expect(try backup.directory.resourceValues(forKeys: [.isExcludedFromBackupKey]).isExcludedFromBackup == true)
        // A failed pair copy leaves staging in place; it must be excluded too.
        try FileManager.default.removeItem(at: backup.directory)
        try FileManager.default.removeItem(at: root.appending(path: Self.id + ".crc"))
        #expect(throws: (any Error).self) { try backup.prepare() }
        let staging = URL(fileURLWithPath: backup.directory.path + ".pending")
        #expect(try staging.resourceValues(forKeys: [.isExcludedFromBackupKey]).isExcludedFromBackup == true)
    }

    @Test func sharedStorageLaunchDecisions() throws {
        struct Case: Decodable { let name: String; let initialWallet: String; let afterNativeWallet: String?; let expected: String }
        struct Fixture: Decodable { let cases: [Case] }
        let url = Self.fixture.deletingLastPathComponent().appending(path: "mmkv-launch-state.json")
        for vector in try JSONDecoder().decode(Fixture.self, from: Data(contentsOf: url)).cases {
            let root = try Self.copyFixture(); defer { Self.close(root); Self.clean(root) }
            let raw = try #require(MMKV(mmapID: Self.id, rootPath: root.path))
            func apply(_ state: String) {
                if state == "absent" { raw.removeValue(forKey: PersistedWalletState.key) }
                else if state == "undecodable" { raw.set(Data([0xff]), forKey: PersistedWalletState.key) }
                else { raw.set("{\"state\":{\"isInitialized\":\(state == "initialized")},\"version\":0}", forKey: PersistedWalletState.key) }
                raw.sync()
            }
            apply(vector.initialWallet); raw.close()
            let outcome: String
            do {
                let store = try SettingsStorage(rootPath: root.path)
                if let after = vector.afterNativeWallet {
                    if after == "absent" || after == "undecodable" {
                        let live = try #require(MMKV(mmapID: Self.id, rootPath: root.path))
                        if after == "absent" { live.removeValue(forKey: PersistedWalletState.key) }
                        else { live.set(Data([0xff]), forKey: PersistedWalletState.key) }
                        live.sync()
                    } else {
                        try store.setPersistedState(PersistedWalletState(isInitialized: after == "initialized"), key: PersistedWalletState.key, version: 0)
                    }
                }
                try store.requireCompatibleRNStores()
                outcome = store.persistedState(PersistedWalletState.self, key: PersistedWalletState.key)?.isInitialized == true ? "existingWallet" : "onboarding"
            } catch { outcome = "incompatibleStorage" }
            #expect(outcome == vector.expected, "\(vector.name)")
        }
    }

    @Test func unavailableRetirementMarkerStopsNativeMutationAndCanRetry() throws {
        let root = try Self.copyFixture(); defer { Self.close(root); Self.clean(root) }
        let store = try SettingsStorage(rootPath: root.path)
        let backup = MMKVUpgradeBackup(root: root, instanceID: Self.id)
        let original = store.string(forKey: PersistedWalletState.key)
        let marker = try Data(contentsOf: backup.retirementFile)
        try FileManager.default.removeItem(at: backup.retirementFile)
        try FileManager.default.createDirectory(at: backup.retirementFile, withIntermediateDirectories: false)
        #expect(throws: (any Error).self) {
            try store.setPersistedState(PersistedWalletState(isInitialized: false), key: PersistedWalletState.key, version: 0)
        }
        #expect(store.string(forKey: PersistedWalletState.key) == original)
        try FileManager.default.removeItem(at: backup.retirementFile)
        try marker.write(to: backup.retirementFile)
        try store.setPersistedState(PersistedWalletState(isInitialized: false), key: PersistedWalletState.key, version: 0)
        #expect(store.persistedState(PersistedWalletState.self, key: PersistedWalletState.key)?.isInitialized == false)
    }

    @Test func deliberateDeletionDoesNotResurrectTheBackup() throws {
        let root = try Self.copyFixture(); defer { Self.close(root); Self.clean(root) }
        let store = try SettingsStorage(rootPath: root.path)
        let backup = MMKVUpgradeBackup(root: root, instanceID: Self.id)
        let staging = URL(fileURLWithPath: backup.directory.path + ".pending")
        try FileManager.default.createDirectory(at: staging, withIntermediateDirectories: true)
        try Data([1]).write(to: staging.appending(path: "old"))
        try store.removeAllDurably(); Self.close(root)
        #expect(!FileManager.default.fileExists(atPath: backup.directory.path))
        #expect(!FileManager.default.fileExists(atPath: staging.path))
        #expect(FileManager.default.fileExists(atPath: backup.retirementFile.path))
        let reopened = try SettingsStorage(rootPath: root.path)
        #expect(Self.rnKeys(reopened).isEmpty)
        #expect(try !backup.hasContent)
        try reopened.setPersistedState(PersistedWalletState(isInitialized: true), key: PersistedWalletState.key, version: 0)
        Self.close(root)
        let reonboarded = try SettingsStorage(rootPath: root.path)
        #expect(reonboarded.persistedState(PersistedWalletState.self, key: PersistedWalletState.key)?.isInitialized == true)
        #expect(!FileManager.default.fileExists(atPath: backup.directory.path))
    }

    /// RN's Delete Wallet unlinked the MMKV folder while open, so a wallet re-created in
    /// that session has no wallet-storage. Absence at first native open is compatible:
    /// the existing wallet resumes onboarding (isInitialized is not true).
    @Test func missingWalletSettingsResumeOnboardingInsteadOfBlocking() throws {
        let root = try Self.copyFixture(); defer { Self.close(root); Self.clean(root) }
        let raw = try #require(MMKV(mmapID: Self.id, rootPath: root.path))
        raw.removeValue(forKey: PersistedWalletState.key); raw.sync(); raw.close()
        let store = try SettingsStorage(rootPath: root.path)
        try store.requireCompatibleRNStores()
        #expect(store.persistedState(PersistedWalletState.self, key: PersistedWalletState.key)?.isInitialized != true)
        try store.setPersistedState(PersistedWalletState(isInitialized: false), key: PersistedWalletState.key, version: 0)
        try store.requireCompatibleRNStores()
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
