package com.noahwallet.services

import com.noahwallet.fixtures.SharedFixtures
import com.noahwallet.services.logging.AppTelemetry
import com.noahwallet.services.logging.AppTelemetry.Variant
import com.noahwallet.services.logging.LogFile
import com.noahwallet.services.logging.TelemetryTransport
import io.sentry.*
import io.sentry.android.core.SentryAndroidOptions
import io.sentry.protocol.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.StringReader
import java.io.StringWriter

class AppTelemetryTest {
    private val serializer = JsonSerializer(SentryOptions.empty())
    private val dsn = "https://public@example.invalid/1"
    private fun options(variant: Variant = Variant.mainnet, debug: Boolean = false, dsn: String = this.dsn) =
        AppTelemetry.options(variant, debug, dsn, "com.noahwallet.${variant.name}", "0.2.0", 100)
    private fun clean(event: SentryEvent) = requireNotNull(AppTelemetry.scrub(event))
    private fun message(text: String) = Message().apply { formatted = text }
    private fun event(text: String? = null) = SentryEvent().apply { level = SentryLevel.ERROR; message = text?.let(::message) }
    private fun json(value: Any) = StringWriter().also { serializer.serialize(value, it) }.toString()

    @Test fun optionsCoverAllSixBuildVariants() {
        for (variant in Variant.entries) for (debug in listOf(true, false)) {
            val options = options(variant, debug)
            assertEquals("$variant debug=$debug", !debug && variant != Variant.regtest, options != null)
        }
        for (dsn in listOf("", " \n")) assertNull(options(dsn = dsn))
        for (variant in listOf(Variant.mainnet, Variant.signet)) {
            val options = requireNotNull(options(variant))
            assertEquals("com.noahwallet.${variant.name}@0.2.0+100", options.release)
            assertEquals("100", options.dist)
            assertEquals("production", options.environment)
            assertEquals(1.0, options.sampleRate)
            assertNull(options.tracesSampleRate)
            assertEquals(variant.name, options.tags["app.variant"])
            assertFalse(options.isSendDefaultPii)
            assertFalse(options.isEnableExternalConfiguration)
            assertFalse(options.isEnableScopePersistence)
            assertFalse(options.isDebug)
            assertEquals(NoOpLogger.getInstance(), options.fatalLogger)
        }
    }

    @Test fun sessionsStayOnWhileSDKLogAndMetricChannelsDrop() {
        val options = requireNotNull(options())
        assertTrue(options.isEnableAutoSessionTracking)
        assertFalse(options.logs.isEnabled)
        assertFalse(options.metrics.isEnabled)
        assertNotNull(options.logs.beforeSend)
        assertNotNull(options.metrics.beforeSend)
        // Callback implementations ignore the contents, including manually captured SDK data.
        assertNull(options.logs.beforeSend!!.execute(SentryLogEvent(SentryId(), 0.0, "synthetic", SentryLogLevel.INFO)))
        assertNull(options.metrics.beforeSend!!.execute(SentryMetricsEvent(SentryId(), 0.0, "synthetic", "counter", 1.0), Hint()))
    }

    @Test fun bypassChannelsAreDisabledAndTheirHooksDrop() {
        val options = requireNotNull(options())
        assertFalse(options.isEnableScopeSync)
        assertFalse(options.isEnableNdkAppHangTracking)
        assertFalse(options.isTombstoneEnabled)
        assertFalse(options.isReportHistoricalTombstones)
        assertFalse(options.isAttachRawTombstone)
        assertFalse(options.isMemoryLimiterEnabled)
        assertFalse(options.isReportHistoricalMemoryLimiterExits)
        assertTrue(options.isAnrEnabled)
        assertTrue(options.isReportHistoricalAnrs)
        assertFalse(options.isAttachAnrThreadDump)
        assertNull(options.anrProfilingSampleRate)
        assertFalse(options.isEnableAutoActivityLifecycleTracing)
        assertFalse(options.isEnableStandaloneAppStartTracing)
        assertFalse(options.isEnablePerformanceV2)
        assertFalse(options.isEnableFramesTracking)
        assertFalse(options.isEnableUserInteractionTracing)
        assertFalse(options.isEnableTimeToFullDisplayTracing)
        assertFalse(options.isEnableAppStartProfiling)
        assertFalse(options.isStartProfilerOnAppStart)
        assertNull(options.profilesSampleRate)
        assertNull(options.profileSessionSampleRate)
        assertEquals(0.0, options.sessionReplay.sessionSampleRate)
        assertEquals(0.0, options.sessionReplay.onErrorSampleRate)
        assertFalse(options.feedbackOptions.isUseShakeGesture)
        assertFalse(options.feedbackOptions.isEnableAttachScreenshot)
        assertFalse(options.isAttachScreenshot)
        assertFalse(options.isAttachViewHierarchy)
        assertNull(options.beforeSendTransaction!!.execute(SentryTransaction("synthetic", 0.0, 1.0, emptyList(), emptyMap(),
            TransactionInfo("custom")), Hint()))
        assertNull(options.beforeSendFeedback!!.execute(event(), Hint()))
        assertNotNull(options.beforeSendReplay)
    }

