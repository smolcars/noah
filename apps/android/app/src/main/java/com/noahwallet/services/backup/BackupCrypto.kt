package com.noahwallet.services.backup

import java.security.NoSuchAlgorithmException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** RN v1/v2: version | salt16 | nonce12 | AES-256-GCM ciphertext | tag16; no AAD. */
object BackupCrypto {
    /** RN's PBKDF2-HMAC-SHA256 rounds: every backup is written and read with this many. Only tests pass fewer `rounds`. */
    const val pbkdf2Rounds = 600_000
    enum class Version(val byte: Byte) { legacyDirectory(1), snapshot(2) }
    enum class Reason { tooShort, newerVersion, unexpectedVersion, keyDerivationFailed, authenticationFailed }
    class Failure(val reason: Reason) : Exception(reason.name)

    fun encrypt(plaintext: ByteArray, mnemonic: String, version: Version, rounds: Int = pbkdf2Rounds): ByteArray {
        val random = SecureRandom()
        return encrypt(plaintext, mnemonic, version,
            ByteArray(16).also(random::nextBytes), ByteArray(12).also(random::nextBytes), rounds)
    }

    /** Deterministic fixture entry point. Production callers must use fresh random salt/nonce. */
    @Suppress("LongParameterList") // salt and nonce are the known-answer test seam
    internal fun encrypt(
        plaintext: ByteArray, mnemonic: String, version: Version, salt: ByteArray, nonce: ByteArray, rounds: Int = pbkdf2Rounds,
    ): ByteArray {
        require(salt.size == 16 && nonce.size == 12)
        val key = deriveKey(mnemonic, salt, rounds)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            byteArrayOf(version.byte) + salt + nonce + cipher.doFinal(plaintext)
        } finally { key.fill(0) }
    }

    fun decrypt(envelope: ByteArray, mnemonic: String, expecting: Version, rounds: Int = pbkdf2Rounds): ByteArray {
        headerFailure(envelope, expecting)?.let { throw Failure(it) }
        val key = deriveKey(mnemonic, envelope.copyOfRange(1, 17), rounds)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"),
                GCMParameterSpec(128, envelope.copyOfRange(17, 29)))
            cipher.doFinal(envelope, 29, envelope.size - 29)
        } catch (_: Exception) {
            throw Failure(Reason.authenticationFailed)
        } finally { key.fill(0) }
    }

    private fun headerFailure(envelope: ByteArray, expecting: Version): Reason? = when {
        envelope.size < 45 -> Reason.tooShort
        (envelope[0].toInt() and 255) > Version.snapshot.byte.toInt() -> Reason.newerVersion
        envelope[0] != expecting.byte -> Reason.unexpectedVersion
        else -> null
    }

    // Android only guarantees the PBKDF2-SHA256 factory on API 26+. RFC 8018's
    // single 32-byte block uses the platform HMAC primitive on API 24/25.
    internal fun deriveKeyOnApi24(mnemonic: String, salt: ByteArray, rounds: Int = pbkdf2Rounds): ByteArray {
        val password = mnemonic.toByteArray(Charsets.UTF_8)
        val mac = Mac.getInstance("HmacSHA256")
        try { mac.init(SecretKeySpec(if (password.isEmpty()) byteArrayOf(0) else password, "HmacSHA256")) }
        finally { password.fill(0) }
        var u = mac.doFinal(salt + byteArrayOf(0, 0, 0, 1))
        val key = u.copyOf()
        try {
            repeat(rounds - 1) {
                val next = mac.doFinal(u)
                u.fill(0)
                u = next
                for (index in key.indices) key[index] = (key[index].toInt() xor u[index].toInt()).toByte()
            }
            return key
        } finally { u.fill(0) }
    }

    private fun deriveKey(mnemonic: String, salt: ByteArray, rounds: Int): ByteArray {
        val password = mnemonic.toCharArray()
        val spec = PBEKeySpec(password, salt, rounds, 256)
        password.fill('\u0000')
        return try {
            val factory = try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256") }
            catch (_: NoSuchAlgorithmException) { null }
            factory?.generateSecret(spec)?.encoded ?: deriveKeyOnApi24(mnemonic, salt, rounds)
        } catch (_: Exception) {
            throw Failure(Reason.keyDerivationFailed)
        } finally { spec.clearPassword() }
    }
}
