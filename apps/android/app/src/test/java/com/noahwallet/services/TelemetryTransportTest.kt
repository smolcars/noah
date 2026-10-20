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
import java.io.StringReader
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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

    private fun nativeEvent() = requireNotNull(javaClass.getResourceAsStream("/telemetry/native-crash.json")).use {
        requireNotNull(serializer.deserialize(StringReader(it.bufferedReader().readText()), SentryEvent::class.java))
    }

    @Test fun nativeOutboxReplayKeepsOnlyAllowlistedSignalThreadAndFrameFields() {
        for (signal in listOf("SIGSEGV", "SIGABRT")) for (attachmentFirst in listOf(false, true)) {
            val outbox = Files.createTempDirectory("noah-native-outbox").toFile()
            val options = options()
            val received = RecordingTransport()
            var hookCalls = 0
            val hook = requireNotNull(options.beforeSend)
            options.setBeforeSend { event, hint -> hookCalls++; hook.execute(event, hint) }
            options.setTransportFactory { configured, _ -> TelemetryTransport(received, configured) }
            val client = SentryClient(options)
            try {
                val event = nativeEvent().apply { exceptions!!.single().type = signal }
                val attachment = item(SentryItemType.Attachment, "synthetic-sensitive-minidump")
                val cached = outbox.resolve("native-crash")
                val eventItem = SentryEnvelopeItem.fromEvent(serializer, event)
                cached.outputStream().use { serializer.serialize(SentryEnvelope(SentryEnvelopeHeader(event.eventId),
                    if (attachmentFirst) listOf(attachment, eventItem) else listOf(eventItem, attachment)), it) }
                val scopes = Scopes(Scope(options).apply { bindClient(client) }, Scope(options), Scope(options), "native-test")
                OutboxSender(scopes, options.envelopeReader, serializer, NoOpLogger.getInstance(), 100, 10).processDirectory(outbox)
                assertEquals(1, hookCalls)
                val envelope = received.envelopes.single()
                assertEquals(listOf(SentryItemType.Event), envelope.items.map { it.header.type })
                val clean = requireNotNull(envelope.items.single().getEvent(serializer))
                assertEquals("native", clean.platform)
                assertEquals(signal, clean.exceptions!!.single().type)
                assertEquals("worker", clean.threads!!.single().name)
                val payload = AppTelemetry.json(clean)
                val exception = payload["exception"]!!.jsonObject["values"]!!.jsonArray.single().jsonObject
                assertEquals(setOf("type", "value", "thread_id", "stacktrace", "mechanism"), exception.keys)
                assertEquals(setOf("type", "handled", "synthetic"), exception["mechanism"]!!.jsonObject.keys)
                for (trace in listOf(clean.exceptions!!.single().stacktrace!!, clean.threads!!.single().stacktrace!!)) {
                    assertNull(trace.registers)
                    val frame = trace.frames!!.single()
                    assertEquals("libbark_bolt_ffi.so", frame.module)
                    assertEquals(setOf("function", "module", "filename", "lineno"), AppTelemetry.json(frame).keys)
                }
                assertNull(clean.debugMeta); assertNull(clean.extras); assertNull(clean.user); assertNull(clean.request)
                assertFalse(payload.toString().contains("synthetic-sensitive"))
                assertFalse(payload.toString().contains("0x1234"))
            } finally { client.close(); outbox.deleteRecursively() }
        }
    }

    @Test fun nativeSecretBearingThreadNamesRejectTheWholeEventAtBothGates() {
        for (name in listOf("abandon ".repeat(11) + "about", "preimage=fake-token")) {
            val event = nativeEvent().apply { threads!!.single().name = name }
            assertNull(options().beforeSend!!.execute(event, Hint()))
            assertNull(TelemetryTransport.filter(SentryEnvelope.from(serializer, event, null)))
        }
    }

    @Test(timeout = 2000) fun droppedCachedEnvelopeCompletesSubmissionAndDisablesRetry() {
        val callback = object : Cached, SubmissionResult, Retryable, Flushable {
            val submitted = CountDownLatch(1)
            var succeeded = false
            var retryable = true
            override fun setResult(success: Boolean) { succeeded = success; submitted.countDown() }
            override fun isSuccess() = succeeded
            override fun setRetry(retry: Boolean) { retryable = retry }
            override fun isRetry() = retryable
            override fun waitFlush() = submitted.await(15_000, TimeUnit.MILLISECONDS)
        }
        TelemetryTransport(RecordingTransport(), options()).send(
            SentryEnvelope(SentryEnvelopeHeader(), listOf(item(SentryItemType.Attachment, "{}"))),
            HintUtils.createWithTypeCheckHint(callback))
        assertTrue(callback.waitFlush())
        assertTrue(callback.isSuccess())
        assertFalse(callback.isRetry())
    }

    @Test(timeout = 2000) fun droppedCrashEnvelopeCompletesSDKDiskFlushHint() {
        val event = failure("UncaughtExceptionHandler", false).apply { release = "preimage=fake-token" }
        val callback = UncaughtExceptionHandlerIntegration.UncaughtExceptionHint(15_000, NoOpLogger.getInstance())
        callback.setFlushable(event.eventId!!)
        TelemetryTransport(RecordingTransport(), options()).send(SentryEnvelope.from(serializer, event, null),
            HintUtils.createWithTypeCheckHint(callback))
        assertTrue(callback.waitFlush())
    }

    @Test(timeout = 2000) fun rejectedCacheBatchDoesNotWaitForSDKDefaultTimeoutOrReachDelegate() {
        val cache = Files.createTempDirectory("noah-rejected-cache").toFile()
        val options = options()
        val received = RecordingTransport()
        options.setTransportFactory { configured, _ -> TelemetryTransport(received, configured) }
        val client = SentryClient(options)
        try {
            repeat(3) { index -> cache.resolve("rejected-$index.envelope").outputStream().use {
                serializer.serialize(SentryEnvelope(SentryEnvelopeHeader(), listOf(item(SentryItemType.Attachment, "{}"))), it)
            } }
            val scopes = Scopes(Scope(options).apply { bindClient(client) }, Scope(options), Scope(options), "drop-test")
            val started = System.nanoTime()
            EnvelopeSender(scopes, serializer, NoOpLogger.getInstance(), 15_000, 10).processDirectory(cache)
            assertTrue("Three deliberate drops must finish in under 500 ms", System.nanoTime() - started < 500_000_000)
            assertTrue(received.envelopes.isEmpty())
            assertTrue(cache.listFiles()!!.isEmpty())
        } finally { client.close(); cache.deleteRecursively() }
    }

    @Test fun acceptedEnvelopeLeavesCompletionAndFlushCloseToDelegate() {
        val options = options()
        class Callback : SubmissionResult, Retryable {
            var completed = false
            var retryValue = true
            override fun setResult(success: Boolean) { completed = true }
            override fun isSuccess() = completed
            override fun setRetry(value: Boolean) { retryValue = value }
            override fun isRetry() = retryValue
        }
        val callback = Callback()
        var sentHint: Hint? = null
        var flushTimeout: Long? = null
        var closed: Boolean? = null
        val delegate = object : ITransport {
            override fun send(envelope: SentryEnvelope, hint: Hint) { sentHint = hint }
            override fun flush(timeoutMillis: Long) { flushTimeout = timeoutMillis }
            override fun close() { closed = false }
            override fun close(isRestarting: Boolean) { closed = isRestarting }
            override fun getRateLimiter(): io.sentry.transport.RateLimiter? = null
        }
        val transport = TelemetryTransport(delegate, options)
        val hint = HintUtils.createWithTypeCheckHint(callback)
        transport.send(SentryEnvelope.from(serializer, SentryEvent(), null), hint)
        assertSame(hint, sentHint)
        assertFalse(callback.completed)
        assertTrue(callback.retryValue)
        transport.flush(123)
        transport.close(true)
        assertEquals(123L, flushTimeout)
        assertEquals(true, closed)
    }

    @Test fun sessionDropsUserIpAndArbitraryAttributes() {
        val session = Session(SentryUUID.generateSentryId(), io.sentry.protocol.User().apply {
            id = "fake-user"; ipAddress = "192.0.2.1"
        }, "production", "com.noahwallet.mainnet@0.2.0+100")
        session.setUnknown(mapOf("user" to "fake-user", "token" to "fake-token"))
        val filtered = requireNotNull(TelemetryTransport.filter(SentryEnvelope.from(serializer, session, null)))
        val output = filtered.items.single().data.toString(Charsets.UTF_8)
        assertFalse(output.contains("192.0.2.1"))
        assertFalse(output.contains("fake-user"))
        assertFalse(output.contains("fake-token"))
        assertFalse(output.contains("ip_address"))
        assertFalse(output.contains("user_agent"))
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
