package com.noahwallet.services.backup

import java.io.File
import java.util.UUID

/** Per-operation plaintext; launch sweeps abandoned folders without touching live operations. */
class ScratchSpace(val root: File) {
    private val inUse = mutableSetOf<String>()
    suspend fun <T> withFolder(purpose: String, body: suspend (File) -> T): T {
        val folder = makeFolder(purpose)
        try { return body(folder) }
        finally { discard(folder) }
    }
    fun <T> withFolderBlocking(purpose: String, body: (File) -> T): T {
        val folder = makeFolder(purpose)
        try { return body(folder) }
        finally { discard(folder) }
    }
    /** For share-sheet lifetimes; the caller must discard after dismissal. */
    fun makeFolder(purpose: String): File = synchronized(inUse) {
        File(root, "$purpose-${UUID.randomUUID()}").also {
            check(it.mkdirs()) { "Cannot create scratch folder" }
            inUse.add(it.name)
        }
    }
    fun discard(folder: File) = synchronized(inUse) {
        folder.deleteRecursively(); inUse.remove(folder.name)
    }
    fun sweepAbandoned() = synchronized(inUse) {
        root.listFiles()?.filter { it.name !in inUse }?.forEach { it.deleteRecursively() }
        Unit
    }
}
