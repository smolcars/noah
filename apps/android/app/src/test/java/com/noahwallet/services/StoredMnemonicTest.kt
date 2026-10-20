package com.noahwallet.services

import com.noahwallet.services.storage.*
import kotlinx.coroutines.runBlocking
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Assert.*
import org.junit.Test

class StoredMnemonicTest {
    private fun vector(count: Int) = List(count - 1) { StoredMnemonic.wordList[0] }
        .plus(StoredMnemonic.wordList[if (count == 12) 3 else 102]).joinToString(" ")

    @Test fun bip39ChecksWordsChecksumCountAndNormalization() {
        for (count in listOf(12, 24)) {
            val phrase = vector(count)
            assertTrue("Public BIP39 vector rejected", StoredMnemonic.normalize(phrase) != null)
            assertTrue("Whitespace/case normalization differs", StoredMnemonic.normalize(" \n" + phrase.uppercase().replace(" ", "\u3000") + "\t") == phrase)
            assertNull(StoredMnemonic.normalize(List(count) { StoredMnemonic.wordList[0] }.joinToString(" ")))
        }
        for (invalid in listOf("", "unknown ".repeat(12), vector(12) + " extra", "word ".repeat(15))) {
            assertNull(StoredMnemonic.normalize(invalid))
        }
    }

    @Test fun validNoncanonicalRNBackupPasswordStaysByteIdentical() {
        val canonical = vector(12)
        val stored = " \n" + canonical.uppercase().replace(" ", "\u3000") + "\t"
        val result = StoredMnemonic.validate(SecretResult.Found(stored))
        assertTrue("Validation must preserve the RN backup password", result is SecretResult.Found && result.value == stored)
        assertEquals(SecretFailure.seedConflict, (StoredMnemonic.agree(listOf(result,
            SecretResult.Found(canonical))) as SecretResult.Unavailable).reason)
    }

    @Test fun everySeedSourceMustAgreeAndInvalidSeedsAreNeverWritten() = runBlocking {
        for (current in listOf(SecretResult.NotFound, SecretResult.Found(vector(12)), SecretResult.Found(""),
            SecretResult.Unavailable(SecretFailure.invalidCiphertext))) {
            for (old in listOf(SecretResult.NotFound, SecretResult.Found(vector(12)), SecretResult.Found(vector(24)),
                SecretResult.Found("corrupt"), SecretResult.Unavailable(SecretFailure.missingKey))) {
                var writes = 0
                var bytes: ByteArray? = null
                val keys = object : SecretKeys {
                    var key: SecretKey? = null
                    override fun existing(alias: String) = key
                    override fun create(alias: String) = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also { key = it }
                }
                val file = object : SecretFile {
                    override fun read() = bytes
                    override fun writeAtomically(value: ByteArray) { writes++; bytes = value }
                    override fun delete() { error("Seed was deleted") }
                }
                val native = NativeSecretStore("test", keys, file)
                if (current is SecretResult.Found) native.save(current.value)
                else if (current is SecretResult.Unavailable) bytes = byteArrayOf(99)
                val before = bytes?.copyOf()
                val count = writes
                val storage = SeedStorage(native) { old }
                val result = storage.read()
                assertEquals("Read must never migrate before DB fingerprint validation", count, writes)
                val blocked = current is SecretResult.Unavailable || old is SecretResult.Unavailable ||
                    (current is SecretResult.Found && StoredMnemonic.normalize(current.value) == null) ||
                    (old is SecretResult.Found && StoredMnemonic.normalize(old.value) == null) ||
                    (current is SecretResult.Found && old is SecretResult.Found && current.value != old.value)
                if (blocked) {
                    assertTrue(result is SecretResult.Unavailable)
                    assertTrue(storage.save(vector(12)) is SecretResult.Unavailable)
                    assertEquals(count, writes)
                    assertTrue(before?.contentEquals(bytes) ?: (bytes == null))
                }
            }
        }
    }

}