    @Test fun nativeCrashIntegrationIsEnabledWithoutScopeSyncOrAttachments() {
        val options = requireNotNull(options())
        assertTrue(options.isEnableNdk)
        assertNotNull(Class.forName("io.sentry.android.ndk.SentryNdk", false, javaClass.classLoader))
        assertFalse(options.isEnableScopeSync)
        assertFalse(options.isEnableNdkAppHangTracking)
        assertFalse(options.isTombstoneEnabled)
        assertFalse(options.isAttachRawTombstone)
        assertEquals(0L, options.maxAttachmentSize)
    }

    @Test fun freshAllowlistDropsExtrasIdentityRequestAndUnknownFieldsWithoutTraversing() {
        val event = event("Wallet sync failed").apply {
            extras = mapOf("records" to List(100_000) { "arbitrary payload" }, "unexpected" to UnexpectedValue())
            contexts.put("wallet", mapOf("restore" to "arbitrary payload"))
            user = User().apply { id = "personal identifier"; email = "alice@example.com" }
            request = Request().apply { headers = mapOf("Authorization" to "fake-token") }
            transaction = "arbitrary payload"; fingerprints = listOf("arbitrary payload")
            logger = "arbitrary payload"; serverName = "personal identifier"
            unknown = mapOf("surprise" to UnexpectedValue())
            setModules(mapOf("private" to "payload"))
            tags = mapOf("app.variant" to "signet", "arbitrary" to "payload")
            debugMeta = DebugMeta().apply { unknown = mapOf("payload" to "secret") }
        }
        val clean = clean(event)
        assertEquals(event.eventId, clean.eventId)
        assertEquals(event.timestamp, clean.timestamp)
        assertEquals("Wallet sync failed", clean.message!!.formatted)
        assertNull(clean.extras); assertNull(clean.user); assertNull(clean.request)
        assertNull(clean.transaction); assertNull(clean.fingerprints)
        assertNull(clean.logger); assertNull(clean.serverName); assertNull(clean.unknown); assertNull(clean.debugMeta)
        assertNull(clean.contexts["wallet"])
        assertEquals(mapOf("app.variant" to "signet"), clean.tags)
        assertFalse(json(clean).contains("payload")); assertFalse(json(clean).contains("personal"))
    }

    @Test fun selectedContextsKeepOnlyNonPersonalFields() {
        val event = event().apply {
            contexts.setApp(App().apply {
                appVersion = "0.2.0"; appBuild = "100"; appIdentifier = "com.noahwallet.mainnet"
                deviceAppHash = "personal identifier"; viewNames = listOf("private view")
                permissions = mapOf("private permission" to "granted")
                unknown = mapOf("arbitrary" to "payload")
            })
            contexts.setDevice(Device().apply {
                model = "Pixel"; family = "Android"; archs = arrayOf("arm64-v8a"); memorySize = 1000L
                name = "personal device"; id = "personal identifier"; locale = "en-US"
                unknown = mapOf("arbitrary" to "payload")
            })
            contexts.setOperatingSystem(OperatingSystem().apply {
                name = "Android"; version = "15"; isRooted = false
                rawDescription = "personal data"; unknown = mapOf("arbitrary" to "payload")
            })
        }
        val clean = clean(event)
        assertEquals("0.2.0", clean.contexts.app!!.appVersion)
        assertNull(clean.contexts.app!!.deviceAppHash); assertNull(clean.contexts.app!!.permissions)
        assertEquals("Pixel", clean.contexts.device!!.model)
        assertArrayEquals(arrayOf("arm64-v8a"), clean.contexts.device!!.archs)
        assertNull(clean.contexts.device!!.id); assertNull(clean.contexts.device!!.name); assertNull(clean.contexts.device!!.locale)
        assertEquals("15", clean.contexts.operatingSystem!!.version)
        assertFalse(json(clean).contains("personal")); assertFalse(json(clean).contains("payload"))
    }

