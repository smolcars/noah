package com.noahwallet.services

import com.noahwallet.services.logging.*
import io.sentry.*
import java.io.StringReader
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class EndpointPrivacyRound9Test {
    private fun cases(kind: String) = Json.parseToJsonElement(javaClass.classLoader!!.getResource("esplora-log-privacy-r9.json")!!.readText())
        .jsonArray.map { it.jsonObject }.filter { it.getValue("kind").jsonPrimitive.content == kind }

    @Test fun serializedNumbersCannotBypassTheFinalTelemetryGuard() {
        val serializer = JsonSerializer(SentryOptions.empty())
        for (test in cases("telemetry") + cases("breadcrumb")) {
            EndpointLogPrivacy.register(test.getValue("endpoint").jsonPrimitive.content)
            val payload = test.getValue("payload").jsonObject
            assertFalse(test.toString(), AppTelemetry.isSafePayload(payload))
            if (test.getValue("kind").jsonPrimitive.content == "telemetry") {
                val event = requireNotNull(serializer.deserialize(StringReader(payload.toString()), SentryEvent::class.java))
                assertNull(test.toString(), AppTelemetry.scrub(event))
            } else {
                val crumb = requireNotNull(serializer.deserialize(StringReader(payload.toString()), Breadcrumb::class.java))
                // Sentry converts this epoch number to an ISO timestamp before submission.
                val clean = requireNotNull(AppTelemetry.scrub(crumb))
                assertFalse(AppTelemetry.json(clean).toString().contains("835917264"))
            }
        }
        val crumb = Breadcrumb().apply { message = "ordinary diagnostic" }
        assertEquals(crumb.message, AppTelemetry.scrub(crumb)?.message)
    }

    @Test fun longCombiningRunsFailClosedBeforeNormalization() {
        for (mark in listOf("\u0301", "\u0903", "\u20DD", "\uD834\uDD65")) {
            assertFalse(EndpointLogPrivacy.hasExcessiveCombiningRun("x" + mark.repeat(32)))
            assertTrue(EndpointLogPrivacy.hasExcessiveCombiningRun("x" + mark.repeat(33)))
            assertFalse(EndpointLogPrivacy.hasExcessiveCombiningRun(("x" + mark.repeat(32)).repeat(2)))
        }
        assertNull(LogFile.decodedViews("x" + "\\u0301\\u0327".repeat(33), percentEncoded = true, compatibility = true))
        for (test in cases("combining")) {
            EndpointLogPrivacy.register(test.getValue("endpoint").jsonPrimitive.content)
            val input = "x" + test.getValue("unit").jsonPrimitive.content.repeat(test.getValue("count").jsonPrimitive.int)
            assertTrue(EndpointLogPrivacy.SecretMatcher(setOf("fakeSpeedSecret")).contains(input))
            assertNull(LogFile.decodedViews(input, percentEncoded = true, compatibility = true))
            assertEquals(input, EndpointLogPrivacy.sanitize(input, emptySet()))
            assertEquals("[custom-esplora]", EndpointLogPrivacy.redact(input))
        }
    }

    @Test fun combiningRunScaling() {
        val vectors = cases("combining").filter { it.getValue("count").jsonPrimitive.int >= 512 }
        for ((name, redact) in listOf("endpoint" to EndpointLogPrivacy::redact, "file" to LogFile::redact, "record" to EndpointLogPrivacy::redactRecord)) {
            val medians = vectors.map { test ->
                EndpointLogPrivacy.register(test.getValue("endpoint").jsonPrimitive.content)
                val input = "x" + test.getValue("unit").jsonPrimitive.content.repeat(test.getValue("count").jsonPrimitive.int)
                redact(input)
                List(5) { val start = System.nanoTime(); redact(input); System.nanoTime() - start }.sorted()[2]
            }
            println("REDACTION long combining run $name 512 -> 8192: ${medians.map { it / 1e6 }} ms")
            assertEquals(2, medians.size)
            assertTrue(medians[1] < medians[0] * 64 + 20_000_000)
            assertTrue(medians[1] < 2_000_000_000)
        }
    }
}
