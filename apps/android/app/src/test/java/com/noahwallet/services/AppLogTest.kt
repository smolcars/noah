package com.noahwallet.services

import com.noahwallet.fixtures.SharedFixtures
import kotlinx.serialization.json.jsonPrimitive
import com.noahwallet.services.logging.AppLog
import com.noahwallet.services.logging.LogFile
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.util.Date
import org.junit.Assert.*
import org.junit.Test
import java.util.ArrayDeque
import java.util.concurrent.Executor
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking

class AppLogTest {
    @Test fun redactionPerformance() {
        repeat(1000) { index ->
            com.noahwallet.services.logging.EndpointLogPrivacy.register("https://scaling-privacy-$index.example/api/fakeScale$index")
        }
        com.noahwallet.services.logging.EndpointLogPrivacy.register("https://fake-r8-scaling.example/fakeOpaque")
        com.noahwallet.services.logging.EndpointLogPrivacy.register("https://xn--strae-oqa.example/api")
        val unicode = listOf("combining mark miss" to "zzzz ordinarye\u0301 ",
            "combining mark hit" to "xxfakeOpaque\u0301yy ",
            "IDNA2008 miss" to "xxordinaryßς\u200D\u200Cyy ",
            "IDNA2008 hit" to "xxstraßeyy ")
        for ((name, unit) in unicode) assertRedactionScales(name, unit, com.noahwallet.services.logging.EndpointLogPrivacy::redact)
        val authorities = listOf("other authorities" to "https://unrelated-scaling.example/other ",
            "registered authorities" to "https://scaling-privacy-999.example/other ",
            "allowed authorities" to "https://noahwallet.io/v0/health ",
            "malformed authorities" to "https://fake-review-r5.example:invalid ",
            "scheme-less hosts" to "scaling-privacy-999.example:invalid ",
            "path history without slash" to "zzzz ordinary diagnostic ",
            "path history with slash" to "/zzzz/ordinary/diagnostic ",
            "path history hit" to "xxfakeScale999yy ")
        for ((name, unit) in performanceInputs + authorities) {
            assertRedactionScales(name, unit, LogFile::redact)
            assertRedactionScales("boundary $name", unit, com.noahwallet.services.logging.EndpointLogPrivacy::redact)
        }
        val matcher = com.noahwallet.services.logging.EndpointLogPrivacy.SecretMatcher(setOf("a"))
        assertRedactionScales("fullwidth bare host", "ａ ") {
            com.noahwallet.services.logging.EndpointLogPrivacy.sanitize(it, setOf("https://a"), matcher, setOf("a")) ?: "[redacted]"
        }
    }

    private fun assertRedactionScales(name: String, unit: String, redact: (String) -> String = LogFile::redact) {
        val inputs = listOf(1024, 16384).map { size -> unit.repeat((size + unit.length - 1) / unit.length).take(size) }
        val clock = ManagementFactory.getThreadMXBean()
        check(clock.isCurrentThreadCpuTimeSupported)
        if (!clock.isThreadCpuTimeEnabled) clock.isThreadCpuTimeEnabled = true
        repeat(5) { inputs.forEach { redact(it) } } // Warm both sizes before sampling.
        val samples = inputs.map { mutableListOf<Long>() }
        repeat(5) {
            inputs.forEachIndexed { index, input ->
                val start = clock.currentThreadCpuTime
                repeat(4) { redact(input) }
                samples[index] += clock.currentThreadCpuTime - start
            }
        }
        val (small, large) = samples.map { it.sorted()[2] }
        println("REDACTION $name CPU 1 KiB $small ns; 16 KiB $large ns (four calls)")
        // 16x input permits 64x CPU work, rejecting quadratic (256x) rescans without counting descheduling.
        assertTrue("$name CPU scaling: $small -> $large ns", small > 0 && large < small * 64)

    }

    private val performanceInputs = listOf(
        "punctuation" to "!@#$%^&*()[]{}:;,?", "hyphen" to "x-", "dot" to "x.",
        "digit" to "1234567890", "mixed" to "wallet sync completed xyzzy ",
        "escaped" to "\\u0078\\u002e", "entities" to "&#x78;&#46;",
        "provider body" to """HttpResponse { status: 404, message: "fake" }""",
    )

