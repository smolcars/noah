package com.noahwallet.services.wallet

import com.noahwallet.services.backup.RestoreTransaction
import com.noahwallet.services.storage.WalletDataLocation
import com.noahwallet.services.storage.syncDirectory
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Files precede secrets: an interrupted deletion must remain recoverable from backup. */
class WalletLifecycle(
    private val session: WalletSession,
    private val location: WalletDataLocation,
    private val setSuspended: (Boolean) -> Unit,
    private val deregister: suspend () -> Unit,
    private val readSeed: suspend () -> com.noahwallet.services.storage.SecretResult,
    private val deleteSeed: suspend () -> Unit,
    private val clearToken: suspend () -> Unit,
    private val clearSettings: () -> Unit,
    private val clearWidget: suspend () -> Unit,
    private val sweepScratch: () -> Unit,
    private val settleBackground: suspend () -> Unit = {},
    private val drainExports: suspend () -> Unit = {},
    private val finishBackground: () -> Unit = {},
    private val isSuspended: () -> Boolean = { false },
    private val syncFiles: () -> Unit = {},
    private val syncIdentityDirectory: (java.io.File) -> Unit = ::syncDirectory,
    private val drainTimeoutMillis: Long = 30_000,
    private val warn: (String) -> Unit = {},
    private val stopAutomation: () -> Unit = {},
    private val cancelPeriodicWork: () -> Unit = {},
) {
    companion object { const val automationStopKey = "native-recurring-automation-stop-generation" }
    class WaitingForSecureStorage : Exception("Waiting for secure storage. Unlock your device and try again.")
    class RestoreSettingsUnavailable : Exception("Restored wallet settings can't be saved yet. Try Resume again when storage is available. Recurring payments remain stopped.")
    class RestorePending : Exception("""Restart Noah to settle the pending restore before deleting""")
    class BackupBusy : Exception("Noah is still finishing a backup. Try again in a moment.")
    class TemporaryFiles : Exception("Noah couldn't remove temporary wallet files. Restart Noah and try again.")
    private val mutation = Mutex()

    /** Always acquire this gate before a session reservation; held through restore completion. */
    suspend fun <T> withWalletMutation(block: suspend () -> T): T = mutation.withLock { block() }

    /** Called by launch and headless recovery; keeps deletion out until restore has settled. */
    suspend fun recoverRestoreAndDeletion(
        walletMutationHeld: Boolean = false,
        recoverRestore: suspend () -> RestoreTransaction.Recovery,
    ): RestoreTransaction.Recovery = withContext(NonCancellable + Dispatchers.IO) {
        if (walletMutationHeld) recoverRestoreAndDeletionLocked(recoverRestore)
        else mutation.withLock { recoverRestoreAndDeletionLocked(recoverRestore) }
    }

    private suspend fun recoverRestoreAndDeletionLocked(
        recoverRestore: suspend () -> RestoreTransaction.Recovery,
    ): RestoreTransaction.Recovery {
        // Deletion refuses a restore journal, and restore refuses a deletion marker.
        // Coexistence proves the marker is stale, even if its seed tag matches.
        // Drop it durably before restore removes its journal: a crash after rollback
        // must not reauthorize deletion of the previous wallet on the next launch.
        if (location.restorePending) {
            if (location.deletionMarker.exists()) {
                if (location.deletionStatus(readSeed()) == WalletDataLocation.DeletionStatus.waitingForSecret) {
                    return RestoreTransaction.Recovery.waitingForSecret
                }
                discardDeletionMarker()
            } else syncFiles() // Retry a prior marker unlink whose directory sync failed.
        }
        if (!location.deletionMarker.exists()) location.removeDiscardedDeletionData { syncFiles() }
        val result = recoverRestore()
        if (result == RestoreTransaction.Recovery.waitingForSecret) return result
        if (recoverDeletionLocked() == RestoreTransaction.Recovery.waitingForSecret) return RestoreTransaction.Recovery.waitingForSecret
        return result
    }

    suspend fun recoverDeletion() = withContext(NonCancellable + Dispatchers.IO) {
        mutation.withLock { recoverDeletionLocked() }
    }

    private suspend fun recoverDeletionLocked(): RestoreTransaction.Recovery {
        if (location.deletionMarker.exists()) {
            when (location.deletionStatus(readSeed())) {
                WalletDataLocation.DeletionStatus.waitingForSecret -> return RestoreTransaction.Recovery.waitingForSecret
                WalletDataLocation.DeletionStatus.mismatched -> discardDeletionMarker()
                WalletDataLocation.DeletionStatus.matching -> {
                    deleteLocked()
                    return RestoreTransaction.Recovery.nothingPending
                }
            }
        }
        location.removeDiscardedDeletionData { syncFiles() }
        return RestoreTransaction.Recovery.nothingPending
    }

    private fun discardDeletionMarker() {
        warn("Discarding deletion marker for a different or unidentifiable wallet")
        check(location.deletionMarker.delete())
        syncFiles()
    }

    suspend fun suspend() = withContext(NonCancellable + Dispatchers.IO) {
        stopDurably()
        mutation.withLock {
            persistSuspended(true)
            try { settleBackground(); session.closeSettled() }
            catch (failure: Exception) { persistSuspended(false); throw failure }
        }
    }

    suspend fun resume(retryRestoreSettings: suspend () -> Unit = {}) = withContext(NonCancellable + Dispatchers.IO) {
        mutation.withLock {
            val transaction = RestoreTransaction(location)
            val receipt = com.noahwallet.ui.RestoreCompletionJournal(location)
            if (transaction.settingsPending || receipt.pending) {
                try { retryRestoreSettings() } catch (_: Exception) { throw RestoreSettingsUnavailable() }
                if (transaction.settingsPending || receipt.pending) throw RestoreSettingsUnavailable()
            }
            authority()?.resume()
            try { setSuspended(false) } catch (failure: Exception) { authority()?.stop(); throw failure }
        }
    }
    private fun authority() = if (location.directory.isDirectory) com.noahwallet.recurring.RecurringAutomationAuthority(location.directory) { file, bytes -> com.noahwallet.recurring.durableRecurringWrite(file, bytes, syncIdentityDirectory) } else null
    private fun stopDurably() { authority()?.stop(); stopAutomation() }
    private fun persistSuspended(value: Boolean) { authority()?.suspension(value); setSuspended(value) }

    suspend fun delete() = withContext(NonCancellable + Dispatchers.IO) {
        stopDurably()
        mutation.withLock { deleteLocked() }
    }

    private suspend fun deleteLocked() {
        stopDurably()
        val wasSuspended = isSuspended()
        persistSuspended(true)
        try {
            try {
                withTimeout(drainTimeoutMillis) { settleBackground(); drainExports() }
            } catch (_: TimeoutCancellationException) { throw BackupBusy() }
            session.closeSettled()
            val reservation = session.reserve()
            try {
                if (location.restorePending) throw RestorePending()
                try { sweepScratch() } catch (_: Exception) { throw TemporaryFiles() }
                // The durable marker commits the deletion; every later launch finishes it.
                val seed = readSeed()
                if (location.deletionMarker.exists()) {
                    when (location.deletionStatus(seed)) {
                        WalletDataLocation.DeletionStatus.waitingForSecret -> throw WaitingForSecureStorage()
                        WalletDataLocation.DeletionStatus.mismatched -> discardDeletionMarker()
                        WalletDataLocation.DeletionStatus.matching -> Unit
                    }
                }
                if (!location.deletionMarker.exists()) {
                    if (seed is com.noahwallet.services.storage.SecretResult.Unavailable) throw WaitingForSecureStorage()
                    location.removeDiscardedDeletionData { syncFiles() }
                    location.writeDeletionIdentity(seed, syncIdentityDirectory)
                    syncFiles()
                }
                cancelPeriodicWork()
                try { deregister() } catch (_: Exception) { warn("Deregistration failed while deleting wallet") }
                if (location.directory.exists()) {
                    // Also finishes an older app's committed marker over abandoned staging.
                    check(!location.deletionDirectory.exists() || location.deletionDirectory.deleteRecursively()) { "Cannot delete prior staged wallet files" }
                    check(location.directory.renameTo(location.deletionDirectory)) { "Cannot stage wallet deletion" }
                    syncFiles()
                }
                check(!location.deletionDirectory.exists() || location.deletionDirectory.deleteRecursively()) { "Cannot delete wallet files" }
                check(!location.restoreDirectory.exists() || location.restoreDirectory.deleteRecursively()) { "Cannot delete restore files" }
                syncFiles()
                com.noahwallet.recurring.RecurringAutomationAuthority.deleted(location.directory)
                deleteSeed()
                clearToken()
                clearSettings()
                clearWidget()
                check(location.deletionMarker.delete())
                syncFiles()
            } finally { session.release(reservation) }
        } catch (failure: Exception) {
            if (!location.deletionMarker.exists()) persistSuspended(wasSuspended)
            throw failure
        } finally { finishBackground() }
    }
}
