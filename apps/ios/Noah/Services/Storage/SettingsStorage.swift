import Foundation
import MMKV

/// App settings and small persisted state, in the same MMKV instance the
/// React Native app used (`noah-wallet-storage` under `Documents/mmkv`,
/// single process, unencrypted). Each zustand store is one key holding a
/// JSON string `{"state": {...}, "version": N}`; the native app keeps that
/// format so existing values are read without a migration step.
///
/// Not for secrets: those live in the Keychain.
final class SettingsStorage: @unchecked Sendable {
    // MMKV instances are internally synchronized.
    private var mmkv: MMKV?
    private static let access = NSRecursiveLock()
    private var backup: MMKVUpgradeBackup?
    private let rootPath: String
    private let storeID: String
    private let quarantine: MMKVQuarantine
    private var recoveryPending = false

    static let instanceID = "noah-wallet-storage"

    static var defaultRootPath: String {
        URL.documentsDirectory.appending(path: "mmkv", directoryHint: .isDirectory).path
    }

    /// Opened after `bootstrap()`; first access must come after launch.
    static let shared: SettingsStorage = {
        do {
            return try SettingsStorage(rootPath: defaultRootPath)
        } catch is RecoveryRequired {
            return SettingsStorage(unavailable: (), recoveryPending: true)
        } catch {
            AppLog("mmkv").e("Settings unavailable; original files preserved. Restart Noah to retry or contact support.")
            return SettingsStorage(unavailable: ())
        }
    }()

    struct OpenError: Error {}
    struct RecoveryRequired: Error {}
    private init(unavailable: Void, recoveryPending: Bool = false) {
        mmkv = nil; backup = nil
        rootPath = Self.defaultRootPath; storeID = Self.instanceID
        quarantine = MMKVQuarantine(root: URL(fileURLWithPath: rootPath), store: storeID)
        self.recoveryPending = recoveryPending
    }
    private static let handler = RecoveryHandler()

    nonisolated(unsafe) private static var isBootstrapped = false

    /// MMKV must be initialized once, on the main thread, before any instance
    /// is opened. Call from `application(_:didFinishLaunchingWithOptions:)`,
    /// which also runs for background (push) launches.
    @MainActor
    static func bootstrap() {
        guard !isBootstrapped else { return }
        try? FileManager.default.createDirectory(
            atPath: defaultRootPath, withIntermediateDirectories: true)
        MMKV.initialize(rootDir: defaultRootPath, logLevel: .warning, handler: handler)
        isBootstrapped = true
    }

    convenience init(rootPath: String, instanceID: String = SettingsStorage.instanceID) throws {
        try self.init(rootPath: rootPath, instanceID: instanceID, acknowledgingRecovery: false)
    }
    private init(rootPath: String, instanceID: String, acknowledgingRecovery: Bool) throws {
        Self.access.lock(); defer { Self.access.unlock() }
        precondition(Self.isBootstrapped, "SettingsStorage.bootstrap() must run first")
        try FileManager.default.createDirectory(
            atPath: rootPath, withIntermediateDirectories: true)
        let backup = MMKVUpgradeBackup(root: URL(fileURLWithPath: rootPath), instanceID: instanceID)
        let quarantine = MMKVQuarantine(root: URL(fileURLWithPath: rootPath), store: instanceID)
        try MMKVIntegrity.quarantineBeforeOpen(root: URL(fileURLWithPath: rootPath), store: instanceID, quarantine: quarantine)
        try backup.prepare()
        try MMKVIntegrity.quarantineBeforeOpen(root: URL(fileURLWithPath: rootPath), store: instanceID, quarantine: quarantine)
        Self.handler.begin(quarantine)
        defer { Self.handler.end() }
        let retiredKeys = try backup.retiredKeys
        let snapshot = retiredKeys == nil ? try Self.snapshotInventory(backup, instanceID: instanceID, quarantine: quarantine) : nil
        let generation = Self.handler.generation
        guard var opened = MMKV(mmapID: instanceID, rootPath: rootPath) else { throw OpenError() }
        do {
            if let snapshot {
                let valid = (try? Self.validate(opened, requiredKeys: snapshot.keys)) != nil && snapshot.readable
                let recovered = Self.handler.generation != generation
                if !valid || (quarantine.exists && (!backup.restored || recovered)) {
                    opened.close()
                    guard !backup.restored else { throw OpenError() }
                    try backup.restore()
                    try MMKVIntegrity.quarantineBeforeOpen(root: URL(fileURLWithPath: rootPath), store: instanceID, quarantine: quarantine)
                    AppLog("mmkv").w("Restoring pre-native settings backup after incomplete recovery")
                    guard let restored = MMKV(mmapID: instanceID, rootPath: rootPath) else { throw OpenError() }
                    opened = restored
                }
                try Self.validate(opened, requiredKeys: snapshot.keys)
                guard snapshot.readable else { throw OpenError() }
            } else { try Self.validate(opened, requiredKeys: retiredKeys ?? []) }
            try quarantine.requireDurable()
            if retiredKeys != nil, quarantine.exists, !acknowledgingRecovery { throw RecoveryRequired() }
            try backup.retire(keys: Self.rnKeys(opened))
            try quarantine.clear()
        } catch { opened.close(); throw error }
        self.mmkv = opened
        self.backup = backup
        self.quarantine = quarantine
        self.rootPath = rootPath; self.storeID = instanceID
    }

