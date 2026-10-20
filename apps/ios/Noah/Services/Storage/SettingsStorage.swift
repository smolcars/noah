import Foundation
import MMKV

/// Native settings retain RN's envelope strings. MMKV is only read from a verified scratch snapshot.
final class SettingsStorage: @unchecked Sendable {
    private static let access = NSRecursiveLock()
    static let instanceID = "noah-wallet-storage"
    static var defaultRootPath: String { URL.documentsDirectory.appending(path: "mmkv").path }
    static let shared = try! SettingsStorage(rootPath: defaultRootPath)
    struct OpenError: Error {}
    private let root: URL
    private let storeID: String
    let nativeFile: NativeSettingsFile
    // All access, including the shared fallback for each file, holds `access`.
    nonisolated(unsafe) private static var fallbacks: [String: NativeSettingsFile.State] = [:]
    private var fallback: NativeSettingsFile.State? {
        get { Self.fallbacks[nativeFile.file.path] }
        set { Self.fallbacks[nativeFile.file.path] = newValue }
    }

    @MainActor static func bootstrap() {} // MMKV is initialized only if a snapshot needs importing.

    init(rootPath: String, instanceID: String = SettingsStorage.instanceID) throws {
        Self.access.lock(); defer { Self.access.unlock() }
        root = URL(fileURLWithPath: rootPath); storeID = instanceID
        nativeFile = NativeSettingsFile(file: URL(fileURLWithPath: rootPath + ".native-settings.json"))
        do {
            try FileManager.default.createDirectory(at: root.deletingLastPathComponent(), withIntermediateDirectories: true)
            if nativeFile.exists || fallback != nil {
                // A damaged native generation never reimports RN or affects the wallet/seed.
                _ = load()
            } else {
                let backup = MMKVUpgradeBackup(root: root, instanceID: instanceID)
                let state: NativeSettingsFile.State
                do {
                    try backup.prepare()
                    state = try backup.hasContent ? backup.inspectSnapshot { scratch in
                        guard MMKVIntegrity.verifies(root: scratch, store: instanceID),
                              FileManager.default.fileExists(atPath: scratch.appending(path: instanceID).path),
                              FileManager.default.fileExists(atPath: scratch.appending(path: instanceID + ".crc").path)
                        else { throw OpenError() }
                        MMKV.initialize(rootDir: scratch.path, logLevel: .none)
                        guard let snapshot = MMKV(mmapID: instanceID, rootPath: scratch.path) else { throw OpenError() }
                        defer { snapshot.close() }
                        return Self.importValues(Dictionary(uniqueKeysWithValues: (snapshot.allKeys() as? [String] ?? []).map {
                            ($0, snapshot.string(forKey: $0))
                        }))
                    } : .init()
                } catch { state = .init(settingsNoticePending: true) }
                fallback = state
                try persist(state)
            }
            if fallback != nil { try persist(load()) }
            try finishCleanup()
        } catch {
            if fallback == nil { fallback = .init(settingsNoticePending: true) }
        }
    }

    static let settingsNotice = "Some settings couldn't be carried over from the previous version, so they're back to their defaults: biometric lock is off, balances are visible, and currency and display settings are reset. Please review Settings."
    static let recurringNotice = "Recurring payments need to be set up again after this update."
    static let emptyRecurring = #"{"state":{"schedules":{},"serverSyncPending":true},"version":0}"#

    static func importValues(_ source: [String: String?]) -> NativeSettingsFile.State {
        var state = NativeSettingsFile.State(settingsNoticePending: source.isEmpty)
        for (key, raw) in source {
            if key == RecurringStore.key {
                // Never admit an RN schedule, even if its schema is unsupported.
                let schedules = raw.flatMap { try? RawObject($0) }.flatMap { $0.fields["state"] }
                    .flatMap { try? RawObject($0) }.flatMap { $0.fields["schedules"] }.flatMap { try? RawObject($0) }
                if schedules?.fields.isEmpty != true {
                    state.recurringNoticePending = true
                    state.values[key] = emptyRecurring
                }
                continue
            }
            guard let raw else { state.settingsNoticePending = true; continue }
            if RNStoreCodec.schemas[key] != nil, (try? RNStoreCodec.decode(raw, key: key)) == nil {
                state.settingsNoticePending = true
            } else { state.values[key] = raw }
        }
        return state
    }

