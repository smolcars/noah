package com.noahwallet.services

import com.noahwallet.services.logging.*
import io.sentry.*
import io.sentry.protocol.Message
import io.sentry.protocol.SentryException
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.Executor

class EndpointPrivacyTest {
    @Test fun equivalentAuthoritiesUseSharedFixturesAtEveryBoundary() {
        val cases = Json.parseToJsonElement(javaClass.classLoader!!.getResource("esplora-log-authorities.json")!!.readText()).jsonArray.map { it.jsonObject }
        cases.forEach { EndpointLogPrivacy.register(it.getValue("endpoint").jsonPrimitive.content) }
        val directory = Files.createTempDirectory("authority").toFile()
        val received = mutableListOf<String>()
        val file = LogFile(directory, executor = Executor { it.run() }, systemSink = { _, _, text -> received += text })
        try {
            val persisted = mutableListOf<String>()
            for (test in cases) {
                val input = test.getValue("input").jsonPrimitive.content
                val safe = LogFile.redact(input)
                assertEquals(input, test.getValue("expected").jsonPrimitive.content, safe)
                val event = endpointEvent(input)
                val clean = requireNotNull(AppTelemetry.scrub(event))
                assertEquals(safe, clean.message?.formatted)
                assertEquals(safe, clean.exceptions!!.first().value)
                assertEquals("FakeEndpointError", clean.exceptions!!.first().type)
                assertEquals(safe, requireNotNull(AppTelemetry.scrub(Breadcrumb().apply { message = input })).message)
                AppLog("authority", file).e(input)
                assertEquals(safe, received.last())
                persisted += safe.trim()
            }
            // One export for all of them: each read redacts every line again.
            val lines = file.lines()
            assertEquals(persisted.size, lines.size)
            for ((line, safe) in lines.zip(persisted)) assertTrue(line, line.endsWith(safe))
            directory.resolve("noah.log.1").writeText(cases.filter { it.getValue("redacted").jsonPrimitive.boolean }.joinToString("\n") {
                it.getValue("input").jsonPrimitive.content
            })
            assertFalse(file.lines().joinToString().contains("bücher.example)"))
            assertFalse(file.lines().joinToString().contains("[0:0::1]"))
        } finally { directory.deleteRecursively() }
    }

