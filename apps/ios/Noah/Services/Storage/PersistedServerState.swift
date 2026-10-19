import Foundation

/// RN `server-storage`, zustand's default persist version (0).
/// Decode fields independently so one old/malformed value cannot erase identity.
struct PersistedServerState: Codable, Equatable, Sendable {
    static let key = "server-storage"
    static let version = 0

    var isRegisteredWithServer: Bool?
    var lightningAddress: String?
    var nostrPubkey: String?
    var isBackupEnabled: Bool?
    var emailAddress: String?
    var isEmailVerified: Bool?
    var isEmailPromptDismissed: Bool?
    var mailboxAuthorizationExpiry: Double?
    var isMailboxAuthorizationEnabled: Bool?
    var lastReportedDeviceFingerprint: String?
    var lastDeviceReportAt: Double?

    init() {}

    enum CodingKeys: String, CodingKey {
        case isRegisteredWithServer
        case lightningAddress
        case nostrPubkey
        case isBackupEnabled
        case emailAddress
        case isEmailVerified
        case isEmailPromptDismissed
        case mailboxAuthorizationExpiry
        case isMailboxAuthorizationEnabled
        case lastReportedDeviceFingerprint
        case lastDeviceReportAt
    }

    init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        isRegisteredWithServer = try? values.decode(Bool.self, forKey: .isRegisteredWithServer)
        lightningAddress = try? values.decode(String.self, forKey: .lightningAddress)
        nostrPubkey = try? values.decode(String.self, forKey: .nostrPubkey)
        isBackupEnabled = try? values.decode(Bool.self, forKey: .isBackupEnabled)
        emailAddress = try? values.decode(String.self, forKey: .emailAddress)
        isEmailVerified = try? values.decode(Bool.self, forKey: .isEmailVerified)
        isEmailPromptDismissed = try? values.decode(Bool.self, forKey: .isEmailPromptDismissed)
        mailboxAuthorizationExpiry = try? values.decode(Double.self, forKey: .mailboxAuthorizationExpiry)
        isMailboxAuthorizationEnabled = try? values.decode(Bool.self, forKey: .isMailboxAuthorizationEnabled)
        lastReportedDeviceFingerprint = try? values.decode(String.self, forKey: .lastReportedDeviceFingerprint)
        lastDeviceReportAt = try? values.decode(Double.self, forKey: .lastDeviceReportAt)
    }

    func encode(to encoder: Encoder) throws {
        var values = encoder.container(keyedBy: CodingKeys.self)
        try values.encodeIfPresent(isRegisteredWithServer, forKey: .isRegisteredWithServer)
        try values.encode(lightningAddress, forKey: .lightningAddress)
        try values.encode(nostrPubkey, forKey: .nostrPubkey)
        try values.encodeIfPresent(isBackupEnabled, forKey: .isBackupEnabled)
        try values.encode(emailAddress, forKey: .emailAddress)
        try values.encodeIfPresent(isEmailVerified, forKey: .isEmailVerified)
        try values.encodeIfPresent(isEmailPromptDismissed, forKey: .isEmailPromptDismissed)
        try values.encode(mailboxAuthorizationExpiry, forKey: .mailboxAuthorizationExpiry)
        try values.encodeIfPresent(isMailboxAuthorizationEnabled, forKey: .isMailboxAuthorizationEnabled)
        try values.encode(lastReportedDeviceFingerprint, forKey: .lastReportedDeviceFingerprint)
        try values.encode(lastDeviceReportAt, forKey: .lastDeviceReportAt)
    }
}

extension SettingsStorage {
    func updateServerState(_ update: (inout PersistedServerState) -> Void) throws -> PersistedServerState {
        var state = persistedState(PersistedServerState.self, key: PersistedServerState.key) ?? .init()
        update(&state)
        try setPersistedState(state, key: PersistedServerState.key, version: PersistedServerState.version)
        return state
    }
}
