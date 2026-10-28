import Foundation
@testable import Noah
import Testing

@MainActor
struct PersistedServerStateTests {
    static let rnJSON = #"{"state":{"isRegisteredWithServer":true,"lightningAddress":"alice@localhost.com","nostrPubkey":null,"#
        + #""isBackupEnabled":true,"emailAddress":"alice@example.com","isEmailVerified":true,"isEmailPromptDismi"#
        + #"ssed":false,"mailboxAuthorizationExpiry":1790000000,"isMailboxAuthorizationEnabled":false,"lastRepor"#
        + #"tedDeviceFingerprint":"[null,null,null,null,null,null]","lastDeviceReportAt":1784160000000},"version"#
        + #"":0}"#

    @Test func readsAndWritesRNEnvelopeWithoutLosingPreferences() throws {
        SettingsStorage.bootstrap()
        let root = FileManager.default.temporaryDirectory.appending(path: "server-state-\(UUID())")
        defer { try? FileManager.default.removeItem(at: root) }
        let settings = try SettingsStorage(rootPath: root.path, instanceID: UUID().uuidString)
        settings.set(Self.rnJSON, forKey: PersistedServerState.key)
        let before = try #require(settings.persistedState(PersistedServerState.self, key: PersistedServerState.key))
        try settings.setPersistedState(before, key: PersistedServerState.key, version: PersistedServerState.version)
        let encoded = try #require(settings.string(forKey: PersistedServerState.key))
        #expect(try Fixtures.foundationJSON(Data(encoded.utf8)) == Fixtures.foundationJSON(Data(Self.rnJSON.utf8)))
        let after = try settings.updateServerState { $0.isEmailPromptDismissed = true; $0.emailAddress = nil }
        #expect(after.isMailboxAuthorizationEnabled == false)
        #expect(after.mailboxAuthorizationExpiry == 1790000000)
        #expect(after.lightningAddress == before.lightningAddress)
        #expect(after.emailAddress == nil)
        #expect(after.isEmailPromptDismissed == true)
        #expect(settings.persistedState(PersistedServerState.self, key: PersistedServerState.key) == after)
    }

    @Test func missingPreferencesDoNotOverrideRNDefaultsWithNull() throws {
        let data = try JSONEncoder().encode(PersistedServerState())
        let values = try #require(JSONSerialization.jsonObject(with: data) as? [String: Any])
        #expect(values["isMailboxAuthorizationEnabled"] == nil)
        #expect(values["nostrPubkey"] is NSNull)
    }

    @Test func lenientDecodeKeepsValidFields() throws {
        let data = Data((#"{"state":{"isRegisteredWithServer":true,"emailAddress":42,"isEmailVerified":"yes","#
                + #""lightningAddress":"alice@localhost.com","futureField":true}}"#).utf8)
        let envelope = try JSONDecoder().decode(PersistedEnvelope<PersistedServerState>.self, from: data)
        #expect(envelope.version == nil)
        #expect(envelope.state.isRegisteredWithServer == true)
        #expect(envelope.state.emailAddress == nil)
        #expect(envelope.state.isEmailVerified == nil)
        #expect(envelope.state.lightningAddress == "alice@localhost.com")
        #expect(try JSONDecoder().decode(PersistedServerState.self, from: Data("{}".utf8)) == PersistedServerState())
    }
}
