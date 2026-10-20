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
    private let mmkv: MMKV?
    private static let access = NSRecursiveLock()
    private let backup: MMKVUpgradeBackup?

    static let instanceID = "noah-wallet-storage"

    static var defaultRootPath: String {
        URL.documentsDirectory.appending(path: "mmkv", directoryHint: .isDirectory).path
    }

    /// Opened after `bootstrap()`; first access must come after launch.
    static let shared: SettingsStorage = {
        do {
            return try SettingsStorage(rootPath: defaultRootPath)
        } catch {
            AppLog("mmkv").e("Settings unavailable; original files preserved. Restart Noah to retry or contact support.")
            return SettingsStorage(unavailable: ())
        }
    }()

    struct OpenError: Error {}
    private init(unavailable: Void) { mmkv = nil; backup = nil }
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

    init(rootPath: String, instanceID: String = SettingsStorage.instanceID) throws {
        Self.access.lock(); defer { Self.access.unlock() }
        precondition(Self.isBootstrapped, "SettingsStorage.bootstrap() must run first")
        try FileManager.default.createDirectory(
            atPath: rootPath, withIntermediateDirectories: true)
        let backup = MMKVUpgradeBackup(root: URL(fileURLWithPath: rootPath), instanceID: instanceID)
        try backup.prepare()
        let retiredKeys = try backup.retiredKeys
        let expectedContent = retiredKeys == nil ? try backup.hasContent : false
        let recoveryGeneration = Self.handler.generation
        guard var opened = MMKV(mmapID: instanceID, rootPath: rootPath) else {
            throw OpenError()
        }
        if expectedContent, !Self.hasRNKeys(opened) {
            opened.close()
            guard !backup.restored else { throw OpenError() }
            try backup.restore()
            AppLog("mmkv").w("Restoring pre-native settings backup after empty recovery")
            guard let recovered = MMKV(mmapID: instanceID, rootPath: rootPath) else { throw OpenError() }
            opened = recovered
            guard Self.hasRNKeys(opened) else { opened.close(); throw OpenError() }
        }
        do {
            try Self.validate(opened, requiredKeys: retiredKeys ?? [])
            if retiredKeys != nil, Self.handler.generation != recoveryGeneration { throw OpenError() }
            try backup.retire(keys: Self.rnKeys(opened))
        } catch { opened.close(); throw error }
        self.mmkv = opened
        self.backup = backup
    }

    private static func rnKeys(_ store: MMKV) -> [String] {
        RNStoreCodec.schemas.keys.filter { store.contains(key: $0) }
    }
    private static func hasRNKeys(_ store: MMKV) -> Bool { !rnKeys(store).isEmpty }

    private static func validate(_ store: MMKV, requiredKeys: [String]) throws {
        guard requiredKeys.allSatisfy({ store.contains(key: $0) }) else { throw OpenError() }
        for key in RNStoreCodec.schemas.keys where key != PersistedEsploraState.key {
            if let raw = store.string(forKey: key) { _ = try RNStoreCodec.decode(raw, key: key) }
        }
    }

    private func admitMutation(key: String? = nil) throws {
        try requireCompatibleRNStores()
        guard let mmkv else { throw OpenError() }
        var keys = Self.rnKeys(mmkv)
        if let key, RNStoreCodec.schemas[key] != nil, !keys.contains(key) { keys.append(key) }
        try backup?.retire(keys: keys)
    }

    func requireHealthy() throws { guard mmkv != nil else { throw OpenError() } }

    func string(forKey key: String) -> String? {
        mmkv?.string(forKey: key)
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
    func requireCompatibleRNStores() throws {
        Self.access.lock(); defer { Self.access.unlock() }
        guard let mmkv else { throw OpenError() }
        try Self.validate(mmkv, requiredKeys: backup?.retiredKeys ?? [])
    }
}

private final class RecoveryHandler: NSObject, MMKVHandler, @unchecked Sendable {
    private let lock = NSLock()
    private var recoveries: UInt64 = 0
    var generation: UInt64 { lock.withLock { recoveries } }
    func onMMKVCRCCheckFail(_ mmapID: String) -> MMKVRecoverStrategic {
        lock.withLock { recoveries &+= 1 }
        AppLog("mmkv").w("CRC check failed; recovering settings")
        return .onErrorRecover
    }
    func onMMKVFileLengthError(_ mmapID: String) -> MMKVRecoverStrategic {
        lock.withLock { recoveries &+= 1 }
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
