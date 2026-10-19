package com.noahwallet.services

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.noahwallet.services.backup.BackupCrypto
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupCryptoDeviceTest {
    @Test fun sharedVectorsUseAndroidCryptoProvider() {
        for (file in listOf("backup-crypto.json", "backup-crypto-unicode.json")) {
            val text = InstrumentationRegistry.getInstrumentation().context.assets.open(file)
                .bufferedReader().use { it.readText() }
            for (case in Json.parseToJsonElement(text).jsonObject.getValue("cases").jsonArray) {
                val input = case.jsonObject.getValue("input").jsonObject
                val expected = case.jsonObject.getValue("expected").jsonObject.hex("envelopeHex")
                val version = BackupCrypto.Version.entries.single { it.byte.toInt() == input.getValue("version").jsonPrimitive.int }
                val mnemonic = input.getValue("mnemonic").jsonPrimitive.content
                val plain = input.hex("plaintextHex")
                assertArrayEquals(expected, BackupCrypto.encrypt(plain, mnemonic, version, input.hex("saltHex"), input.hex("nonceHex")))
                assertArrayEquals(plain, BackupCrypto.decrypt(expected, mnemonic, version))
            }
        }
    }
    private fun JsonObject.hex(key: String) = getValue(key).jsonPrimitive.content.chunked(2)
        .map { it.toInt(16).toByte() }.toByteArray()
}
