package com.noahwallet.onchain

import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/** Stored inside the wallet datadir: deletion/restore also removes discovery and attempts. */
class ExitHistoryStore internal constructor(private val storage: OnchainFile) {
    constructor(file: File) : this(OnchainFile(file))
    private val mutex = Mutex()
    private fun read(): Map<String, List<String>> = if (storage.file.exists()) Json.decodeFromString(storage.file.readText()) else emptyMap()
    suspend fun ids(walletID: String): List<String> = mutex.withLock { read()[walletID].orEmpty() }
    suspend fun remember(ids: List<String>, walletID: String) = mutex.withLock {
        val values = read()
        val merged = (values[walletID].orEmpty() + ids).distinct().sorted()
        if (values[walletID] != merged) storage.write(Json.encodeToString(values + (walletID to merged)))
    }
}
