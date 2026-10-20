package com.noahwallet.services

import com.noahwallet.services.storage.*
import com.noahwallet.fixtures.SharedFixtures
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RNStoreMigrationTest {
    @Test fun unifiedRNFixturesValidateMigrateAndPreserveOriginalBytes() {
        val cases = SharedFixtures.load("rn-store-compatibility")
        assertEquals(196, cases.size)
        for (case in cases) {
            val input = case.input.jsonObject
            val expected = case.expected.jsonObject
            val key = input.getValue("key").jsonPrimitive.content
            val raw = input.getValue("raw").jsonPrimitive.content
            val imported = SettingsStorage.importValues(mapOf(key to raw))
            assertEquals(case.name, expected.getValue("imported").jsonPrimitive.boolean, key != "recurring-payment-storage" && imported.values[key] == raw)
            if (key == "recurring-payment-storage") {
                assertTrue(case.name, imported.values[key] == null || imported.values[key] == SettingsStorage.emptyRecurring)
            } else if (expected.getValue("available").jsonPrimitive.boolean) assertEquals(case.name, raw, imported.values[key])
            else { assertNull(case.name, imported.values[key]); assertTrue(imported.settingsNoticePending) }
            var stored = raw
            val recurring = com.noahwallet.recurring.RecurringStore({ stored }, { stored = it })
            if (!expected.getValue("available").jsonPrimitive.boolean) {
                assertThrows(case.name, RNStoreCodec.Unreadable::class.java) { RNStoreCodec.decode(key, raw) }
                assertThrows(case.name, RNStoreCodec.Unreadable::class.java) {
                    stored = RNStoreCodec.merge(key, stored, """{"state":{},"version":0}""")
                }
                if (key == "recurring-payment-storage") {
                    assertTrue(case.name, runCatching { recurring.load() }.isFailure)
                    assertTrue(case.name, runCatching { recurring.setPending(false) }.isFailure)
                }
                assertEquals(case.name, raw, stored)
                continue
            }
            val decoded = RNStoreCodec.decode(key, raw)
            if (key == "recurring-payment-storage") recurring.load()
            val state = if (key == "transaction-storage") SettingsPreferences.transaction(raw) else decoded.getValue("state").jsonObject
            val wanted = Json.parseToJsonElement(expected.getValue("stateRaw").jsonPrimitive.content).jsonObject
            for ((field, value) in wanted) assertEquals("${case.name}: $field", value, state[field])
            assertEquals("Reads must not migrate bytes: ${case.name}", raw, stored)
            expected["consumed"]?.jsonObject?.let { consumed ->
                assertEquals(consumed.getValue("preferredCurrency").jsonPrimitive.content, SettingsPreferences.preferredCurrency(raw))
                assertEquals(consumed.getValue("bitcoinAmountUnit").jsonPrimitive.content, SettingsPreferences.amountUnit(raw).name)
            }
            if (key == "recurring-payment-storage") recurring.setPending(false)
            else stored = RNStoreCodec.merge(key, stored, buildJsonObject {
                put("state", wanted); put("version", expected.getValue("version"))
            }.toString())
            val saved = Json.parseToJsonElement(stored).jsonObject
            expected["preservedTokens"]?.jsonObject?.forEach { (path, token) ->
                val (scope, field) = path.split('.')
                val value = if (scope == "root") saved[field] else saved.getValue("state").jsonObject[field]
                assertEquals("${case.name}: $path", token.jsonPrimitive.content, value?.jsonPrimitive?.content)
            }
        }
    }

    @Test fun unsupportedProfilePreferencesFallBackWithoutBlockingOrRepairingStoredValues() {
        val original = """{"state":{"displayName":"Name","preferredCurrency":"future-currency","bitcoinAmountUnit":"future-unit"},"version":0}"""
        RNStoreCodec.decode("profile-storage", original)
        assertEquals("USD", SettingsPreferences.preferredCurrency(original))
        assertEquals(com.noahwallet.formatting.BitcoinAmountUnit.bip177, SettingsPreferences.amountUnit(original))
        val next = SettingsPreferences.update(original, mapOf("displayName" to JsonPrimitive("Updated")))
        val saved = RNStoreCodec.decode("profile-storage", RNStoreCodec.merge("profile-storage", original, next)).getValue("state").jsonObject
        assertEquals(JsonPrimitive("future-currency"), saved["preferredCurrency"])
        assertEquals(JsonPrimitive("future-unit"), saved["bitcoinAmountUnit"])
    }

    @Test fun onlyExactVersionTokensAreAccepted() {
        for (key in RNStoreCodec.versions.keys) {
            for (token in listOf("-0", "0e5", "1e-400", "0.0", "1.0", "1e0", "-1", "99", "true", "null", "\"0\"")) {
                assertThrows(RNStoreCodec.Unreadable::class.java) { RNStoreCodec.decode(key, "{\"state\":{},\"version\":$token}") }
            }
            RNStoreCodec.decode(key, "{\"state\":{}}")
            RNStoreCodec.decode(key, "{\"state\":{},\"version\": 0 }")
            if (key == "transaction-storage") RNStoreCodec.decode(key, "{\"state\":{},\"version\":1}")
            else assertThrows(RNStoreCodec.Unreadable::class.java) { RNStoreCodec.decode(key, "{\"state\":{},\"version\":1}") }
        }
    }

    @Test fun corruptFutureOrWronglyTypedStoresCannotBeOverwritten() {
        for (key in RNStoreCodec.versions.keys) {
            for (bad in listOf("", "null", "{}", "{\"state\":[]}", "{\"state\":{},\"version\":99}",
                "{\"state\":{},\"version\":\"0\"}")) {
                assertThrows(RNStoreCodec.Unreadable::class.java) { RNStoreCodec.merge(key, bad, "{\"state\":{},\"version\":0}") }
            }
        }
        for ((key, field) in mapOf("wallet-storage" to "isInitialized", "backup-storage" to "backupPending", "esplora-storage" to "endpointOverride",
            "privacy-storage" to "isHomeBalanceHidden", "profile-storage" to "displayName", "recurring-payment-storage" to "schedules",
            "server-storage" to "isMailboxAuthorizationEnabled", "transaction-storage" to "isAutoBoardingEnabled")) {
            assertThrows(RNStoreCodec.Unreadable::class.java) { RNStoreCodec.decode(key, "{\"state\":{\"$field\":42}}") }
        }
        val original = "{\"state\":{\"isHomeBalanceHidden\":true},\"version\":0}"
        assertEquals(Json.parseToJsonElement(original), Json.parseToJsonElement(RNStoreCodec.merge("privacy-storage", original, original)))
    }
}
