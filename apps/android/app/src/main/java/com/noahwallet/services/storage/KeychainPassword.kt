package com.noahwallet.services.storage

import android.content.Context
import android.security.keystore.KeyInfo
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import kotlinx.coroutines.flow.first
import java.io.File
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory

// One instance per process/file. Deliberately NO SharedPreferencesMigration (which deletes legacy entries).
internal val Context.rnKeychain by preferencesDataStore(name = "RN_KEYCHAIN")

class KeychainPassword private constructor(
    private val context: Context,
    val service: String,
    private val mnemonicVariant: String? = null,
) {
    suspend fun read(): SecretResult = LegacyPasswordReader(
        dataStore = {
            val prefs = context.rnKeychain.data.first()
            fields.mapNotNull { field ->
                prefs[stringPreferencesKey("$service:$field")]?.let { field to it }
            }.toMap().takeIf { it.isNotEmpty() }
        },
        preferences = {
            val prefs = context.getSharedPreferences("RN_KEYCHAIN", Context.MODE_PRIVATE)
            fields.mapNotNull { field -> prefs.getString("$service:$field", null)?.let { field to it } }
                .toMap().takeIf { it.isNotEmpty() }
        },
        key = { AndroidSecretKeys().existing(service) },
        base64 = { Base64.decode(it, Base64.DEFAULT) },
        compareSeeds = mnemonicVariant != null,
        fallback = { mnemonicVariant?.let { readEncryptedMnemonic(context, it) } ?: SecretResult.NotFound },
    ).read()

    /** Write only renewable server credentials, preserving RN's alias, cipher and :u/:p/:c row. */
    suspend fun saveAuthToken(token: String) {
        check(service.startsWith("com.noah.auth-token."))
        val keys = AndroidSecretKeys()
        val key = keys.existing(service) ?: keys.create(service)
        val info = SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore")
            .getKeySpec(key, KeyInfo::class.java) as KeyInfo
        val gcm = "GCM" in info.blockModes
        check(gcm || "CBC" in info.blockModes) { "Unsupported auth token cipher" }
        fun encrypt(value: String): String {
            val cipher = Cipher.getInstance(if (gcm) "AES/GCM/NoPadding" else "AES/CBC/PKCS7Padding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            return Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.DEFAULT)
        }
        val username = encrypt("noah")
        val password = encrypt(token)
        context.rnKeychain.edit {
            it[stringPreferencesKey("$service:u")] = username
            it[stringPreferencesKey("$service:p")] = password
            it[stringPreferencesKey("$service:c")] = if (gcm) "KeystoreAESGCM_NoAuth" else "KeystoreAESCBC"
        }
    }

    suspend fun clearAuthToken() {
        check(service.startsWith("com.noah.auth-token."))
        clearRecords()
    }

    suspend fun deleteWalletSeed() {
        check(mnemonicVariant != null)
        clearRecords()
        // This preferences file is wallet-specific (the package separates variants). Clearing even
        // unreadable encrypted records prevents the fallback reader from resurrecting a deleted seed.
        check(context.getSharedPreferences("noah_native_secrets", Context.MODE_PRIVATE).edit().clear().commit())
        check(context.deleteSharedPreferences("noah_native_secrets")) { "Cannot delete legacy seed preferences" }
        val directory = File(context.applicationInfo.dataDir, "shared_prefs")
        if (directory.exists()) {
            val fd = android.system.Os.open(directory.path, android.system.OsConstants.O_RDONLY, 0)
            try { android.system.Os.fsync(fd) } finally { android.system.Os.close(fd) }
        }
    }

    private suspend fun clearRecords() {
        context.rnKeychain.edit { prefs -> fields.forEach { prefs.remove(stringPreferencesKey("$service:$it")) } }
        val editor = context.getSharedPreferences("RN_KEYCHAIN", Context.MODE_PRIVATE).edit()
        fields.forEach { editor.remove("$service:$it") }
        check(editor.commit()) { "Cannot clear server token" }
    }

    companion object {
        private val fields = listOf("u", "p", "c")
        fun mnemonic(context: Context, variant: String) = KeychainPassword(context.applicationContext, "com.noah.mnemonic.$variant", variant)
        fun serverAuthToken(context: Context, variant: String) = KeychainPassword(context.applicationContext, "com.noah.auth-token.$variant")
    }
}

@Suppress("DEPRECATION")
internal fun readEncryptedMnemonic(
    context: Context,
    variant: String,
    preferencesName: String = "noah_native_secrets",
    masterAlias: String = MasterKeys.AES256_GCM_SPEC.keystoreAlias,
): SecretResult {
    val path = File(context.applicationInfo.dataDir, "shared_prefs/$preferencesName.xml")
    if (!path.exists() && !File(path.path + ".bak").exists()) return SecretResult.NotFound
    val raw = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    // EncryptedSharedPreferences.create generates missing keysets. Do not let a reader do that.
    if (!raw.contains("__androidx_security_crypto_encrypted_prefs_key_keyset__") ||
        !raw.contains("__androidx_security_crypto_encrypted_prefs_value_keyset__")) {
        return SecretResult.Unavailable(SecretFailure.incompleteRecord)
    }
    if (AndroidSecretKeys().existing(masterAlias) == null) {
        return SecretResult.Unavailable(SecretFailure.missingKey)
    }
    val prefs = EncryptedSharedPreferences.create(preferencesName, masterAlias,
        context, EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    return prefs.getString("mnemonic_$variant", null)?.let { SecretResult.Found(it) }
        ?: SecretResult.NotFound
}
