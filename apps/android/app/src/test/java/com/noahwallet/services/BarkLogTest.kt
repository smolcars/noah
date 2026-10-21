package com.noahwallet.services

import com.noahwallet.fixtures.SharedFixtures
import com.noahwallet.services.logging.AppLog
import com.noahwallet.services.logging.BarkLog
import com.noahwallet.services.logging.LogFile
import io.github.niteshbalusu11.bark.LogLevel
import java.nio.file.Files
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class BarkLogTest {
    @Test fun customEndpointPathsAreRemovedAtSDKAppAndTelemetryBoundaries() {
        val endpoint = "https://esplora-privacy.example/api/syntheticOpaqueToken"
        com.noahwallet.services.logging.EndpointLogPrivacy.register(endpoint)
        val message = "Using Esplora at $endpoint/block-height/0"
        val expected = "Using Esplora at [custom-esplora]"
        assertEquals(expected, com.noahwallet.services.logging.EndpointLogPrivacy.redact(message))
        assertEquals(expected, LogFile.redact(message))
        assertEquals(expected, LogFile.redactForTelemetry(message))
        val directory = Files.createTempDirectory("esplora-log").toFile()
        val file = LogFile(directory, executor = Executor { it.run() }, systemSink = { _, _, _ -> })
        try {
            BarkLog(file).log(LogLevel.INFO, "wallet", message)
            assertTrue(file.lines().last().endsWith(expected))
        } finally { directory.deleteRecursively() }
    }

    @Test fun relativeAndEscapedProviderErrorsCannotLeakEndpointCredentials() {
        val token = "fakeOpaqueCredential59df"
        com.noahwallet.services.logging.EndpointLogPrivacy.register("https://relative-privacy.example/api/$token")
        val inputs = listOf(
            "No route: /api/$token/fee-estimates",
            "fee estimation failed: HttpResponse(404): No route: /api/$token/fee-estimates\nCaused by: $token",
            """No route: \/api\/fakeOpaqueCredential59df\/fee-estimates""",
            """No route: \u002fapi\u002ffakeOpaqueCredential59df\u002ffee-estimates""",
            "No route: %2fapi%2ffakeOpaqueCredential59df%2ffee-estimates",
            "No route: /api/%66akeOpaqueCredential59df/fee-estimates",
            """No route: /api/\u0066akeOpaqueCredential59df/fee-estimates""",
            "No route: %252Fapi%252FfakeOpaqueCredential59df%252Ffee-estimates",
            """No route: \\/api\\/fakeOpaqueCredential59df\\/fee-estimates""",
        )
        val directory = Files.createTempDirectory("esplora-relative-log").toFile()
        val received = mutableListOf<String>()
        val file = LogFile(directory, executor = Executor { it.run() }, systemSink = { _, tag, text -> received += "$tag $text" })
        try {
            directory.resolve("noah.log.1").writeText(inputs.joinToString("\n"))
            for (input in inputs) {
                assertNotEquals(input, com.noahwallet.services.logging.EndpointLogPrivacy.redact(input))
                assertNotEquals(input, LogFile.redact(input))
                assertNotEquals(input, LogFile.redactForTelemetry(input))
                BarkLog(file).log(LogLevel.WARN, "bark::onchain", input)
                AppLog("wallet", file).e(input)
                val event = io.sentry.SentryEvent().apply { message = io.sentry.protocol.Message().apply { formatted = input } }
                val clean = requireNotNull(com.noahwallet.services.logging.AppTelemetry.scrub(event))
                assertFalse(clean.message!!.formatted!!.contains(token))
                event.release = input
                assertNull("Sentry final guard", com.noahwallet.services.logging.AppTelemetry.scrub(event))
            }
            assertFalse(received.joinToString().contains(token))
            assertFalse(file.lines().joinToString().contains(token))
            assertEquals("HTTP 404: fee-estimates failed", com.noahwallet.services.logging.EndpointLogPrivacy.redact("HTTP 404: fee-estimates failed"))
        } finally { directory.deleteRecursively() }
    }

    @Test fun sdkSuppressesProviderResponseBodiesButKeepsErrorTypeAndStatus() {
        val directory = Files.createTempDirectory("esplora-body-log").toFile()
        val received = mutableListOf<String>()
        val file = LogFile(directory, executor = Executor { it.run() }, systemSink = { _, _, text -> received += text })
        try {
            BarkLog(file).log(LogLevel.WARN, "bark::onchain",
                """fee estimation failed: HttpResponse { status: 404, message: "No route: /unknown/fakeUnregisteredCredential; \"nested\"" }; caused by: upstream unavailable""")
            val expected = "HttpResponse { status: 404 }"
            assertEquals(listOf(expected), received)
            assertTrue(file.lines().single().endsWith(expected))
        } finally { directory.deleteRecursively() }
    }

    @Test fun exportRetainsMaximumSizedDiagnostics() {
        val directory = Files.createTempDirectory("esplora-export-size").toFile()
        val file = LogFile(directory, executor = Executor { it.run() }, systemSink = { _, _, _ -> })
        try {
            val input = "x".repeat(16384)
            AppLog("sdk", file).e(input)
            assertTrue(file.lines().single().endsWith(input))
        } finally { directory.deleteRecursively() }
    }

    @Test fun matchesIOSAndRNDebugVerbosityAndLevels() {
        assertEquals(LogLevel.DEBUG, BarkLog.maxLevel)
        assertEquals(listOf(AppLog.Level.error, AppLog.Level.warn, AppLog.Level.info,
            AppLog.Level.debug, AppLog.Level.verbose), LogLevel.entries.map(BarkLog::appLevel))
    }

    @Test fun sdkRustErrorChainsAreRedactedBeforeBothSinks() {
        val directory = Files.createTempDirectory("bark-error-chains").toFile()
        val received = mutableListOf<String>()
        val writer = Executors.newSingleThreadExecutor()
        val file = LogFile(directory, executor = writer, systemSink = { _, tag, message -> received += "[$tag] $message" })
        try {
            val logger = BarkLog(file)
            for (text in listOf(
                "[ONCHAIN] Using Bitcoin Core RPC at http://fake-user:fake-password@localhost:18443",
                "request failed: error sending request for url (https://example.invalid/x?X-Amz-Signature=fake-presigned-value)\n\nCaused by:\n    transport error",
                "sync failed: invalid signature: " + "ab".repeat(64),
                "wallet failed: private_key=0x" + "cd".repeat(32))) {
                logger.log(LogLevel.ERROR, "bark::wallet", text)
            }
            file.flush()
            val saved = file.lines().joinToString("\n")
            for (secret in listOf("fake-user", "fake-password", "fake-presigned-value", "ab".repeat(64), "cd".repeat(32))) {
                assertFalse(secret, received.joinToString().contains(secret))
                assertFalse(secret, saved.contains(secret))
            }
            assertEquals(4, received.size)
        } finally { writer.shutdownNow(); directory.deleteRecursively() }
    }

    @Test fun concurrentCallbacksPreserveEachProducersOrderUnderLoad() {
        com.noahwallet.services.logging.EndpointLogPrivacy.register("https://review-number.example/835917264")
        val directory = Files.createTempDirectory("bark-order").toFile()
        val writer = Executors.newSingleThreadExecutor()
        val callers = Executors.newFixedThreadPool(4)
        val received = mutableListOf<String>()
        val file = LogFile(directory, capacity = 3000, executor = writer,
            systemSink = { _, _, message -> received += message })
        val logger = BarkLog(file)
        val date = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US).parse("2026-10-21 08:21:42.292")!!
        try {
            val start = CountDownLatch(1)
            val futures = (0 until 4).map { producer -> callers.submit {
                start.await()
                repeat(500) { logger.log(LogLevel.DEBUG, "load", "$producer:$it", date) }
            } }
            start.countDown()
            futures.forEach { it.get(10, TimeUnit.SECONDS) }
            file.flush()
            assertEquals(2000, received.size)
            repeat(4) { producer ->
                assertEquals((0 until 500).map { "$producer:$it" }, received.filter { it.startsWith("$producer:") })
            }
            assertEquals(received, file.lines().map { it.substringAfter("] ") })
        } finally {
            callers.shutdownNow(); writer.shutdownNow(); directory.deleteRecursively()
        }
    }

    @Test fun callbacksDoNotWaitOnBlockedWriterAndOverflowStaysOrdered() {
        val directory = Files.createTempDirectory("bark-overflow").toFile()
        val writer = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val file = LogFile(directory, capacity = 2, executor = writer, systemSink = { _, _, _ ->
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS))
        })
        val logger = BarkLog(file)
        try {
            logger.log(LogLevel.INFO, "load", "record 0")
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val started = System.nanoTime()
            repeat(1000) { logger.log(LogLevel.INFO, "load", "record ${it + 1}") }
            assertTrue("1000 callbacks must finish within 100 ms with a blocked sink",
                System.nanoTime() - started < 100_000_000)
            assertFalse(directory.resolve("noah.log").exists())
            release.countDown()
            file.flush()
            val lines = file.lines()
            assertEquals(3, lines.size)
            assertTrue(lines[0].endsWith("record 0"))
            assertTrue(lines[1].endsWith("record 1"))
            assertTrue(lines[2].endsWith("[dropped 999 log records]"))
        } finally {
            release.countDown(); writer.shutdownNow(); directory.deleteRecursively()
        }
    }

    @Test fun all147SDKVectorsRedactOnQueueBeforeEverySinkIncludingTargets() {
        val directory = Files.createTempDirectory("bark-redact").toFile()
        val tasks = ArrayDeque<Runnable>()
        val received = mutableListOf<Pair<String, String>>()
        val file = LogFile(directory, capacity = 500, executor = Executor(tasks::addLast),
            systemSink = { _, tag, message -> received += tag to message })
        val logger = BarkLog(file)
        try {
            val fixtures = SharedFixtures.load("log-redaction")
            assertEquals(147, fixtures.size)
            fixtures.forEach { logger.log(LogLevel.INFO, it.input.jsonPrimitive.content, it.input.jsonPrimitive.content) }
            assertTrue(received.isEmpty())
            assertFalse(directory.resolve("noah.log").exists())
            while (tasks.isNotEmpty()) tasks.removeFirst().run()
            fixtures.forEachIndexed { index, fixture ->
                assertEquals(fixture.name, LogFile.redact(fixture.input.jsonPrimitive.content), received[index].second)
                assertEquals(fixture.name, LogFile.redact("bark:${fixture.input.jsonPrimitive.content}"), received[index].first)
            }
            val persisted = directory.resolve("noah.log").readText()
            fixtures.filter { it.input != it.expected }.forEach {
                assertFalse(it.name, persisted.contains(it.input.jsonPrimitive.content))
            }
        } finally { directory.deleteRecursively() }
    }
}
