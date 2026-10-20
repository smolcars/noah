import Foundation
import Testing

@testable import Noah

@MainActor
struct RNStoreTests {
    struct Input: Decodable { let key: String; let raw: String }
    struct Expected: Decodable {
        let available: Bool
        let imported: Bool
        let version: Int?
        let stateRaw: String?
        let consumed: [String: String]?
        let preservedTokens: [String: String]?
    }

    @Test func unifiedRNFixturesValidateMigrateAndPreserveOriginalBytes() throws {
        SettingsStorage.bootstrap()
        let root = FileManager.default.temporaryDirectory.appending(path: "rn-stores-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: root) }
        let cases = try Fixtures.load("rn-store-compatibility", input: Input.self, expected: Expected.self)
        #expect(cases.count == 196)
        for item in cases {
            // Each raw RN vector is independent; a corrupt store blocks later native writes.
            let path = root.appending(path: UUID().uuidString).path
            let storage = try SettingsStorage(rootPath: path)
            let key = item.input.key
            let raw = item.input.raw
            let imported = SettingsStorage.importValues([key: raw])
            #expect((key != RecurringStore.key && imported.values[key] == raw) == item.expected.imported, "\(item.name)")
            if key == RecurringStore.key {
                #expect(imported.values[key] == nil || imported.values[key] == SettingsStorage.emptyRecurring, "\(item.name)")
            } else if item.expected.available { #expect(imported.values[key] == raw, "\(item.name)") }
            else { #expect(imported.values[key] == nil && imported.settingsNoticePending, "\(item.name)") }
            storage.set(raw, forKey: key)
            let recurring = RecurringStore(settings: storage)
            if !item.expected.available {
                if key == RecurringStore.key {
                    #expect(throws: (any Error).self) { try recurring.load() }
                    #expect(throws: (any Error).self) { try recurring.setPending(false) }
                } else {
                    #expect(throws: (any Error).self) { try RNStoreCodec.decode(raw, key: key) }
                    #expect(throws: (any Error).self) {
                        try RNStoreCodec.merging(#"{"state":{},"version":0}"#, into: raw, key: key)
                    }
                }
                #expect(storage.string(forKey: key) == raw, "\(item.name)")
                continue
            }
            let decoded: RawObject
            if key == RecurringStore.key { _ = try recurring.load(); decoded = try RawObject(raw) }
            else { decoded = try RNStoreCodec.decode(raw, key: key) }
            let state = try RawObject(decoded.fields["state"]!)
            let wanted = try RawObject(try #require(item.expected.stateRaw))
            for (field, token) in wanted.fields {
                #expect(state.fields[field]?.trimmingCharacters(in: .whitespacesAndNewlines) == token, "\(item.name): \(field)")
            }
            #expect(storage.string(forKey: key) == raw, "Reads must not migrate bytes: \(item.name)")
            if let consumed = item.expected.consumed {
                let profile = try #require(storage.persistedState(PersistedProfileState.self, key: key))
                #expect(profile.currency == consumed["preferredCurrency"])
                #expect(profile.amountUnit.rawValue == consumed["bitcoinAmountUnit"])
            }
            if key == RecurringStore.key { try recurring.setPending(false) }
            else {
                let incoming = "{\"state\":\(item.expected.stateRaw!),\"version\":\(item.expected.version!)}"
                try storage.setDurably(RNStoreCodec.merging(incoming, into: raw, key: key), forKey: key)
            }
            let saved = try RawObject(try #require(storage.string(forKey: key)))
            let savedState = try RawObject(saved.fields["state"]!)
            for (path, token) in item.expected.preservedTokens ?? [:] {
                let parts = path.split(separator: ".")
                let value = parts[0] == "root" ? saved.fields[String(parts[1])] : savedState.fields[String(parts[1])]
                #expect(value?.trimmingCharacters(in: .whitespacesAndNewlines) == token, "\(item.name): \(path)")
            }
        }
    }

    @Test func onlyExactVersionTokensAreAccepted() throws {
        for key in RNStoreCodec.schemas.keys {
            for token in ["-0", "0e5", "1e-400", "0.0", "1.0", "1e0", "-1", "99", "true", "null", "\"0\""] {
                #expect(throws: (any Error).self) { try RNStoreCodec.decode("{\"state\":{},\"version\":\(token)}", key: key) }
            }
            _ = try RNStoreCodec.decode(#"{"state":{}}"#, key: key)
            _ = try RNStoreCodec.decode(#"{"state":{},"version": 0 }"#, key: key)
            if key == "transaction-storage" { _ = try RNStoreCodec.decode(#"{"state":{},"version":1}"#, key: key) }
            else { #expect(throws: (any Error).self) { try RNStoreCodec.decode(#"{"state":{},"version":1}"#, key: key) } }
        }
    }

    @Test func transactionV0MigratesExactlyLikeRNWithoutWriting() throws {
        SettingsStorage.bootstrap()
        let root = FileManager.default.temporaryDirectory.appending(path: "rn-store-v0-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: root) }
        let storage = try SettingsStorage(rootPath: root.path)
        let raw = #"{"state":{"isAutoBoardingEnabled":false,"hasAttemptedAutoBoarding":true,"future":123},"version":0}"#
        storage.set(raw, forKey: PersistedTransactionState.key)
        let state = PersistedTransactionState.load(from: storage)
        #expect(!state.isAutoBoardingEnabled && !state.hasAttemptedAutoBoarding)
        #expect(storage.string(forKey: PersistedTransactionState.key) == raw)
        try storage.setPersistedState(state, key: PersistedTransactionState.key, version: 1)
        let saved = try RawObject(try #require(storage.string(forKey: PersistedTransactionState.key)))
        #expect(saved.fields["version"] == "1")
        #expect(try RawObject(saved.fields["state"]!).fields["future"] == "123")
    }
}