    /// Called only by the foreground launch gate after the user chooses Continue.
    @MainActor
    static func acknowledgeRecovery(rootPath: String, instanceID: String = SettingsStorage.instanceID) throws -> SettingsStorage {
        let marker = MMKVQuarantine(root: URL(fileURLWithPath: rootPath), store: instanceID)
        let backup = MMKVUpgradeBackup(root: URL(fileURLWithPath: rootPath), instanceID: instanceID)
        guard marker.exists, try backup.retiredKeys != nil else { throw OpenError() }
        return try SettingsStorage(rootPath: rootPath, instanceID: instanceID, acknowledgingRecovery: true)
    }

    @MainActor
    func acknowledgeRecovery() throws {
        let admitted = try Self.acknowledgeRecovery(rootPath: rootPath, instanceID: storeID)
        Self.access.lock(); defer { Self.access.unlock() }
        mmkv = admitted.mmkv; backup = admitted.backup; recoveryPending = false
    }

    private static func rnKeys(_ store: MMKV) -> [String] {
        RNStoreCodec.storeKeys.filter { store.contains(key: $0) }
    }
    private struct SnapshotInventory { let keys: [String]; let readable: Bool }
    private static func snapshotInventory(_ backup: MMKVUpgradeBackup, instanceID: String, quarantine: MMKVQuarantine) throws -> SnapshotInventory {
        guard try backup.hasContent else { return SnapshotInventory(keys: [], readable: true) }
        return try backup.inspectSnapshot { root in
            try MMKVIntegrity.quarantineBeforeOpen(root: root, store: instanceID, quarantine: quarantine)
            guard let snapshot = MMKV(mmapID: instanceID, rootPath: root.path) else { throw OpenError() }
            defer { snapshot.close() }
            let keys = rnKeys(snapshot)
            return SnapshotInventory(keys: keys, readable: !keys.isEmpty && (try? validate(snapshot, requiredKeys: keys)) != nil)
        }
    }

    private static func validate(_ store: MMKV, requiredKeys: [String]) throws {
        guard requiredKeys.allSatisfy({ store.contains(key: $0) }) else { throw OpenError() }
        for key in RNStoreCodec.storeKeys where key != PersistedEsploraState.key {
            if store.contains(key: key) {
                guard let raw = store.string(forKey: key) else { throw OpenError() }
                if key == RecurringStore.key { _ = try RecurringStore.decodeEnvelope(raw) }
                else { _ = try RNStoreCodec.decode(raw, key: key) }
            }
        }
    }

    private func admitMutation(key: String? = nil) throws {
        try requireCompatibleRNStores()
        guard let mmkv else { throw OpenError() }
        var keys = Self.rnKeys(mmkv)
        if let key, RNStoreCodec.storeKeys.contains(key), !keys.contains(key) { keys.append(key) }
        try backup?.retire(keys: keys)
    }

    func requireHealthy() throws {
        Self.access.lock(); defer { Self.access.unlock() }
        guard let mmkv else {
            if recoveryPending { throw RecoveryRequired() }
            throw OpenError()
        }
        try Self.validate(mmkv, requiredKeys: backup?.retiredKeys ?? [])
        try quarantine.requireDurable()
        if quarantine.exists { throw RecoveryRequired() }
    }

    func string(forKey key: String) -> String? {
        quarantine.exists ? nil : mmkv?.string(forKey: key)
    }

