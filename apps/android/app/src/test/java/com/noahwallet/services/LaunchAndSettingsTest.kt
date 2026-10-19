package com.noahwallet.services

import com.noahwallet.fixtures.SharedFixtures
import com.noahwallet.services.launch.LaunchState
import com.noahwallet.services.storage.*
import java.nio.file.Files
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class LaunchAndSettingsTest {
    @Test fun sharedLaunchDecisions() {
        for (case in SharedFixtures.load("launch-state")) {
            val input = case.input.jsonObject
            assertEquals(case.name, case.expected.jsonPrimitive.content, LaunchState.resolve(
                LaunchState.SeedStatus.valueOf(input.getValue("seed").jsonPrimitive.content),
                input.getValue("walletDatabaseExists").jsonPrimitive.boolean,
            ).name)
        }
        assertEquals(LaunchState.SeedStatus.absent, LaunchState.SeedStatus.from(SecretResult.Found("")))
        assertEquals(LaunchState.SeedStatus.unavailable,
            LaunchState.SeedStatus.from(SecretResult.Unavailable(SecretFailure.missingKey)))
    }

    @Test fun optionalFieldsUnknownFieldsAndEnvelopes() {
        assertEquals(PersistedWalletState(), PersistedStateCodec.decode<PersistedWalletState>("""{"state":{},"version":0}"""))
        val state = PersistedWalletState(isInitialized = true, isWalletLoaded = false,
            staticVtxoPubkey = "pubkey", isBiometricsEnabled = true, isDebugModeEnabled = true,
            isWalletSuspended = true, hasSeenBatteryOptimizationPrompt = false)
        val encoded = PersistedStateCodec.encode(state, 0)
        assertEquals(state, PersistedStateCodec.decode<PersistedWalletState>(encoded))
        assertEquals(0, Json.parseToJsonElement(encoded).jsonObject.getValue("version").jsonPrimitive.int)
        assertEquals(true, PersistedStateCodec.decode<PersistedWalletState>(
            """{"state":{"isInitialized":true,"futureFlag":42}}""")?.isInitialized)
        for (invalid in listOf("bad json", "{}", """{"state":null}""", """{"state":{"isInitialized":{}}}""")) {
            assertNull(PersistedStateCodec.decode<PersistedWalletState>(invalid))
        }
    }

    @Test fun databasePreflightDoesNotCreateOrChangeData() {
        val root = Files.createTempDirectory("wallet-location").toFile()
        try {
            for (variant in listOf("mainnet", "signet", "regtest")) {
                val location = WalletDataLocation(root, variant)
                assertEquals(root.resolve("noah-data-$variant/db.sqlite"), location.database)
                assertFalse(location.databaseExists)
                assertFalse(location.directory.exists())
                location.directory.mkdirs()
                location.database.writeText("unchanged")
                assertTrue(location.databaseExists)
                assertEquals("unchanged", location.database.readText())
            }
        } finally { root.deleteRecursively() }
    }
}
