package com.noahwallet.services.storage

/** Never include a value or a provider exception in diagnostics. */
sealed interface SecretResult {
    class Found(val value: String) : SecretResult {
        override fun toString() = "Found([redacted])"
    }
    data object NotFound : SecretResult
    data class Unavailable(val reason: SecretFailure) : SecretResult
}

enum class SecretFailure {
    storageUnreadable, incompleteRecord, unsupportedCipher, missingKey,
    invalidCiphertext, unexpectedAccount, migrationFailed, invalidMnemonic, seedConflict,
}

internal fun ByteArray.strictUtf8(): String = Charsets.UTF_8.newDecoder()
    .decode(java.nio.ByteBuffer.wrap(this)).toString()