    func set(_ value: String, forKey key: String) {
        do { try setDurably(value, forKey: key) }
        catch { AppLog("mmkv").e("Settings write stopped: storage unavailable") }
    }

    /// Financial automation must observe write failures and flush the before-send marker.
    func setDurably(_ value: String, forKey key: String) throws {
        Self.access.lock(); defer { Self.access.unlock() }
        try admitMutation(key: key)
        guard let mmkv, mmkv.set(value, forKey: key) else { throw OpenError() }
        mmkv.sync()
        guard mmkv.string(forKey: key) == value else { throw OpenError() }
    }

    func remove(forKey key: String) {
        Self.access.lock(); defer { Self.access.unlock() }
        do { try admitMutation(); mmkv?.removeValue(forKey: key) }
        catch { AppLog("mmkv").e("Settings removal stopped: storage unavailable") }
    }

    /// Every store, as RN's wallet deletion wiped `Documents/mmkv`.
    func removeAll() {
        do { try removeAllDurably() }
        catch { AppLog("mmkv").e("Settings deletion stopped: backup marker unavailable") }
    }

    func removeAllDurably() throws {
        Self.access.lock(); defer { Self.access.unlock() }
        guard let mmkv else { throw OpenError() }
        try backup?.markReset()
        mmkv.clearAll()
        mmkv.sync()
        try backup?.deleteSnapshot()
    }

    /// Reads a zustand-persisted store. Returns `nil` when the key is absent
    /// or does not decode; callers fall back to their defaults.
    func persistedState<State: Decodable>(_ type: State.Type, key: String) -> State? {
        guard let json = string(forKey: key), let data = json.data(using: .utf8) else {
            return nil
        }
        if RNStoreCodec.schemas[key] != nil {
            guard let checked = try? RNStoreCodec.decode(json, key: key).json() else { return nil }
            return try? JSONDecoder().decode(PersistedEnvelope<State>.self, from: Data(checked.utf8)).state
        }
        return try? JSONDecoder().decode(PersistedEnvelope<State>.self, from: data).state
    }

    /// Writes a zustand-compatible envelope so the value round-trips.
    func setPersistedState<State: Encodable>(_ state: State, key: String, version: Int) throws {
        let data = try JSONEncoder().encode(PersistedEnvelope(state: state, version: version))
        let encoded = String(decoding: data, as: UTF8.self)
        let value = RNStoreCodec.schemas[key] == nil ? encoded
            : try RNStoreCodec.merging(encoded, into: string(forKey: key), key: key)
        try setDurably(value, forKey: key)
    }

    /// An unreadable consumed store blocks startup and headless wallet work;
    /// defaults must never overwrite a future or corrupt RN envelope.
    func requireCompatibleRNStores() throws { try requireHealthy() }
}

private final class RecoveryHandler: NSObject, MMKVHandler, @unchecked Sendable {
    private let lock = NSLock()
    private var active: MMKVQuarantine?
    private var recoveries: UInt64 = 0
    var generation: UInt64 { lock.withLock { recoveries } }
    func begin(_ quarantine: MMKVQuarantine) { lock.withLock { active = quarantine } }
    func end() { lock.withLock { active = nil } }
    func onMMKVCRCCheckFail(_ mmapID: String) -> MMKVRecoverStrategic {
        lock.withLock { active?.recordRecovery(); recoveries &+= 1 }
        AppLog("mmkv").w("CRC check failed; recovering settings")
        return .onErrorRecover
    }
    func onMMKVFileLengthError(_ mmapID: String) -> MMKVRecoverStrategic {
        lock.withLock { active?.recordRecovery(); recoveries &+= 1 }
        AppLog("mmkv").w("File length check failed; recovering settings")
        return .onErrorRecover
    }
    func mmkvLog(with level: MMKVLogLevel, file: UnsafePointer<CChar>!, line: Int32,
                 func funcname: UnsafePointer<CChar>!, message: String!) {
        // MMKV messages can contain keys/values. Only fixed diagnostics reach the redacting logger.
        if level == .error { AppLog("mmkv").e("MMKV reported a storage error") }
        else if level == .warning { AppLog("mmkv").w("MMKV reported a storage warning") }
    }
}

/// zustand `persist` + `createJSONStorage` value layout.
struct PersistedEnvelope<State> {
    var state: State
    var version: Int?
}

extension PersistedEnvelope: Decodable where State: Decodable {}
extension PersistedEnvelope: Encodable where State: Encodable {}
