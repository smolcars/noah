package com.noahwallet.services.logging

import io.sentry.Hint
import io.sentry.JsonSerializer
import io.sentry.SentryEnvelope
import io.sentry.SentryEnvelopeHeader
import io.sentry.SentryEnvelopeItem
import io.sentry.SentryItemType
import io.sentry.SentryOptions
import io.sentry.Session
import io.sentry.hints.DiskFlushNotification
import io.sentry.hints.Retryable
import io.sentry.hints.SubmissionResult
import io.sentry.transport.ITransport
import io.sentry.util.HintUtils
import java.io.StringReader
import kotlinx.serialization.json.*

/** Also gates cached envelopes and legacy user feedback, which bypass beforeSend. */
internal class TelemetryTransport(private val delegate: ITransport, private val options: SentryOptions) : ITransport by delegate {
    override fun send(envelope: SentryEnvelope, hint: Hint) {
        val filtered = filter(envelope, options.proguardUuid, options.release)
        if (filtered != null) {
            delegate.send(filtered, hint)
        } else {
            HintUtils.runIfHasType(hint, Retryable::class.java) { it.setRetry(false) }
            HintUtils.runIfHasType(hint, DiskFlushNotification::class.java) {
                if (it.isFlushable(envelope.header.eventId)) it.markFlushed()
            }
            // Consumed successfully: let outbox replay continue with later items.
            HintUtils.runIfHasType(hint, SubmissionResult::class.java) { it.setResult(true) }
        }
    }

    override fun send(envelope: SentryEnvelope) { send(envelope, Hint()) }

    companion object {
        private val serializer = JsonSerializer(SentryOptions.empty())
        private val sessionID = Regex("(?:[0-9a-fA-F]{32}|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})")

        internal fun filter(envelope: SentryEnvelope, mappingUuid: String? = null, mappingRelease: String? = null): SentryEnvelope? = try {
            val items = envelope.items.mapNotNull { item ->
                // A corrupt cached item must not discard a crash in the same envelope.
                try { when (item.header.type) {
                    SentryItemType.Event -> item.getEvent(serializer)?.let { AppTelemetry.scrub(it, mappingUuid, mappingRelease) }?.let {
                        SentryEnvelopeItem.fromEvent(serializer, it)
                    }
                    SentryItemType.Session -> session(item)
                    // Includes attachments, all feedback APIs, transactions/spans, profiles,
                    // replays, logs, metrics, check-ins, client reports and future item types.
                    else -> null
                } } catch (_: Throwable) { null }
            }
            if (items.isEmpty()) null else {
                val header = SentryEnvelopeHeader(envelope.header.eventId,
                    envelope.header.sdkVersion?.let(AppTelemetry::sdk))
                SentryEnvelope(header, items).takeIf { AppTelemetry.isSafePayload(AppTelemetry.json(header)) }
            }
        } catch (_: Throwable) { null }

        private fun session(item: SentryEnvelopeItem): SentryEnvelopeItem? {
            val original = Json.parseToJsonElement(item.data.toString(Charsets.UTF_8)).jsonObject
            // Release Health needs random session/install IDs, never an app-set user identity.
            for (key in listOf("sid", "did")) original[key]?.let {
                if (it !is JsonPrimitive || !it.isString || !sessionID.matches(it.content)) return null
            }
            val attrs = original["attrs"]?.jsonObject ?: return null
            val clean = buildJsonObject {
                for (key in listOf("sid", "did", "init", "started", "timestamp", "status", "seq", "errors", "duration")) {
                    original[key]?.takeIf { it is JsonPrimitive }?.let { put(key, it) }
                }
                putJsonObject("attrs") {
                    for (key in listOf("release", "environment")) {
                        attrs[key]?.takeIf { it is JsonPrimitive }?.let { put(key, it) }
                    }
                }
            }
            if (!AppTelemetry.isSafePayload(clean)) return null
            val session = serializer.deserialize(StringReader(clean.toString()), Session::class.java) ?: return null
            return SentryEnvelopeItem.fromSession(serializer, session)
        }
    }
}
