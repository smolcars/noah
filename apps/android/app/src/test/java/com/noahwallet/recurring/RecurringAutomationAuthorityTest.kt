package com.noahwallet.recurring

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class RecurringAutomationAuthorityTest {
    @Test fun stopSurvivesReopenAndOnlyExplicitResumeClearsIt() {
        val directory = Files.createTempDirectory("automation-authority-").toFile()
        try {
            fun authority() = RecurringAutomationAuthority(directory) { file, bytes -> durableRecurringWrite(file, bytes) {} }
            val before = authority().generation()
            authority().stop()
            assertFalse(authority().allowed())
            authority().suspension(false) // Failure rollback is not user reapproval.
            assertFalse(authority().allowed())
            authority().resume()
            assertTrue(authority().allowed())
            assertNotEquals(before, authority().generation())
            authority().suspension(true)
            assertFalse(authority().allowed())
        } finally { directory.deleteRecursively() }
    }
    @Test fun fileSyncPrecedesRenameAndDirectorySync() {
        val directory = Files.createTempDirectory("automation-order-").toFile()
        try {
            val file = java.io.File(directory, "authority")
            val events = mutableListOf<String>()
            durableRecurringWrite(file, "stop".toByteArray(), { parent ->
                assertEquals(directory, parent); assertEquals("stop", file.readText()); events.add("directory")
            }, { descriptor ->
                assertFalse(file.exists()); assertTrue(descriptor.valid()); descriptor.sync(); events.add("file")
            })
            assertEquals(listOf("file", "directory"), events)
        } finally { directory.deleteRecursively() }
    }
    @Test fun interruptedBootstrapAndEachWriteBoundaryStayStopped() {
        for (failAt in 1..6) {
            val directory = Files.createTempDirectory("automation-crash-").toFile()
            try {
                var step = 0
                val authority = RecurringAutomationAuthority(directory) { file, bytes ->
                    durableRecurringWrite(file, bytes, { if (++step == failAt) error("directory fsync") }, {
                        it.sync(); if (++step == failAt) error("file fsync")
                    })
                }
                assertTrue(runCatching { authority.stop() }.isFailure)
                assertFalse(RecurringAutomationAuthority(directory) { file, bytes -> durableRecurringWrite(file, bytes) {} }.allowed())
            } finally { directory.deleteRecursively() }
        }
    }
    @Test fun missingOrCorruptAuthorityNeverBootstrapsAgain() {
        for (damage in listOf("missing", "marker-missing", "corrupt", "missing-stop-field")) {
            val directory = Files.createTempDirectory("automation-damage-").toFile()
            try {
                fun authority() = RecurringAutomationAuthority(directory) { file, bytes -> durableRecurringWrite(file, bytes) {} }
                authority().generation()
                when (damage) {
                    "missing" -> authority().file.delete()
                    "marker-missing" -> authority().marker.delete()
                    "corrupt" -> authority().file.writeText("{")
                    else -> authority().file.writeText(authority().file.readText().replace("\"stopped\":false", "\"other\":false"))
                }
                assertFalse(damage, authority().allowed())
                assertTrue(runCatching { authority().resume() }.isFailure)
            } finally { directory.deleteRecursively() }
        }
    }
}
