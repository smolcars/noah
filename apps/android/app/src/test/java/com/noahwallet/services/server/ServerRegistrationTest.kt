package com.noahwallet.services.server

import com.noahwallet.api.generated.models.DeviceInfo as DevicePayload
import com.noahwallet.services.storage.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ServerRegistrationTest {
    private val response = """{"display_name":"New","email":null,"is_email_verified":false,"lightning_address":"new@localhost.com","nostr_pubkey":null,"status":"OK","user_status":"active"}"""
    private val device = DevicePayload(appVersion = "0.2.0", appBuild = "100")

    @Test fun mailboxRefreshMatchesIOSDefaultsThresholdAndReregistration() = runBlocking {
        val values = mutableMapOf<String, String>()
        val storage = ServerStateStorage(values::get, { k, v -> values[k] = v })
        var requests = 0
        var fail = false
        val now = 1_800_000_000_000L
        val engine = MockEngine { request ->
            if (request.url.encodedPath == "/v0/mailbox/authorize") {
                requests++
                val payload = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                assertEquals(now / 1000 + 89 * 86400, payload.getValue("expiry").jsonPrimitive.long)
                if (fail) return@MockEngine respond("unavailable", HttpStatusCode.ServiceUnavailable)
            }
            respond("""{"success":true}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        ServerClient.make("https://example.test", engine = engine).use { client ->
            val service = ServerRegistration(client, storage, { "ark-test" }, { device }, { now }, {},
                mailboxAuthorization = { com.noahwallet.api.generated.models.AuthorizeMailboxPayload("encoded", it, "mailbox") })
            service.refreshMailboxIfNeeded(); assertEquals(0, requests) // not registered
            storage.update { it.copy(isRegisteredWithServer = true) }
            service.refreshMailboxIfNeeded(); assertEquals(1, requests) // new wallet's default is enabled
            assertNull(service.state.isMailboxAuthorizationEnabled)
            service.refreshMailboxIfNeeded(); assertEquals(1, requests)
            storage.update { it.copy(mailboxAuthorizationExpiry = (now / 1000 + 60 * 86400).toDouble()) }
            service.refreshMailboxIfNeeded(); assertEquals(1, requests) // exact iOS threshold
            storage.update { it.copy(mailboxAuthorizationExpiry = (now / 1000 + 60 * 86400 - 1).toDouble()) }
            fail = true
            val before = service.state
            assertTrue(runCatching { service.refreshMailboxIfNeeded() }.isFailure)
            assertEquals(before, service.state)
            fail = false; service.refreshMailboxIfNeeded(); assertEquals(3, requests)
            service.setMailboxEnabled(false)
            service.refreshMailboxIfNeeded(); assertEquals(3, requests)
            service.resetRegistration(); service.apply(Json.decodeFromString(response), device)
            service.refreshMailboxIfNeeded(); assertEquals(3, requests) // explicit opt-out survives registration
            service.setMailboxEnabled(true); assertEquals(4, requests)
            service.resetRegistration(); service.apply(Json.decodeFromString(response), device)
            service.refreshMailboxIfNeeded(); assertEquals(5, requests) // registration clears expiry
        }
    }

    @Test fun backupPreferenceCommitsAfterServerSuccessAndResetMatchesRN() = runBlocking {
        val values = mutableMapOf(PersistedServerState.key to ServerStateTest.rnJSON)
        val storage = ServerStateStorage(values::get, { k, v -> values[k] = v })
        var failing = true
        val engine = MockEngine {
            if (failing) respond("unavailable", HttpStatusCode.ServiceUnavailable)
            else respond("""{"success":true}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        ServerClient.make("https://example.test", engine = engine).use { client ->
            val service = ServerRegistration(client, storage, { "ark-test" }, { device }, { 1000 }, {})
            val before = service.state
            assertTrue(runCatching { service.setBackupEnabled(true) }.isFailure)
            assertEquals(before, service.state)
            failing = false; service.setBackupEnabled(true); assertEquals(true, service.state.isBackupEnabled)
            service.setBackupEnabled(false); assertEquals(false, service.state.isBackupEnabled)
            service.resetRegistration()
            assertEquals(false, service.state.isRegisteredWithServer)
            assertNull(service.state.lightningAddress); assertNull(service.state.nostrPubkey)
            assertNull(service.state.emailAddress); assertEquals(false, service.state.isEmailVerified)
            assertEquals(false, service.state.isEmailPromptDismissed)
            assertNull(service.state.mailboxAuthorizationExpiry)
            assertNull(service.state.lastReportedDeviceFingerprint); assertNull(service.state.lastDeviceReportAt)
            assertEquals(before.isMailboxAuthorizationEnabled, service.state.isMailboxAuthorizationEnabled)
        }
    }

    @Test fun registrationReconcilesIdentityPreservesPreferencesAndRunsOnce() = runBlocking {
        val values = mutableMapOf(PersistedServerState.key to ServerStateTest.rnJSON,
            "profile-storage" to """{"state":{"displayName":"Old","preferredCurrency":"EUR","bitcoinAmountUnit":"sats"},"version":0}""")
        val store = ServerStateStorage(values::get, { k, v -> values[k] = v })
        store.update { it.copy(isRegisteredWithServer = false) }
        val paths = mutableListOf<String>()
        val engine = MockEngine { request ->
            paths += request.url.encodedPath
            if (request.url.encodedPath == "/v0/app_version")
                respond("""{"update_required":false,"minimum_required_version":"0.0.1"}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
            else {
                val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                assertEquals("ark-test", body.getValue("ark_address").jsonPrimitive.content)
                assertEquals(Json.encodeToJsonElement(device), body.getValue("device_info"))
                respond(response, headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }
        ServerClient.make("https://example.test", engine = engine).use { client ->
            val service = ServerRegistration(client, store, { "ark-test" }, { device }, { ServerAuthTest.now }, {})
            (1..10).map { async { service.ensureRegistered() } }.awaitAll()
            assertEquals(listOf("/v0/app_version", "/v0/register"), paths)
            assertEquals(true, service.state.isRegisteredWithServer)
            assertEquals(true, service.state.isBackupEnabled)
            assertEquals(false, service.state.isEmailVerified)
            assertNull(service.state.emailAddress)
            assertEquals("new@localhost.com", service.state.lightningAddress)
            assertEquals(false, service.state.isMailboxAuthorizationEnabled)
            assertEquals(DeviceInfo.fingerprint(device), service.state.lastReportedDeviceFingerprint)
            val profile = Json.parseToJsonElement(values.getValue("profile-storage")).jsonObject.getValue("state").jsonObject
            assertEquals("New", profile.getValue("displayName").jsonPrimitive.content)
            assertEquals("EUR", profile.getValue("preferredCurrency").jsonPrimitive.content)
            assertEquals("sats", profile.getValue("bitcoinAmountUnit").jsonPrimitive.content)
        }
    }

    @Test fun checksVersionOnRegisteredFastPathAndOnlyRecordsSuccessfulReports() = runBlocking {
        val values = mutableMapOf<String, String>()
        val store = ServerStateStorage(values::get, { k, v -> values[k] = v })
        store.update { it.copy(isRegisteredWithServer = true) }
        var failReport = true
        var time = ServerAuthTest.now
        val paths = mutableListOf<String>()
        val bodies = mutableListOf<JsonObject>()
        val engine = MockEngine { request ->
            paths += request.url.encodedPath
            bodies += Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            when {
                request.url.encodedPath == "/v0/app_version" -> respond("unavailable", HttpStatusCode.ServiceUnavailable)
                failReport -> respond("unavailable", HttpStatusCode.ServiceUnavailable)
                else -> respond("""{"success":true}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }
        ServerClient.make("https://example.test", engine = engine).use { client ->
            val service = ServerRegistration(client, store, { error("Already registered") }, { device }, { time }, {})
            service.ensureRegistered(); service.ensureRegistered()
            assertEquals(listOf("/v0/app_version"), paths)
            val before = service.state
            try { service.reportLastLogin(); fail("expected failure") } catch (_: ServerFailure) { }
            assertEquals(before, service.state)
            assertTrue(bodies.last().containsKey("device_info"))
            failReport = false
            service.reportLastLogin()
            assertTrue(bodies.last().containsKey("device_info"))
            assertEquals(time.toDouble(), service.state.lastDeviceReportAt)
            service.reportLastLogin()
            assertTrue(bodies.last().isEmpty())
            time += DeviceInfo.refreshIntervalMillis
            service.reportLastLogin()
            assertTrue(bodies.last().containsKey("device_info"))
        }
    }

    @Test fun failedRegistrationCanRetryWithoutMarkingRegistered() = runBlocking {
        val values = mutableMapOf<String, String>()
        val store = ServerStateStorage(values::get, { k, v -> values[k] = v })
        var failRegister = true
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath == "/v0/app_version" -> respond("""{"update_required":true,"minimum_required_version":"9.0.0"}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
                failRegister -> respond("unavailable", HttpStatusCode.ServiceUnavailable)
                else -> respond(response, headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }
        ServerClient.make("https://example.test", engine = engine).use { client ->
            val service = ServerRegistration(client, store, { "ark-test" }, { device }, warn = {})
            try { service.ensureRegistered(); fail("expected failure") } catch (_: ServerFailure) { }
            assertNull(service.state.isRegisteredWithServer)
            assertEquals(true, service.versionInfo?.updateRequired)
            failRegister = false
            service.ensureRegistered()
            assertEquals(true, service.state.isRegisteredWithServer)
        }
    }

    @Test fun emailIdentityAndDismissalOnlyPersistSuccessfulChanges() = runBlocking {
        val values = mutableMapOf(PersistedServerState.key to ServerStateTest.rnJSON)
        val store = ServerStateStorage(values::get, { k, v -> values[k] = v })
        var verified = false
        var already = false
        var identityFails = false
        val engine = MockEngine { request ->
            val body = when (request.url.encodedPath) {
                "/v0/app_version" -> """{"update_required":false,"minimum_required_version":"0.0.1"}"""
                "/v0/email/send_verification" -> if (already) """{"success":true,"message":"Email already verified","email":"canonical@example.com"}""" else """{"success":true,"message":"Sent"}"""
                "/v0/email/verify" -> """{"success":$verified}"""
                else -> if (identityFails) return@MockEngine respond("failed", HttpStatusCode.BadRequest)
                    else """{"lightning_address":"changed@localhost.com","nostr_pubkey":null}"""
            }
            respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        ServerClient.make("https://example.test", engine = engine).use { client ->
            val service = ServerRegistration(client, store, { "ark-test" }, { device }, warn = {})
            assertFalse(service.sendEmail("new@example.com"))
            assertEquals("alice@example.com", service.state.emailAddress)
            try { service.verifyEmail("123456", "new@example.com"); fail("expected failure") } catch (_: ServerFailure) { }
            assertEquals("alice@example.com", service.state.emailAddress)
            verified = true
            service.verifyEmail("123456", " new@example.com ")
            assertEquals("new@example.com", service.state.emailAddress)
            assertEquals(true, service.state.isEmailVerified)
            already = true
            assertTrue(service.sendEmail("new@example.com"))
            assertEquals("canonical@example.com", service.state.emailAddress)
            identityFails = true
            try { service.saveIdentity("changed", ""); fail("expected failure") } catch (_: ServerFailure) { }
            assertEquals("alice@localhost.com", service.state.lightningAddress)
            identityFails = false
            service.saveIdentity("changed", "")
            assertEquals("changed@localhost.com", service.state.lightningAddress)
            assertNull(service.state.nostrPubkey)
            service.dismissEmailPrompt()
            assertEquals(true, service.state.isEmailPromptDismissed)
            assertEquals(false, service.state.isMailboxAuthorizationEnabled)
        }
    }
}
