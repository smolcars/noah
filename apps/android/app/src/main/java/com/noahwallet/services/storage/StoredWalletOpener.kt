package com.noahwallet.services.storage

/** Background opens never copy a seed. Only a verified foreground open may migrate it. */
internal class StoredWalletOpener(
    private val seed: SeedStorage,
    private val open: suspend (String) -> Unit,
) {
    private suspend fun read(): String = (seed.read() as? SecretResult.Found)?.value
        ?: throw IllegalStateException("Wallet seed unavailable")

    suspend fun ensureOpen() { open(read()) }

    suspend fun openInForeground() {
        val original = read()
        open(original) // The SDK checks the DB fingerprint before migration.
        check(seed.save(original) is SecretResult.Found) { "Wallet seed migration unavailable" }
    }
}
