package com.noahwallet.services.server

import com.noahwallet.api.generated.PublicOperations
import com.noahwallet.api.generated.client.V0ReportLastLoginClient
import com.noahwallet.api.generated.models.ReportLastLoginPayload
import io.ktor.client.engine.mock.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

class ServerAuthTest {
    companion object {
        const val now = 1_800_000_000_000L
        fun token(seconds: Long = 72 * 3600, suffix: String = "sig") =
            "e30.${Base64.getUrlEncoder().withoutPadding().encodeToString("{\"exp\":${now / 1000 + seconds}}".toByteArray())}.$suffix"
    }

    class Fake(var stored: String? = null) {
        val logins = AtomicInteger()
        var gate: CompletableDeferred<Unit>? = null
        var started = CompletableDeferred<Unit>()
        var fail = false
        var failSave = false
        fun provider() = AuthTokenProvider(
            readToken = { stored }, saveToken = {
                if (failSave) error("secret")
                stored = it
            },
            clearToken = { stored = null }, readMnemonic = { "fixture" },
            login = {
                val index = logins.incrementAndGet()
                started.complete(Unit)
                gate?.await()
                if (fail) error("secret")
                token(suffix = index.toString())
            }, now = { now },
        )
    }

    @Test fun expiryMatchesRNAtRefreshBoundary() {
        assertFalse(JWT.needsRefresh(token(10_861), now))
        assertTrue(JWT.needsRefresh(token(10_860), now))
        assertTrue(JWT.needsRefresh(token(-1), now))
        assertEquals((now / 1000 + 72 * 3600).toDouble(), JWT.expiry(token()), 0.0)
    }

    @Test fun invalidExpiryDoesNotLeakPayload() {
        val payloads = listOf("{}", "{\"exp\":true}", "{\"exp\":\"9999999999\"}", "[]", "{\"exp\":1e999}", "private-data")
        for (payload in payloads) {
            val invalid = "e30.${Base64.getUrlEncoder().encodeToString(payload.toByteArray())}.sig"
            val failure = assertThrows(IllegalArgumentException::class.java) { JWT.expiry(invalid) }
            assertEquals("Invalid server token expiry", failure.message)
            assertNull(failure.cause)
        }
        assertThrows(IllegalArgumentException::class.java) { JWT.expiry("not-a-token") }
    }

    /** After the seed changes, a still-valid token for the old wallet must not be reused. */
    @Test fun tokenForAnotherWalletIsNeverReused() = runBlocking {
        fun tokenFor(subject: String, suffix: String) = "e30.${Base64.getUrlEncoder().withoutPadding()
            .encodeToString("{\"sub\":\"$subject\",\"exp\":${now / 1000 + 72 * 3600}}".toByteArray())}.$suffix"
        var stored: String? = tokenFor("02previous", "old")
        val logins = AtomicInteger()
        val provider = AuthTokenProvider(
            readToken = { stored }, saveToken = { stored = it }, clearToken = { stored = null },
            readMnemonic = { "fixture" }, login = { logins.incrementAndGet(); tokenFor("02current", "new") },
            now = { now }, loginKey = { "02current" },
        )
        assertEquals(tokenFor("02current", "new"), provider.token())
        assertEquals(1, logins.get())
        assertEquals(tokenFor("02current", "new"), provider.token())
        assertEquals(1, logins.get())
    }

    @Test fun freshTokenIsReused() = runBlocking {
        val fake = Fake(token())
        val provider = fake.provider()
        assertEquals(fake.stored, provider.token())
        assertEquals(0, fake.logins.get())
    }

    @Test fun expiredAndMalformedTokensAreReplaced() = runBlocking {
        for (old in listOf(token(60), "bad-token", null)) {
            val fake = Fake(old)
            val provider = fake.provider()
            val fresh = provider.token()
            assertEquals(fresh, provider.token())
            assertEquals(fresh, fake.stored)
            assertEquals(1, fake.logins.get())
        }
    }

    @Test fun concurrentCallersShareOneLogin() = runBlocking {
        val fake = Fake().apply { gate = CompletableDeferred() }
        val provider = fake.provider()
        val first = async { provider.token() }
        fake.started.await()
        val rest = (1..30).map { async(start = CoroutineStart.UNDISPATCHED) { provider.token(forceRefresh = true) } }
        fake.gate!!.complete(Unit)
        val result = first.await()
        rest.forEach { assertEquals(result, it.await()) }
        assertEquals(1, fake.logins.get())
    }

    @Test fun cancellationOfFirstCallerDoesNotCancelLogin() = runBlocking {
        val fake = Fake().apply { gate = CompletableDeferred() }
        val provider = fake.provider()
        val first = async { provider.token() }
        fake.started.await()
        first.cancelAndJoin()
        val second = async { provider.token() }
        fake.gate!!.complete(Unit)
        assertEquals(second.await(), fake.stored)
        assertEquals(1, fake.logins.get())
    }

    @Test fun failedLoginAndFailedSaveCanRetryWithoutCaching() = runBlocking {
        for (save in listOf(false, true)) {
            val fake = Fake().apply { fail = !save; failSave = save }
            val provider = fake.provider()
            try { provider.token(); fail("expected failure") } catch (failure: ServerFailure) {
                assertEquals("Server authentication failed", failure.message)
                assertNull(failure.cause)
            }
            assertNull(fake.stored)
            fake.fail = false; fake.failSave = false
            assertEquals(provider.token(), fake.stored)
            assertEquals(2, fake.logins.get())
        }
    }

    @Test fun forcedRefreshAndLateRejectionOfOldToken() = runBlocking {
        val old = token()
        val fake = Fake(old)
        val provider = fake.provider()
        val fresh = provider.token(forceRefresh = true, rejectedToken = old)
        assertEquals(fresh, provider.token(forceRefresh = true, rejectedToken = old))
        assertEquals(1, fake.logins.get())
        provider.token(forceRefresh = true)
        assertEquals(2, fake.logins.get())
    }

    @Test fun publicRoutesMatchSpecSecurityAndEverythingElseRequiresBearer() {
        val spec = Json.parseToJsonElement(javaClass.getResource("/openapi.json")!!.readText()).jsonObject
        val expected = spec.getValue("paths").jsonObject.flatMap { (path, methods) ->
            methods.jsonObject.mapNotNull { (method, value) ->
                val operation = value.jsonObject
                if (operation["operationId"] == null) null else {
                    val security = operation["security"] ?: spec["security"]
                    if (security == null || security.jsonArray.isEmpty() || JsonObject(emptyMap()) in security.jsonArray)
                        "${method.uppercase()} $path" else null
                }
            }
        }.toSet()
        assertEquals(expected, PublicOperations.routes)
        expected.forEach {
            val (method, path) = it.split(' ', limit = 2)
            assertTrue(ServerClient.isPublic(method, path.replace("{username}", "alice")))
        }
        assertFalse(ServerClient.isPublic("POST", "/v0/register"))
        assertFalse(ServerClient.isPublic("POST", "/new-gated-endpoint"))
        assertFalse(ServerClient.isPublic("POST", "/v0/getk1"))
    }

    @Test fun publicRequestsDoNotLoginOrSendBearer() = runBlocking {
        val fake = Fake()
        val engine = MockEngine { request ->
            assertNull(request.headers[HttpHeaders.Authorization])
            respond("{}", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        ServerClient.make("https://example.test", fake.provider(), engine).use { client ->
            client.get("/health") { header(HttpHeaders.Authorization, "must-be-removed") }
            client.post("/v0/app_version")
        }
        assertEquals(0, fake.logins.get())
    }

    @Test fun authErrorsRetryExactlyOnceAndOtherErrorsDoNot() = runBlocking {
        for ((status, code, expected) in listOf(
            Triple(401, "AUTH_REQUIRED", 2), Triple(401, "INVALID_TOKEN", 2), Triple(401, "TOKEN_EXPIRED", 2),
            Triple(401, "EMAIL_NOT_VERIFIED", 1), Triple(403, "INVALID_TOKEN", 1), Triple(500, "SERVER_ERROR", 1),
            Triple(401, "malformed", 1))) {
            val fake = Fake(token())
            val headers = mutableListOf<String?>()
            val engine = MockEngine { request ->
                headers += request.headers[HttpHeaders.Authorization]
                val body = if (code == "malformed") "invalid-json" else
                    """{"status":"ERROR","code":"$code","message":"Denied","reason":"test"}"""
                respond(body, HttpStatusCode.fromValue(status), headersOf(HttpHeaders.ContentType, "application/json"))
            }
            ServerClient.make("https://example.test", fake.provider(), engine).use { client ->
                try { V0ReportLastLoginClient(client).reportLastLogin(ReportLastLoginPayload()).value(); fail("expected failure") }
                catch (failure: ServerFailure) { assertEquals(status, failure.status) }
            }
            assertEquals(code, expected, headers.size)
            assertEquals("Bearer ${token()}", headers.first())
            assertEquals(expected - 1, fake.logins.get())
            if (expected == 2) assertNotEquals(headers[0], headers[1])
        }
    }

    @Test fun rejectedTokenRetriesToSuccess() = runBlocking {
        val fake = Fake(token())
        var attempts = 0
        val engine = MockEngine {
            attempts++
            if (attempts == 1) respond("""{"status":"ERROR","code":"TOKEN_EXPIRED","message":"Expired","reason":"test"}""",
                HttpStatusCode.Unauthorized, headersOf(HttpHeaders.ContentType, "application/json"))
            else respond("""{"success":true}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        ServerClient.make("https://example.test", fake.provider(), engine).use { client ->
            assertTrue(V0ReportLastLoginClient(client).reportLastLogin(ReportLastLoginPayload()).value().success)
        }
        assertEquals(2, attempts)
    }

    @Test fun cannotSendBearerToAnotherOrigin() = runBlocking {
        val fake = Fake(token())
        val engine = MockEngine { error("Must not send") }
        ServerClient.make("https://example.test", fake.provider(), engine).use { client ->
            try { client.get("https://other.test/v0/register"); fail("expected failure") }
            catch (_: IllegalStateException) { }
        }
        assertEquals(0, fake.logins.get())
    }

    @Test fun endpointsMatchVariantsAndSupportRegtestOverride() {
        assertEquals("https://noahwallet.io", ServerEndpoint.baseURL("mainnet"))
        assertEquals("https://signet.noahwallet.io", ServerEndpoint.baseURL("signet"))
        assertEquals("http://10.0.2.2:3000", ServerEndpoint.baseURL("regtest"))
        assertEquals("http://localhost:1234", ServerEndpoint.baseURL("regtest", "http://localhost:1234"))
    }
}
