package com.noahwallet.services.storage

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class NativeSettingsFileTest {
    @Test fun interruptionOnBothSidesOfRenameKeepsOneWholeGeneration() {
        for (first in listOf(false, true)) for (stop in listOf("write", "fileSync", "rename", "directorySync")) interrupt(first, stop)
    }

    /** Saves a new generation over an old one (if [first]) and stops at [stop]; one whole generation must remain. */
    private fun interrupt(first: Boolean, stop: String) {
        val dir = Files.createTempDirectory("native-settings").toFile()
        try {
            val file = File(dir, "settings.json")
            val old = NativeSettingsFile.State(mapOf("wallet-storage" to "old"))
            val next = NativeSettingsFile.State(mapOf("wallet-storage" to "new"), true, true)
            if (first) NativeSettingsFile(file, {}).save(old)
            val steps = mutableListOf<String>()
            val writer = NativeSettingsFile(file, { steps.add(if (it == dir) "syncDirectory" else "syncFile") }, {
                steps.add(it); if (it == stop) error("interrupted")
            })
            assertTrue(runCatching { writer.save(next) }.isFailure)
            val committed = stop == "rename" || stop == "directorySync"
            if (committed) assertEquals(next, NativeSettingsFile(file).load())
            else if (first) assertEquals(old, NativeSettingsFile(file).load())
            else assertFalse(file.exists())
            val order = listOf("write", "syncFile", "fileSync", "rename", "syncDirectory", "directorySync")
            assertEquals(order.take(steps.size), steps)
        } finally { dir.deleteRecursively() }
    }
    @Test fun strictDecodingRejectsDamageAndUnsupportedVersions() {
        val dir = Files.createTempDirectory("native-settings-strict").toFile()
        try {
            val file = File(dir, "settings.json"); val store = NativeSettingsFile(file, {})
            store.save(NativeSettingsFile.State(mapOf("raw" to "\"日本語\"")))
            val bytes = file.readBytes(); assertEquals("\"日本語\"", store.load().values["raw"])
            for (bad in listOf("{}", String(bytes).replace("\"version\":1", "\"version\":1.0"), String(bytes).replace("日本語", "changed"))) {
                file.writeText(bad); assertTrue(runCatching { store.load() }.isFailure)
            }
            file.writeBytes(byteArrayOf(0xc3.toByte(), 0x28)); assertTrue(runCatching { store.load() }.isFailure)
        } finally { dir.deleteRecursively() }
    }
}
