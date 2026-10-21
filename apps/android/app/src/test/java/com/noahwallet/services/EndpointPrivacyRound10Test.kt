package com.noahwallet.services

import com.noahwallet.services.logging.*
import io.sentry.*
import io.sentry.protocol.Message
import io.sentry.protocol.SentryException
import java.nio.file.Files
import java.util.concurrent.Executor
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class EndpointPrivacyRound10Test {
    @Test fun reorderedAccentsCannotExposeEndpointTokens() {
        val cases = Json.parseToJsonElement(javaClass.classLoader!!.getResource("esplora-log-privacy-r10.json")!!.readText()).jsonArray.map { it.jsonObject }
        val directory = Files.createTempDirectory("r10").toFile()
        val received = mutableListOf<String>()
        val file = LogFile(directory, executor = Executor { it.run() }, systemSink = { _, _, text -> received += text })
        try {
            assertTrue(EndpointLogPrivacy.SecretMatcher(setOf("fakeR9Secreté")).contains(cases[0].getValue("input").jsonPrimitive.content))
            for (test in cases) {
                EndpointLogPrivacy.register(test.getValue("endpoint").jsonPrimitive.content)
                val input = test.getValue("input").jsonPrimitive.content
                val expected = test.getValue("expected").jsonPrimitive.content
                assertEquals(input, EndpointLogPrivacy.sanitize(input, emptySet()))
                assertEquals(expected, LogFile.redact(input))
                assertEquals(expected, EndpointLogPrivacy.redactRecord(input))
                val event = SentryEvent().apply {
                    message = Message().apply { formatted = input }
                    exceptions = listOf(SentryException().apply { value = input; type = "FakeEndpointError" })
                }
                val clean = requireNotNull(AppTelemetry.scrub(event))
                assertEquals(expected, clean.message?.formatted)
                assertEquals(expected, clean.exceptions!!.first().value)
                assertEquals(expected, requireNotNull(AppTelemetry.scrub(Breadcrumb().apply { message = input })).message)
                AppLog("r10", file).e(input)
                assertEquals(expected, received.last())
                assertTrue(file.lines().last().endsWith(expected))
                directory.resolve("noah.log.1").writeText(input)
                assertTrue(file.lines().first().endsWith(expected))
            }
        } finally { directory.deleteRecursively() }
    }

    private fun expandedRun(count: Int) = "x" + ("\u0301\u0327".repeat(16) + "\uFF9E").repeat(count)

    @Test fun compatibilityExpansionCannotResetCombiningRunBound() {
        // U+FF9E is a letter in the input, but decomposes to a combining mark.
        assertFalse(EndpointLogPrivacy.hasExcessiveCombiningRun("x" + "\uFF9E".repeat(32)))
        assertTrue(EndpointLogPrivacy.hasExcessiveCombiningRun("x" + "\uFF9E".repeat(33)))
        for (input in listOf(expandedRun(1), expandedRun(16), expandedRun(256))) {
            assertTrue(EndpointLogPrivacy.hasExcessiveCombiningRun(input))
            assertTrue(EndpointLogPrivacy.SecretMatcher(setOf("fakeSpeedSecret")).contains(input))
            assertNull(LogFile.decodedViews(input, percentEncoded = true, compatibility = true))
            assertEquals(input, EndpointLogPrivacy.sanitize(input, emptySet()))
        }
        val escaped = expandedRun(2).replace("\uFF9E", "\\uFF9E")
        assertNull(LogFile.decodedViews(escaped, percentEncoded = true, compatibility = true))
    }

    @Test fun compatibilityExpansionScaling() {
        EndpointLogPrivacy.register("https://fake-r10-speed.example/fakeSpeedSecret")
        for ((name, redact) in listOf("endpoint" to EndpointLogPrivacy::redact, "file" to LogFile::redact, "record" to EndpointLogPrivacy::redactRecord)) {
            val medians = listOf(16, 256).map { count ->
                val input = expandedRun(count)
                assertEquals(if (name == "file" && count == 256) "[oversized log message omitted]" else "[custom-esplora]", redact(input))
                List(5) { val start = System.nanoTime(); redact(input); System.nanoTime() - start }.sorted()[2]
            }
            println("REDACTION compatibility expansion $name 16 -> 256: ${medians.map { it / 1e6 }} ms")
            assertTrue(medians[1] < medians[0] * 64 + 20_000_000)
            assertTrue(medians[1] < 2_000_000_000)
        }
    }
}
