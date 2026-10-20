package com.noahwallet.services

import com.noahwallet.services.logging.*
import io.sentry.*
import io.sentry.protocol.Message
import java.nio.file.Files
import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Test

class EndpointPrivacyTest {
    @Test fun hostCredentialsAndEncodedIdentitiesNeverReachSinks() {
        val origin = "https://fakehostcredential73.example.com:8443"
        EndpointLogPrivacy.register("$origin/api")
        EndpointLogPrivacy.register("https://fakepunctcredential92.example.com/api")
        val inputs = listOf("https://fakepunctcredential92.example.com:443)", origin, "$origin/api/blocks/tip/height", origin.uppercase(), "$origin)", "$origin,", "$origin.",
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
        assertEquals("prefix[custom-esplora]", LogFile.redact("prefix$origin"))
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

    @Test fun routeOnlyEndpointsPreserveOtherOriginsAndDiagnostics() {
        EndpointLogPrivacy.register("https://route-privacy.example/api")
        for (input in listOf("https://mempool.space/api/blocks/tip/height", "HTTP 404: /blocks/tip/height failed", "API v1 request completed", "https://.", "https://route-privacy.example.other/api", "https://route-privacy.example:8443/api")) {
            assertEquals(input, LogFile.redact(input))
        }
    }
}
