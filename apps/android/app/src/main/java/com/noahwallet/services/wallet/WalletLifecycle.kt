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
    private val clearWidget: () -> Unit,
    private val sweepScratch: () -> Unit,
    private val settleBackground: suspend () -> Unit = {},
    private val drainExports: suspend () -> Unit = {},
    private val finishBackground: () -> Unit = {},
    private val isSuspended: () -> Boolean = { false },
    private val syncFiles: () -> Unit = {},
    private val syncIdentityDirectory: (java.io.File) -> Unit = ::syncDirectory,
    private val drainTimeoutMillis: Long = 30_000,
    private val warn: (String) -> Unit = {},
) {
    class RestorePending : Exception("Restart Noah to settle the pending restore before deleting")
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
            if (location.deletionMarker.exists()) discardDeletionMarker()
            else syncFiles() // Retry a prior marker unlink whose directory sync failed.
        }
        val result = recoverRestore()
        if (result == RestoreTransaction.Recovery.waitingForSecret) return result
        recoverDeletionLocked()
        return result
    }

    suspend fun recoverDeletion() = withContext(NonCancellable + Dispatchers.IO) {
        mutation.withLock { recoverDeletionLocked() }
    }

    private suspend fun recoverDeletionLocked() {
        if (!location.deletionMarker.exists()) return
        if (!location.deletionMatchesWallet(readSeed())) {
            discardDeletionMarker()
            return
        }
        deleteLocked()
    }

    private fun discardDeletionMarker() {
        warn("Discarding deletion marker for a different or unidentifiable wallet")
        check(location.deletionMarker.delete())
        syncFiles()
    }

    suspend fun suspend() = withContext(NonCancellable + Dispatchers.IO) {
        mutation.withLock {
            setSuspended(true)
            try { settleBackground(); session.closeSettled() }
            catch (failure: Exception) { setSuspended(false); throw failure }
        }
    }

    suspend fun resume() = mutation.withLock { setSuspended(false) }

    suspend fun delete() = withContext(NonCancellable + Dispatchers.IO) {
        mutation.withLock { deleteLocked() }
    }

    private suspend fun deleteLocked() {
        val wasSuspended = isSuspended()
        setSuspended(true)
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
                if (!location.deletionMarker.exists() || !location.deletionMatchesWallet(readSeed())) {
                    location.writeDeletionIdentity(readSeed(), syncIdentityDirectory)
                    syncFiles()
                }
                try { deregister() } catch (_: Exception) { warn("Deregistration failed while deleting wallet") }
                if (location.directory.exists()) {
                    check(!location.deletionDirectory.exists() && location.directory.renameTo(location.deletionDirectory)) { "Cannot stage wallet deletion" }
                    syncFiles()
                }
                check(!location.deletionDirectory.exists() || location.deletionDirectory.deleteRecursively()) { "Cannot delete wallet files" }
                check(!location.restoreDirectory.exists() || location.restoreDirectory.deleteRecursively()) { "Cannot delete restore files" }
                syncFiles()
                deleteSeed()
                clearToken()
                clearSettings()
                clearWidget()
                check(location.deletionMarker.delete())
                syncFiles()
            } finally { session.release(reservation) }
        } catch (failure: Exception) {
            if (!location.deletionMarker.exists()) setSuspended(wasSuspended)
            throw failure
        } finally { finishBackground() }
    }
}
