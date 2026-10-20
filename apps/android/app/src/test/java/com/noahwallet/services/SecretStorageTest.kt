package com.noahwallet.services

import com.noahwallet.services.storage.*
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SecretStorageTest {
    private val secret = List(11) { StoredMnemonic.wordList[0] }.plus(StoredMnemonic.wordList[3]).joinToString(" ")
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private fun row(gcm: Boolean, username: String = "noah", password: String = secret): Map<String, String> {
        fun encrypt(value: String): String {
            val cipher = Cipher.getInstance(if (gcm) "AES/GCM/NoPadding" else "AES/CBC/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            return Base64.getMimeEncoder().encodeToString(cipher.iv + cipher.doFinal(value.toByteArray()))
        }
        return mapOf("u" to encrypt(username), "p" to encrypt(password),
            "c" to if (gcm) "KeystoreAESGCM_NoAuth" else "KeystoreAESCBC")
    }

    private fun reader(
        data: Map<String, String>? = null, prefs: Map<String, String>? = null,
        existing: SecretKey? = key, fallback: () -> SecretResult = { SecretResult.NotFound },
    ) = LegacyPasswordReader({ data }, { prefs }, { existing }, { Base64.getMimeDecoder().decode(it) }, fallback,
        // SunJCE calls AES's identical block padding PKCS5; AndroidKeyStore needs PKCS7.
        cipherFactory = { Cipher.getInstance(it.replace("PKCS7Padding", "PKCS5Padding")) })

    @Test fun readsBothCiphersAndBothLocations() = runBlocking {
        for (gcm in listOf(true, false)) for (fromDataStore in listOf(true, false)) {
            val row = row(gcm)
            val result = if (fromDataStore) reader(data = row).read() else reader(prefs = row).read()
            assertTrue(result is SecretResult.Found && result.value == secret)
            assertFalse(result.toString().contains(secret))
        }
    }

    @Test fun onlyAbsenceAllowsFallback() = runBlocking {
        var called = false
        val fallback = { called = true; SecretResult.Found(secret) }
        assertTrue(reader(fallback = fallback).read() is SecretResult.Found)
        assertTrue(called)
        called = false
        val valid = row(true)
        for (broken in listOf(mapOf("c" to "KeystoreAESGCM_NoAuth"), valid + ("c" to "unknown"),
            valid + ("p" to "broken"), row(true, "someone-else"))) {
            assertTrue(reader(data = broken, prefs = valid, fallback = fallback).read() is SecretResult.Unavailable)
            assertFalse(called)
        }
        assertTrue(reader(data = valid, existing = null, fallback = fallback).read() is SecretResult.Unavailable)
        assertFalse(called)
        assertTrue(reader(data = valid, prefs = row(false, "wrong"), fallback = fallback).read() is SecretResult.Found)
    }

    @Test fun mnemonicComparesBothRNCiphersAndFallbackWithoutChoosingOne() = runBlocking {
        val other = List(23) { StoredMnemonic.wordList[0] }.plus(StoredMnemonic.wordList[102]).joinToString(" ")
        for (gcm in listOf(true, false)) {
            fun compare(prefs: Map<String, String>, fallback: SecretResult) = LegacyPasswordReader(
                { row(gcm) }, { prefs }, { key }, { Base64.getMimeDecoder().decode(it) }, { fallback },
                compareSeeds = true, cipherFactory = { Cipher.getInstance(it.replace("PKCS7Padding", "PKCS5Padding")) })
            assertFound(compare(row(!gcm), SecretResult.Found(secret)).read())
            assertEquals(SecretFailure.seedConflict, (compare(row(!gcm, password = other), SecretResult.NotFound).read() as SecretResult.Unavailable).reason)
            assertEquals(SecretFailure.seedConflict, (compare(row(!gcm), SecretResult.Found(other)).read() as SecretResult.Unavailable).reason)
            assertEquals(SecretFailure.invalidMnemonic, (compare(row(!gcm, password = ""), SecretResult.NotFound).read() as SecretResult.Unavailable).reason)
            assertTrue(compare(mapOf("c" to "broken"), SecretResult.NotFound).read() is SecretResult.Unavailable)
        }
    }

    @Test fun unreadableDataStoreAndCancellationDoNotFallThrough() = runBlocking {
        var touched = false
        val reader = LegacyPasswordReader({ throw IllegalStateException(secret) },
            { touched = true; null }, { key }, { ByteArray(0) })
        val result = reader.read()
        assertTrue(result is SecretResult.Unavailable)
        assertFalse(touched)
        assertFalse(result.toString().contains(secret))
        val cancelled = LegacyPasswordReader({ throw CancellationException() }, { null }, { key }, { ByteArray(0) })
        assertThrows(CancellationException::class.java) { runBlocking { cancelled.read() } }
        Unit
    }

    @Test fun nativeRoundTripFreshNoncesAndNoKeyCreationOnRead() {
        val keys = FakeKeys()
        val file = FakeFile()
        val store = NativeSecretStore("test", keys, file)
        assertSame(SecretResult.NotFound, store.read())
        assertEquals(0, keys.created)
        assertFound(store.save(secret))
        val first = file.bytes!!.copyOf()
        assertFound(store.save(secret))
        assertFalse(first.contentEquals(file.bytes))
        assertEquals(1, keys.created)
        assertFound(NativeSecretStore("test", keys, file).read())
        keys.key = null
        assertTrue(store.read() is SecretResult.Unavailable)
        assertTrue(store.save("replacement") is SecretResult.Unavailable)
        assertEquals(1, keys.created)
    }

    @Test fun invalidCredentialEncodingIsInvalidSeedAndNeverRewritten() = runBlocking {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val encrypted = cipher.iv + cipher.doFinal(byteArrayOf(0xff.toByte(), 0xfe.toByte()))
        val file = FakeFile().apply { bytes = byteArrayOf(1) + encrypted }
        val keys = FakeKeys().apply { this.key = this@SecretStorageTest.key }
        val native = NativeSecretStore("test", keys, file)
        val before = file.bytes!!.copyOf()
        assertEquals(SecretFailure.invalidMnemonic, (native.read() as SecretResult.Unavailable).reason)
        assertTrue(native.save(secret) is SecretResult.Unavailable)
        assertTrue(before.contentEquals(file.bytes))
        val rn = row(true) + ("p" to Base64.getMimeEncoder().encodeToString(encrypted))
        val reader = LegacyPasswordReader({ rn }, { null }, { key }, { Base64.getMimeDecoder().decode(it) }, compareSeeds = true)
        assertEquals(SecretFailure.invalidMnemonic, (reader.read() as SecretResult.Unavailable).reason)
    }

    @Test fun corruptVersionTagAndFailedWriteNeverReplaceExistingData() {
        val keys = FakeKeys()
        val file = FakeFile()
        val store = NativeSecretStore("test", keys, file)
        assertFound(store.save(secret))
        val original = file.bytes!!.copyOf()
        file.failWrite = true
        assertTrue(store.save("replacement") is SecretResult.Unavailable)
        assertArrayEquals(original, file.bytes)
        file.failWrite = false
        for (bytes in listOf(byteArrayOf(), original.copyOf(12), original.copyOf().also { it[0] = 2 },
            original.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() })) {
            file.bytes = bytes
            assertTrue(store.read() is SecretResult.Unavailable)
            assertTrue(store.save("replacement") is SecretResult.Unavailable)
            assertArrayEquals(bytes, file.bytes)
        }
        assertEquals(1, keys.created)
    }

    @Test fun firstWriteThatLandsDespiteFailureStaysReadableForRestoreCommit() {
        val keys = FakeKeys()
        val file = FakeFile().apply { failAfterWrite = true }
        val store = NativeSecretStore("test", keys, file)
        assertTrue(store.save(secret) is SecretResult.Unavailable)
        assertFound(store.read())
        assertNotNull(file.bytes)
    }

    /**
     * Review finding: a restore's seed commit whose read-back fails must keep the (possibly durable)
     * new seed so restore keeps its journal and settles later; migration/creation may discard it.
     */
    @Test fun unverifiedFirstWriteIsKeptForRestoreCommitOnly() {
        for (keep in listOf(true, false)) {
            val file = FakeFile()
            val writing = object : SecretFile by file {
                override fun writeAtomically(bytes: ByteArray) { file.writeAtomically(bytes); file.unreadable = true }
            }
            val store = NativeSecretStore("test", FakeKeys(), writing)
            assertTrue(store.save(secret, keepUnverifiedWrite = keep) is SecretResult.Unavailable)
            file.unreadable = false
            if (keep) assertNotNull("restore commit keeps the written seed", file.bytes)
            else assertNull("migration/creation discards it", file.bytes)
        }
    }

    @Test fun migrationPreservesTheVerbatimRNBackupPassword() = runBlocking {
        val stored = " \n" + secret.uppercase().replace(" ", "\u3000") + "\t"
        val native = NativeSecretStore("test", FakeKeys(), FakeFile())
        val storage = SeedStorage(native) { SecretResult.Found(stored) }
        assertTrue("Read changed backup password", (storage.read() as? SecretResult.Found)?.value == stored)
        assertTrue("Save changed backup password", (storage.save(stored) as? SecretResult.Found)?.value == stored)
        assertTrue("Migration changed backup password", (native.read() as? SecretResult.Found)?.value == stored)
        assertEquals(SecretFailure.seedConflict, (storage.save(secret) as SecretResult.Unavailable).reason)
    }

    @Test fun migrationVerifiesBeforeUseAndKeepsLegacyOnFailure() = runBlocking {
        val keys = FakeKeys()
        val file = FakeFile()
        val native = NativeSecretStore("test", keys, file)
        var reads = 0
        val legacy = { reads++; SecretResult.Found(secret) }
        val storage = SeedStorage(native, legacy)
        // A failed or corrupt copy still yields the legacy seed and leaves no file behind.
        file.failWrite = true
        assertFound(storage.read())
        storage.save(secret)
        assertNull(file.bytes)
        file.failWrite = false
        file.corruptWrite = true
        assertFound(storage.read())
        storage.save(secret)
        assertNull(file.bytes)
        // Once the copy works it is used from then on.
        file.corruptWrite = false
        assertFound(storage.read())
        assertFound(storage.save(secret))
        assertTrue(file.bytes != null)
        val count = reads
        List(10) { async { assertFound(SeedStorage(native, legacy).read()) } }.awaitAll()
        assertEquals(count + 10, reads)
    }

    @Test fun migrationNeverFallsBackFromUnreadableNativeOrLegacy() = runBlocking {
        val file = FakeFile().apply { bytes = byteArrayOf(99) }
        var called = false
        val storage = SeedStorage(NativeSecretStore("test", FakeKeys(), file)) {
            called = true; SecretResult.Found(secret)
        }
        assertTrue(storage.read() is SecretResult.Unavailable)
        assertFalse(called)
        file.bytes = null
        val keys = FakeKeys()
        val unavailable = SeedStorage(NativeSecretStore("test", keys, file)) {
            SecretResult.Unavailable(SecretFailure.missingKey)
        }
        assertTrue(unavailable.read() is SecretResult.Unavailable)
        assertEquals(0, keys.created)
        assertNull(file.bytes)
    }

    @Test fun rollbackDeletesOnlyMatchingReadableNativeSeed() {
        val file = FakeFile()
        val store = NativeSecretStore("test", FakeKeys(), file)
        assertFound(store.save(secret))
        val original = file.bytes!!.copyOf()
        store.delete("another-attempt")
        assertArrayEquals(original, file.bytes)
        file.bytes = byteArrayOf(99)
        store.delete(secret)
        assertArrayEquals(byteArrayOf(99), file.bytes)
        file.bytes = original
        store.delete(secret)
        assertEquals(SecretResult.NotFound, store.read())
    }

    @Test fun explicitDeleteClearsLegacyBeforeNativeAndCannotMigrateBack() = runBlocking {
        val file = FakeFile()
        val native = NativeSecretStore("test", FakeKeys(), file)
        native.save(secret)
        var legacy: SecretResult = SecretResult.Found(secret)
        val storage = SeedStorage(native, deleteLegacy = {
            assertTrue(native.read() is SecretResult.Found)
            legacy = SecretResult.NotFound
        }, legacy = { legacy })
        storage.deleteWalletSeed()
        assertEquals(SecretResult.NotFound, storage.read())
        assertNull(file.bytes)
    }

    @Test fun failedLegacyDeletePreservesNativeSeed() = runBlocking {
        val file = FakeFile()
        val native = NativeSecretStore("test", FakeKeys(), file)
        native.save(secret)
        val storage = SeedStorage(native, deleteLegacy = { error("Delete failed") }, legacy = { SecretResult.NotFound })
        assertTrue(runCatching { storage.deleteWalletSeed() }.isFailure)
        assertFound(native.read())
    }

    private fun assertFound(result: SecretResult) {
        assertTrue("Secret round trip failed", result is SecretResult.Found && result.value == secret)
    }

    private class FakeKeys : SecretKeys {
        var key: SecretKey? = null
        var created = 0
        override fun existing(alias: String) = key
        override fun create(alias: String): SecretKey {
            check(key == null)
            created++
            return KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also { key = it }
        }
    }
    private class FakeFile : SecretFile {
        var bytes: ByteArray? = null
        var failWrite = false
        var corruptWrite = false
        var failAfterWrite = false
        /** The write lands, then reads fail for a while (e.g. a Keystore hiccup). */
        var unreadable = false
        override fun read() = if (unreadable) error("Read failed") else bytes?.copyOf()
        override fun writeAtomically(bytes: ByteArray) {
            if (failWrite) error("Write failed")
            this.bytes = if (corruptWrite) byteArrayOf(0) else bytes.copyOf()
            if (failAfterWrite) error("Write landed but directory sync failed")
        }
        override fun delete() { bytes = null }
    }
}
