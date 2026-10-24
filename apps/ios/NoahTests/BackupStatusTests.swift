import Foundation
import Testing
@testable import Noah

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

    @Test func rootRowShowsFailureAndRunningBeforeTheSwitch() {
        #expect(BackupStatus(state(.failed), enabled: true).rowValue(enabled: true) == "Failed")
        #expect(BackupStatus(state(.inProgress), enabled: false).rowValue(enabled: false) == "Backing up…")
        #expect(BackupStatus(state(.success), enabled: true).rowValue(enabled: true) == "On")
        #expect(BackupStatus(state(.success), enabled: false).rowValue(enabled: false) == "Off")
    }
}
