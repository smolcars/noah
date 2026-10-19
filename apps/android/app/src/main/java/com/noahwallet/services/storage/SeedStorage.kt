package com.noahwallet.services.storage

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SeedStorage internal constructor(
    private val native: NativeSecretStore,
    private val deleteLegacy: suspend () -> Unit,
    private val legacy: suspend () -> SecretResult,
) {
    internal constructor(native: NativeSecretStore, legacy: suspend () -> SecretResult) : this(native, {}, legacy)

    constructor(context: Context, variant: String) : this(
        nativeSecretStore(context, variant),
        { KeychainPassword.mnemonic(context, variant).deleteWalletSeed() },
        { KeychainPassword.mnemonic(context, variant).read() },
    )

    /**
     * New store first; otherwise the RN app's seed, copied into the new store. A failed copy
     * still returns the legacy seed (the copy is retried next time) so a storage hiccup can
     * never lock the user out. An unreadable new store stays fail-closed: it may hold a newer
     * seed than the legacy record (e.g. after a native restore).
     */
    suspend fun read(): SecretResult = migrationLock.withLock {
        when (val current = native.read()) {
            SecretResult.NotFound -> when (val old = legacy()) {
                is SecretResult.Found -> {
                    native.save(old.value)
                    old
                }
                else -> old
            }
            else -> current
        }
    }

    suspend fun save(value: String, keepUnverifiedWrite: Boolean = false): SecretResult =
        migrationLock.withLock { native.save(value, keepUnverifiedWrite) }

    internal suspend fun delete(onlyIfValueIs: String) = migrationLock.withLock {
        native.delete(onlyIfValueIs)
    }

    suspend fun deleteWalletSeed() = migrationLock.withLock {
        // Keep the native copy if clearing a legacy source fails; never migrate an old seed back.
        deleteLegacy()
        native.deleteWalletSeed()
    }

    companion object {
        private val migrationLock = Mutex()
    }
}
