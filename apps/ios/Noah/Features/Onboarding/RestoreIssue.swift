import BarkBoltFFI
import Foundation
import OpenAPIRuntime

/// Fixed user-facing copy only: server and FFI errors may contain secrets.
struct RestoreIssue: Equatable {
    let message: String
    var requiresRestart = false

    static let newerBackup = Self(message: "This backup was created by a newer version of Noah. Update the app, then try restoring again.")
    static let invalidPhrase = Self(message: "Check your recovery phrase. Enter all 12 or 24 words in the correct order.")
    static let committed = Self(
        message: "Your backup was restored, but setup couldn't finish. Do not restore again. Close and restart Noah to continue.",
        requiresRestart: true)

    static func from(_ error: Error) -> Self {
        if let error = error as? ClientError { return from(error.underlyingError) }
        if let failure = error as? WalletRestorer.Failure {
            switch failure {
            case .wrongNetwork:
                return Self(message: "This backup belongs to a different Bitcoin network. Use the matching version of Noah.")
            case .identityMismatch:
                return Self(message: "The backup's wallet information doesn't match. Noah couldn't safely restore it.")
            case .noUsableBackup:
                return Self(message: "No usable backup was found for this recovery phrase. Check the words and try again.")
            case .unsupportedFormat:
                return newerBackup
            case .downloadMismatch:
                return Self(message: "The downloaded backup is incomplete or damaged. Check your connection and try again.")
            case .secureStorageUnavailable:
                return Self(message: "Noah couldn't access secure storage. Unlock your device and try again.")
            case .rollbackFailed:
                return Self(message: "Noah couldn't safely undo the restore. Do not retry. Close and restart Noah so it can recover your wallet.", requiresRestart: true)
            case .seedStateUnknown:
                return Self(message: "Noah couldn't confirm that your recovery phrase was saved. Do not retry. Unlock your device, then close and restart Noah to recover safely.", requiresRestart: true)
            }
        }
        if error is RestoreTransaction.Failure || error is RestoreCompletion.RestartRequired {
            return Self(message: "An earlier restore needs recovery. Do not retry. Close and restart Noah to continue safely.", requiresRestart: true)
        }
        if error as? BackupCrypto.Failure == .newerVersion || error as? BackupArchive.Failure == .newerVersion {
            return newerBackup
        }
        // The SDK has no typed error code for a future snapshot schema yet.
        if let error = error as? BarkError,
            error.message.range(of: #"^Snapshot schema version [0-9]+ is newer than supported version [0-9]+$"#,
                options: .regularExpression) != nil {
            return newerBackup
        }
        if error as? BackupCrypto.Failure == .authenticationFailed {
            return Self(message: "This phrase couldn't unlock the backup, or the backup is damaged. Check all 12 or 24 words and their order.")
        }
        if let server = error as? ServerFailure, server.statusCode == 404 || server.code == "USER_NOT_FOUND" {
            return Self(message: "No backup was found for this recovery phrase. Check the words and make sure this wallet had backups enabled.")
        }
        if error is URLError {
            return Self(message: "Noah couldn't download your backup. Check your internet connection and try again.")
        }
        return Self(message: "Noah couldn't restore your wallet. Please try again later.")
    }
}