    @Test fun secretTokensUseSharedFixturesAtEveryBoundary() {
        val cases = Json.parseToJsonElement(javaClass.classLoader!!.getResource("esplora-log-tokens.json")!!.readText()).jsonArray.map { it.jsonObject }
        cases.forEach { EndpointLogPrivacy.register(it.getValue("endpoint").jsonPrimitive.content) }
        val directory = Files.createTempDirectory("tokens").toFile()
        val received = mutableListOf<String>()
        val file = LogFile(directory, executor = Executor { it.run() }, systemSink = { _, _, text -> received += text })
        try {
            for (test in cases) {
                val input = test.getValue("input").jsonPrimitive.content
                val expected = test.getValue("expected").jsonPrimitive.content
                assertEquals(input, expected, LogFile.redact(input))
                val event = endpointEvent(input)
                val clean = requireNotNull(AppTelemetry.scrub(event))
                assertEquals(expected, clean.message?.formatted)
                assertEquals(expected, clean.exceptions!!.first().value)
                assertEquals(expected, requireNotNull(AppTelemetry.scrub(Breadcrumb().apply { message = input })).message)
                AppLog("tokens", file).e(input)
                assertEquals(expected, received.last())
                assertTrue(file.lines().last().endsWith(expected))
                directory.resolve("noah.log.1").writeText(input)
                assertTrue(file.lines().first().endsWith(expected))
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun combiningMarksCannotHideRawTokens() = round8Tokens("combining")
    @Test fun idna2008UnicodeFormsAreRegistered() = round8Tokens("IDNA2008")

    private fun round8Tokens(family: String) {
        val cases = Json.parseToJsonElement(javaClass.classLoader!!.getResource("esplora-log-tokens.json")!!.readText()).jsonArray.map { it.jsonObject }
        cases.filter { it.getValue("name").jsonPrimitive.content.startsWith(family) }.forEach { test ->
            EndpointLogPrivacy.register(test.getValue("endpoint").jsonPrimitive.content)
            assertEquals(test.getValue("name").jsonPrimitive.content, test.getValue("expected").jsonPrimitive.content,
                LogFile.redact(test.getValue("input").jsonPrimitive.content))
        }
    }

    @Test fun tokenAutomatonFollowsFailureLinksAndProtectsProviderSummaries() {
        val matcher = EndpointLogPrivacy.SecretMatcher(setOf("he", "sheq", "hers", "his", "fake😀Secret"))
        for (input in listOf("she", "ushers", "hish", "FAKE😀SECRET")) assertTrue(matcher.contains(input))
        assertFalse(matcher.contains("ordinary diagnostic"))
        assertTrue(EndpointLogPrivacy.SecretMatcher(setOf("fakeStraßeSecret")).contains("FAKESTRAẞESECRET"))
        val unicode = "fakeΐSecret761"
        val normalized = requireNotNull(EndpointLogPrivacy.SecretMatcher.normalize(unicode))
        assertEquals(normalized, EndpointLogPrivacy.SecretMatcher.normalize(normalized))
        assertTrue(EndpointLogPrivacy.SecretMatcher(setOf(normalized)).contains(unicode))
        val provider = EndpointLogPrivacy.SecretMatcher(setOf("HttpResponse"))
        for (input in listOf("HttpResponse { status: 503 }", "HttpResponse unknown", """HttpResponse \uD800""")) {
            assertEquals("[custom-esplora]", EndpointLogPrivacy.sanitize(input, setOf("https://fake-provider.example"), provider))
        }
    }

    @Test fun hostCredentialsAndEncodedIdentitiesNeverReachSinks() {
        val origin = "https://fakehostcredential73.example.com:8443"
        EndpointLogPrivacy.register("$origin/api")
        EndpointLogPrivacy.register("https://fakepunctcredential92.example.com/api")
        val inputs = listOf("https://fakepunctcredential92.example.com:443)", origin, "$origin/api/blocks/tip/height", origin.uppercase(),
            "$origin)", "$origin,", "$origin.",
            origin.replace("fake", """\u0066ake"""), origin.replace(":", "%3A").replace("/", "%2F"), origin.replace("/", "%2F"),
            origin.replace("/", "\\/"), origin.replace(":", "&#58;").replace("/", "&#x2f;"))
        for (input in inputs) {
            assertEquals("[custom-esplora]", LogFile.redact(input))
            val event = SentryEvent().apply { message = Message().apply { formatted = input } }
            assertEquals("[custom-esplora]", requireNotNull(AppTelemetry.scrub(event)).message?.formatted)
            val crumb = Breadcrumb().apply { message = input }
            assertEquals("[custom-esplora]", requireNotNull(AppTelemetry.scrub(crumb)).message)
            event.release = input
            assertNull(AppTelemetry.scrub(event))
        }
        assertEquals("[custom-esplora]", LogFile.redact("prefix$origin"))
        assertEquals("TransportError HTTP 503: [custom-esplora]", LogFile.redact("TransportError HTTP 503: fakehostcredential73.example.com:invalid"))
    }

    @Test fun unknownHTMLEntitiesAndUnterminatedNumericEntitiesCannotExposeHosts() {
        EndpointLogPrivacy.register("https://fakeentitycredential91.example.com/api")
        val unknown = "https://fakeentitycredential91&period;example&period;com/api"
        assertEquals("[redacted]", LogFile.redact(unknown))
        assertNull(LogFile.redactForTelemetry(unknown))
        val numeric = "https:&#47&#47fakeentitycredential91.example.com/api"
        assertEquals("[custom-esplora]", LogFile.redact(numeric))
    }

    @Test fun providerBodiesAreRemovedAtEveryBoundaryAndHistoricalExport() {
        val input = """HttpResponse { status: 404, message: "Account: &#x66;akeBodyCredential83" }"""
        val expected = "HttpResponse { status: 404 }"
        for (response in listOf(input, """HttpResponse { status: 404, message: "truncated fake""",
            """HttpResponse { status: 404, message: "bad \uD800 escape" }""",
            input.replace("{", "&#123;").replace("}", "&#125;"))) {
            assertEquals(expected, LogFile.redact(response))
            assertEquals(expected, LogFile.redactForTelemetry(response))
        }
        assertEquals("HttpResponse", LogFile.redact("""HttpResponse { message: "fakeUnknownBody" }"""))
        val event = SentryEvent().apply { message = Message().apply { formatted = input } }
        assertEquals(expected, requireNotNull(AppTelemetry.scrub(event)).message?.formatted)
        val directory = Files.createTempDirectory("endpoint-boundary").toFile()
        val received = mutableListOf<String>()
        val file = LogFile(directory, executor = Executor { it.run() }, systemSink = { _, _, text -> received += text })
        try {
            directory.resolve("noah.log.1").writeText(input)
            AppLog("wallet", file).e(input)
            assertEquals(listOf(expected), received)
            assertTrue(file.lines().all { it.endsWith(expected) })
        } finally { directory.deleteRecursively() }
    }

    @Test fun configuredOriginsAndDiagnosticsRemainVisibleInPrivacyMode() {
        EndpointLogPrivacy.register("https://route-privacy.example/api")
        for (input in listOf("https://mempool.space/api/blocks/tip/height", "HTTP 404: /blocks/tip/height failed", "API v1 request completed")) {
            assertEquals(input, LogFile.redact(input))
        }
        for (input in listOf("https://.", "https://route-privacy.example.other/api", "https://route-privacy.example:8443/api")) {
            assertEquals("[custom-esplora]", LogFile.redact(input))
        }
    }

    @Test fun cleanInstallAndUserEnteredDefaultsUseSeparatePolicies() {
        val cases = Json.parseToJsonElement(javaClass.classLoader!!.getResource("esplora-log-authorities.json")!!.readText()).jsonArray
        for (test in cases) {
            val input = test.jsonObject.getValue("input").jsonPrimitive.content
            assertEquals(input, EndpointLogPrivacy.sanitize(input, emptySet()))
        }
        for (variant in listOf("mainnet", "signet", "regtest")) {
            val config = com.noahwallet.services.wallet.WalletConfiguration.base(variant)
            for (endpoint in listOfNotNull(com.noahwallet.services.server.ServerEndpoint.baseURL(variant, com.noahwallet.BuildConfig.REGTEST_SERVER_URL),
                config.arkServer, config.esplora)) {
                val origin = endpoint.substringBefore("/api").trimEnd('/')
                assertEquals(endpoint, EndpointLogPrivacy.sanitize(endpoint, setOf("https://fake-custom.example")))
                assertEquals("[custom-esplora]", EndpointLogPrivacy.sanitize(endpoint, setOf(origin)))
            }
        }
        val hosts = setOf("abandon", "mnemonic", "preimage", "password")
        val registered = hosts.map { "https://$it" }.toSet()
        for (test in com.noahwallet.fixtures.SharedFixtures.load("log-redaction").filter {
            it.name in setOf("mnemonic-lower", "label-mnemonic", "label-preimage", "label-password")
        }) {
            val input = test.input.jsonPrimitive.content
            val expected = test.expected.jsonPrimitive.content
            assertEquals(expected, EndpointLogPrivacy.sanitize(input, registered, hosts = hosts))
            assertEquals("TransportError HTTP 503: $expected", EndpointLogPrivacy.sanitize("TransportError HTTP 503: $input", registered, hosts = hosts))
        }
    }

    /** An error event carrying [input] as its message and its exception's value. */
    private fun endpointEvent(input: String) = SentryEvent().apply {
        message = Message().apply { formatted = input }
        exceptions = listOf(SentryException().apply { value = input; type = "FakeEndpointError" })
    }
}
