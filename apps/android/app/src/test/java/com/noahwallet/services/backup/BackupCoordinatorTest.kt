package com.noahwallet.services.backup

import com.noahwallet.services.storage.PersistedBackupState
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class BackupCoordinatorTest {
    private class Clock {
        data class Sleep(val duration: Long, val gate: CompletableDeferred<Unit>)
        private val sleepers = mutableListOf<Sleep>()
        suspend fun sleep(ms: Long) {
            val sleeper = Sleep(ms, CompletableDeferred())
            synchronized(this) { sleepers += sleeper }
            try { sleeper.gate.await() } finally { synchronized(this) { sleepers.remove(sleeper) } }
        }
        @Synchronized fun pending() = sleepers.map { it.duration }
        @Synchronized fun fire() { sleepers.removeAt(0).gate.complete(Unit) }
    }
    private class World {
        val clock = Clock()
        @Volatile var state = PersistedBackupState()
        val calls = java.util.concurrent.CopyOnWriteArrayList<String?>()
        @Volatile var failures = 0
        @Volatile var sha = "sha-1"
        @Volatile var eligible = true
        @Volatile var enabled = true
        @Volatile var held: CompletableDeferred<Unit>? = null
        val entered = CompletableDeferred<Unit>()
        val coordinator = BackupCoordinator(BackupCoordinator.Dependencies(
            perform = { last, uploading ->
                calls.add(last); val snapshot = sha; entered.complete(Unit); held?.await()
                if (failures > 0) { failures--; error("private provider error") }
                if (last == snapshot) BackupService.Outcome(null, snapshot, false)
                else { uploading(); BackupService.Outcome("id-$snapshot", snapshot, true) }
            }, canBackup = { eligible && (!it || enabled) }, loadState = { state }, saveState = { state = it },
            sleep = clock::sleep, jitter = { 1.0 }, now = { 1000.0 }))
    }
    private suspend fun eventually(condition: () -> Boolean) = withTimeout(5000) { while (!condition()) delay(2) }
    @Test fun deletingLatestSchedulesDebouncedReplacementOnlyWhenEligible() = runBlocking {
        val w = World()
        w.coordinator.flush(BackupCoordinator.Reason.manual)
        w.coordinator.forgetUploadedBackup("other")
        assertTrue(w.clock.pending().isEmpty())
        w.coordinator.forgetUploadedBackup("id-sha-1")
        eventually { w.clock.pending() == listOf(5000L) }
        assertEquals(1, w.calls.size)
        w.clock.fire()
        eventually { w.calls.size == 2 && w.state.lastBackupStatus == "success" }
        assertEquals(listOf<String?>(null, null), w.calls)
        w.coordinator.cancelScheduled()
    }

    @Test fun deletingLatestWhileDisabledOrSuspendedDoesNotSchedule() = runBlocking {
        for (suspended in listOf(false, true)) {
            val w = World()
            w.coordinator.flush(BackupCoordinator.Reason.manual)
            w.enabled = suspended; w.eligible = !suspended
            w.coordinator.forgetUploadedBackup("id-sha-1")
            assertNull(w.state.lastUploadedSnapshotSha256)
            assertTrue(w.state.backupPending)
            assertTrue(w.clock.pending().isEmpty())
            assertEquals(1, w.calls.size)
        }
    }

    @Test fun statusSnapshotsPublishOnlyAfterPersistenceAndRetainEarlierValues() = runBlocking {
        val w = World()
        val initial = w.coordinator.statusChanges.value
        assertEquals(w.state, initial)
        w.coordinator.schedule(BackupCoordinator.Reason.databaseChanged)
        assertTrue(w.coordinator.statusChanges.value.backupPending)
        assertFalse(initial.backupPending)
        w.coordinator.flush(BackupCoordinator.Reason.manual)
        val success = w.coordinator.statusChanges.value
        assertEquals(w.state, success)
        assertEquals("success", success.lastBackupStatus)
        w.coordinator.forgetUploadedBackup(success.lastBackupId!!)
        assertEquals("idle", w.coordinator.statusChanges.value.lastBackupStatus)
        assertEquals("success", success.lastBackupStatus)
        w.state = PersistedBackupState() // wallet deletion clears persisted state
        w.coordinator.reloadStatus()
        assertEquals(PersistedBackupState(), w.coordinator.statusChanges.value)
        w.coordinator.cancelScheduled()
    }

    @Test fun deletionCancelsUploadAndWaitsForItsFinalStateWrite() = runBlocking {
        val w = World()
        w.held = CompletableDeferred()
        w.coordinator.schedule(BackupCoordinator.Reason.manual, true)
        w.entered.await(); w.eligible = false
        withTimeout(1000) { w.coordinator.settleForDeletion() }
        assertEquals("idle", w.state.lastBackupStatus)
        assertTrue(w.state.backupPending)
        assertNull(w.state.lastBackupError)
        assertNull(com.noahwallet.ui.home.HomeRules.backup(w.state, 0, java.util.Locale.US))
        assertTrue(w.clock.pending().isEmpty())
        assertNull(w.state.lastBackupAt)
    }

    @Test fun failureUsesRNCopyAndNeverDisplaysPersistedTypeNames() = runBlocking {
        val w = World(); w.failures = 1
        assertTrue(runCatching { w.coordinator.flush(BackupCoordinator.Reason.manual) }.isFailure)
        assertEquals("An unknown error occurred while backing up.", w.state.lastBackupError)
        val legacy = w.state.copy(lastBackupError = "ClientError")
        assertEquals("An unknown error occurred while backing up.",
            com.noahwallet.ui.home.HomeRules.backup(legacy, 0, java.util.Locale.US)?.message)
        w.coordinator.cancelScheduled()
    }

    @Test fun debounceCoalescesRequests() = runBlocking {
        val w = World(); repeat(3) { w.coordinator.schedule(BackupCoordinator.Reason.databaseChanged) }
        eventually { w.clock.pending() == listOf(5000L) }; assertTrue(w.calls.isEmpty()); w.clock.fire()
        eventually { w.state.lastBackupStatus == "success" }; assertEquals(listOf<String?>(null), w.calls); assertFalse(w.state.backupPending)
    }
    @Test fun unchangedSnapshotKeepsUploadTimeAndId() = runBlocking {
        val w = World(); w.coordinator.flush(BackupCoordinator.Reason.manual); w.coordinator.flush(BackupCoordinator.Reason.manual)
        assertEquals(listOf(null, "sha-1"), w.calls); assertEquals("idle", w.state.lastBackupStatus)
        assertEquals("id-sha-1", w.state.lastBackupId); assertEquals(1000.0, w.state.lastBackupAt)
    }
    @Test fun retriesAndResetsBackoff() = runBlocking {
        val w = World(); w.failures = 2; w.coordinator.schedule(BackupCoordinator.Reason.startup, true)
        eventually { w.clock.pending() == listOf(15000L) }; w.clock.fire()
        eventually { w.clock.pending() == listOf(30000L) }; w.clock.fire()
        eventually { w.state.lastBackupStatus == "success" }
        w.failures = 1; w.coordinator.schedule(BackupCoordinator.Reason.startup, true)
        eventually { w.clock.pending() == listOf(15000L) }; w.coordinator.cancelScheduled()
    }
    @Test fun retryJitterAndCap() {
        assertEquals(listOf(15000L, 12000L, 480000L, 900000L, 900000L, 720000L),
            listOf(0 to 1.0, 0 to 0.8, 5 to 1.0, 6 to 1.0, 6 to 1.2, 10 to 0.8).map { BackupCoordinator.retryDelay(it.first, it.second) })
    }
    @Test fun flushStartsSecondPassForNewGenerationWithoutSpinning() = runBlocking {
        val w = World(); val gate = CompletableDeferred<Unit>(); w.held = gate
        w.coordinator.schedule(BackupCoordinator.Reason.startup, true); w.entered.await(); w.sha = "sha-2"
        val flush = async(start = CoroutineStart.UNDISPATCHED) { w.coordinator.flush(BackupCoordinator.Reason.push, false) }
        gate.complete(Unit); withTimeout(5000) { flush.await() }
        assertEquals(listOf(null, "sha-1"), w.calls); assertEquals("sha-2", w.state.lastUploadedSnapshotSha256)
        w.coordinator.cancelScheduled()
    }
    @Test fun cancelledFlushLeavesPassRunning() = runBlocking {
        val w = World(); val gate = CompletableDeferred<Unit>(); w.held = gate
        val flush = launch { w.coordinator.flush(BackupCoordinator.Reason.push, false) }; w.entered.await()
        withTimeout(1000) { flush.cancelAndJoin() }; assertNotEquals("success", w.state.lastBackupStatus)
        gate.complete(Unit); eventually { w.state.lastBackupStatus == "success" }; assertEquals(1, w.calls.size)
    }
    @Test fun eligibilityAndPushBypassEnabled() = runBlocking {
        val w = World(); w.enabled = false
        w.coordinator.schedule(BackupCoordinator.Reason.startup)
        assertTrue(runCatching { w.coordinator.flush(BackupCoordinator.Reason.manual) }.isFailure)
        assertTrue(w.calls.isEmpty()); w.coordinator.flush(BackupCoordinator.Reason.push, false); assertEquals(1, w.calls.size)
        w.eligible = false; assertTrue(runCatching { w.coordinator.flush(BackupCoordinator.Reason.push, false) }.isFailure)
    }
    @Test fun cancelScheduledStopsTimer() = runBlocking {
        val w = World(); w.coordinator.schedule(BackupCoordinator.Reason.databaseChanged)
        eventually { w.clock.pending().size == 1 }; w.coordinator.cancelScheduled(); eventually { w.clock.pending().isEmpty() }
        assertTrue(w.calls.isEmpty())
    }
    @Test fun deletedUploadInvalidatesInFlightUnchangedPass() = runBlocking {
        val w = World()
        w.coordinator.flush(BackupCoordinator.Reason.manual, false)
        val gate = CompletableDeferred<Unit>(); w.held = gate
        val pass = async { w.coordinator.flush(BackupCoordinator.Reason.manual, false) }
        eventually { w.calls.size == 2 }
        val deletion = async { w.coordinator.forgetUploadedBackup("id-sha-1") }
        yield(); assertFalse(deletion.isCompleted)
        gate.complete(Unit); pass.await(); deletion.await()
        assertNull(w.state.lastUploadedSnapshotSha256); assertNull(w.state.lastBackupId); assertTrue(w.state.backupPending)
        eventually { w.clock.pending() == listOf(5000L) }
        w.clock.fire()
        eventually { w.calls.size == 3 && w.state.lastBackupStatus == "success" }
        assertNull(w.calls.last())
        w.coordinator.cancelScheduled()
    }

    @Test fun settleDrainsAnInFlightBackupBeforeStateCanBeErased() = runBlocking {
        val w = World(); val gate = CompletableDeferred<Unit>(); w.held = gate
        w.coordinator.schedule(BackupCoordinator.Reason.startup, true); w.entered.await()
        w.eligible = false
        val drain = async { w.coordinator.settle() }; yield(); assertFalse(drain.isCompleted)
        gate.complete(Unit); drain.await()
        w.state = PersistedBackupState()
        w.coordinator.schedule(BackupCoordinator.Reason.databaseChanged)
        assertEquals(PersistedBackupState(), w.state); assertTrue(w.clock.pending().isEmpty())
    }

    @Test fun rnShapeAndExplicitNulls() {
        val state = PersistedBackupState.decode("""{"state":{"backupPending":true,"lastBackupAt":1784160000000,"lastBackupAttemptAt":null,"lastBackupStatus":"in_progress","lastBackupError":null,"lastBackupId":"b1","lastUploadedSnapshotSha256":"ab"},"version":0}""")
        state.markPending(); assertEquals("in_progress", state.lastBackupStatus); assertEquals("ab", state.lastUploadedSnapshotSha256)
        assertTrue(state.forgetUploadedBackup("b1")); assertFalse(state.forgetUploadedBackup("other")); assertNull(state.lastUploadedSnapshotSha256)
        assertTrue(PersistedBackupState().encode().contains("\"lastBackupId\":null"))
        assertEquals(PersistedBackupState(), PersistedBackupState.decode(PersistedBackupState().encode()))
    }
}
