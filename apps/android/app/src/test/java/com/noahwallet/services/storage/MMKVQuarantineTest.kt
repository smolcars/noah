package com.noahwallet.services.storage

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class MMKVQuarantineTest {
    private fun fixture(test: (java.io.File) -> Unit) {
        val root = Files.createTempDirectory("mmkv-quarantine").toFile()
        try { test(root) } finally { root.deleteRecursively() }
    }
    @Test fun recoveryIsDurableBeforeReturningAndSurvivesRetries() = fixture { root ->
        val synced = mutableListOf<java.io.File>()
        val marker = MMKVQuarantine(root, SettingsStorage.instanceID, synced::add)
        marker.recordRecovery(); marker.requireDurable()
        assertEquals("1\nnoah-wallet-storage\n", marker.file.readText())
        assertEquals(listOf(marker.file, root, marker.file, root), synced)
        repeat(2) { assertTrue(MMKVQuarantine(root, SettingsStorage.instanceID) {}.exists) }
        marker.clear(); assertFalse(marker.exists); assertEquals(root, synced.last())
    }
    @Test fun markerOrSyncFailureFailsClosed() = fixture { root ->
        val marker = MMKVQuarantine(root, SettingsStorage.instanceID) { error("fsync") }
        marker.recordRecovery(); assertTrue(marker.writeFailed)
        assertTrue(runCatching { marker.requireDurable() }.isFailure)
        assertTrue(runCatching { marker.clear() }.isFailure); assertTrue(marker.exists)
    }
    @Test fun failedAcknowledgementRetainsQuarantine() = fixture { root ->
        var fail = false
        val marker = MMKVQuarantine(root, SettingsStorage.instanceID) { if (fail && !java.io.File(root, "mmkv.native-quarantine").exists()) error("fsync") }
        marker.recordRecovery(); fail = true
        assertTrue(runCatching { marker.clear() }.isFailure); assertTrue(marker.exists)
    }
    @Test fun malformedMarkerNeverOffersAcknowledgement() = fixture { root ->
        val marker = MMKVQuarantine(root, SettingsStorage.instanceID) {}
        marker.file.writeText("future version")
        assertTrue(runCatching { marker.requireDurable() }.isFailure); assertTrue(marker.exists)
    }
}
