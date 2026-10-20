package com.noahwallet.services.wallet

import com.noahwallet.services.storage.WalletDataLocation
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
    private val warn: (String) -> Unit = {},
) {
    class RestorePending : Exception("Restart Noah to settle the pending restore before deleting")
    class TemporaryFiles : Exception("Noah couldn't remove temporary wallet files. Restart Noah and try again.")
    private val mutation = Mutex()

    suspend fun recoverDeletion() = withContext(NonCancellable + Dispatchers.IO) {
        mutation.withLock { if (location.deletionMarker.exists()) deleteLocked() }
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
            settleBackground()
            drainExports()
            session.closeSettled()
            val reservation = session.reserve()
            try {
                if (location.restorePending) throw RestorePending()
                try { sweepScratch() } catch (_: Exception) { throw TemporaryFiles() }
                // The durable marker commits the deletion; every later launch finishes it.
                if (!location.deletionMarker.exists()) {
                    java.io.FileOutputStream(location.deletionMarker).use { it.write(1); it.fd.sync() }
                    syncFiles()
                }
                try { deregister() } catch (_: Exception) { warn("Deregistration failed while deleting wallet") }
                check(!location.directory.exists() || location.directory.deleteRecursively()) { "Cannot delete wallet files" }
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
