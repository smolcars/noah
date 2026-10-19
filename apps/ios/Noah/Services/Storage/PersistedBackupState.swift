import Foundation

/// RN `backup-storage` (`client/src/store/backupStore.ts`), zustand's default
/// persist version (0). The mutations mirror the store's actions so a value
/// written by either app reads the same in the other.
struct PersistedBackupState: Codable, Equatable, Sendable {
    static let key = "backup-storage"
    static let version = 0

    enum Status: String, Codable, Sendable {
        case idle
        case inProgress = "in_progress"
        case success
        case failed
    }

    var backupPending = false
    /// Milliseconds since 1970, like `Date.now()`.
    var lastBackupAt: Double?
    var lastBackupAttemptAt: Double?
    var lastBackupStatus: Status = .idle
    var lastBackupError: String?
    var lastBackupId: String?
    var lastUploadedSnapshotSha256: String?

    init() {}

    enum CodingKeys: String, CodingKey {
        case backupPending, lastBackupAt, lastBackupAttemptAt, lastBackupStatus, lastBackupError
        case lastBackupId, lastUploadedSnapshotSha256
    }

    /// Field by field, so one odd value can't drop the last uploaded hash.
    init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        backupPending = (try? values.decode(Bool.self, forKey: .backupPending)) ?? false
        lastBackupAt = try? values.decode(Double.self, forKey: .lastBackupAt)
        lastBackupAttemptAt = try? values.decode(Double.self, forKey: .lastBackupAttemptAt)
        lastBackupStatus = (try? values.decode(Status.self, forKey: .lastBackupStatus)) ?? .idle
        lastBackupError = try? values.decode(String.self, forKey: .lastBackupError)
        lastBackupId = try? values.decode(String.self, forKey: .lastBackupId)
        lastUploadedSnapshotSha256 = try? values.decode(String.self, forKey: .lastUploadedSnapshotSha256)
    }

    /// Nulls written explicitly, as `JSON.stringify` of the zustand state does.
    func encode(to encoder: Encoder) throws {
        var values = encoder.container(keyedBy: CodingKeys.self)
        try values.encode(backupPending, forKey: .backupPending)
        try values.encode(lastBackupAt, forKey: .lastBackupAt)
        try values.encode(lastBackupAttemptAt, forKey: .lastBackupAttemptAt)
        try values.encode(lastBackupStatus, forKey: .lastBackupStatus)
        try values.encode(lastBackupError, forKey: .lastBackupError)
        try values.encode(lastBackupId, forKey: .lastBackupId)
        try values.encode(lastUploadedSnapshotSha256, forKey: .lastUploadedSnapshotSha256)
    }

    mutating func markPending() {
        backupPending = true
        lastBackupStatus = lastBackupStatus == .inProgress ? .inProgress : .idle
        lastBackupError = nil
    }

    mutating func markInProgress(now: Double) {
        backupPending = true
        lastBackupStatus = .inProgress
        lastBackupAttemptAt = now
        lastBackupError = nil
    }

    mutating func markSuccess(snapshotSha256: String, backupId: String?, uploaded: Bool, stillPending: Bool, now: Double) {
        backupPending = stillPending
        lastBackupStatus = uploaded ? .success : .idle
        if uploaded { lastBackupAt = now }
        lastBackupError = nil
        lastBackupId = backupId ?? lastBackupId
        lastUploadedSnapshotSha256 = snapshotSha256
    }

    mutating func markFailed(_ error: String) {
        backupPending = true
        lastBackupStatus = .failed
        lastBackupError = error
    }

    /// After a restore: the restored snapshot is what the server already has.
    mutating func seedRestored(snapshotSha256: String, backupId: String?, now: Double) {
        backupPending = false
        lastBackupAt = now
        lastBackupStatus = .success
        lastBackupError = nil
        lastBackupId = backupId
        lastUploadedSnapshotSha256 = snapshotSha256
    }

    /// The server deleted `backupId`: upload again next time. Returns whether it was the last one.
    mutating func forgetUploadedBackup(_ backupId: String) -> Bool {
        guard lastBackupId == backupId else { return false }
        backupPending = true
        lastBackupId = nil
        lastUploadedSnapshotSha256 = nil
        lastBackupStatus = .idle
        lastBackupError = nil
        return true
    }
}
