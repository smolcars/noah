package com.noahwallet.services.wallet

import com.noahwallet.fixtures.SharedFixtures
import com.noahwallet.services.storage.WalletDataLocation
import java.nio.file.Files
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class WalletValuesTest {
    @Test fun balanceMatchesSharedFixture() {
        for (case in SharedFixtures.load("balance")) {
            val balance = Json.decodeFromJsonElement<WalletBalance>(case.input)
            val expected = case.expected.jsonObject
            assertEquals(case.name, expected.getValue("total").jsonPrimitive.content.toULong(), balance.totalSats)
            assertEquals(case.name, expected.getValue("offchain").jsonPrimitive.content.toULong(), balance.offchainSats)
            assertEquals(case.name, expected.getValue("onchain").jsonPrimitive.content.toULong(), balance.onchainSats)
            assertEquals(case.name, expected.getValue("pending").jsonPrimitive.content.toULong(), balance.pendingSats)
        }
    }
    @Test fun configsConvertFeeUnitsAndUseAndroidHost() {
        for (variant in listOf("mainnet", "signet", "regtest")) {
            val config = WalletConfiguration.base(variant)
            assertEquals(10_000uL, config.fallbackFeeRateSatPerKvb)
            assertEquals(2_500uL, config.sdkConfig("test").fallbackFeeRate)
            assertEquals(if (variant == "mainnet") "bitcoin" else variant, config.networkName)
            assertEquals("noah-$variant-android/0.2.0", WalletConfiguration.userAgent(variant, "0.2.0"))
        }
        val regtest = WalletConfiguration.base("regtest")
        assertEquals("http://10.0.2.2:3535", regtest.arkServer)
        assertEquals("http://10.0.2.2:18443", regtest.bitcoind)
        assertNull(regtest.esplora)
        assertEquals(144.toUShort(), WalletConfiguration.base("mainnet").vtxoRefreshExpiryThreshold)
        assertEquals(12.toUShort(), WalletConfiguration.base("signet").vtxoRefreshExpiryThreshold)
        assertEquals(24.toUShort(), regtest.vtxoRefreshExpiryThreshold)
    }
    @Test fun walletDataMeansAnyFileAndCleanupKeepsPreexistingEmptyDirectory() {
        val root = Files.createTempDirectory("wallet-location").toFile()
        try {
            val location = WalletDataLocation(root, "regtest")
            assertFalse(location.hasAnyData)
            location.directory.mkdirs(); assertFalse(location.hasAnyData)
            location.directory.resolve("config.toml").writeText("partial")
            assertTrue(location.hasAnyData); assertFalse(location.databaseExists)
            assertTrue(location.removeAttemptFiles(true)); assertTrue(location.directory.isDirectory)
            location.directory.resolve("partial").writeText("partial")
            assertTrue(location.removeAttemptFiles(false)); assertFalse(location.directory.exists())
        } finally { root.deleteRecursively() }
    }
}