    @Test fun callerOnlyEnqueuesAndEverySinkWaitsForRedaction() = withFile { directory, _ ->
        val tasks = ArrayDeque<Runnable>()
        val sinks = mutableListOf<String>()
        val file = LogFile(directory, capacity = 100, executor = Executor { tasks.addLast(it) },
            systemSink = { _, tag, message -> sinks += "[$tag] $message" })
        AppLog("warmup", file).i("warmup")
        var slowest = 0L
        for ((_, unit) in performanceInputs) {
            val input = unit.repeat((16384 + unit.length - 1) / unit.length).take(16384)
            val started = System.nanoTime()
            AppLog(input, file).e(input)
            slowest = maxOf(slowest, System.nanoTime() - started)
        }
        assertTrue("caller ${slowest / 1e6} ms", slowest < 5_000_000)
        println("LOG ADMISSION maximum ${slowest / 1e6} ms")
        val phrase = "abandon ".repeat(11) + "about"
        AppLog(phrase, file).e(phrase)
        assertTrue(sinks.isEmpty())
        assertFalse(directory.resolve("noah.log").exists())
        while (tasks.isNotEmpty()) tasks.removeFirst().run()
        assertEquals(10, sinks.size)
        assertEquals("[[redacted]] [redacted]", sinks.last())
        assertFalse(directory.resolve("noah.log").readText().contains(phrase))
    }

    @Test fun boundedWriterKeepsOverflowAndAwaitableFlushInOrder() = withFile { directory, _ ->
        val tasks = ArrayDeque<Runnable>()
        val file = LogFile(directory, capacity = 2, executor = Executor { tasks.addLast(it) })
        repeat(5) { file.append(AppLog.Level.info, "test", "record $it") }
        assertFalse(directory.resolve("noah.log").exists())
        runBlocking {
            var flushed = false
            val flush = async(start = CoroutineStart.UNDISPATCHED) { file.flushAsync(); flushed = true }
            assertFalse(flushed)
            while (tasks.isNotEmpty()) tasks.removeFirst().run()
            flush.await()
            assertTrue(flushed)
        }
        val lines = directory.resolve("noah.log").readLines()
        assertEquals(3, lines.size)
        assertTrue(lines[0].endsWith("record 0"))
        assertTrue(lines[1].endsWith("record 1"))
        assertTrue(lines[2].endsWith("[dropped 3 log records]"))
    }

    @Test fun failedTokenCandidatesStayBounded() {
        for (unit in listOf("seed.", "http://", "ExpoPushToken[", "x@x.", "xprv", "\\u0078!")) {
            assertRedactionScales("failed candidate $unit", unit)
        }
    }

    @Test fun tokenScanPreservesBoundaryAndTrailingPunctuationBehavior() {
        com.noahwallet.services.logging.EndpointLogPrivacy.register("https://token-scan.example/api")
        for ((input, expected) in listOf(
            "foo@b.co." to "[redacted].", "foo@b.co-" to "[redacted]-",
            "-key=fake" to "-[redacted]", "foo.key=fake" to "foo.[redacted]",
            "x-amz-foo.bar: ordinary" to "x-amz-foo.bar: ordinary",
            "seed phrase: fake" to "[redacted]", "private keyStore: fake" to "[redacted]",
            "http://http://ordinary" to "[custom-esplora]",
            "ExpoPushToken[unfinished" to "[redacted]",
        )) assertEquals(input, expected, LogFile.redact(input))
    }

    private fun withFile(test: (java.io.File, LogFile) -> Unit) {
        val directory = Files.createTempDirectory("log-test").toFile()
        val file = LogFile(directory, capacity = 5000)
        try { test(directory, file) } finally { file.flush(); directory.deleteRecursively() }
    }
    private fun redactBaseline(message: String): String = LogFile.redactForTelemetry(message) {
        com.noahwallet.services.logging.EndpointLogPrivacy.sanitize(it, emptySet())
    } ?: "[redacted]"

