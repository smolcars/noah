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
        return store.getKey(alias, null) as? SecretKey ?: error("Secure key unavailable")
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
        file.openRead().use { it.readBytes() }
    } catch (_: FileNotFoundException) {
        // AtomicFile also recovers its old .bak format on read; an unreadable existing file is NOT absent.
        if (path.exists() || File(path.path + ".bak").exists()) error("Secure file unreadable")
        null
    }

    override fun delete() = file.delete()

    override fun writeAtomically(bytes: ByteArray) {
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            file.finishWrite(stream)
        } catch (failure: Exception) {
            file.failWrite(stream)
            throw failure
        }
    }
}

internal fun nativeSecretStore(context: Context, variant: String): NativeSecretStore {
    require(variant in setOf("mainnet", "signet", "regtest"))
    return NativeSecretStore("noah.native.seed.$variant", AndroidSecretKeys(),
        AtomicSecretFile(File(context.noBackupFilesDir, "noah-native-seed-$variant.bin")))
}
