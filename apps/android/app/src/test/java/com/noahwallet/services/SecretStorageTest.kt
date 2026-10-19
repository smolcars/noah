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
    private val secret = "test-only-secret"
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private fun row(gcm: Boolean, username: String = "noah"): Map<String, String> {
        fun encrypt(value: String): String {
            val cipher = Cipher.getInstance(if (gcm) "AES/GCM/NoPadding" else "AES/CBC/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            return Base64.getMimeEncoder().encodeToString(cipher.iv + cipher.doFinal(value.toByteArray()))
        }
        return mapOf("u" to encrypt(username), "p" to encrypt(secret),
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

    @Test fun migrationVerifiesBeforeUseAndKeepsLegacyOnFailure() = runBlocking {
        val keys = FakeKeys()
        val file = FakeFile()
        val native = NativeSecretStore("test", keys, file)
        var reads = 0
        val legacy = { reads++; SecretResult.Found(secret) }
        val storage = SeedStorage(native, legacy)
        file.failWrite = true
        assertTrue(storage.read() is SecretResult.Unavailable)
        file.failWrite = false
        file.corruptWrite = true
        assertTrue(storage.read() is SecretResult.Unavailable)
        // Simulate fixing the failed destination; legacy is still independently readable.
        assertFound(legacy())
        file.bytes = null
        file.corruptWrite = false
        assertFound(storage.read())
        val count = reads
        List(10) { async { assertFound(SeedStorage(native, legacy).read()) } }.awaitAll()
        assertEquals(count, reads)
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
        override fun read() = bytes?.copyOf()
        override fun writeAtomically(bytes: ByteArray) {
            if (failWrite) error("Write failed")
            this.bytes = if (corruptWrite) byteArrayOf(0) else bytes.copyOf()
        }
    }
}
