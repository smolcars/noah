package com.noahwallet.services

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.noahwallet.services.storage.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.util.UUID

@Suppress("DEPRECATION")
@RunWith(AndroidJUnit4::class)
class EncryptedMnemonicTest {
    @Test fun fallbackReadsExistingKeysetsAndNeverCreatesMissingKeys() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "noah-test-${UUID.randomUUID()}"
        val alias = "noah.test.master.${UUID.randomUUID()}"
        val file = File(context.applicationInfo.dataDir, "shared_prefs/$name.xml")
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        try {
            assertSame(SecretResult.NotFound, readEncryptedMnemonic(context, "regtest", name, alias))
            assertFalse(store.containsAlias(alias))
            // The writer is test setup only, configured exactly like MasterKeys.AES256_GCM_SPEC.
            MasterKeys.getOrCreate(KeyGenParameterSpec.Builder(alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes("GCM").setEncryptionPaddings("NoPadding").build())
            val prefs = EncryptedSharedPreferences.create(name, alias, context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
            prefs.edit().putString("mnemonic_regtest", "test-only-fallback").commit()
            val bytes = file.readBytes()
            val result = readEncryptedMnemonic(context, "regtest", name, alias)
            assertTrue(result is SecretResult.Found && result.value == "test-only-fallback")
            assertSame(SecretResult.NotFound, readEncryptedMnemonic(context, "signet", name, alias))
            assertArrayEquals(bytes, file.readBytes())
            store.deleteEntry(alias)
            assertEquals(SecretResult.Unavailable(SecretFailure.missingKey),
                readEncryptedMnemonic(context, "regtest", name, alias))
            assertFalse(store.containsAlias(alias))
            assertArrayEquals(bytes, file.readBytes())
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit()
                .remove("__androidx_security_crypto_encrypted_prefs_key_keyset__").commit()
            val partial = file.readBytes()
            assertEquals(SecretResult.Unavailable(SecretFailure.incompleteRecord),
                readEncryptedMnemonic(context, "regtest", name, alias))
            assertArrayEquals(partial, file.readBytes())
            assertFalse(store.containsAlias(alias))
        } finally {
            store.deleteEntry(alias)
            context.deleteSharedPreferences(name)
        }
    }
}
