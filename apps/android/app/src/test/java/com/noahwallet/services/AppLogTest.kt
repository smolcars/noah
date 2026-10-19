package com.noahwallet.services

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
        assertEquals("seed: [redacted]!", LogFile.redact("seed: $phrase!"))
        val normal = "wallet loaded in 3 seconds after sync"
        assertEquals(normal, LogFile.redact(normal))
        file.append(AppLog.Level.info, "test", "seed: $phrase!")
        assertFalse(directory.resolve("noah.log").readText().contains(phrase))
        assertTrue(file.lines().single().endsWith("seed: [redacted]!"))
    }
}
