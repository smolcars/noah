package com.noahwallet.services

import android.content.Context
import android.content.ContextWrapper
import android.os.StrictMode
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.noahwallet.services.storage.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory

@RunWith(AndroidJUnit4::class)
class AndroidStorageTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    // Public BIP39 zero-entropy vector: the stores now accept only valid mnemonics.
    private val secret = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"

    @Test fun realKeystoreAtomicPersistenceAndMissingKey() {
        val alias = "noah.test.${UUID.randomUUID()}"
        val path = File(context.noBackupFilesDir, "$alias.bin")
        val keys = AndroidSecretKeys()
        val store = NativeSecretStore(alias, keys, AtomicSecretFile(path))
        try {
            assertSame(SecretResult.NotFound, store.read())
            assertNull(keys.existing(alias))
            assertFound(store.save(secret))
            val key = keys.existing(alias)!!
            val info = SecretKeyFactory.getInstance("AES", "AndroidKeyStore").getKeySpec(key, KeyInfo::class.java) as KeyInfo
            assertEquals(256, info.keySize)
            assertFalse(info.isUserAuthenticationRequired)
            val first = path.readBytes()
            assertFound(store.save(secret))
            assertFalse(first.contentEquals(path.readBytes()))
            assertFound(NativeSecretStore(alias, keys, AtomicSecretFile(path)).read())
            // Simulate a process dying before AtomicFile.finishWrite: the committed value survives.
            AtomicFile(path).startWrite().use { it.write(byteArrayOf(0, 1)) }
            assertFound(store.read())
            val preserved = path.readBytes()
            keyStore().deleteEntry(alias)
            assertTrue(store.read() is SecretResult.Unavailable)
            assertTrue(store.save("replacement") is SecretResult.Unavailable)
            assertNull(keys.existing(alias))
            assertArrayEquals(preserved, path.readBytes())
        } finally {
            keyStore().deleteEntry(alias)
            AtomicFile(path).delete()
        }
    }

    @Test fun realRNFormatsDataStoreAndPreferencesRemainUnchanged() = runBlocking {
        for (gcm in listOf(true, false)) for (dataStore in listOf(true, false)) checkRNFormat(gcm, dataStore)
    }

    /** The u/p/c row RN's keychain writes for [secret], encrypted under a new [alias] key in its GCM or CBC format. */
    private fun rnRow(alias: String, gcm: Boolean): Map<String, String> {
        val key = KeyGenerator.getInstance("AES", "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(if (gcm) "GCM" else "CBC")
                .setEncryptionPaddings(if (gcm) "NoPadding" else "PKCS7Padding").build())
            generateKey()
        }
        fun encrypted(value: String): String {
            val cipher = Cipher.getInstance(if (gcm) "AES/GCM/NoPadding" else "AES/CBC/PKCS7Padding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            return Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray()), Base64.DEFAULT)
        }
        return mapOf("u" to encrypted("noah"), "p" to encrypted(secret),
            "c" to if (gcm) "KeystoreAESGCM_NoAuth" else "KeystoreAESCBC")
    }

    /** Stores [row] under [keys] where RN's keychain kept it: its DataStore, or the older shared preferences. */
    private suspend fun writeRNRow(dataStore: Boolean, prefs: android.content.SharedPreferences, keys: Map<String, String>, row: Map<String, String>) {
        if (dataStore) context.rnKeychain.edit { p -> row.forEach { (k, v) -> p[stringPreferencesKey(keys.getValue(k))] = v } }
        else prefs.edit().apply { row.forEach { (k, v) -> putString(keys.getValue(k), v) } }.commit()
    }

    private suspend fun checkRNFormat(gcm: Boolean, dataStore: Boolean) {
        val variant = "test-${UUID.randomUUID()}"
        val reader = KeychainPassword.mnemonic(context, variant)
        val alias = reader.service
        val prefs = context.getSharedPreferences("RN_KEYCHAIN", Context.MODE_PRIVATE)
        val keys = listOf("u", "p", "c").associateWith { "$alias:$it" }
        val row = rnRow(alias, gcm)
        try {
            writeRNRow(dataStore, prefs, keys, row)
            val result = reader.read()
            assertTrue("RN read failed (gcm=$gcm, DataStore=$dataStore, reason=${(result as? SecretResult.Unavailable)?.reason})",
                result is SecretResult.Found && result.value == secret)
            assertFound(reader.read())
            if (!dataStore) row.forEach { (k, v) -> assertTrue(v == prefs.getString(keys.getValue(k), null)) }
            // The reader must not recreate a deleted key even when ciphertext still exists.
            keyStore().deleteEntry(alias)
            assertTrue(reader.read() is SecretResult.Unavailable)
            assertFalse(keyStore().containsAlias(alias))
        } finally {
            keyStore().deleteEntry(alias)
            context.rnKeychain.edit { p -> keys.values.forEach { p.remove(stringPreferencesKey(it)) } }
            prefs.edit().apply { keys.values.forEach(::remove) }.commit()
        }
    }

    @Test fun expoPrecedenceMigrationAndSessionCaching() {
        val directory = File(context.cacheDir, "expo-test-${UUID.randomUUID()}").apply { mkdirs() }
        val prefsName = "expo-test-${UUID.randomUUID()}"
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val primary = UUID.randomUUID().toString()
        val preference = UUID.randomUUID().toString()
        val legacyFile = UUID.randomUUID().toString()
        try {
            File(directory, ExpoInstallationID.fileName).writeText(primary)
            prefs.edit().putString("uuid", preference).commit()
            File(directory, "expo_installation_uuid.txt").writeText(legacyFile)
            assertEquals(primary, ExpoInstallationID(directory, prefs).getOrCreate())
            File(directory, ExpoInstallationID.fileName).delete()
            assertEquals(preference, ExpoInstallationID(directory, prefs).getOrCreate())
            assertFalse(prefs.contains("uuid"))
            assertEquals(preference, File(directory, ExpoInstallationID.fileName).readText())
            File(directory, ExpoInstallationID.fileName).writeText("invalid UUID")
            assertEquals(legacyFile, ExpoInstallationID(directory, prefs).getOrCreate())
            assertTrue(File(directory, "expo_installation_uuid.txt").exists())
            directory.listFiles()!!.forEach { it.delete() }
            val created = ExpoInstallationID(directory, prefs).getOrCreate()
            assertEquals(created, ExpoInstallationID(directory, prefs).getOrCreate())
            val badDirectory = File(directory, "file-not-directory").apply { writeText("") }
            val unavailable = ExpoInstallationID(badDirectory, prefs)
            val session = unavailable.getOrCreate()
            assertEquals(session, unavailable.getOrCreate())
        } finally {
            directory.deleteRecursively()
            context.deleteSharedPreferences(prefsName)
        }
    }

    /** On a real filesystem the settings stamp works: unchanged reads never open the file; a same-size replacement is read. */
    @SdkSuppress(minSdkVersion = 28) // StrictMode's violation listener
    @Test
    fun settingsReadsComeFromMemoryUntilTheFileIsReplaced() {
        val root = File(context.cacheDir, "settings-cache-${UUID.randomUUID()}").apply { check(mkdir()) }
        val isolated = object : ContextWrapper(context) {
            override fun getFilesDir() = root
            override fun getNoBackupFilesDir() = root
        }
        val previous = StrictMode.getThreadPolicy()
        val reads = java.util.concurrent.CopyOnWriteArrayList<String>()
        val watching = StrictMode.ThreadPolicy.Builder().detectDiskReads()
            .penaltyListener(Runnable::run) { reads += it.stackTrace.joinToString("\n") }.build()
        fun watched(body: () -> Unit) { StrictMode.setThreadPolicy(watching); try { body() } finally { StrictMode.setThreadPolicy(previous) } }
        try {
            val settings = SettingsStorage(isolated)
            settings.set("ours", "key")
            assertEquals("ours", settings.string("key")) // the read after a write verifies the file
            watched { repeat(5) { assertEquals("ours", settings.string("key")) } }
            assertTrue(reads.joinToString("\n\n"), reads.none { "NativeSettingsFile" in it })
            val size = settings.nativeFile.file.length()
            settings.nativeFile.save(NativeSettingsFile.State(mapOf("key" to "them"))) // same size, moments later
            check(settings.nativeFile.file.length() == size)
            watched { assertEquals("them", settings.string("key")) }
            assertTrue("A replaced file is read again", reads.any { "NativeSettingsFile.load" in it })
        } finally { StrictMode.setThreadPolicy(previous); root.deleteRecursively() }
    }

    private fun assertFound(result: SecretResult) {
        assertTrue("Secret round trip failed", result is SecretResult.Found && result.value == secret)
    }
    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
}
