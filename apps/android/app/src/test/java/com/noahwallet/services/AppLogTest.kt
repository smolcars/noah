package com.noahwallet.services

import com.noahwallet.fixtures.SharedFixtures
import kotlinx.serialization.json.jsonPrimitive
import com.noahwallet.services.logging.AppLog
import com.noahwallet.services.logging.LogFile
import java.nio.file.Files
import java.util.Date
import org.junit.Assert.*
import org.junit.Test

class AppLogTest {
    private fun withFile(test: (java.io.File, LogFile) -> Unit) {
        val directory = Files.createTempDirectory("log-test").toFile()
        try { test(directory, LogFile(directory)) } finally { directory.deleteRecursively() }
    }
    @Test fun sharedSecretRedactionVectors() = withFile { _, file ->
        for (case in SharedFixtures.load("log-redaction").filterNot { it.name.startsWith("address-") }) {
            val input = case.input.jsonPrimitive.content
            val expected = case.expected.jsonPrimitive.content
            assertEquals(case.name, expected, LogFile.redact(input))
            assertEquals("idempotent ${case.name}", expected, LogFile.redact(expected))
            file.append(AppLog.Level.info, "fixture", input)
            assertTrue("persistence ${case.name}", file.lines().last().endsWith(expected))
        }
    }
    @Test fun prefixedHexSecretsFromRustAreRedacted() {
        assertRedactionCases("r2-label-")
        assertRedactionCases("r2-separator-")
    }
    @Test fun unicodeEquivalentMnemonicsAreRedacted() { assertRedactionCases("r2-mnemonic-") }
    @Test fun urlUserinfoCredentialsAreRedacted() { assertRedactionCases("r2-userinfo-") }

    private fun assertRedactionCases(prefix: String) {
        for (case in SharedFixtures.load("log-redaction").filter { it.name.startsWith(prefix) }) {
            assertEquals(case.name, case.expected.jsonPrimitive.content, LogFile.redact(case.input.jsonPrimitive.content))
        }
    }

    @Test fun sharedAddressRedactionVectors() = withFile { _, file ->
        for (case in SharedFixtures.load("log-redaction").filter { it.name.startsWith("address-") }) {
            val expected = case.expected.jsonPrimitive.content
            val input = case.input.jsonPrimitive.content
            assertEquals(case.name, expected, LogFile.redact(input))
            file.append(AppLog.Level.info, "fixture", input)
            assertTrue("persistence ${case.name}", file.lines().last().endsWith(expected))
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
        assertFalse(directory.resolve("noah.log").readText().contains(phrase))
        assertTrue(file.lines().single().endsWith("[redacted]"))
    }
}
