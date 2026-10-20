package com.noahwallet.services

import com.noahwallet.fixtures.SharedFixtures
import com.noahwallet.services.backup.BackupCrypto
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class BackupCryptoTest {
    @Test fun sharedVectorsInBothDirections() {
        for (case in SharedFixtures.load("backup-crypto") + SharedFixtures.load("backup-crypto-unicode")) {
            val input = case.input.jsonObject
            val version = BackupCrypto.Version.entries.single { it.byte.toInt() == input.getValue("version").jsonPrimitive.int }
            val mnemonic = input.getValue("mnemonic").jsonPrimitive.content
            val plaintext = input.bytes("plaintextHex")
            val expected = case.expected.jsonObject.bytes("envelopeHex")
            assertArrayEquals(case.name, expected, BackupCrypto.encrypt(plaintext, mnemonic, version,
                input.bytes("saltHex"), input.bytes("nonceHex")))
            assertArrayEquals(case.name, plaintext, BackupCrypto.decrypt(expected, mnemonic, version))
            val key = BackupCrypto.deriveKeyOnApi24(mnemonic, input.bytes("saltHex"))
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"),
                javax.crypto.spec.GCMParameterSpec(128, input.bytes("nonceHex")))
            assertArrayEquals(case.name, plaintext, cipher.doFinal(expected, 29, expected.size - 29))
        }
    }

    @Test fun rejectsWrongMnemonicTamperingVersionAndTruncation() {
        val case = SharedFixtures.load("backup-crypto").first()
        val input = case.input.jsonObject
        val envelope = case.expected.jsonObject.bytes("envelopeHex")
        val mnemonic = input.getValue("mnemonic").jsonPrimitive.content
        fun fails(bytes: ByteArray, password: String, reason: BackupCrypto.Reason) {
            val failure = assertThrows(BackupCrypto.Failure::class.java) {
                BackupCrypto.decrypt(bytes, password, BackupCrypto.Version.legacyDirectory)
            }
            assertEquals(reason, failure.reason)
        }
        fails(envelope, "wrong mnemonic", BackupCrypto.Reason.authenticationFailed)
        fails(envelope.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }, mnemonic,
            BackupCrypto.Reason.authenticationFailed)
        fails(envelope.copyOf().also { it[0] = 2 }, mnemonic, BackupCrypto.Reason.unexpectedVersion)
        fails(envelope.copyOf().also { it[0] = 99 }, mnemonic, BackupCrypto.Reason.newerVersion)
        for (size in 0..44) fails(envelope.copyOf(size), mnemonic, BackupCrypto.Reason.tooShort)
    }

    @Test fun randomWritesAndUnicodePasswordRoundTrip() {
        val plaintext = "backup".toByteArray()
        val password = "あいこくしん café 🪙"
        val a = BackupCrypto.encrypt(plaintext, password, BackupCrypto.Version.snapshot)
        val b = BackupCrypto.encrypt(plaintext, password, BackupCrypto.Version.snapshot)
        assertFalse(a.contentEquals(b))
        assertArrayEquals(plaintext, BackupCrypto.decrypt(a, password, BackupCrypto.Version.snapshot))
    }
}

internal fun JsonObject.bytes(key: String): ByteArray = getValue(key).jsonPrimitive.content
    .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
