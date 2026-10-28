import Foundation
@testable import Noah
import Testing

/// Backup's status mark and the root row's value, from today's backup state.
struct BackupStatusTests {
    private func state(_ status: PersistedBackupState.Status, at: Double? = 1_760_000_000_000) -> PersistedBackupState {
        var state = PersistedBackupState()
        state.lastBackupStatus = status
        state.lastBackupAt = at
        return state
    }

    @Test func marksFollowTheLastBackup() {
        #expect(BackupStatus(state(.success), enabled: true).mark == .done)
        #expect(BackupStatus(state(.idle), enabled: true).mark == .done)
        #expect(BackupStatus(state(.inProgress), enabled: true).mark == .running)
        #expect(BackupStatus(state(.failed), enabled: true).mark == .failed)
        #expect(BackupStatus(state(.success), enabled: false).mark == .off)
        #expect(BackupStatus(state(.idle, at: nil), enabled: false).mark == .never)
        #expect(BackupStatus(state(.idle, at: nil), enabled: true).mark == .never)
        #expect(BackupStatus(state(.failed), enabled: true).lastBackup == Date(timeIntervalSince1970: 1_760_000_000))
    }

    @Test func aPendingBackupIsNeverShownAsBackedUp() {
        var state = state(.success)
        state.backupPending = true
        #expect(BackupStatus(state, enabled: true).mark == .pending)
        // Deleting the remembered upload while suspended keeps its timestamp but leaves nothing backed up.
        var deleted = PersistedBackupState()
        deleted.markSuccess(snapshotSha256: "a", backupId: "b1", uploaded: true, stillPending: false, now: 1_760_000_000_000)
        #expect(BackupStatus(deleted, enabled: true).mark == .done)
        let forgot = deleted.forgetUploadedBackup("b1")
        #expect(forgot)
        #expect(deleted.lastBackupAt != nil)
        #expect(BackupStatus(deleted, enabled: true).mark == .pending)
        #expect(BackupStatus(deleted, enabled: false).mark == .pending)
        // Until a replacement succeeds.
        deleted.markInProgress(now: 1_760_000_100_000)
        #expect(BackupStatus(deleted, enabled: true).mark == .running)
        deleted.markSuccess(snapshotSha256: "c", backupId: "b2", uploaded: true, stillPending: false, now: 1_760_000_200_000)
        #expect(BackupStatus(deleted, enabled: true).mark == .done)
    }

    @Test func rootRowShowsFailureAndRunningBeforeTheSwitch() {
        #expect(BackupStatus(state(.failed), enabled: true).rowValue(enabled: true) == "Failed")
        #expect(BackupStatus(state(.inProgress), enabled: false).rowValue(enabled: false) == "Backing up…")
        #expect(BackupStatus(state(.success), enabled: true).rowValue(enabled: true) == "On")
        #expect(BackupStatus(state(.success), enabled: false).rowValue(enabled: false) == "Off")
    }
}
