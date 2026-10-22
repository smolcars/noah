import Foundation

/// Native only, a separate key so React Native's stores stay untouched.
/// Home asks for a backup until `isBackedUp` is true: after the user views the
/// phrase and confirms writing it down, or after a restore from a typed phrase
/// (typing it proves the user has it; reinstall recovery from the stored seed
/// doesn't). Missing means not backed up: React Native never
/// recorded an acknowledgement (a user who quit its onboarding before the
/// phrase was still finished on the next launch), and earlier native builds
/// didn't either. Deleting the wallet clears it with the other settings.
struct PersistedRecoveryPhraseBackup: Codable, Equatable, Sendable {
    static let key = "native-recovery-phrase-backup-v1"
    static let version = 0

    var isBackedUp: Bool?

    var needsBackup: Bool { isBackedUp != true }

    /// Reads the flag strictly: only the envelope version this build writes
    /// counts. Any other version, a missing or malformed one, or a value that
    /// doesn't decode reads as not backed up, so it can never hide the prompt.
    static func read(from settings: SettingsStorage) -> Self {
        guard let raw = settings.string(forKey: key),
              let envelope = try? JSONDecoder().decode(Envelope.self, from: Data(raw.utf8)),
              envelope.version == version else { return .init() }
        return envelope.state
    }

    private struct Envelope: Decodable {
        let state: PersistedRecoveryPhraseBackup
        let version: Int
    }
}
