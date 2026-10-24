package com.noahwallet.services.backup

import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first

/** Per-operation plaintext; launch sweeps abandoned folders without touching live operations. */
class ScratchSpace(val root: File, private val syncDirectory: (File) -> Unit = { directory ->
    val descriptor = android.system.Os.open(directory.path, android.system.OsConstants.O_RDONLY, 0)
    try { android.system.Os.fsync(descriptor) } finally { android.system.Os.close(descriptor) }
}) {
    private val inUse = mutableSetOf<String>()
    private var deleting = false
    private val operations = kotlinx.coroutines.flow.MutableStateFlow(0)

    suspend fun <T> withOperation(body: suspend () -> T): T {
        synchronized(inUse) { check(!deleting); operations.value++ }
        try { return body() }
        finally { synchronized(inUse) { operations.value-- } }
    }

    suspend fun drainForWalletDeletion(timeoutMillis: Long = 30_000) {
        synchronized(inUse) { check(!deleting); deleting = true }
        try { kotlinx.coroutines.withTimeout(timeoutMillis) { operations.first { it == 0 } } }
        catch (failure: Exception) {
            finishWalletDeletion()
            if (failure is kotlinx.coroutines.TimeoutCancellationException) {
                throw com.noahwallet.services.wallet.WalletLifecycle.BackupBusy()
            }
            throw failure
        }
    }

    fun finishWalletDeletion() = synchronized(inUse) { deleting = false }

    suspend fun <T> withFolder(purpose: String, body: suspend (File) -> T): T = withOperation {
        val folder = makeFolder(purpose)
        try { body(folder) }
        finally { discard(folder) }
    }
    fun <T> withFolderBlocking(purpose: String, body: (File) -> T): T {
        val folder = makeFolder(purpose)
        try { return body(folder) }
        finally { discard(folder) }
    }
    /** For share-sheet lifetimes; the caller must discard after dismissal. */
    fun makeFolder(purpose: String): File = synchronized(inUse) {
        check(!deleting) { "Wallet deletion is in progress" }
        File(root, "$purpose-${UUID.randomUUID()}").also {
            check(it.mkdirs()) { "Cannot create scratch folder" }
            inUse.add(it.name)
        }
    }
    fun discard(folder: File) = synchronized(inUse) {
        folder.deleteRecursively(); inUse.remove(folder.name)
    }
    /** Deletion must fail before erasing the seed if any plaintext survives or is still in use. */
    fun sweepForWalletDeletion() = synchronized(inUse) {
        check(operations.value == 0 && (deleting || inUse.isEmpty())) { "Scratch operations are still active" }
        if (root.exists()) {
            val folders = root.listFiles() ?: error("""Cannot inspect scratch files""")
            folders.forEach { check(it.deleteRecursively()) { "Cannot delete scratch files" } }
            inUse.clear()
            syncDirectory(root)
        }
        root.parentFile?.takeIf { it.exists() }?.let(syncDirectory)
    }

    fun sweepAbandoned() = synchronized(inUse) {
        root.listFiles()?.filter { it.name !in inUse }?.forEach { it.deleteRecursively() }
        Unit
    }
}
