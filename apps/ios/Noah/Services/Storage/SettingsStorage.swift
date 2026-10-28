import Foundation
import MMKV

/// Native settings retain RN's envelope strings. MMKV is only read from a verified scratch snapshot.
final class SettingsStorage: @unchecked Sendable {
    private static let access = NSRecursiveLock()
    static let instanceID = "noah-wallet-storage"
    static var defaultRootPath: String { URL.documentsDirectory.appending(path: "mmkv").path }
    static let shared = SettingsStorage(rootPath: defaultRootPath, instanceID: instanceID, file: NativeSettingsFile(file: fileURL(defaultRootPath)))
    struct OpenError: Error {}
    private let root: URL
    private let storeID: String
    let nativeFile: NativeSettingsFile
    // All access, including the shared fallback for each file, holds `access`.
    private nonisolated(unsafe) static var fallbacks: [String: NativeSettingsFile.State] = [:]
    private var fallback: NativeSettingsFile.State? {
        get { Self.fallbacks[nativeFile.file.path] }
        set { Self.fallbacks[nativeFile.file.path] = newValue }
    }
    /// The last verified or committed generation of each file and its stamp, so a read that finds the
    /// same bytes on disk skips the read, parse and hash. Any other write to the file changes the stamp.
    private nonisolated(unsafe) static var committed: [String: (stamp: NativeSettingsFile.Stamp, state: NativeSettingsFile.State)] = [:]

    @MainActor static func bootstrap() { /* MMKV is initialized only if a snapshot needs importing. */ }

    // swiftlint:disable:next unneeded_throws_rethrows - public API stays throwing; callers and tests already handle it
    convenience init(rootPath: String, instanceID: String = SettingsStorage.instanceID) throws {
        self.init(rootPath: rootPath, instanceID: instanceID, file: NativeSettingsFile(file: Self.fileURL(rootPath)))
    }
    #if DEBUG
        // swiftlint:disable unneeded_throws_rethrows - public API stays throwing; callers and tests already handle it
        /// Tests only: the file's fault injection (`NativeSettingsFile.step`).
        convenience init(rootPath: String, instanceID: String = SettingsStorage.instanceID, step: @escaping (String) throws -> Void) throws {
            // swiftlint:enable unneeded_throws_rethrows
            self.init(rootPath: rootPath, instanceID: instanceID, file: NativeSettingsFile(file: Self.fileURL(rootPath), step: step))
        }
    #endif
    private static func fileURL(_ rootPath: String) -> URL { URL(fileURLWithPath: rootPath + ".native-settings.json") }

