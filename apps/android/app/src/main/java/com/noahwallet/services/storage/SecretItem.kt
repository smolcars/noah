package com.noahwallet.services.storage

/** Restore reads after a failed write to determine whether the seed commit landed. */
interface SecretItem {
    suspend fun read(): SecretResult
    suspend fun save(value: String)
}

class SeedItem(private val storage: SeedStorage) : SecretItem {
    class Unavailable : Exception("Secure storage unavailable")
    override suspend fun read() = storage.read()
    override suspend fun save(value: String) {
        // The restore commit: an unverified write stays for launch recovery to read later.
        val result = storage.save(value, keepUnverifiedWrite = true)
        if (result !is SecretResult.Found || result.value != value) throw Unavailable()
    }
}
