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
    private val syncFiles: () -> Unit = {},
    private val warn: (String) -> Unit = {},
) {
    class RestorePending : Exception("Restart Noah to settle the pending restore before deleting")
    private val mutation = Mutex()

    suspend fun suspend() = withContext(NonCancellable + Dispatchers.IO) {
        mutation.withLock {
            setSuspended(true)
            try { settleBackground(); session.closeSettled() }
            catch (failure: Exception) { setSuspended(false); throw failure }
        }
    }

    suspend fun resume() = mutation.withLock { setSuspended(false) }

    suspend fun delete() = withContext(NonCancellable + Dispatchers.IO) {
        mutation.withLock {
            // Also gates delayed foreground/push opens and drains backup state writes before MMKV is cleared.
            setSuspended(true)
            settleBackground()
            try { deregister() } catch (_: Exception) { warn("Deregistration failed while deleting wallet") }
            session.closeSettled()
            val reservation = session.reserve()
            try {
                if (location.restorePending) throw RestorePending()
                check(!location.directory.exists() || location.directory.deleteRecursively()) { "Cannot delete wallet files" }
                check(!location.restoreDirectory.exists() || location.restoreDirectory.deleteRecursively()) { "Cannot delete restore files" }
                sweepScratch()
                syncFiles()
                deleteSeed()
                clearToken()
                clearSettings()
                clearWidget()
            } finally { session.release(reservation) }
        }
    }
}
