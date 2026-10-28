package com.noahwallet.services

import com.noahwallet.fixtures.SharedFixtures
import com.noahwallet.services.storage.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class StoredMnemonicTest {
    private fun vector(count: Int) = List(count - 1) { StoredMnemonic.wordList[0] }
        .plus(StoredMnemonic.wordList[if (count == 12) 3 else 102]).joinToString(" ")

    @Test fun sharedUnicodeNormalizationVectorsKeepOriginalCredentials() {
        for (case in SharedFixtures.load("stored-mnemonic")) {
            val raw = case.input.jsonObject.getValue("raw").jsonPrimitive.content
            val expected = case.expected.jsonObject["normalized"]?.jsonPrimitive?.contentOrNull
            assertTrue(case.name, StoredMnemonic.normalize(raw) == expected)
            val result = StoredMnemonic.validate(SecretResult.Found(raw))
            assertTrue(case.name, if (expected == null) result is SecretResult.Unavailable
            else result is SecretResult.Found && result.value == raw)
        }
    }

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

    @Test fun sharedFormattingVectorsReachWalletAndAuthSDKsNormalized() = runBlocking {
        val stoppedBeforeSDK = IllegalStateException("Test SDK boundary")
        for (case in SharedFixtures.load("stored-mnemonic").filter { it.input.jsonObject.getValue("exerciseCrypto").jsonPrimitive.boolean }) {
            val raw = case.input.jsonObject.getValue("raw").jsonPrimitive.content
            val expected = case.expected.jsonObject.getValue("normalized").jsonPrimitive.content
            val opener = com.noahwallet.services.wallet.WalletSession.sdkOpener("regtest", {},
                openOnchain = { _, normalized, _, _, create ->
                    assertTrue(case.name, normalized == expected)
                    assertFalse(create)
                    throw stoppedBeforeSDK
                })
            assertSame(stoppedBeforeSDK, runCatching { opener(raw, "unused", false, "test", false) }.exceptionOrNull())
            val derive: (String) -> String = { normalized -> assertTrue(case.name, normalized == expected); "public-test-result" }
            com.noahwallet.services.server.ServerClient.loginKey("regtest", raw, derive)
            com.noahwallet.services.server.ServerClient.signChallenge("challenge", "regtest", raw, derive)
        }
    }

    @Test fun authDerivationAndSigningNormalizeButBackupKeepsOriginalPassword() {
        val canonical = vector(12)
        for (original in listOf(canonical.uppercase(), " \n" + canonical.replace(" ", "\t  ") + "\n",
            canonical.map { if (it in 'a'..'z') (it.code + 0xfee0).toChar() else it }.joinToString(""))) {
            val derive: (String) -> String = { normalized ->
                assertTrue("SDK must receive the same normalized phrase as wallet open", normalized == canonical)
                "public-test-result"
            }
            com.noahwallet.services.server.ServerClient.loginKey("regtest", original, derive)
            com.noahwallet.services.server.ServerClient.signChallenge("public test challenge", "regtest", original, derive)
            val sealed = com.noahwallet.services.backup.BackupCrypto.encrypt(byteArrayOf(1, 2, 3), original,
                com.noahwallet.services.backup.BackupCrypto.Version.snapshot, testRounds)
            assertTrue(com.noahwallet.services.backup.BackupCrypto.decrypt(sealed, original,
                com.noahwallet.services.backup.BackupCrypto.Version.snapshot, testRounds).contentEquals(byteArrayOf(1, 2, 3)))
            assertTrue(runCatching { com.noahwallet.services.backup.BackupCrypto.decrypt(sealed, canonical,
                com.noahwallet.services.backup.BackupCrypto.Version.snapshot, testRounds) }.isFailure)
        }
    }

    /** Whether a read of these two seed sources must refuse: one is unreadable or invalid, or they disagree. */
    private fun blocked(current: SecretResult, old: SecretResult) = current is SecretResult.Unavailable || old is SecretResult.Unavailable ||
        (current is SecretResult.Found && StoredMnemonic.normalize(current.value) == null) ||
        (old is SecretResult.Found && StoredMnemonic.normalize(old.value) == null) ||
        (current is SecretResult.Found && old is SecretResult.Found && current.value != old.value)

    @Test fun everySeedSourceMustAgreeAndInvalidSeedsAreNeverWritten() = runBlocking {
        for (current in listOf(SecretResult.NotFound, SecretResult.Found(vector(12)), SecretResult.Found(""),
            SecretResult.Unavailable(SecretFailure.invalidCiphertext))) {
            for (old in listOf(SecretResult.NotFound, SecretResult.Found(vector(12)), SecretResult.Found(vector(24)),
                SecretResult.Found("corrupt"), SecretResult.Unavailable(SecretFailure.missingKey))) {
                var writes = 0
                var stored: ByteArray? = null
                val keys = object : SecretKeys {
                    var key: SecretKey? = null
                    override fun existing(alias: String) = key
                    override fun create(alias: String) = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also { key = it }
                }
                val file = object : SecretFile {
                    override fun read() = stored
                    override fun writeAtomically(bytes: ByteArray) { writes++; stored = bytes }
                    override fun delete() { error("Seed was deleted") }
                }
                val native = NativeSecretStore("test", keys, file)
                if (current is SecretResult.Found) native.save(current.value)
                else if (current is SecretResult.Unavailable) stored = byteArrayOf(99)
                val before = stored?.copyOf()
                val count = writes
                val storage = SeedStorage(native) { old }
                val result = storage.read()
                assertEquals("Read must never migrate before DB fingerprint validation", count, writes)
                if (blocked(current, old)) {
                    assertTrue(result is SecretResult.Unavailable)
                    assertTrue(storage.save(vector(12)) is SecretResult.Unavailable)
                    assertEquals(count, writes)
                    assertTrue(before?.contentEquals(stored) ?: (stored == null))
                }
            }
        }
    }
}
