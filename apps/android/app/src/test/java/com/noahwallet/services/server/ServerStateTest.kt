package com.noahwallet.services.server

import com.noahwallet.api.generated.models.DeviceInfo as DevicePayload
import com.noahwallet.fixtures.SharedFixtures
import com.noahwallet.services.storage.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ServerStateTest {
    companion object {
        const val rnJSON = """{"state":{"isRegisteredWithServer":true,"lightningAddress":"alice@localhost.com","nostrPubkey":null,"isBackupEnabled":true,"emailAddress":"alice@example.com","isEmailVerified":true,"isEmailPromptDismissed":false,"mailboxAuthorizationExpiry":1790000000,"isMailboxAuthorizationEnabled":false,"lastReportedDeviceFingerprint":"[null,null,null,null,null,null]","lastDeviceReportAt":1784160000000},"version":0}"""
    }

    @Test fun rnEnvelopeRoundTripsAndUpdatesPreservePreferences() {
        val map = mutableMapOf(PersistedServerState.key to rnJSON)
        val store = ServerStateStorage(map::get, { k, v -> map[k] = v })
        val state = store.state
        val encoded = PersistedServerState.encode(state)
        val after = PersistedServerState.decode(encoded)
        assertEquals(state, after)
        val originalFields = Json.parseToJsonElement(rnJSON).jsonObject.getValue("state").jsonObject
        val newFields = Json.parseToJsonElement(encoded).jsonObject.getValue("state").jsonObject
        originalFields.forEach { (key, value) ->
            if (key in setOf("lastDeviceReportAt", "mailboxAuthorizationExpiry"))
                assertEquals(value.jsonPrimitive.double, newFields.getValue(key).jsonPrimitive.double, 0.0)
            else assertEquals(value, newFields[key])
        }
        store.update { it.copy(emailAddress = null, isEmailPromptDismissed = true) }
        assertNull(store.state.emailAddress)
        assertEquals(true, store.state.isEmailPromptDismissed)
        assertEquals(false, store.state.isMailboxAuthorizationEnabled)
        assertEquals(state.mailboxAuthorizationExpiry, store.state.mailboxAuthorizationExpiry)
        assertEquals(state.lightningAddress, store.state.lightningAddress)
        assertEquals(0, Json.parseToJsonElement(map.getValue(PersistedServerState.key)).jsonObject.getValue("version").jsonPrimitive.int)
    }

    @Test fun lenientFieldsAndMissingDefaultsMatchIOS() {
        val state = PersistedServerState.decode("""{"state":{"isRegisteredWithServer":true,"emailAddress":42,"isEmailVerified":"yes","lightningAddress":"alice@localhost.com","futureField":true}}""")
        assertEquals(true, state.isRegisteredWithServer)
        assertEquals("alice@localhost.com", state.lightningAddress)
        assertNull(state.emailAddress)
        assertNull(state.isEmailVerified)
        val empty = Json.parseToJsonElement(PersistedServerState.encode(PersistedServerState())).jsonObject.getValue("state").jsonObject
        assertFalse(empty.containsKey("isMailboxAuthorizationEnabled"))
        assertEquals(JsonNull, empty["nostrPubkey"])
    }

    @Test fun failedWriteDoesNotPublishMutatedState() {
        val store = ServerStateStorage({ rnJSON }, { _, _ -> error("disk") })
        assertThrows(IllegalStateException::class.java) { store.update { it.copy(emailAddress = null) } }
        assertEquals("alice@example.com", store.state.emailAddress)
    }

    @Test fun mutationsReReadLatestPreferences() {
        var value = rnJSON
        val store = ServerStateStorage({ value }, { _, new -> value = new })
        val other = ServerStateStorage({ value }, { _, new -> value = new })
        other.update { it.copy(isMailboxAuthorizationEnabled = true) }
        store.update { it.copy(isEmailPromptDismissed = true) }
        assertEquals(true, store.state.isMailboxAuthorizationEnabled)
    }

    @Test fun deviceReportingMatchesSharedRNFixture() {
        for (case in SharedFixtures.load("device-info")) {
            val input = case.input.jsonObject
            val expected = case.expected.jsonObject
            val info = Json.decodeFromJsonElement<DevicePayload>(input.getValue("device"))
            assertEquals(case.name, expected.getValue("fingerprint").jsonPrimitive.content, DeviceInfo.fingerprint(info))
            assertEquals(case.name, expected.getValue("shouldReport").jsonPrimitive.boolean,
                DeviceInfo.shouldReport(info, input.getValue("fingerprint").jsonPrimitive.contentOrNull,
                    input.getValue("reportedAt").jsonPrimitive.doubleOrNull, input.getValue("now").jsonPrimitive.long))
        }
    }

    @Test fun normalizesUsernameAndOptionalNpub() {
        val payload = LightningIdentity.payload("  Alice.Test_1-2\n", "  npub1test\n")
        assertEquals("alice.test_1-2", payload.username)
        assertEquals("npub1test", payload.nostrPubkey)
        assertNull(LightningIdentity.payload("a", " \n").nostrPubkey)
        assertEquals("0._-", LightningIdentity.payload("0._-", "").username)
    }

    @Test fun rejectsInvalidUsernamesAndPrivateKeys() {
        for (username in listOf("", " \n", "two words", "alice@example.com", "é", "a/b"))
            assertThrows(IllegalArgumentException::class.java) { LightningIdentity.payload(username, "") }
        for (key in listOf(" nsec1test ", "NSEC1test", "b0635d6a", "note1test"))
            assertThrows(IllegalArgumentException::class.java) { LightningIdentity.payload("alice", key) }
    }

    @Test fun nostrEncodingMatchesSharedFixtures() {
        for (case in SharedFixtures.load("nostr-npub"))
            assertEquals(case.name, case.expected.jsonPrimitive.content, Nostr.npub(case.input.jsonPrimitive.contentOrNull))
    }
}
