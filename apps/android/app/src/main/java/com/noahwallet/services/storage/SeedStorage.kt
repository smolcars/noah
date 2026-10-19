package com.noahwallet.services.storage

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SeedStorage internal constructor(
    private val native: NativeSecretStore,
    private val legacy: suspend () -> SecretResult,
) {
    constructor(context: Context, variant: String) : this(
        nativeSecretStore(context, variant),
        { KeychainPassword.mnemonic(context, variant).read() },
    )

    suspend fun read(): SecretResult = migrationLock.withLock {
        when (val current = native.read()) {
            SecretResult.NotFound -> when (val old = legacy()) {
                is SecretResult.Found -> native.save(old.value)
                else -> old
            }
            else -> current
        }
    }

    suspend fun save(value: String): SecretResult = migrationLock.withLock { native.save(value) }

    companion object {
        private val migrationLock = Mutex()
    }
}
