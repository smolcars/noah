package com.noahwallet.services

import com.noahwallet.services.logging.AppTelemetry
import com.noahwallet.services.logging.TelemetryTransport
import io.sentry.*
import io.sentry.hints.*
import io.sentry.protocol.Mechanism
import io.sentry.protocol.SentryException
import io.sentry.transport.ITransport
import io.sentry.util.HintUtils
import java.io.ByteArrayInputStream
import java.nio.file.Files
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TelemetryTransportTest {
    private val serializer = JsonSerializer(SentryOptions.empty())
    private fun options() = requireNotNull(AppTelemetry.options(AppTelemetry.Variant.mainnet, false,
        "https://public@example.invalid/1", "com.noahwallet.mainnet", "0.2.0", 100))

    private fun item(type: SentryItemType, payload: String): SentryEnvelopeItem {
        val input = "{}\n{\"type\":\"${type.itemType}\",\"length\":${payload.toByteArray().size}}\n$payload\n"
        return requireNotNull(serializer.deserializeEnvelope(ByteArrayInputStream(input.toByteArray()))).items.single()
    }

    private fun session() = Session(SentryUUID.generateSentryId(), null, "production", "com.noahwallet.mainnet@0.2.0+100")

    @Test fun sdkGeneratedSessionSurvivesFilter() {
        val session = session()
        assertEquals(32, session.sessionId!!.length)
        val filtered = requireNotNull(TelemetryTransport.filter(SentryEnvelope.from(serializer, session, null)))
        val payload = Json.parseToJsonElement(filtered.items.single().data.toString(Charsets.UTF_8)).jsonObject
        assertEquals(session.sessionId, payload["sid"]!!.jsonPrimitive.content)
        assertEquals(session.distinctId, payload["did"]!!.jsonPrimitive.content)
        assertTrue(setOf("sid", "did", "init", "started", "timestamp", "status", "seq", "errors", "duration", "attrs").containsAll(payload.keys))
        assertEquals(setOf("release", "environment"), payload["attrs"]!!.jsonObject.keys)
    }

    @Test fun sessionIDsAcceptOnlySDKHexOrCanonicalUUIDs() {
        val payload = SentryEnvelopeItem.fromSession(serializer, session()).data.toString(Charsets.UTF_8)
        for (id in listOf("60e12f748d9b47cba162903fa0b155a1", "60e12f74-8d9b-47cb-a162-903fa0b155a1")) {
            val input = Json.parseToJsonElement(payload).jsonObject.toMutableMap().apply {
                put("sid", JsonPrimitive(id)); put("did", JsonPrimitive(id))
            }
            assertNotNull(id, TelemetryTransport.filter(SentryEnvelope(SentryEnvelopeHeader(), listOf(item(SentryItemType.Session, JsonObject(input).toString())))))
        }
        for (id in listOf("", "1-1-1-1-1", "g".repeat(32), "a".repeat(31), "a".repeat(33), "alice@example.com", "60e12f74-8d9b47cb-a162-903fa0b155a1")) {
            for (key in listOf("sid", "did")) {
                val input = Json.parseToJsonElement(payload).jsonObject.toMutableMap().apply { put(key, JsonPrimitive(id)) }
                assertNull("$key=$id", TelemetryTransport.filter(SentryEnvelope(SentryEnvelopeHeader(), listOf(item(SentryItemType.Session, JsonObject(input).toString())))))
            }
        }
    }

    @Test fun malformedItemsCannotDiscardValidEventsOrSessions() {
        val validEvent = SentryEnvelopeItem.fromEvent(serializer, failure("UncaughtExceptionHandler", false))
        val validSession = SentryEnvelopeItem.fromSession(serializer, session())
        for (type in listOf(SentryItemType.Event, SentryItemType.Session)) {
            for (payload in listOf("malformed JSON", "{")) {
                val malformed = item(type, payload)
                for (items in listOf(listOf(malformed, validEvent, validSession), listOf(validEvent, validSession, malformed))) {
                    val clean = requireNotNull(TelemetryTransport.filter(SentryEnvelope(SentryEnvelopeHeader(), items)))
                    assertEquals(listOf(SentryItemType.Event, SentryItemType.Session), clean.items.map { it.header.type })
                }
            }
        }
    }

    @Test fun handledErrorWithActiveSDKSessionReachesDelegate() = captureWithSession("generic", true, "ok")
    @Test fun unhandledCrashWithActiveSDKSessionReachesDelegate() = captureWithSession("UncaughtExceptionHandler", false, "crashed")
    @Test fun anrWithActiveSDKSessionReachesDelegate() = captureWithSession("ANR", false, "crashed")

    private fun captureWithSession(mechanism: String, handled: Boolean, status: String) {
        val options = options()
        val received = RecordingTransport()
        options.setTransportFactory { configured, _ -> TelemetryTransport(received, configured) }
        val client = SentryClient(options)
        try {
            val scope = Scope(options).apply { startSession() }
            val event = failure(mechanism, handled)
            client.captureEvent(event, scope, Hint())
            val envelope = received.envelopes.single()
            assertEquals(listOf(SentryItemType.Event, SentryItemType.Session), envelope.items.map { it.header.type })
            assertEquals(event.eventId, envelope.items.first().getEvent(serializer)!!.eventId)
            val session = Json.parseToJsonElement(envelope.items.last().data.toString(Charsets.UTF_8)).jsonObject
            assertEquals(status, session["status"]!!.jsonPrimitive.content)
            assertEquals(1, session["errors"]!!.jsonPrimitive.int)
        } finally { client.close() }
    }

    @Test fun crashOnNextLaunchReplaysCombinedSDKEnvelopeThroughCacheSender() {
        val cache = Files.createTempDirectory("noah-telemetry-replay").toFile()
        try {
            val options = options()
            val captured = RecordingTransport()
            options.setTransportFactory { _, _ -> captured }
            val client = SentryClient(options)
            try {
                client.captureEvent(failure("UncaughtExceptionHandler", false), Scope(options).apply { startSession() }, Hint())
            } finally { client.close() }
            val cached = cache.resolve("crash.envelope")
            cached.outputStream().use { serializer.serialize(captured.envelopes.single(), it) }
            val nextLaunch = options()
            val received = RecordingTransport()
            nextLaunch.setTransportFactory { configured, _ -> TelemetryTransport(received, configured) }
            val nextClient = SentryClient(nextLaunch)
            try {
                val scope = Scope(nextLaunch).apply { bindClient(nextClient) }
                val scopes = Scopes(scope, Scope(nextLaunch), Scope(nextLaunch), "telemetry-test")
                EnvelopeSender(scopes, serializer, NoOpLogger.getInstance(), 100, 10).processDirectory(cache)
                assertEquals(listOf(SentryItemType.Event, SentryItemType.Session), received.envelopes.single().items.map { it.header.type })
                assertFalse(cached.exists())
            } finally { nextClient.close() }
        } finally { cache.deleteRecursively() }
    }

    private fun failure(mechanismType: String, handled: Boolean) = SentryEvent().apply {
        level = if (handled) SentryLevel.ERROR else SentryLevel.FATAL
        exceptions = listOf(SentryException().apply {
            type = if (mechanismType == "ANR") "ApplicationNotResponding" else "IllegalStateException"
            value = "synthetic failure"
            mechanism = Mechanism().apply { type = mechanismType; isHandled = handled }
        })
    }

    private class RecordingTransport : ITransport {
        val envelopes = mutableListOf<SentryEnvelope>()
        override fun send(envelope: SentryEnvelope, hint: Hint) {
            envelopes += envelope
            HintUtils.runIfHasType(hint, SubmissionResult::class.java) { it.setResult(true) }
        }
        override fun flush(timeoutMillis: Long) {}
        override fun close() {}
        override fun close(isRestarting: Boolean) {}
        override fun getRateLimiter(): io.sentry.transport.RateLimiter? = null
    }
}
