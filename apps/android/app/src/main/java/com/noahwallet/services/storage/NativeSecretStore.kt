package com.noahwallet.services.storage

import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The only key creation path is an explicit write to an absent/readable native store. */
internal interface SecretKeys {
    fun existing(alias: String): SecretKey?
    fun create(alias: String): SecretKey
}

internal interface SecretFile {
    fun read(): ByteArray?
    fun writeAtomically(bytes: ByteArray)
    fun delete()
}

/** v1: [version:1][random nonce:12][AES-256-GCM ciphertext + tag:16]. */
internal class NativeSecretStore(
    private val alias: String,
    private val keys: SecretKeys,
    private val file: SecretFile,
) {
    fun read(): SecretResult = synchronized(lock) { readUnlocked() }

    private fun readUnlocked(): SecretResult = try {
        val bytes = file.read()
        if (bytes == null) SecretResult.NotFound
        else if (bytes.size < 29 || bytes[0] != 1.toByte()) {
            SecretResult.Unavailable(SecretFailure.invalidCiphertext)
        } else {
            val key = keys.existing(alias)
            if (key == null) SecretResult.Unavailable(SecretFailure.missingKey)
            else {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
                val plaintext = cipher.doFinal(bytes, 13, bytes.size - 13)
                try { SecretResult.Found(plaintext.strictUtf8()) } finally { plaintext.fill(0) }
            }
        }
    } catch (_: java.nio.charset.CharacterCodingException) {
        SecretResult.Unavailable(SecretFailure.invalidMnemonic)
    } catch (_: Exception) {
        SecretResult.Unavailable(SecretFailure.storageUnreadable)
    }

    /**
     * Refuse to overwrite unreadable data. Verify persistence before handing the value to callers.
     * `keepUnverifiedWrite`: a restore's seed commit must never delete a written-but-unverified
     * seed (it may be durable); the restore keeps its journal and settles once it can be read.
     */
    fun save(value: String, keepUnverifiedWrite: Boolean = false): SecretResult = synchronized(lock) {
        val prior = readUnlocked()
        if (prior is SecretResult.Unavailable) return@synchronized prior
        try {
            val key = keys.existing(alias) ?: keys.create(alias)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            // AndroidKeyStore generates the random nonce; never supply an encryption IV for this key.
            cipher.init(Cipher.ENCRYPT_MODE, key)
            check(cipher.iv.size == 12)
            val plaintext = value.toByteArray(Charsets.UTF_8)
            val ciphertext = try { cipher.doFinal(plaintext) } finally { plaintext.fill(0) }
            file.writeAtomically(byteArrayOf(1) + cipher.iv + ciphertext)
            val verified = readUnlocked()
            if (verified is SecretResult.Found && verified.value == value) verified
            else failedWrite(prior, keepUnverifiedWrite)
        } catch (_: Exception) {
            failedWrite(prior, keepUnverifiedWrite)
        }
    }

    /** Roll back only our fresh creation; never remove a different or unreadable seed. */
    fun delete(onlyIfValueIs: String) = synchronized(lock) {
        val current = readUnlocked()
        if (current is SecretResult.Found && current.value == onlyIfValueIs) file.delete()
    }

    fun deleteWalletSeed() = synchronized(lock) { file.delete() }

    /**
     * Migration and creation: a failed first write must not leave an unreadable file that would
     * mask the legacy seed forever (the deletion is durable). A restore commit keeps it instead.
     */
    private fun failedWrite(prior: SecretResult, keepUnverifiedWrite: Boolean): SecretResult {
        if (!keepUnverifiedWrite && prior == SecretResult.NotFound && readUnlocked() !is SecretResult.Found) {
            runCatching { file.delete() }
        }
        return SecretResult.Unavailable(SecretFailure.migrationFailed)
    }

    companion object {
        // All instances in the app process share the key/file critical section.
        private val lock = Any()
    }
}
