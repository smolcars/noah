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

    /** Read only: do not migrate until the SDK has verified the existing DB's seed fingerprint. */
    suspend fun read(): SecretResult = migrationLock.withLock { readUnlocked() }

    private suspend fun readUnlocked(): SecretResult {
        val current = StoredMnemonic.validate(native.read())
        if (current is SecretResult.Unavailable) return current
        return StoredMnemonic.agree(listOf(current, StoredMnemonic.validate(legacy())))
    }

    suspend fun save(value: String, keepUnverifiedWrite: Boolean = false): SecretResult = migrationLock.withLock {
        if (StoredMnemonic.normalize(value) == null)
            return@withLock SecretResult.Unavailable(SecretFailure.invalidMnemonic)
        when (val current = readUnlocked()) {
            is SecretResult.Unavailable -> current
            is SecretResult.Found -> if (current.value != value) SecretResult.Unavailable(SecretFailure.seedConflict)
            else when (val copy = native.read()) {
                is SecretResult.Found -> if (copy.value == value) copy else SecretResult.Unavailable(SecretFailure.seedConflict)
                is SecretResult.Unavailable -> copy
                SecretResult.NotFound -> native.save(value, keepUnverifiedWrite)
            }
            SecretResult.NotFound -> native.save(value, keepUnverifiedWrite)
        }
    }

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