    private func load() -> NativeSettingsFile.State {
        if let fallback { return fallback }
        do { return try nativeFile.load() }
        catch {
            let defaults = NativeSettingsFile.State(settingsNoticePending: true)
            fallback = defaults
            try? persist(defaults)
            return defaults
        }
    }
    private func persist(_ state: NativeSettingsFile.State) throws {
        try FileManager.default.createDirectory(at: root.deletingLastPathComponent(), withIntermediateDirectories: true)
        try nativeFile.save(state)
        fallback = nil
    }
    func requireHealthy() throws {
        Self.access.lock(); defer { Self.access.unlock() }
        _ = load()
        if fallback != nil { throw OpenError() }
    }
    func string(forKey key: String) -> String? {
        Self.access.lock(); defer { Self.access.unlock() }
        return load().values[key]
    }
    func set(_ value: String, forKey key: String) {
        do { try setDurably(value, forKey: key) }
        catch { AppLog("settings").e("Settings write failed") }
    }
    func setDurably(_ value: String, forKey key: String) throws {
        Self.access.lock(); defer { Self.access.unlock() }
        let previous = load()
        var state = previous; state.values[key] = value
        do { try persist(state) }
        catch { fallback = previous; throw error }
    }
    func remove(forKey key: String) {
        Self.access.lock(); defer { Self.access.unlock() }
        let previous = load()
        do { var state = previous; state.values.removeValue(forKey: key); try persist(state) }
        catch { fallback = previous; AppLog("settings").e("Settings removal failed") }
    }
    /// Called by Home only after it becomes visible. Durable flags survive background launches.
    func takeUpgradeNotices() throws -> [String] {
        Self.access.lock(); defer { Self.access.unlock() }
        var state = load()
        let notices = (state.settingsNoticePending ? [Self.settingsNotice] : []) + (state.recurringNoticePending ? [Self.recurringNotice] : [])
        guard !notices.isEmpty else { return [] }
        state.settingsNoticePending = false; state.recurringNoticePending = false
        fallback = state
        try? persist(state)
        return notices
    }
    func removeAll() { do { try removeAllDurably() } catch { AppLog("settings").e("Settings deletion failed") } }
    func removeAllDurably() throws {
        Self.access.lock(); defer { Self.access.unlock() }
        let previous = load()
        do { try persist(.init(cleanupPending: true)) }
        catch { fallback = previous; throw error }
        try finishCleanup()
    }
    private func finishCleanup() throws {
        guard load().cleanupPending else { return }
        let fm = FileManager.default
        if fm.fileExists(atPath: root.path) { try fm.removeItem(at: root) }
        try RestoreTransaction.fullSync(root.deletingLastPathComponent())
        try MMKVUpgradeBackup(root: root, instanceID: storeID).deleteSnapshot()
        try persist(.init()) // Keep the import marker through re-onboarding.
    }

    /// Only committed restore completion may publish flags in memory after a failed write.
    @discardableResult func completeRestore() -> Bool {
        Self.access.lock(); defer { Self.access.unlock() }
        var state = load()
        var wallet = persistedState(PersistedWalletState.self, key: PersistedWalletState.key) ?? .init()
        wallet.isInitialized = true; wallet.isWalletLoaded = true
        do {
            let data = try JSONEncoder().encode(PersistedEnvelope(state: wallet, version: PersistedWalletState.version))
            state.values[PersistedWalletState.key] = try RNStoreCodec.merging(String(decoding: data, as: UTF8.self), into: state.values[PersistedWalletState.key], key: PersistedWalletState.key)
            try persist(state)
            return true
        } catch { fallback = state; return false }
    }

    func beginOnboarding() throws {
        var wallet = persistedState(PersistedWalletState.self, key: PersistedWalletState.key) ?? .init()
        wallet.isInitialized = false
        try setPersistedState(wallet, key: PersistedWalletState.key, version: PersistedWalletState.version)
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

    /// Native writes remain observable by financial automation.
    func requireCompatibleRNStores() throws { try requireHealthy() }
}

/// zustand `persist` + `createJSONStorage` value layout.
struct PersistedEnvelope<State> {
    var state: State
    var version: Int?
}
extension PersistedEnvelope: Decodable where State: Decodable {}
extension PersistedEnvelope: Encodable where State: Encodable {}
