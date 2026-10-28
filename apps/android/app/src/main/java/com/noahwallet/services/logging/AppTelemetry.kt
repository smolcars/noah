package com.noahwallet.services.logging

import android.content.Context
import com.noahwallet.BuildConfig
import io.sentry.Breadcrumb
import io.sentry.Hint
import io.sentry.JsonSerializable
import io.sentry.JsonSerializer
import io.sentry.NoOpLogger
import io.sentry.SentryEvent
import io.sentry.SentryOptions
import io.sentry.android.core.SentryAndroid
import io.sentry.android.core.SentryAndroidOptions
import io.sentry.protocol.App
import io.sentry.protocol.DebugImage
import io.sentry.protocol.DebugMeta
import io.sentry.protocol.Device
import io.sentry.protocol.Mechanism
import io.sentry.protocol.Message
import io.sentry.protocol.OperatingSystem
import io.sentry.protocol.SdkVersion
import io.sentry.protocol.SentryException
import io.sentry.protocol.SentryStackFrame
import io.sentry.protocol.SentryStackTrace
import io.sentry.protocol.SentryThread
import kotlinx.serialization.json.*
import java.io.StringReader
import java.io.StringWriter
import java.util.UUID

object AppTelemetry {
    enum class Variant { mainnet, signet, regtest }
    private val serializer = JsonSerializer(SentryOptions.empty())

    fun start(context: Context) {
        val variant = Variant.entries.firstOrNull { it.name == BuildConfig.APP_VARIANT } ?: return
        if (!enabled(variant, BuildConfig.DEBUG, BuildConfig.SENTRY_DSN)) return
        SentryAndroid.init(context) { configure(it, variant, BuildConfig.SENTRY_DSN,
            release(BuildConfig.APPLICATION_ID, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE), BuildConfig.VERSION_CODE) }
    }

    internal fun enabled(variant: Variant, debug: Boolean, dsn: String) =
        !debug && variant != Variant.regtest && dsn.isNotBlank()

    @Suppress("LongParameterList") // the BuildConfig fields, passed one by one so tests can vary each
    internal fun options(variant: Variant, debug: Boolean, dsn: String,
        applicationId: String, versionName: String, versionCode: Int): SentryAndroidOptions? {
        if (!enabled(variant, debug, dsn)) return null
        return SentryAndroidOptions().also { configure(it, variant, dsn, release(applicationId, versionName, versionCode), versionCode) }
    }

    private fun release(applicationId: String, versionName: String, versionCode: Int) = "$applicationId@$versionName+$versionCode"

    private fun configure(options: SentryAndroidOptions, variant: Variant, dsn: String, release: String, versionCode: Int) = with(options) {
        this.dsn = dsn
        environment = "production"
        this.release = release
        dist = versionCode.toString()
        setTag("app.variant", variant.name)
        isSendDefaultPii = false
        sampleRate = 1.0
        tracesSampleRate = null
        isEnableAutoSessionTracking = true // Release Health: product decision 2026-10-20.
        isDebug = false
        setLogger(NoOpLogger.getInstance())
        setFatalLogger(NoOpLogger.getInstance())
        isEnableExternalConfiguration = false
        isEnableScopePersistence = false
        isSendModules = false
        setSendClientReports(false)
        setTransportFactory { configured, details ->
            TelemetryTransport(io.sentry.AsyncHttpTransportFactory().create(configured, details), configured)
        }
        disableCollection()
        setBeforeBreadcrumb { breadcrumb, _ -> scrub(breadcrumb) }
        setBeforeSend { event, hint ->
            clearAttachments(hint)
            scrub(event, proguardUuid, this.release).also { clean ->
                if (clean == null) TelemetrySubmission.drop(hint, event.eventId, flushTimeoutMillis)
            }
        }
    }

