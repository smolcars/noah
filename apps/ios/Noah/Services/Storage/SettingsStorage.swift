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
    private let mmkv: MMKV

    static let instanceID = "noah-wallet-storage"

    static var defaultRootPath: String {
        URL.documentsDirectory.appending(path: "mmkv", directoryHint: .isDirectory).path
    }

    /// Opened after `bootstrap()`; first access must come after launch.
    static let shared: SettingsStorage = {
        do {
            return try SettingsStorage(rootPath: defaultRootPath)
        } catch {
            preconditionFailure("Failed to open MMKV settings storage: \(error)")
        }
    }()

    struct OpenError: Error {}

    nonisolated(unsafe) private static var isBootstrapped = false

    /// MMKV must be initialized once, on the main thread, before any instance
    /// is opened. Call from `application(_:didFinishLaunchingWithOptions:)`,
    /// which also runs for background (push) launches.
    @MainActor
    static func bootstrap() {
        guard !isBootstrapped else { return }
        try? FileManager.default.createDirectory(
            atPath: defaultRootPath, withIntermediateDirectories: true)
        MMKV.initialize(rootDir: defaultRootPath)
        isBootstrapped = true
    }

    init(rootPath: String, instanceID: String = SettingsStorage.instanceID) throws {
        precondition(Self.isBootstrapped, "SettingsStorage.bootstrap() must run first")
        try FileManager.default.createDirectory(
            atPath: rootPath, withIntermediateDirectories: true)
        guard let mmkv = MMKV(mmapID: instanceID, rootPath: rootPath) else {
            throw OpenError()
        }
        self.mmkv = mmkv
    }

    func string(forKey key: String) -> String? {
        mmkv.string(forKey: key)
    }

    func set(_ value: String, forKey key: String) {
        mmkv.set(value, forKey: key)
    }

    /// Financial automation must observe write failures and flush the before-send marker.
    func setDurably(_ value: String, forKey key: String) throws {
        guard mmkv.set(value, forKey: key) else { throw OpenError() }
        mmkv.sync()
        guard mmkv.string(forKey: key) == value else { throw OpenError() }
    }

    func remove(forKey key: String) {
        mmkv.removeValue(forKey: key)
    }

    /// Every store, as RN's wallet deletion wiped `Documents/mmkv`.
    func removeAll() {
        mmkv.clearAll()
    }

    /// Reads a zustand-persisted store. Returns `nil` when the key is absent
    /// or does not decode; callers fall back to their defaults.
    func persistedState<State: Decodable>(_ type: State.Type, key: String) -> State? {
        guard let json = string(forKey: key), let data = json.data(using: .utf8) else {
            return nil
        }
        return try? JSONDecoder().decode(PersistedEnvelope<State>.self, from: data).state
    }

    /// Writes a zustand-compatible envelope so the value round-trips.
    func setPersistedState<State: Encodable>(_ state: State, key: String, version: Int) throws {
        let data = try JSONEncoder().encode(PersistedEnvelope(state: state, version: version))
        set(String(decoding: data, as: UTF8.self), forKey: key)
    }
}

/// zustand `persist` + `createJSONStorage` value layout.
struct PersistedEnvelope<State> {
    var state: State
    var version: Int?
}

extension PersistedEnvelope: Decodable where State: Decodable {}
extension PersistedEnvelope: Encodable where State: Encodable {}