    @Test fun exceptionThreadAndFrameAllowlistExcludesVariablesRegistersAndMechanismData() {
        val frame = SentryStackFrame().apply {
            function = "syncWallet"; module = "Noah"; filename = "Sync.kt"; lineno = 42
            vars = mapOf("private" to "payload"); contextLine = "payload"
            preContext = listOf("payload"); postContext = listOf("payload"); absPath = "private path"
            unknown = mapOf("private" to "payload")
        }
        val trace = SentryStackTrace(listOf(frame)).apply { registers = mapOf("private" to "payload") }
        val event = event().apply {
            exceptions = listOf(SentryException().apply {
                type = "WalletError"; module = "Noah"; threadId = 1L; value = "preimage=synthetic-value"
                stacktrace = trace
                mechanism = Mechanism().apply {
                    type = "ANR"; isHandled = false; synthetic = true
                    data = mapOf("private" to "payload"); meta = mapOf("private" to "payload"); helpLink = "private path"
                }
            })
            threads = listOf(SentryThread().apply {
                id = 1L; name = "main"; isMain = true; isCurrent = true; isCrashed = false
                stacktrace = trace; state = "payload"; unknown = mapOf("private" to "payload")
            })
        }
        val clean = clean(event)
        val exception = clean.exceptions!!.single()
        assertEquals("WalletError", exception.type); assertEquals("[redacted]", exception.value)
        assertEquals(false, exception.mechanism!!.isHandled); assertNull(exception.mechanism!!.data)
        assertNull(exception.mechanism!!.meta); assertNull(exception.mechanism!!.helpLink)
        assertEquals("syncWallet", exception.stacktrace!!.frames!!.single().function)
        assertNull(exception.stacktrace!!.frames!!.single().vars)
        assertNull(exception.stacktrace!!.registers)
        assertEquals("main", clean.threads!!.single().name); assertTrue(clean.threads!!.single().isMain!!)
        assertNull(clean.threads!!.single().state)
        assertFalse(json(clean).contains("payload")); assertFalse(json(clean).contains("private path"))
    }

    @Test fun sdkInfoIsDetachedAndDropsUnknownSDKAndPackageFields() {
        val event = event().apply {
            sdk = serializer.deserialize(StringReader("""{"name":"sentry.java.android","version":"8.57.0","integrations":["ANR"],""" +
                """"packages":[{"name":"maven:io.sentry:sentry","version":"8.57.0","secret":"payload"}],"surprise":"payload"}"""), SdkVersion::class.java)
        }
        val clean = clean(event)
        assertEquals("sentry.java.android", clean.sdk!!.name)
        assertEquals(setOf("ANR"), clean.sdk!!.integrationSet)
        assertEquals(1, clean.sdk!!.packageSet.size)
        assertFalse(json(clean).contains("payload"))
        assertNull(clean.sdk!!.unknown)
    }

    @Test fun enumVariantTagIsTheOnlyAllowedTag() {
        for (variant in Variant.entries) assertEquals(mapOf("app.variant" to variant.name),
            clean(event().apply { tags = mapOf("app.variant" to variant.name, "other" to "payload") }).tags)
        assertTrue(clean(event().apply { tags = mapOf("app.variant" to "unexpected") }).tags!!.isEmpty())
    }

