package com.noahwallet.services.storage

/** Background opens never copy a seed. Only a verified foreground open may migrate it. */
internal class StoredWalletOpener(
    private val seed: SeedStorage,
    private val walletState: () -> PersistedWalletState?,
    private val open: suspend (String) -> Unit,
) {
    private suspend fun read(): String = (seed.read() as? SecretResult.Found)?.value
        ?: throw IllegalStateException("""Wallet seed unavailable""")

    class OnboardingIncomplete : Exception("""Finish onboarding before background wallet work""")

    fun requireHeadlessAdmission() {
        if (walletState()?.isInitialized == false || walletState()?.isWalletSuspended == true) throw OnboardingIncomplete()
    }

    suspend fun ensureOpen() { requireHeadlessAdmission(); open(read()) }

    suspend fun openInForeground() {
        val original = read()
        open(original) // The SDK checks the DB fingerprint before migration.
        check(seed.save(original) is SecretResult.Found) { "Wallet seed migration unavailable" }
    }
}