    private init(rootPath: String, instanceID: String, file: NativeSettingsFile) {
        Self.access.lock(); defer { Self.access.unlock() }
        root = URL(fileURLWithPath: rootPath); storeID = instanceID
        nativeFile = file
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
                } catch { state = .init(values: [EsploraSettings.historyUnavailableKey: "1"], settingsNoticePending: true) }
                fallback = state
                try persist(state)
            }
            if fallback != nil { try persist(load()) }
            try finishCleanup()
        } catch {
            if fallback == nil { fallback = .init(values: [EsploraSettings.historyUnavailableKey: "1"], settingsNoticePending: true) }
        }
    }

    // swiftlint:disable:next line_length - one literal: translations.mjs reads copy as a single string
    static let settingsNotice = "Some settings couldn't be carried over from the previous version, so they're back to their defaults: biometric lock is off, balances are visible, and currency and display settings are reset. Please review Settings."
    static func settingsNotice(values: [String: String]) -> String {
        let wallet = values[PersistedWalletState.key].flatMap { try? RNStoreCodec.decode($0, key: PersistedWalletState.key).json() }
            .flatMap { try? JSONDecoder().decode(PersistedEnvelope<PersistedWalletState>.self, from: Data($0.utf8)).state }
        let privacy = values[PersistedPrivacyState.key].flatMap { try? RNStoreCodec.decode($0, key: PersistedPrivacyState.key).json() }
            .flatMap { try? JSONDecoder().decode(PersistedEnvelope<PersistedPrivacyState>.self, from: Data($0.utf8)).state }
        var defaults: [String] = []
        if wallet?.isBiometricsEnabled == nil { defaults.append("biometric lock is off") }
        if privacy?.isHomeBalanceHidden == nil { defaults.append("balances are visible") }
        guard !defaults.isEmpty else {
            return "Some display settings couldn't be carried over from the previous version and were reset. Please review Settings."
        }
        if values[PersistedProfileState.key] == nil { defaults.append("currency and display settings are reset") }
        let descriptions = defaults.count == 1 ? defaults[0] : defaults.dropLast()
            .joined(separator: ", ") + (defaults.count > 2 ? ", and " : " and ") + defaults[defaults.count - 1]
        return "Some settings couldn't be carried over from the previous version, so they're back to their defaults: \(descriptions). Please review Settings."
    }
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
        // Every write starts here under the lock, so it merges into what's on disk: a changed stamp reloads and
        // verifies first. Stamped before reading: a write in between leaves a stale stamp, so the next read loads again.
        let stamp = nativeFile.stamp
        if let stamp, let cached = Self.committed[nativeFile.file.path], cached.stamp == stamp { return cached.state }
        do {
            let state = try nativeFile.load()
            if let stamp { Self.committed[nativeFile.file.path] = (stamp, state) }
            return state
        } catch {
            let defaults = NativeSettingsFile.State(values: [EsploraSettings.historyUnavailableKey: "1"], settingsNoticePending: true)
            fallback = defaults
            try? persist(defaults)
            return defaults
        }
    }
    private func persist(_ state: NativeSettingsFile.State) throws {
        try FileManager.default.createDirectory(at: root.deletingLastPathComponent(), withIntermediateDirectories: true)
        let stamp = try nativeFile.save(state)
        fallback = nil
        // Write-through only once committed, under that generation's own stamp; without one the next load verifies.
        Self.committed[nativeFile.file.path] = stamp.map { ($0, state) }
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
        try setDurably([key: value])
    }
    private func setDurably(_ values: [String: String]) throws {
        Self.access.lock(); defer { Self.access.unlock() }
        let previous = load()
        var state = previous; state.values.merge(values) { _, new in new }
        do { try persist(state) }
        catch { fallback = previous; throw error }
    }
    /// User-selected safe fallback: foreground keeps it even when disk writes fail.
    func useDefaultEsplora(for variant: AppVariant) {
        Self.access.lock(); defer { Self.access.unlock() }
        var state = load()
        do {
            let retained = try EsploraSettings.retainingLogPrivacy(state.values)
            let history = try retained[EsploraSettings.key].flatMap { try JSONDecoder().decode(EsploraSettings.Record.self, from: Data($0.utf8)).logEndpoints }
            let record = EsploraSettings.Record(active: .init(variant: variant.rawValue), logEndpoints: history)
            state.values[EsploraSettings.key] = try String(json: JSONEncoder().encode(record))
            try persist(state)
        } catch { fallback = state }
    }
    func remove(forKey key: String) {
        Self.access.lock(); defer { Self.access.unlock() }
        let previous = load()
        do { var state = previous; state.values.removeValue(forKey: key); try persist(state) }
        catch { fallback = previous; AppLog("settings").e("Settings removal failed") }
    }
    /// Called by Home only after it becomes visible. Durable flags survive background launches.
    func takeUpgradeNotices() -> [String] {
        Self.access.lock(); defer { Self.access.unlock() }
        var state = load()
        let notices = (state.settingsNoticePending ? [Self.settingsNotice(values: state.values)] : []) +
            (state.recurringNoticePending ? [Self.recurringNotice] : [])
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
        do { try persist(.init(values: EsploraSettings.retainingLogPrivacy(previous.values), cleanupPending: true)) }
        catch { fallback = previous; throw error }
        try finishCleanup()
    }
    private func finishCleanup() throws {
        guard load().cleanupPending else { return }
        let fm = FileManager.default
        if fm.fileExists(atPath: root.path) { try fm.removeItem(at: root) }
        try RestoreTransaction.fullSync(root.deletingLastPathComponent())
        try MMKVUpgradeBackup(root: root, instanceID: storeID).deleteSnapshot()
        try persist(.init(values: EsploraSettings.retainingLogPrivacy(load().values))) // Keep log protection through re-onboarding.
    }

    /// Only committed restore completion may publish flags in memory after a failed write.
    @discardableResult func completeRestore() -> Bool {
        Self.access.lock(); defer { Self.access.unlock() }
        var state = load()
        var wallet = persistedState(PersistedWalletState.self, key: PersistedWalletState.key) ?? .init()
        wallet.isInitialized = true; wallet.isWalletLoaded = true
        do {
            let data = try JSONEncoder().encode(PersistedEnvelope(state: wallet, version: PersistedWalletState.version))
            state.values[PersistedWalletState.key] = try RNStoreCodec.merging(
                String(json: data),
                into: state.values[PersistedWalletState.key],
                key: PersistedWalletState.key)
            try persist(state)
            return true
        } catch { fallback = state; return false }
    }

    /// One commit before the seed exists. `false` for a new wallet, which never
    /// inherits an earlier wallet's flag; `true` only for a typed-phrase
    /// restore; nil leaves the flag as it is (a restore from the stored seed
    /// proves nothing about a backup).
    func beginOnboarding(recoveryPhraseBackedUp: Bool?) throws { // swiftlint:disable:this discouraged_optional_boolean - nil leaves the stored flag as it is
        Self.access.lock(); defer { Self.access.unlock() }
        var wallet = persistedState(PersistedWalletState.self, key: PersistedWalletState.key) ?? .init()
        wallet.isInitialized = false
        var values = [PersistedWalletState.key: try encodedPersistedState(wallet, key: PersistedWalletState.key, version: PersistedWalletState.version)]
        if let recoveryPhraseBackedUp {
            values[PersistedRecoveryPhraseBackup.key] = try encodedPersistedState(PersistedRecoveryPhraseBackup(isBackedUp: recoveryPhraseBackedUp),
                                                                                  key: PersistedRecoveryPhraseBackup.key,
                                                                                  version: PersistedRecoveryPhraseBackup.version)
        }
        try setDurably(values)
    }

    /// Reads a zustand-persisted store. Returns `nil` when the key is absent
    /// or does not decode; callers fall back to their defaults.
    func persistedState<State: Decodable>(_: State.Type, key: String) -> State? {
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
    func setPersistedState(_ state: some Encodable, key: String, version: Int) throws {
        try setDurably(encodedPersistedState(state, key: key, version: version), forKey: key)
    }
    private func encodedPersistedState(_ state: some Encodable, key: String, version: Int) throws -> String {
        let data = try JSONEncoder().encode(PersistedEnvelope(state: state, version: version))
        let encoded = try String(json: data)
        return RNStoreCodec.schemas[key] == nil ? encoded
            : try RNStoreCodec.merging(encoded, into: string(forKey: key), key: key)
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