    @Test fun installedHooksRedactMessagesAndDropTemplatesParametersAndBreadcrumbData() {
        val options = requireNotNull(options())
        val text = "preimage=synthetic-sensitive-value"
        val crumb = Breadcrumb().apply {
            category = "wallet"; level = SentryLevel.INFO; message = text
            type = "private type"; setData("private_key", UnexpectedValue()); unknown = mapOf("surprise" to "payload")
        }
        val cleanCrumb = requireNotNull(options.beforeBreadcrumb!!.execute(crumb, Hint()))
        assertEquals("[redacted]", cleanCrumb.message); assertTrue(cleanCrumb.data.isEmpty())
        assertNull(cleanCrumb.type); assertNull(cleanCrumb.unknown)
        val event = event(text).apply {
            message!!.message = "signature=synthetic-value"; message!!.params = listOf(text)
            exceptions = listOf(SentryException().apply { type = "WalletError"; value = text })
            breadcrumbs = listOf(crumb)
        }
        val clean = requireNotNull(options.beforeSend!!.execute(event, Hint()))
        assertEquals("[redacted]", clean.message!!.formatted)
        assertNull(clean.message!!.message); assertNull(clean.message!!.params)
        assertEquals("[redacted]", clean.exceptions!!.single().value)
        assertFalse(json(clean).contains("synthetic"))
    }

    @Test fun everyAttachmentSlotIsClearedByInstalledHook() {
        val options = requireNotNull(options())
        val attachment = Attachment("fake attachment".toByteArray(), "fake.txt")
        val hint = Hint.withAttachment(attachment).apply {
            screenshot = attachment; viewHierarchy = attachment; threadDump = attachment; tombstone = attachment
        }
        assertNotNull(options.beforeSend!!.execute(event(), hint))
        assertTrue(hint.attachments.isEmpty()); assertNull(hint.screenshot); assertNull(hint.viewHierarchy)
        assertNull(hint.threadDump); assertNull(hint.tombstone)
        assertEquals(0L, options.maxAttachmentSize)
    }

    @Test fun all147FixturesPassEveryBoundaryAndFinalMetadataGuard() {
        val fixtures = SharedFixtures.load("log-redaction")
        assertEquals(147, fixtures.size)
        for (fixture in fixtures) {
            val input = fixture.input.jsonPrimitive.content
            val expected = LogFile.redact(input)
            for (boundary in listOf("message", "exception", "context", "breadcrumb")) {
                val event = event()
                plantText(event, boundary, input)
                val clean = requireNotNull(AppTelemetry.scrub(event)) { "$boundary ${fixture.name}" }
                assertEquals("$boundary ${fixture.name}", if (boundary == "context") null else expected, plantedText(clean, boundary))
            }
            assertEquals("final ${fixture.name}", input != expected,
                AppTelemetry.scrub(event().apply { release = input }) == null)
        }
    }

    private fun plantText(event: SentryEvent, boundary: String, input: String) {
        when (boundary) {
            "message" -> event.message = message(input)
            "exception" -> event.exceptions = listOf(SentryException().apply { type = "WalletError"; value = input })
            "context" -> event.contexts.put("nested", mapOf("text" to input))
            else -> event.breadcrumbs = listOf(Breadcrumb().apply { category = "diagnostics"; message = input })
        }
    }

    private fun plantedText(clean: SentryEvent, boundary: String) = when (boundary) {
        "message" -> clean.message?.formatted
        "exception" -> clean.exceptions?.first()?.value
        "context" -> clean.contexts["nested"] as? String
        else -> clean.breadcrumbs?.first()?.message
    }

    @Test fun uncertainAndOversizedMessagesAreOmitted() {
        for (text in listOf("invalid escape: \\uD800", "x".repeat(16385))) {
            val event = event(text).apply {
                exceptions = listOf(SentryException().apply { type = "WalletError"; value = text })
                breadcrumbs = listOf(Breadcrumb().apply { message = text })
            }
            val clean = clean(event)
            assertNull(clean.message); assertNull(clean.exceptions!!.single().value)
            assertNull(clean.breadcrumbs!!.single().message)
        }
    }

    @Test fun escapedJSONIsRedactedAtEveryMessageBoundary() {
        val value = Json.encodeToString(listOf(Json.encodeToString(mapOf("preimage" to "ab".repeat(32)))))
        assertEquals("[redacted]", clean(event(value)).message!!.formatted)
    }

    @Test fun mnemonicDictionaryKeysAndNestedWordObjectsHaveNoAdmissionPath() {
        val words = "ozone drill grab fiber curtain grace pudding thank cruise elder eight picnic".split(" ")
        val event = event().apply {
            extras = mapOf("restore" to words.mapIndexed { index, word -> mapOf(word to index) },
                "nested" to words.mapIndexed { index, word -> mapOf("position" to index, "word" to word) })
        }
        assertNull(clean(event).extras)
        assertFalse(json(clean(event)).contains("ozone"))
    }

