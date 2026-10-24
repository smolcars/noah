package com.noahwallet.services.backup

import com.noahwallet.services.storage.PersistedBackupState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A pass owns its slot until state is updated, then clears it BEFORE resuming flush waiters. */
class BackupCoordinator(private val dependencies: Dependencies) {
    enum class Reason { appForeground, databaseChanged, manual, push, resyncRequired, startup }
    class NotReady : Exception("""Wallet is not ready for backup""")
    class Dependencies(
        val perform: suspend (String?, () -> Unit) -> BackupService.Outcome,
        val canBackup: (Boolean) -> Boolean,
        val loadState: () -> PersistedBackupState,
        val saveState: (PersistedBackupState) -> Unit,
        val sleep: suspend (Long) -> Unit = { delay(it) },
        val jitter: () -> Double = { kotlin.random.Random.nextDouble(0.8, 1.2) },
        val now: () -> Double = { System.currentTimeMillis().toDouble() },
    )
    private val status = MutableStateFlow(dependencies.loadState().copy())
    val statusChanges = status.asStateFlow()
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var requested = 0L
    private var completed = 0L
    private var pendingRequireEnabled = true
    private var retryAttempt = 0
    private var debounce: Job? = null
    private var retry: Job? = null
    private var inFlight: Deferred<Result<Unit>>? = null

    fun reloadStatus() = synchronized(lock) { status.value = dependencies.loadState().copy() }

    fun schedule(reason: Reason, immediate: Boolean = false, requireEnabled: Boolean = true) = synchronized(lock) {
        if (!dependencies.canBackup(requireEnabled)) return@synchronized
        request(requireEnabled)
        retry?.cancel(); retry = null
        if (immediate) { debounce?.cancel(); debounce = null; passTask() }
        else trailing()
        Unit
    }
    suspend fun flush(reason: Reason, requireEnabled: Boolean = true) {
        val target = synchronized(lock) {
            if (!dependencies.canBackup(requireEnabled)) throw NotReady()
            request(requireEnabled); cancelScheduled(); requested
        }
        do {
            synchronized(lock) { passTask() }.await().getOrThrow()
        } while (synchronized(lock) { completed < target })
    }
    private fun request(requireEnabled: Boolean) {
        requested++; pendingRequireEnabled = pendingRequireEnabled && requireEnabled
        update { it.markPending() }
    }
    fun cancelScheduled() = synchronized(lock) {
        debounce?.cancel(); debounce = null; retry?.cancel(); retry = null
    }
    suspend fun settle() {
        val pass = synchronized(lock) { cancelScheduled(); inFlight }
        pass?.await()
        cancelScheduled()
    }

    suspend fun settleForDeletion() {
        val pass = synchronized(lock) { cancelScheduled(); inFlight }
        pass?.cancel()
        pass?.join()
        cancelScheduled()
    }

    suspend fun forgetUploadedBackup(id: String) {
        while (true) {
            val pass = synchronized(lock) {
                inFlight ?: run {
                    var deletedCurrent = false
                    update { deletedCurrent = it.forgetUploadedBackup(id) }
                    if (deletedCurrent) schedule(Reason.manual)
                    return
                }
            }
            pass.await()
        }
    }

    private fun passTask(): Deferred<Result<Unit>> {
        inFlight?.let { return it }
        return scope.async(start = CoroutineStart.LAZY) {
            try { pass() } finally { synchronized(lock) { inFlight = null } }
        }.also { inFlight = it; it.start() }
    }
    private suspend fun pass(): Result<Unit> {
        val (generation, requireEnabled, last) = synchronized(lock) {
            val required = pendingRequireEnabled
            if (!dependencies.canBackup(required)) return Result.failure(NotReady())
            pendingRequireEnabled = true
            Triple(requested, required, dependencies.loadState().lastUploadedSnapshotSha256)
        }
        return try {
            val outcome = dependencies.perform(last) { synchronized(lock) { update { it.markInProgress(dependencies.now()) } } }
            synchronized(lock) {
                retryAttempt = 0; retry?.cancel(); retry = null
                completed = maxOf(completed, generation)
                update { it.markSuccess(outcome.snapshotSha256, outcome.backupId, outcome.uploaded, requested > completed, dependencies.now()) }
                if (requested > completed) trailing()
            }
            Result.success(Unit)
        } catch (failure: Exception) {
            val cancelled = failure is CancellationException || !currentCoroutineContext().isActive
            synchronized(lock) {
                pendingRequireEnabled = pendingRequireEnabled && requireEnabled
                if (cancelled) {
                    update { it.markPending(); it.lastBackupStatus = "idle" }
                    return Result.failure(failure)
                }
                // Exception messages from HTTP/SDK providers can contain secrets or presigned URLs.
                update { it.markFailed("An unknown error occurred while backing up.") }
                if (retry == null && debounce == null && dependencies.canBackup(pendingRequireEnabled)) {
                    val delay = retryDelay(retryAttempt, dependencies.jitter()); retryAttempt = minOf(retryAttempt + 1, 10)
                    retry = timer(delay, false)
                }
            }
            Result.failure(failure)
        }
    }
    private fun trailing() { if (debounce == null) debounce = timer(5000, true) }
    private fun timer(milliseconds: Long, isDebounce: Boolean): Job = scope.launch(start = CoroutineStart.LAZY) {
        dependencies.sleep(milliseconds)
        synchronized(lock) {
            ensureActive()
            if (isDebounce) debounce = null else retry = null
            passTask()
        }
    }.also { it.start() }
    private fun update(change: (PersistedBackupState) -> Unit) {
        val next = dependencies.loadState().copy().also(change)
        dependencies.saveState(next)
        status.value = next.copy()
    }
    fun seedRestored(sha: String, backupId: String?) = synchronized(lock) {
        update { it.seedRestored(sha, backupId, dependencies.now()) }
    }
    companion object {
        fun retryDelay(attempt: Int, jitter: Double): Long =
            minOf((minOf(15000L * (1L shl attempt.coerceIn(0, 10)), 900000L) * jitter).toLong(), 900000L)
    }
}
