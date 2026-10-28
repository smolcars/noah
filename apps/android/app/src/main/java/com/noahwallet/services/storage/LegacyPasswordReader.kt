package com.noahwallet.services.storage

import kotlinx.coroutines.CancellationException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec

/** Sources return null only when ALL three fields are absent, never for a partial record. */
@Suppress("LongParameterList") // dependency seams: tests inject each store, the key and the cipher
internal class LegacyPasswordReader(
    private val dataStore: suspend () -> Map<String, String>?,
    private val preferences: () -> Map<String, String>?,
    private val key: () -> SecretKey?,
    private val base64: (String) -> ByteArray,
    private val fallback: () -> SecretResult = { SecretResult.NotFound },
    private val compareSeeds: Boolean = false,
    private val cipherFactory: (String) -> Cipher = { Cipher.getInstance(it) },
) {
    suspend fun read(): SecretResult = try {
        if (compareSeeds) {
            StoredMnemonic.agree(listOf(
                dataStore()?.let(::decrypt) ?: SecretResult.NotFound,
                preferences()?.let(::decrypt) ?: SecretResult.NotFound,
                fallback(),
            ).map(StoredMnemonic::validate))
        } else {
            val row = dataStore() ?: preferences()
            if (row == null) fallback() else decrypt(row)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        SecretResult.Unavailable(SecretFailure.storageUnreadable)
    }

    private fun decrypt(row: Map<String, String>): SecretResult {
        val username = row["u"]
        val password = row["p"]
        val marker = row["c"]
        if (username == null || password == null || marker == null) {
            return SecretResult.Unavailable(SecretFailure.incompleteRecord)
        }
        val gcm = when (marker) {
            "KeystoreAESGCM_NoAuth" -> true
            "KeystoreAESCBC" -> false
            else -> return SecretResult.Unavailable(SecretFailure.unsupportedCipher)
        }
        val existingKey = key() ?: return SecretResult.Unavailable(SecretFailure.missingKey)
        return try {
            if (decryptField(username, gcm, existingKey) != "noah") SecretResult.Unavailable(SecretFailure.unexpectedAccount)
            else SecretResult.Found(decryptField(password, gcm, existingKey))
        } catch (_: java.nio.charset.CharacterCodingException) {
            SecretResult.Unavailable(if (compareSeeds) SecretFailure.invalidMnemonic else SecretFailure.invalidCiphertext)
        } catch (_: Exception) {
            SecretResult.Unavailable(SecretFailure.invalidCiphertext)
        }
    }

    private fun decryptField(encoded: String, gcm: Boolean, key: SecretKey): String {
        val bytes = base64(encoded)
        val ivLength = if (gcm) 12 else 16
        require(bytes.size >= ivLength + 16)
        // AndroidKeyStore requires RN's exact PKCS7 transformation for CBC keys.
        val cipher = cipherFactory(if (gcm) "AES/GCM/NoPadding" else "AES/CBC/PKCS7Padding")
        val parameters = if (gcm) GCMParameterSpec(128, bytes.copyOfRange(0, ivLength))
        else IvParameterSpec(bytes.copyOfRange(0, ivLength))
        cipher.init(Cipher.DECRYPT_MODE, key, parameters)
        val plaintext = cipher.doFinal(bytes, ivLength, bytes.size - ivLength)
        return try { plaintext.strictUtf8() } finally { plaintext.fill(0) }
    }
}
