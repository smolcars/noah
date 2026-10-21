package com.noahwallet.ui

import com.noahwallet.services.backup.*
import com.noahwallet.services.server.ServerFailure
import com.noahwallet.services.wallet.WalletSession
import io.github.niteshbalusu11.bark.BarkError
import java.io.IOException

/** Fixed copy only: provider/server exceptions can contain secrets. */
data class RestoreIssue(val message: String, val requiresRestart: Boolean = false) {
    companion object {
        val newerBackup = RestoreIssue("This backup was created by a newer version of Noah. Update the app, then try restoring again.")
        val invalidPhrase = RestoreIssue("Check your recovery phrase. Enter all 12 or 24 words in the correct order.")
        val committed = RestoreIssue("Your backup was restored, but setup couldn't finish. Do not restore again. Close and restart Noah to continue.", true)
        val recovery = RestoreIssue("An earlier restore needs recovery. Do not retry. Close and restart Noah to continue safely.", true)
        val existingData = RestoreIssue("Wallet data already exists on this device. Noah won't replace it. Return to your wallet or restart Noah to recover safely.", true)
        fun from(error: Exception): RestoreIssue = when (error) {
            is WalletRestorer.Failure -> when (error.reason) {
                WalletRestorer.Reason.wrongNetwork -> RestoreIssue("This backup belongs to a different Bitcoin network. Use the matching version of Noah.")
                WalletRestorer.Reason.identityMismatch -> RestoreIssue("The backup's wallet information doesn't match. Noah couldn't safely restore it.")
                WalletRestorer.Reason.noUsableBackup -> RestoreIssue("No usable backup was found for this recovery phrase. Check the words and try again.")
                WalletRestorer.Reason.unsupportedFormat -> newerBackup
                WalletRestorer.Reason.downloadMismatch -> RestoreIssue("The downloaded backup is incomplete or damaged. Check your connection and try again.")
                WalletRestorer.Reason.secureStorageUnavailable -> RestoreIssue("Noah couldn't access secure storage. Unlock your device and try again.")
                WalletRestorer.Reason.rollbackFailed -> RestoreIssue("Noah couldn't safely undo the restore. Do not retry. Close and restart Noah so it can recover your wallet.", true)
                WalletRestorer.Reason.seedStateUnknown -> RestoreIssue("Noah couldn't confirm that your recovery phrase was saved. Do not retry. Unlock your device, then close and restart Noah to recover safely.", true)
            }
            is RestoreTransaction.Failure, is RestoreCompletion.RestartRequired -> recovery
            is WalletSession.OpenError -> when (error.reason) {
                WalletSession.OpenFailure.restorePending, WalletSession.OpenFailure.teardownFailed -> recovery
                WalletSession.OpenFailure.walletDatabaseExists -> existingData
                else -> RestoreIssue("Noah couldn't open the restored wallet safely. Close and restart Noah to continue.", true)
            }
            is BackupCrypto.Failure -> when (error.reason) {
                BackupCrypto.Reason.newerVersion -> newerBackup
                BackupCrypto.Reason.authenticationFailed -> RestoreIssue("This phrase couldn't unlock the backup, or the backup is damaged. Check all 12 or 24 words and their order.")
                else -> generic
            }
            is BackupArchive.Failure -> if (error.reason == BackupArchive.Reason.newerVersion) newerBackup else generic
            // The SDK has no typed error code for a future snapshot schema yet.
            is BarkError -> if (futureSchema.matches(error.message.orEmpty())) newerBackup else generic
            is ServerFailure -> if (error.kind == ServerFailure.Kind.connectivity) connectivity
            else if (error.status == 404 || error.code == "USER_NOT_FOUND")
                RestoreIssue("No backup was found for this recovery phrase. Check the words and make sure this wallet had backups enabled.") else generic
            is IOException -> connectivity
            else -> generic
        }
        private val futureSchema = Regex("Snapshot schema version [0-9]+ is newer than supported version [0-9]+")
        private val connectivity = RestoreIssue("Noah couldn't download your backup. Check your internet connection and try again.")
        private val generic = RestoreIssue("Noah couldn't restore your wallet. Please try again later.")
    }
}
