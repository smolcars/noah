package com.noahwallet.services.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

internal class AndroidSecretKeys : SecretKeys {
    private fun store() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    override fun existing(alias: String): SecretKey? {
        val store = store()
        if (!store.containsAlias(alias)) return null
        return store.getKey(alias, null) as? SecretKey ?: error("""Secure key unavailable""")
    }

    override fun create(alias: String): SecretKey {
        check(!store().containsAlias(alias)) { "Refusing to replace a secure key" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false)
                .build())
            generateKey()
        }
    }
}

internal class AtomicSecretFile(private val path: File) : SecretFile {
    private val file = AtomicFile(path)

    override fun read(): ByteArray? = try {
        file.openRead().use { it.readBytes() }.also {
            // Read-back after a failed rename/sync is a commit decision: only return durable bytes.
            syncPath(path)
            syncPath(path.parentFile!!)
        }
    } catch (_: FileNotFoundException) {
        // AtomicFile also recovers its old .bak format on read; an unreadable existing file is NOT absent.
        if (path.exists() || File(path.path + ".bak").exists()) error("""Secure file unreadable""")
        null
    }

    private fun syncPath(target: File) {
        val descriptor = android.system.Os.open(target.path, android.system.OsConstants.O_RDONLY, 0)
        try { android.system.Os.fsync(descriptor) } finally { android.system.Os.close(descriptor) }
    }

    /** Durable: the unlink is synced, so a deleted seed can't reappear after power loss. */
    override fun delete() {
        file.delete()
        check(listOf(path, File(path.path + ".bak"), File(path.path + ".new")).none { it.exists() }) { "Cannot delete seed file" }
        syncPath(path.parentFile!!)
    }

    override fun writeAtomically(bytes: ByteArray) {
        // AtomicFile.finishWrite hides fsync failures. Restore requires real durability errors.
        val temporary = File(path.parentFile, ".seed-${java.util.UUID.randomUUID()}")
        try {
            java.io.FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
            android.system.Os.rename(temporary.path, path.path)
            syncPath(path.parentFile!!)
        } finally { temporary.delete() }
    }
}

internal fun nativeSecretStore(context: Context, variant: String): NativeSecretStore {
    require(variant in setOf("mainnet", "signet", "regtest"))
    return NativeSecretStore("noah.native.seed.$variant", AndroidSecretKeys(),
        AtomicSecretFile(File(context.noBackupFilesDir, "noah-native-seed-$variant.bin")))
}
