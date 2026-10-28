package com.noahwallet.ui

import com.noahwallet.services.logging.LogFile
import com.noahwallet.ui.settings.LogSnapshot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class LogSnapshotTest {
    @Test fun initialReadAndFailureHaveExplicitStates() = runBlocking {
        val state = LogSnapshot()
        assertTrue(state.loading)
        state.load(read = { error("private provider detail") })
        assertFalse(state.loading)
        assertEquals("Failed to fetch logs.", state.error)
        assertNull(state.updatedAt)
        state.load(read = { listOf("older", "newest") }, now = { 100L })
        assertFalse(state.loading); assertNull(state.error)
        assertEquals("newest", state.lines.last()); assertEquals(100L, state.updatedAt)
        state.load(read = { error("unreadable") })
        assertEquals(100L, state.updatedAt); assertEquals(listOf("older", "newest"), state.lines)
    }

    @Test fun rotationIsDisplayedOldestFirstWithNewestAtTheEnd() {
        val root = Files.createTempDirectory("log-order").toFile()
        try {
            File(root, "noah.log.1").writeText("older\n")
            File(root, "noah.log").writeText("newest\n")
            assertEquals(listOf("older", "newest"), LogFile(root).lines())
        } finally { root.deleteRecursively() }
    }
}