    @Test fun sharedSecretRedactionVectors() = withFile { _, file ->
        for (case in SharedFixtures.load("log-redaction").filterNot { it.name.startsWith("address-") }) {
            val input = case.input.jsonPrimitive.content
            val expected = case.expected.jsonPrimitive.content
            assertEquals(case.name, expected, redactBaseline(input))
            assertEquals("idempotent ${case.name}", expected, redactBaseline(expected))
            file.append(AppLog.Level.info, "fixture", input)
            assertTrue("persistence ${case.name}", file.lines().last().endsWith(LogFile.redact(input)))
        }
    }
    @Test fun escapedCredentialsAreRedacted() { assertRedactionCases("r3-escaped-") }
    @Test fun visuallyRecoverableMnemonicsAreRedacted() { assertRedactionCases("r3-skeleton-") }

    @Test fun prefixedHexSecretsFromRustAreRedacted() {
        assertRedactionCases("r2-label-")
        assertRedactionCases("r2-separator-")
    }
    @Test fun unicodeEquivalentMnemonicsAreRedacted() { assertRedactionCases("r2-mnemonic-") }
    @Test fun urlUserinfoCredentialsAreRedacted() { assertRedactionCases("r2-userinfo-") }

    private fun assertRedactionCases(prefix: String) {
        for (case in SharedFixtures.load("log-redaction").filter { it.name.startsWith(prefix) }) {
            assertEquals(case.name, case.expected.jsonPrimitive.content, redactBaseline(case.input.jsonPrimitive.content))
        }
    }

    @Test fun sharedAddressRedactionVectors() = withFile { _, file ->
        for (case in SharedFixtures.load("log-redaction").filter { it.name.startsWith("address-") }) {
            val expected = case.expected.jsonPrimitive.content
            val input = case.input.jsonPrimitive.content
            assertEquals(case.name, expected, redactBaseline(input))
            file.append(AppLog.Level.info, "fixture", input)
            assertTrue("persistence ${case.name}", file.lines().last().endsWith(LogFile.redact(input)))
        }
    }
    @Test fun oversizedMessagesAreOmitted() {
        assertEquals("[oversized log message omitted]", LogFile.redact("x".repeat(16385)))
    }
    @Test fun writesReactNativeLineFormat() = withFile { _, file ->
        file.append(AppLog.Level.warn, "sync", "  first\nsecond  ", Date(0)); file.flush()
        assertEquals(2, file.lines().size)
        assertTrue(file.lines()[0].endsWith(" W [sync] first"))
        assertEquals("  second", file.lines()[1])
    }
    @Test fun rotatesAtMostFourOldFiles() = withFile { directory, file ->
        val count = LogFile.maxFileSizeBytes / 1000 * 6
        repeat(count) { file.append(AppLog.Level.info, "t", "$it ${"x".repeat(1000)}") }
        file.flush()
        assertEquals(listOf("noah.log", "noah.log.1", "noah.log.2", "noah.log.3", "noah.log.4"),
            directory.list()!!.sorted())
        assertTrue(file.lines().last().contains("] ${count - 1} x"))
        assertTrue(file.lines().size < count)
    }
    @Test fun exportsNewestLines() = withFile { _, file ->
        repeat(LogFile.maxLines + 100) { file.append(AppLog.Level.info, "t", "line $it") }
        assertEquals(LogFile.maxLines, file.lines().size)
        assertTrue(file.lines().first().endsWith("line 100"))
        assertTrue(file.lines().last().endsWith("line ${LogFile.maxLines + 99}"))
    }
    @Test fun redactsMnemonicShapeBeforePersisting() = withFile { directory, file ->
        val phrase = "abandon ".repeat(11) + "about"
        assertEquals("[redacted]", LogFile.redact("seed: $phrase!"))
        val normal = "wallet loaded in 3 seconds after sync"
        assertEquals(normal, LogFile.redact(normal))
        file.append(AppLog.Level.info, "test", "seed: $phrase!")
        file.flush()
        assertFalse(directory.resolve("noah.log").readText().contains(phrase))
        assertTrue(file.lines().single().endsWith("[redacted]"))
    }
}
