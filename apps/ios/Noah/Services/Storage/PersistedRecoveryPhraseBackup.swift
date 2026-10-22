import Foundation

/// Native only, a separate key so React Native's stores stay untouched.
/// Home asks for a backup until `isBackedUp` is true: after the user views the
/// phrase and confirms writing it down, or after a restore (typing the phrase
/// proves the user has it). Missing means not backed up: React Native never
/// recorded an acknowledgement (a user who quit its onboarding before the
/// phrase was still finished on the next launch), and earlier native builds
/// didn't either. Deleting the wallet clears it with the other settings.
struct PersistedRecoveryPhraseBackup: Codable, Equatable, Sendable {
    static let key = "native-recovery-phrase-backup-v1"
    static let version = 0

    var isBackedUp: Bool?

    var needsBackup: Bool { isBackedUp != true }
}