    /** Everything but errors stays off: attachments, logs, metrics, tracing, profiling, replay and feedback. */
    private fun SentryAndroidOptions.disableCollection() {
        setAttachServerName(false)
        isAttachScreenshot = false
        isAttachViewHierarchy = false
        maxAttachmentSize = 0
        logs.isEnabled = false
        logs.setBeforeSend { null }
        metrics.isEnabled = false
        metrics.setBeforeSend { _, _ -> null }
        // NDK outbox events return through captureEvent and the same allowlist/transport gate.
        isEnableNdk = true
        isEnableScopeSync = false
        isEnableNdkAppHangTracking = false
        isTombstoneEnabled = false
        isReportHistoricalTombstones = false
        isAttachRawTombstone = false
        isMemoryLimiterEnabled = false
        isReportHistoricalMemoryLimiterExits = false
        // JVM crashes and live/historical ANR events all pass through beforeSend.
        isAnrEnabled = true
        isReportHistoricalAnrs = true
        isAttachAnrThreadDump = false
        anrProfilingSampleRate = null
        isEnableAutoActivityLifecycleTracing = false
        isEnableStandaloneAppStartTracing = false
        isEnablePerformanceV2 = false
        isEnableFramesTracking = false
        isEnableUserInteractionTracing = false
        isEnableTimeToFullDisplayTracing = false
        isEnableAppStartProfiling = false
        isStartProfilerOnAppStart = false
        profilesSampleRate = null
        profileSessionSampleRate = null
        sessionReplay.sessionSampleRate = 0.0
        sessionReplay.onErrorSampleRate = 0.0
        feedbackOptions.isUseShakeGesture = false
        feedbackOptions.isEnableAttachScreenshot = false
        setBeforeSendTransaction { _, _ -> null }
        setBeforeSendReplay { _, _ -> null }
        setBeforeSendFeedback { _, hint -> clearAttachments(hint); null }
    }

    private fun clearAttachments(hint: Hint) {
        hint.clearAttachments()
        hint.screenshot = null
        hint.viewHierarchy = null
        hint.threadDump = null
        hint.tombstone = null
        hint.replayRecording = null
    }

    internal fun scrub(event: SentryEvent, mappingUuid: String? = null, mappingRelease: String? = null): SentryEvent? = try {
        // Never traverse dropped extras or arbitrary contexts, even to serialize them.
        val clean = SentryEvent(event.timestamp).apply {
            eventId = event.eventId
            level = event.level
            platform = event.platform
            release = event.release
            dist = event.dist
            environment = event.environment
            message = event.message?.formatted?.let(LogFile::redactForTelemetry)?.let(::message)
            sdk = event.sdk?.let(::sdk)
            // Only this build's mapping identifier; no incoming debug image has admission.
            // Cached errors from another release must never use the current build's mapping.
            if (mappingUuid != null && (mappingRelease == null || event.release == mappingRelease)) debugMeta = debugMeta(mappingUuid)
            tags = event.tags?.filter { (key, value) -> key == "app.variant" && Variant.entries.any { it.name == value } }
            threads = event.threads?.map(::thread)
            exceptions = event.exceptions?.map(::exception)
            breadcrumbs = event.breadcrumbs?.mapNotNull(::scrub)
            copyContexts(event.contexts, contexts)
        }
        clean.takeIf { isSafePayload(json(it)) }
    } catch (_: Throwable) { null } // Inspection errors fail closed, without logging the payload.

    private fun message(text: String) = Message().apply { formatted = text }

    private fun debugMeta(mappingUuid: String): DebugMeta {
        require(UUID.fromString(mappingUuid).toString() == mappingUuid)
        return DebugMeta().apply { images = listOf(DebugImage().apply {
            type = DebugImage.PROGUARD; uuid = mappingUuid
        }) }
    }

    private fun thread(thread: SentryThread) = SentryThread().apply {
        id = thread.id; name = thread.name
        isCurrent = thread.isCurrent; isCrashed = thread.isCrashed; isMain = thread.isMain
        stacktrace = thread.stacktrace?.let(::stacktrace)
    }

    private fun exception(exception: SentryException) = SentryException().apply {
        type = exception.type; module = exception.module; threadId = exception.threadId
        value = exception.value?.let(LogFile::redactForTelemetry)
        stacktrace = exception.stacktrace?.let(::stacktrace)
        mechanism = exception.mechanism?.let { original -> Mechanism().apply {
            type = original.type; isHandled = original.isHandled; synthetic = original.synthetic
        } }
    }

