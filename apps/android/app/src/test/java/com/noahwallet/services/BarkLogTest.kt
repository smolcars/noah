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
        val directory = Files.createTempDirectory("bark-order").toFile()
        val writer = Executors.newSingleThreadExecutor()
        val callers = Executors.newFixedThreadPool(4)
        val received = mutableListOf<String>()
        val file = LogFile(directory, capacity = 3000, executor = writer,
            systemSink = { _, _, message -> received += message })
        val logger = BarkLog(file)
        try {
            val start = CountDownLatch(1)
            val futures = (0 until 4).map { producer -> callers.submit {
                start.await()
                repeat(500) { logger.log(LogLevel.DEBUG, "load", "$producer:$it") }
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
                assertEquals(fixture.name, fixture.expected.jsonPrimitive.content, received[index].second)
                assertEquals(fixture.name, LogFile.redact("bark:${fixture.input.jsonPrimitive.content}"), received[index].first)
            }
            val persisted = directory.resolve("noah.log").readText()
            fixtures.filter { it.input != it.expected }.forEach {
                assertFalse(it.name, persisted.contains(it.input.jsonPrimitive.content))
            }
        } finally { directory.deleteRecursively() }
    }
}
