package com.noahwallet.services

import com.noahwallet.fixtures.SharedFixtures
import com.noahwallet.services.launch.LaunchState
import com.noahwallet.services.storage.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class LaunchAndSettingsTest {
    @Test fun sharedLaunchDecisions() {
        for ((fixture, count) in listOf("launch-state" to 6, "launch-seed-safety" to 30)) {
            val cases = SharedFixtures.load(fixture)
            assertEquals(count, cases.size)
            for (case in cases) {
                val input = case.input.jsonObject
                val sources = input.getValue("sources").jsonArray.map { source ->
                    val fields = source.jsonObject
                    when (fields.getValue("read").jsonPrimitive.content) {
                        "found" -> SecretResult.Found(fields.getValue("raw").jsonPrimitive.content)
                        "absent" -> SecretResult.NotFound
                        "conflict" -> SecretResult.Unavailable(SecretFailure.seedConflict)
                        "invalidEncoding" -> SecretResult.Unavailable(SecretFailure.invalidMnemonic)
                        else -> SecretResult.Unavailable(SecretFailure.storageUnreadable)
                    }
                }
                val result = LaunchState.inspect(sources, input.getValue("walletDatabaseExists").jsonPrimitive.boolean,
                    fingerprintMatches = input.getValue("databaseFingerprint").jsonPrimitive.content != "mismatch",
                    multipleDatabases = input.getValue("multipleDatabases").jsonPrimitive.boolean)
                assertEquals(case.name, case.expected.jsonObject.getValue("outcome").jsonPrimitive.content, result.state.name)
                assertEquals(case.name, case.expected.jsonObject.getValue("reason").jsonPrimitive.content, result.reason.name)
            }
        }
        assertEquals(LaunchState.SeedStatus.invalid, LaunchState.SeedStatus.from(SecretResult.Found("")))
        assertEquals(LaunchState.SeedStatus.unavailable,
            LaunchState.SeedStatus.from(SecretResult.Unavailable(SecretFailure.missingKey)))
        assertEquals(LaunchState.seedConflict,
            LaunchState.openFailure(io.github.niteshbalusu11.bark.BarkError("incorrect mnemonic for existing wallet",
                io.github.niteshbalusu11.bark.BarkErrorCode.OTHER)))
    }

    @Test fun optionalFieldsUnknownFieldsAndEnvelopes() {
        assertEquals(PersistedWalletState(), PersistedStateCodec.decode<PersistedWalletState>("""{"state":{},"version":0}"""))
        val state = PersistedWalletState(isInitialized = true, isWalletLoaded = false,
            staticVtxoPubkey = "pubkey", isBiometricsEnabled = true, isDebugModeEnabled = true,
            isWalletSuspended = true, hasSeenBatteryOptimizationPrompt = false)
        val encoded = PersistedStateCodec.encode(state, 0)
        assertEquals(state, PersistedStateCodec.decode<PersistedWalletState>(encoded))
        assertEquals(0, Json.parseToJsonElement(encoded).jsonObject.getValue("version").jsonPrimitive.int)
        assertEquals(true, PersistedStateCodec.decode<PersistedWalletState>("""{"state":{"isInitialized":true,"futureFlag":42}}""")?.isInitialized)
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
