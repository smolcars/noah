package com.noahwallet.services.push

import android.content.Context
import com.noahwallet.BuildConfig
import com.noahwallet.services.storage.AndroidSecretKeys
import com.noahwallet.services.storage.AtomicSecretFile
import com.noahwallet.services.storage.SecretFile
import com.noahwallet.services.storage.SecretKeys
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec

/**
 * The key the server seals an LNURL-pay payment's payer data with in its push (docs/lnurl_pay.md). Registered with
 * the push token; kept outside backups, encrypted under an AndroidKeyStore key, as `[version:1][nonce:12][AES-GCM]`.
 * Unlike the seed, a key that can't be read is simply replaced: the next registration sends the new one.
 */
internal class NotificationKey(private val alias: String, private val keys: SecretKeys, private val file: SecretFile) {
    /** The stored key, or null when there is none or it can't be read. */
    fun current(): ByteArray? = synchronized(lock) { read() }

    /** The stored key, made and stored first when there is none (or it can't be read). */
    fun currentOrCreate(): ByteArray = synchronized(lock) {
        read() ?: ByteArray(KEY_BYTES).also { key ->
            SecureRandom().nextBytes(key)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            // AndroidKeyStore generates the random nonce; never supply an encryption IV for this key.
            cipher.init(Cipher.ENCRYPT_MODE, keys.existing(alias) ?: keys.create(alias))
            file.writeAtomically(byteArrayOf(1) + cipher.iv + cipher.doFinal(key))
        }
    }

    private fun read(): ByteArray? = try {
        val bytes = file.read()?.takeIf { it.size == STORED_BYTES && it[0] == 1.toByte() }
        val key = keys.existing(alias)
        if (bytes == null || key == null) null
        else Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
            doFinal(bytes, 13, bytes.size - 13)
        }
    } catch (_: Exception) { null }

    companion object {
        const val KEY_BYTES = 32
        private const val STORED_BYTES = 1 + 12 + KEY_BYTES + 16
        private val lock = Any()

        /** Base64 for `register_push_token`; null when the keystore fails, so the token still registers. */
        fun registrationValue(context: Context): String? = runCatching {
            android.util.Base64.encodeToString(of(context).currentOrCreate(), android.util.Base64.NO_WRAP)
        }.getOrNull()

        fun of(context: Context, variant: String = BuildConfig.APP_VARIANT): NotificationKey {
            require(variant in setOf("mainnet", "signet", "regtest"))
            return NotificationKey("noah.native.notification-key.$variant", AndroidSecretKeys(),
                AtomicSecretFile(File(context.noBackupFilesDir, "noah-notification-key-$variant.bin")))
        }
    }
}
