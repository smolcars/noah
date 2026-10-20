package com.noahwallet.services

import com.noahwallet.services.storage.*
import com.noahwallet.fixtures.SharedFixtures
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RNStoreMigrationTest {
    @Test fun rnEnvelopesAreDecodedAndUnknownFieldsSurviveTypedWrites() {
        for (case in SharedFixtures.load("rn-stores-android-shapes")) {
            val input = case.input.jsonObject
            val key = input.getValue("key").jsonPrimitive.content
            val envelope = input.getValue("envelope").jsonObject
            assertEquals(envelope, RNStoreCodec.decode(key, envelope.toString()))
            if (case.name == "transaction v0 migration") {
                val native = SettingsPreferences.transaction(envelope.toString())
                val expected = case.expected.jsonObject.getValue("state").jsonObject
                for (field in listOf("isAutoBoardingEnabled", "hasAttemptedAutoBoarding")) assertEquals(expected[field], native[field])
                assertTrue("Native must retain fields RN partialize drops", native["future"] is JsonObject)
            }
            val withUnknown = JsonObject(envelope + ("futureEnvelope" to JsonPrimitive(42)) +
                ("state" to JsonObject(envelope.getValue("state").jsonObject + ("futureField" to buildJsonObject { put("keep", true) }))))
            val merged = RNStoreCodec.decode(key, RNStoreCodec.merge(key, withUnknown.toString(), envelope.toString()))
            assertEquals(JsonPrimitive(42), merged["futureEnvelope"])
            assertEquals(true, merged.getValue("state").jsonObject.getValue("futureField").jsonObject.getValue("keep").jsonPrimitive.boolean)
        }
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