    /** Only the app, device and OS contexts, field by field. */
    private fun copyContexts(from: io.sentry.protocol.Contexts, to: io.sentry.protocol.Contexts) {
        from.app?.let { original -> to.setApp(App().apply {
            appIdentifier = original.appIdentifier; appVersion = original.appVersion; appBuild = original.appBuild
            appStartTime = original.appStartTime; buildType = original.buildType; inForeground = original.inForeground
        }) }
        from.device?.let { original -> to.setDevice(Device().apply {
            family = original.family; model = original.model; modelId = original.modelId
            archs = original.archs?.clone(); batteryLevel = original.batteryLevel; isCharging = original.isCharging
            isSimulator = original.isSimulator; memorySize = original.memorySize; freeMemory = original.freeMemory
            usableMemory = original.usableMemory; storageSize = original.storageSize; freeStorage = original.freeStorage
            bootTime = original.bootTime; processorCount = original.processorCount; cpuDescription = original.cpuDescription
            screenWidthPixels = original.screenWidthPixels; screenHeightPixels = original.screenHeightPixels
            screenDensity = original.screenDensity; screenDpi = original.screenDpi
            orientation = original.orientation; isLowMemory = original.isLowMemory
        }) }
        from.operatingSystem?.let { original -> to.setOperatingSystem(OperatingSystem().apply {
            name = original.name; version = original.version; build = original.build
            kernelVersion = original.kernelVersion; isRooted = original.isRooted
        }) }
    }

    internal fun scrub(breadcrumb: Breadcrumb): Breadcrumb? = try {
        Breadcrumb(breadcrumb.timestamp).apply {
            category = breadcrumb.category
            level = breadcrumb.level
            message = breadcrumb.message?.let(LogFile::redactForTelemetry)
        }.takeIf { isSafePayload(json(it)) }
    } catch (_: Throwable) { null }

    private fun stacktrace(trace: SentryStackTrace) = SentryStackTrace(trace.frames?.map { frame ->
        SentryStackFrame().apply {
            function = frame.function; module = frame.module; filename = frame.filename; lineno = frame.lineno
        }
    })

    internal fun sdk(original: SdkVersion): SdkVersion? {
        // SDK constructors share global package/integration sets: deserialize a detached allowlist.
        val payload = buildJsonObject {
            put("name", original.name); put("version", original.version)
            putJsonArray("integrations") { original.integrationSet.forEach { add(it) } }
            putJsonArray("packages") { original.packageSet.forEach { entry -> add(buildJsonObject {
                put("name", entry.name); put("version", entry.version)
            }) } }
        }
        return serializer.deserialize(StringReader(payload.toString()), SdkVersion::class.java)
    }

    internal fun json(value: JsonSerializable): JsonObject {
        val text = StringWriter().also { serializer.serialize(value, it) }.toString()
        return Json.parseToJsonElement(text).jsonObject
    }

    internal fun isSafePayload(payload: Map<String, *>): Boolean = try {
        val scan = PayloadScan()
        // Severity is typed metadata; exclude it only from aggregate mnemonic detection.
        val level = payload["level"]
        when {
            !scan.visit(payload.filterKeys { it != "level" }, 0) -> false
            LogFile.containsMnemonicIn(scan.keys) || LogFile.containsMnemonicIn(scan.values) -> false
            level != null && !scan.visit(level, 0) -> false
            !EndpointLogPrivacy.requiresSerializedGuard -> true
            else -> {
                // Inspect the wire form too: numeric fields and joins are not string leaves.
                val json = if (payload is JsonObject) payload.toString()
                else StringWriter().also { serializer.serialize(payload, it) }.toString()
                EndpointLogPrivacy.redactRecord(json) == json
            }
        }
    } catch (_: Throwable) { false }

    /** Collects every key and string leaf while checking each one, within a total size and depth. */
    private class PayloadScan {
        val keys = mutableListOf<String>()
        val values = mutableListOf<String>()
        private var size = 0

        private fun string(value: String, destination: MutableList<String>): Boolean {
            size += value.length
            if (size > 16 * 1024 || LogFile.redactForTelemetry(value) != value) return false
            destination += value
            return true
        }

        fun visit(value: Any?, depth: Int): Boolean = if (depth >= 32) false else visitValue(value, depth)

        private fun visitValue(value: Any?, depth: Int): Boolean = when (value) {
            null, JsonNull -> true
            is JsonPrimitive -> if (value.isString) string(value.content, values)
            else value.booleanOrNull != null || value.doubleOrNull?.isFinite() == true
            is String -> string(value, values)
            is Boolean, is Byte, is Short, is Int, is Long -> true
            is Float -> value.isFinite()
            is Double -> value.isFinite()
            is Map<*, *> -> value.keys.all { it is String } && value.keys.filterIsInstance<String>().sorted().all { key ->
                string(key, keys) && visit(value[key], depth + 1)
            }
            is List<*> -> value.all { visit(it, depth + 1) }
            else -> false
        }
    }
}