    @Test fun aggregateMnemonicFragmentsAcrossExceptionsAndWholeEventAreRejected() {
        val words = List(11) { "abandon" } + "about"
        assertNull(AppTelemetry.scrub(event().apply {
            exceptions = words.map { SentryException().apply { type = "WalletError"; value = it } }
        }))
        assertNull(AppTelemetry.scrub(event(words.take(6).joinToString(" ")).apply {
            breadcrumbs = listOf(Breadcrumb().apply { message = words.drop(6).joinToString(" ") })
        }))
        assertNotNull(AppTelemetry.scrub(event(List(11) { "abandon" }.joinToString(" "))))
    }

    @Test fun finalGuardIncludesKeysUnknownObjectsDepthSizeAndInspectionErrors() {
        val words = "ozone drill grab fiber curtain grace pudding thank cruise elder eight picnic".split(" ")
        assertFalse(AppTelemetry.isSafePayload(mapOf("records" to words.mapIndexed { i, word -> mapOf(word to i) })))
        assertFalse(AppTelemetry.isSafePayload(mapOf("preimage=fake" to 0)))
        assertFalse(AppTelemetry.isSafePayload(mapOf("opaque" to UnexpectedValue())))
        assertFalse(AppTelemetry.isSafePayload(mapOf("records" to listOf("abandon ".repeat(6), "abandon ".repeat(6)))))
        assertFalse(AppTelemetry.isSafePayload(mapOf("number" to Double.NaN)))
        assertFalse(AppTelemetry.isSafePayload(mapOf("large" to "x".repeat(16385))))
        assertTrue(AppTelemetry.isSafePayload(mapOf("status" to "sync failed", "attempt" to 3)))
        var deep: Map<String, Any> = mapOf("status" to "sync failed")
        repeat(33) { deep = mapOf("nested" to deep) }
        assertFalse(AppTelemetry.isSafePayload(deep))
        val broken = object : AbstractMap<String, Any>() {
            override val entries: Set<Map.Entry<String, Any>> get() = error("synthetic inspection failure")
        }
        assertFalse(AppTelemetry.isSafePayload(broken))
    }

    @Test fun finalGuardCatchesSecretsInEveryAllowedMetadataShape() {
        for (boundary in listOf("release", "context", "frame", "thread", "sdk", "mechanism", "category")) {
            val secret = "preimage=synthetic-sensitive-value"
            val event = event()
            plantMetadata(event, boundary, secret)
            if (boundary == "category") assertTrue(clean(event).breadcrumbs!!.isEmpty())
            else assertNull(boundary, AppTelemetry.scrub(event))
        }
    }

    private fun plantMetadata(event: SentryEvent, boundary: String, secret: String) {
        when (boundary) {
            "release" -> event.release = secret
            "context" -> event.contexts.setDevice(Device().apply { model = secret })
            "frame" -> event.threads = listOf(SentryThread().apply {
                stacktrace = SentryStackTrace(listOf(SentryStackFrame().apply { function = secret }))
            })
            "thread" -> event.threads = listOf(SentryThread().apply { name = secret })
            "sdk" -> event.sdk = SdkVersion(secret, "8.57.0")
            "mechanism" -> event.exceptions = listOf(SentryException().apply {
                type = "WalletError"; mechanism = Mechanism().apply { type = secret }
            })
            else -> event.breadcrumbs = listOf(Breadcrumb().apply { category = secret })
        }
    }

