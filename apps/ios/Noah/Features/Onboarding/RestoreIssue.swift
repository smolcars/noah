import BarkBoltFFI
import Foundation
import OpenAPIRuntime

/// Fixed user-facing copy only: server and FFI errors may contain secrets.
struct RestoreIssue: Equatable {
    /// A short red headline over a calm body; without one the message is the error line.
    var title: String?
    let message: String
    var requiresRestart = false
    /// A second way forward the screen offers under the message.
    var action: Action?

    enum Action: Equatable {
        /// Rebuild the balance from the Ark server (`WalletRestorer.Source.arkServer`).
        case recoverFromArkServer
        /// The Ark server knows nothing about the phrase; restore it anyway.
        case restoreAnyway

        var label: String {
            switch self {
            case .recoverFromArkServer: "Recover my balance"
            case .restoreAnyway: "Restore anyway"
            }
        }
    }

    static let newerBackup = Self(message: "This backup was created by a newer version of Noah. Update the app, then try restoring again.")
    static let noBackup = Self(
        title: "No backup found",
        message: "Check the words. If they're right, Noah can recover your balance from the Ark server, but not your payment history or settings.",
        action: .recoverFromArkServer)
    static let nothingFound = Self(
        title: "Nothing found",
        message: "The Ark server has no coins for this recovery phrase. Check the words. If they're right, you can restore it anyway.",
        action: .restoreAnyway)
    static let invalidPhrase = Self(message: "Check your recovery phrase. Enter all 12 or 24 words in the correct order.")
    static let committed = Self(
        message: "Your backup was restored, but setup couldn't finish. Do not restore again. Close and restart Noah to continue.",
        requiresRestart: true)

    /// After a restore from `source`: a recovery from the Ark server that failed for any other reason (offline) can be
    /// tried again.
    static func from(_ error: Error, after source: WalletRestorer.Source) -> Self {
        var issue = from(error)
        if source != .backup, issue.action == nil, !issue.requiresRestart { issue.action = .recoverFromArkServer }
        return issue
    }

    static func from(_ error: Error) -> Self {
        if let error = error as? ClientError { return from(error.underlyingError) }
        if let failure = error as? WalletRestorer.Failure { return from(failure) }
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
            return noBackup
        }
        if error is URLError {
            return Self(title: "Couldn't restore", message: "Check your connection and try again.")
        }
        return Self(title: "Couldn't restore", message: "Please try again later.")
    }

    private static func from(_ failure: WalletRestorer.Failure) -> Self {
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
            return Self(
                message: "Noah couldn't safely undo the restore. Do not retry. Close and restart Noah so it can recover your wallet.",
                requiresRestart: true)
        case .serverRecoveryIncomplete:
            return Self(
                title: "Couldn't restore",
                message: "Noah couldn't finish recovering your balance from the Ark server. Check your connection and try again.",
                action: .recoverFromArkServer)
        case .nothingFoundOnServer:
            return nothingFound
        case .seedStateUnknown:
            return Self(
                // swiftlint:disable:next line_length - one literal: translations.mjs reads copy as a single string
                message: "Noah couldn't confirm that your recovery phrase was saved. Do not retry. Unlock your device, then close and restart Noah to recover safely.",
                requiresRestart: true)
        }
    }
}
