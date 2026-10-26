package com.noahwallet.services

import com.noahwallet.services.logging.AppTelemetry
import com.noahwallet.services.logging.TelemetryTransport
import io.sentry.*
import io.sentry.hints.*
import io.sentry.transport.ITransport
import io.sentry.util.HintUtils
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class TelemetryOutboxTest {
    private val serializer = JsonSerializer(SentryOptions.empty())
    private fun options() = requireNotNull(AppTelemetry.options(AppTelemetry.Variant.mainnet, false,
        "https://public@example.invalid/1", "com.noahwallet.mainnet", "0.2.0", 100)).apply {
        flushTimeoutMillis = 1_000
    }
    private fun event(rejected: Boolean = false) = SentryEvent().apply {
        platform = "native"
        if (rejected) release = "preimage=fake-token"
    }
    private fun eventItem() = SentryEnvelopeItem.fromEvent(serializer, event())
    private fun attachment() = SentryEnvelopeItem.fromAttachment(serializer, NoOpLogger.getInstance(),
        Attachment("fake-sensitive-attachment".toByteArray(), "fake.txt"), 1_000)
    private fun sessionItem() = SentryEnvelopeItem.fromSession(serializer,
        Session(SentryUUID.generateSentryId(), null, "production", "com.noahwallet.mainnet@0.2.0+100"))

    @Test fun attachmentBeforeEventWaitsForUpload() = pendingUpload(listOf(attachment(), eventItem()))
    @Test fun eventBeforeAttachmentWaitsForUpload() = pendingUpload(listOf(eventItem(), attachment()))
    @Test fun allAcceptedWaitForEveryUpload() = pendingUpload(listOf(eventItem(), sessionItem()))
    @Test fun allAcceptedInReverseOrderWaitForEveryUpload() = pendingUpload(listOf(sessionItem(), eventItem()))

    @Test fun processDeathWithLeadingAttachmentKeepsCrashForNextLaunch() =
        pendingUpload(listOf(attachment(), eventItem()), processDeath = true)
    @Test fun processDeathWithTrailingAttachmentKeepsCrashForNextLaunch() =
        pendingUpload(listOf(eventItem(), attachment()), processDeath = true)
    @Test fun processDeathDuringSecondAcceptedItemKeepsEnvelopeForNextLaunch() =
        pendingUpload(listOf(eventItem(), sessionItem()), processDeath = true)
    @Test fun processDeathDuringFirstAcceptedItemKeepsEnvelopeForNextLaunch() =
        pendingUpload(listOf(sessionItem(), eventItem()), processDeath = true, dieAt = 0)

    private fun pendingUpload(items: List<SentryEnvelopeItem>, processDeath: Boolean = false,
                              acceptedTypes: List<SentryItemType> = items.filter { it.header.type != SentryItemType.Attachment }.map { it.header.type },
                              dieAt: Int = acceptedTypes.lastIndex) {
        Replay(items).use { replay ->
            for ((index, type) in acceptedTypes.withIndex()) {
                val pending = replay.transport.take()
                assertEquals(type, pending.envelope.items.single().header.type)
                assertTrue("Cached submissions must retain the Cached marker", HintUtils.hasType(pending.hint, Cached::class.java))
                assertFalse("A previous item must not complete this upload", pending.result.isSuccess)
                assertFalse("Replay must wait for the pending upload", replay.finished.await(50, TimeUnit.MILLISECONDS))
                assertTrue("The outbox file must survive while upload is pending", replay.cached.exists())
                if (processDeath && index == dieAt) {
                    // Interrupt replay and discard its transport without delivering an upload result.
                    replay.stop()
                    assertTrue("Process death must leave the original envelope on disk", replay.cached.exists())
                    val nextLaunch = RecordingTransport()
                    replay.replayAgain(nextLaunch)
                    assertEquals(acceptedTypes, nextLaunch.types)
                    assertFalse(replay.cached.exists())
                    return
                }
                pending.result.setResult(true)
            }
            replay.awaitFinished()
            assertFalse("Delete only after every accepted upload finishes", replay.cached.exists())
            assertTrue(replay.transport.pending.isEmpty())
        }
    }

    @Test fun allDroppedItemsCompleteWithoutUploadOrTimeout() {
        Replay(listOf(attachment(), attachment())).use { replay ->
            replay.awaitFinished()
            assertFalse(replay.cached.exists())
            assertTrue(replay.transport.pending.isEmpty())
        }
    }

    @Test fun rejectedNativeEventCompletesWithoutWaitingForTimeout() {
        val rejected = SentryEnvelopeItem.fromEvent(serializer, event(rejected = true))
        Replay(listOf(rejected)).use { replay ->
            replay.awaitFinished()
            assertFalse(replay.cached.exists())
            assertTrue(replay.transport.pending.isEmpty())
        }
    }

    @Test fun rejectedEventBeforeAcceptedSessionDoesNotCompleteItsUpload() = pendingUpload(listOf(
        SentryEnvelopeItem.fromEvent(serializer, event(rejected = true)), sessionItem()), acceptedTypes = listOf(SentryItemType.Session))

    @Test fun acceptedSessionBeforeRejectedEventDoesNotWaitForTimeout() {
        Replay(listOf(sessionItem(), SentryEnvelopeItem.fromEvent(serializer, event(rejected = true)))).use { replay ->
            replay.transport.take().result.setResult(true)
            replay.awaitFinished()
            assertFalse(replay.cached.exists())
            assertTrue(replay.transport.pending.isEmpty())
        }
    }

    @Test(timeout = 2_000) fun rejectedNativeEventCompletesCrashFlushWithoutTimeout() {
        val event = event(rejected = true)
        val callback = UncaughtExceptionHandlerIntegration.UncaughtExceptionHint(1_000, NoOpLogger.getInstance())
        callback.setFlushable(event.eventId!!)
        val started = System.nanoTime()
        assertNull(options().beforeSend!!.execute(event, HintUtils.createWithTypeCheckHint(callback)))
        assertTrue(callback.waitFlush())
        assertTrue("Rejected crash flush must finish in under 500ms", System.nanoTime() - started < 500_000_000)
    }

    @Test fun timedOutUploadKeepsOutboxFile() {
        Replay(listOf(attachment(), eventItem())).use { replay ->
            replay.transport.take()
            replay.awaitFinished(2_000)
            assertTrue("An unresolved cached upload has no second disk copy", replay.cached.exists())
        }
    }

    private inner class Replay(items: List<SentryEnvelopeItem>) : AutoCloseable {
        private val outbox = Files.createTempDirectory("noah-outbox-reliability").toFile()
        val cached = outbox.resolve("native-crash")
        val transport = PendingTransport()
        val finished = CountDownLatch(1)
        private val failure = AtomicReference<Throwable>()
        private val options = options()
        private val client: SentryClient
        private val worker: Thread
        init {
            val eventId = items.firstOrNull { it.header.type == SentryItemType.Event }?.getEvent(serializer)?.eventId
            cached.outputStream().use { serializer.serialize(SentryEnvelope(SentryEnvelopeHeader(eventId), items), it) }
            options.setTransportFactory { configured, _ -> TelemetryTransport(transport, configured) }
            client = SentryClient(options)
            worker = Thread {
                try { process(client, options) } catch (error: Throwable) { failure.set(error) }
                finally { finished.countDown() }
            }.apply { name = "telemetry-outbox-test"; start() }
        }
        private fun process(client: SentryClient, options: SentryOptions) {
            val scopes = Scopes(Scope(options).apply { bindClient(client) }, Scope(options), Scope(options), "outbox-test")
            OutboxSender(scopes, options.envelopeReader, serializer, NoOpLogger.getInstance(),
                options.flushTimeoutMillis, 10).processDirectory(outbox)
        }
        fun awaitFinished(timeout: Long = 500) {
            assertTrue("Replay exceeded ${timeout}ms budget (SDK timeout is 1000ms)", finished.await(timeout, TimeUnit.MILLISECONDS))
            failure.get()?.let { throw AssertionError("Replay failed", it) }
        }
        fun stop() { worker.interrupt(); worker.join(2_000); assertFalse(worker.isAlive) }
        fun replayAgain(delegate: ITransport) {
            val next = options()
            next.setTransportFactory { configured, _ -> TelemetryTransport(delegate, configured) }
            val nextClient = SentryClient(next)
            try { process(nextClient, next) } finally { nextClient.close() }
        }
        override fun close() { stop(); client.close(); outbox.deleteRecursively() }
    }

    private class Pending(val envelope: SentryEnvelope, val hint: Hint) {
        val result get() = HintUtils.getSentrySdkHint(hint) as SubmissionResult
    }
    private class PendingTransport : ITransport {
        val pending = LinkedBlockingQueue<Pending>()
        fun take() = requireNotNull(pending.poll(2, TimeUnit.SECONDS)) { "No accepted upload received" }
        override fun send(envelope: SentryEnvelope, hint: Hint) { pending.add(Pending(envelope, hint)) }
        override fun flush(timeoutMillis: Long) {}
        override fun close() {}
        override fun close(isRestarting: Boolean) {}
        override fun getRateLimiter(): io.sentry.transport.RateLimiter? = null
    }
    private class RecordingTransport : ITransport {
        val types = mutableListOf<SentryItemType>()
        override fun send(envelope: SentryEnvelope, hint: Hint) {
            types += envelope.items.map { it.header.type }
            HintUtils.runIfHasType(hint, SubmissionResult::class.java) { it.setResult(true) }
        }
        override fun flush(timeoutMillis: Long) {}
        override fun close() {}
        override fun close(isRestarting: Boolean) {}
        override fun getRateLimiter(): io.sentry.transport.RateLimiter? = null
    }
}
