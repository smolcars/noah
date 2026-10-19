package com.noahwallet.services.backup

import java.io.File
import java.util.UUID

/** Per-operation plaintext; launch sweeps abandoned folders without touching live operations. */
class ScratchSpace(val root: File) {
    private val inUse = mutableSetOf<String>()
    suspend fun <T> withFolder(purpose: String, body: suspend (File) -> T): T {
        val folder = synchronized(inUse) {
            File(root, "$purpose-${UUID.randomUUID()}").also {
                check(it.mkdirs()) { "Cannot create scratch folder" }
                inUse.add(it.name)
            }
        }
        try { return body(folder) }
        finally { synchronized(inUse) { folder.deleteRecursively(); inUse.remove(folder.name) } }
    }
    fun sweepAbandoned() = synchronized(inUse) {
        root.listFiles()?.filter { it.name !in inUse }?.forEach { it.deleteRecursively() }
        Unit
    }
}