    private fun envelope(type: SentryItemType, payload: String): SentryEnvelope {
        val bytes = payload.toByteArray(Charsets.UTF_8)
        val text = "{}\n{\"type\":\"${type.itemType}\",\"length\":${bytes.size}}\n$payload\n"
        return requireNotNull(serializer.deserializeEnvelope(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8))))
    }

    @Test fun transportDropsEveryNonEventAndNonSessionSDKEnvelopeType() {
        for (type in SentryItemType.entries.filter { it != SentryItemType.Event && it != SentryItemType.Session }) {
            assertNull(type.name, TelemetryTransport.filter(envelope(type, "{}")))
        }
    }

    @Test fun transportRescrubsCachedEventsAndDropsAttachmentsAndHeaderUnknowns() {
        val original = event("preimage=synthetic-value").apply { extras = mapOf("private" to "payload") }
        val eventItem = SentryEnvelopeItem.fromEvent(serializer, original)
        val attachment = envelope(SentryItemType.Attachment, "arbitrary payload").items.single()
        val header = SentryEnvelopeHeader(original.eventId).apply { unknown = mapOf("private" to "payload") }
        val filtered = requireNotNull(TelemetryTransport.filter(SentryEnvelope(header, listOf(eventItem, attachment))))
        assertEquals(1, filtered.items.count())
        assertNull(filtered.header.unknown)
        val clean = filtered.items.single().getEvent(serializer)!!
        assertEquals("[redacted]", clean.message!!.formatted); assertNull(clean.extras)
        assertNull(TelemetryTransport.filter(envelope(SentryItemType.Event, "malformed JSON")))
    }

    @Test fun releaseHealthSessionsKeepRandomIDsAndStatusButDropIdentityAndUnknownAttributes() {
        val payload =
            """{"sid":"60e12f74-8d9b-47cb-a162-903fa0b155a1","did":"22d5eb12-8839-493d-bf9d-f2540d210f30","init":true,"started":"2026-10-20T12:00:00Z",""" +
                """"status":"ok","errors":0,"private":"payload","attrs":{"release":"com.noahwallet.mainnet@0.2.0+100","environment":"production",""" +
                """"ip_address":"192.0.2.1","user_agent":"personal data"}}"""
        val filtered = requireNotNull(TelemetryTransport.filter(envelope(SentryItemType.Session, payload)))
        val actual = Json.parseToJsonElement(filtered.items.single().data.toString(Charsets.UTF_8)).jsonObject
        assertEquals("ok", actual["status"]!!.jsonPrimitive.content)
        assertEquals("22d5eb12-8839-493d-bf9d-f2540d210f30", actual["did"]!!.jsonPrimitive.content)
        assertNull(actual["private"])
        assertEquals(setOf("release", "environment"), actual["attrs"]!!.jsonObject.keys)
        assertNull(TelemetryTransport.filter(envelope(SentryItemType.Session, payload.replace("22d5eb12-8839-493d-bf9d-f2540d210f30", "alice@example.com"))))
        assertNull(TelemetryTransport.filter(envelope(SentryItemType.Session, payload.replace("com.noahwallet.mainnet@0.2.0+100", "preimage=synthetic-value"))))
    }

    @Test fun onlyGeneratedMappingUUIDIsAdmittedAndInvalidMetadataFailsClosed() {
        val uuid = "53e98d84-ef56-4eab-89d1-c508652f39dc"
        val original = event().apply { debugMeta = DebugMeta().apply {
            images = listOf(DebugImage().apply { type = "elf"; debugFile = "private path"; codeFile = "payload" })
        } }
        val clean = requireNotNull(AppTelemetry.scrub(original, uuid))
        val image = clean.debugMeta!!.images!!.single()
        assertEquals("proguard", image.type); assertEquals(uuid, image.uuid)
        assertNull(image.debugFile); assertNull(image.codeFile)
        assertNull(AppTelemetry.scrub(original, "preimage=fake"))
        val currentRelease = "com.noahwallet.mainnet@0.2.0+100"
        original.release = currentRelease
        assertNotNull(AppTelemetry.scrub(original, uuid, currentRelease)!!.debugMeta)
        original.release = "com.noahwallet.mainnet@0.1.0+99"
        assertNull(AppTelemetry.scrub(original, uuid, currentRelease)!!.debugMeta)
        val envelope = SentryEnvelope.from(serializer, clean, null)
        val filtered = requireNotNull(TelemetryTransport.filter(envelope, uuid))
        assertEquals(uuid, filtered.items.single().getEvent(serializer)!!.debugMeta!!.images!!.single().uuid)
        clean.release = "com.noahwallet.mainnet@0.1.0+99"
        val cached = requireNotNull(TelemetryTransport.filter(SentryEnvelope.from(serializer, clean, null), uuid, currentRelease))
        assertNull(cached.items.single().getEvent(serializer)!!.debugMeta)
    }

    private class UnexpectedValue {
        override fun toString(): String = error("Dropped objects must never be inspected or serialized")
    }
}
