import Foundation
import Testing

@testable import Noah

@MainActor
struct RNStoreTests {
    struct Input: Decodable { let key: String; let raw: String }
    struct Expected: Decodable { let version: Int }

    @Test func actualRNStoresDecodeAndPreserveUnknownFields() throws {
        SettingsStorage.bootstrap()
        let root = FileManager.default.temporaryDirectory.appending(path: "rn-stores-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: root) }
        let storage = try SettingsStorage(rootPath: root.path)
        let cases = try Fixtures.load("rn-stores", input: Input.self, expected: Expected.self)
        #expect(cases.count == 8)
        for item in cases where item.input.key != RecurringStore.key {
            let key = item.input.key
            var envelope = try RawObject(item.input.raw)
            var state = try RawObject(envelope.fields["state"]!)
            state.fields["futureNumber"] = "9223372036854775000"
            envelope.fields["state"] = try state.json()
            envelope.fields["futureRoot"] = #"{"nested":[1,true,null]}"#
            let raw = try envelope.json()
            storage.set(raw, forKey: key)
            let merged = try RNStoreCodec.merging(item.input.raw, into: raw, key: key)
            let result = try RawObject(merged)
            #expect(try RawObject(result.fields["state"]!).fields["futureNumber"] == "9223372036854775000")
            #expect(result.fields["futureRoot"] == envelope.fields["futureRoot"])
        }
        #expect(storage.persistedState(PersistedWalletState.self, key: PersistedWalletState.key)?.isInitialized == true)
        #expect(storage.persistedState(PersistedProfileState.self, key: PersistedProfileState.key)?.preferredCurrency == "EUR")
        #expect(storage.persistedState(PersistedPrivacyState.self, key: PersistedPrivacyState.key)?.isHomeBalanceHidden == true)
        #expect(!PersistedTransactionState.load(from: storage).isAutoBoardingEnabled)
        #expect(storage.persistedState(PersistedServerState.self, key: PersistedServerState.key)?.isMailboxAuthorizationEnabled == false)
        #expect(storage.persistedState(PersistedBackupState.self, key: PersistedBackupState.key)?.lastBackupId == "fixture-backup")
        #expect(storage.persistedState(PersistedEsploraState.self, key: PersistedEsploraState.key)?.endpointOverride == "https://fixture.invalid/api")
    }

    @Test func badAndFutureEnvelopesAreNeverReplaced() throws {
        SettingsStorage.bootstrap()
        let root = FileManager.default.temporaryDirectory.appending(path: "rn-store-invalid-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: root) }
        let storage = try SettingsStorage(rootPath: root.path)
        let bad = ["broken", #"{"state":[],"version":0}"#, #"{"state":{},"version":99}"#,
                   #"{"state":{"isHomeBalanceHidden":"true"},"version":0}"#,
                   #"{"state":{},"version":false}"#, #"{"state":{},"version":-1}"#]
        for raw in bad {
            storage.set(raw, forKey: PersistedPrivacyState.key)
            #expect(throws: (any Error).self) { try storage.requireCompatibleRNStores() }
            #expect(throws: (any Error).self) {
                try storage.setPersistedState(PersistedPrivacyState(isHomeBalanceHidden: false), key: PersistedPrivacyState.key, version: 0)
            }
            #expect(storage.string(forKey: PersistedPrivacyState.key) == raw)
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
