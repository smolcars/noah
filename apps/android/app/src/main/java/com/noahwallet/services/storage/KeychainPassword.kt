package com.noahwallet.services.storage

import android.content.Context
import android.util.Base64
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import java.io.File
import kotlinx.coroutines.flow.first

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
        fallback = { mnemonicVariant?.let { readEncryptedMnemonic(context, it) } ?: SecretResult.NotFound },
    ).read()

    companion object {
        private val fields = listOf("u", "p", "c")
        fun mnemonic(context: Context, variant: String) = KeychainPassword(
            context.applicationContext, "com.noah.mnemonic.$variant", variant)
        fun serverAuthToken(context: Context, variant: String) = KeychainPassword(
            context.applicationContext, "com.noah.auth-token.$variant")
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
