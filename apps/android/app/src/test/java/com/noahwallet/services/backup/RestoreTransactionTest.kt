package com.noahwallet.services.backup

import com.noahwallet.services.storage.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal class FakeSeed(var value: String?) : SecretItem {
    var unavailable = false
    var fail = false
    var lands = false
    var lockAfterWrite = false
    var onSave: () -> Unit = {}
    override suspend fun read(): SecretResult = if (unavailable) SecretResult.Unavailable(SecretFailure.storageUnreadable)
    else value?.let { SecretResult.Found(it) } ?: SecretResult.NotFound
    override suspend fun save(value: String) {
        if (!fail || lands) this.value = value
        if (lockAfterWrite) unavailable = true
        onSave()
        if (fail) error("Seed write failed")
    }
}
internal fun transaction(location: WalletDataLocation, sync: (File) -> Unit = {}): RestoreTransaction =
    RestoreTransaction(location, sync, { from, to -> Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING) }, {})

class RestoreTransactionTest {
    private class World {
        val root = Files.createTempDirectory("restore-tx").toFile()
        val location = WalletDataLocation(root, "regtest")
        val tx = transaction(location)
        fun write(directory: File, value: String) { directory.mkdirs(); File(directory, "db.sqlite").writeText(value) }
        fun read(directory: File = location.directory) = File(directory, "db.sqlite").takeIf { it.exists() }?.readText()
        fun begin(previous: Boolean = true) {
            if (previous) write(location.directory, "old")
            tx.prepare(); write(tx.staged, "new"); File(tx.work, "plaintext").writeText("secret"); tx.begin("new seed")
        }
        fun settled() { assertFalse(tx.isPending); assertFalse(location.restorePending)
            listOf(tx.previous, tx.staged, tx.discard, tx.work).forEach { assertFalse(it.exists()) } }
    }
    private inline fun world(body: World.() -> Unit) {
        val w = World(); try { w.body() } finally { w.root.deleteRecursively() }
    }
    @Test fun committedRestoreRepairsPartialAuthorityAsStopped() = runBlocking {
        for (damage in listOf("markerOnly", "stateOnly", "corrupt", "mismatched", "stopped")) world {
            begin(); tx.markCommitting()
            fun authority() = com.noahwallet.recurring.RecurringAutomationAuthority(location.directory) { file, bytes ->
                com.noahwallet.recurring.durableRecurringWrite(file, bytes) {}
            }
            authority().stop()
            when (damage) {
                "markerOnly" -> authority().file.delete()
                "stateOnly" -> authority().marker.delete()
                "corrupt" -> authority().file.writeText("{")
                "mismatched" -> authority().marker.writeText("{\"version\":1,\"instance\":\"${java.util.UUID.randomUUID()}\"}")
            }
            assertFalse(authority().allowed())
            val realStop = RestoreTransaction(location, {}, { from, to -> Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING) })
            assertEquals(RestoreTransaction.Recovery.completed, realStop.recover(FakeSeed("new seed")))
            settled(); assertEquals("new", read()); assertTrue(tx.settingsPending)
            assertFalse(authority().allowed())
            authority().suspension(false); assertFalse(authority().allowed())
        }
    }
    @Test fun authorityInitializationPersistsStateBeforeMarker() = world {
        write(location.directory, "wallet")
        val authority = com.noahwallet.recurring.RecurringAutomationAuthority(location.directory) { file, bytes ->
            com.noahwallet.recurring.durableRecurringWrite(file, bytes, {
                val state = File(location.directory, ".noah-recurring-automation.json")
                if (File("${state.path}.initialized").exists()) {
                    assertTrue(state.exists()); assertTrue(state.readText().contains("generation"))
                }
            })
        }
        authority.generation()
    }
    @Test fun swapKeepsPreviousAndJournalContainsOnlySeedTag() = world {
        begin(); assertEquals("new", read()); assertEquals("old", read(tx.previous))
        assertEquals(RestoreTransaction.Phase.installing, tx.readJournal().phase)
        assertFalse(tx.journalFile.readText().contains("new seed"))
    }
    @Test fun crashAfterJournalBeforeMoves() = runBlocking { world {
        begin(); location.directory.renameTo(tx.staged); tx.previous.renameTo(location.directory)
        assertEquals(RestoreTransaction.Recovery.rolledBack, tx.recover(FakeSeed("old seed")))
        assertEquals("old", read()); settled()
    } }
    @Test fun crashBetweenMoves() = runBlocking { world {
        begin(); location.directory.renameTo(tx.staged)
        tx.recover(FakeSeed("old seed")); assertEquals("old", read()); settled()
    } }
    @Test fun installingAlwaysUndoneEvenWhenSeedMatches() = runBlocking { world {
        begin(); tx.recover(FakeSeed("new seed")); assertEquals("old", read()); settled()
    } }
    @Test fun crashDuringUndoBeforePreviousMovedBack() = runBlocking { world {
        begin(); location.directory.deleteRecursively(); tx.recover(FakeSeed("old seed")); assertEquals("old", read()); settled()
    } }
    @Test fun crashDuringDiscardDeletion() = runBlocking { world {
        begin(); location.directory.renameTo(tx.discard); File(tx.discard, RestoreTransaction.markerName).delete()
        tx.recover(FakeSeed("old seed")); assertEquals("old", read()); settled()
    } }
    @Test fun crashDuringDiscardOnEmptyDevice() = runBlocking { world {
        begin(false); location.directory.renameTo(tx.discard)
        tx.recover(FakeSeed(null)); assertNull(read()); settled()
    } }
    @Test fun committedSeedFinishes() = runBlocking { world {
        begin(); tx.markCommitting(); tx.recover(FakeSeed("new seed")); assertEquals("new", read()); settled()
        assertFalse(File(location.directory, RestoreTransaction.markerName).exists())
    } }
    @Test fun missingOrDifferentSeedRollsBack() = runBlocking {
        for (seed in listOf(null, "old seed")) world {
            begin(); tx.markCommitting(); tx.recover(FakeSeed(seed)); assertEquals("old", read()); settled()
        }
    }
    @Test fun unreadableSeedWaitsWithoutTouchingEitherWallet() = runBlocking { world {
        begin(); tx.markCommitting(); val seed = FakeSeed("new seed").apply { unavailable = true }
        assertEquals(RestoreTransaction.Recovery.waitingForSecret, tx.recover(seed))
        assertEquals("old", read(tx.previous)); assertEquals("new", read()); assertTrue(tx.isPending)
        seed.unavailable = false; tx.recover(seed); settled()
    } }
    @Test fun emptyDeviceRollback() = runBlocking { world {
        begin(false); tx.recover(FakeSeed(null)); assertFalse(location.directory.exists()); settled()
    } }
    @Test fun undoRefusesUnmarkedDatadir() = runBlocking { world {
        begin(false); location.directory.deleteRecursively(); write(location.directory, "other")
        assertTrue(runCatching { tx.recover(FakeSeed(null)) }.isFailure)
        assertEquals("other", read()); assertTrue(tx.isPending)
    } }
    @Test fun unreadableJournalTouchesNothing() = runBlocking { world {
        begin(); tx.journalFile.writeText("{")
        assertTrue(runCatching { tx.recover(FakeSeed(null)) }.isFailure)
        assertEquals("new", read()); assertEquals("old", read(tx.previous))
    } }
    @Test fun noJournalSweepsPlaintextButPreservesOrphanPrevious() = runBlocking { world {
        write(location.directory, "current"); write(tx.previous, "only copy"); write(tx.work, "plain"); write(tx.staged, "plain")
        tx.recover(FakeSeed(null)); assertEquals("current", read()); assertEquals("only copy", read(tx.previous))
        assertFalse(tx.work.exists()); assertFalse(tx.staged.exists()); assertTrue(runCatching { tx.prepare() }.isFailure)
    } }
    @Test fun pendingBlocksPrepare() = world { begin(); assertTrue(runCatching { tx.prepare() }.isFailure) }
    @Test fun finishCanResumeAfterMarkerAndPreviousDeleted() = runBlocking { world {
        begin(); tx.markCommitting(); File(location.directory, RestoreTransaction.markerName).delete(); tx.previous.deleteRecursively()
        tx.recover(FakeSeed("new seed")); assertEquals("new", read()); settled()
    } }
    @Test fun undoCanResumeAfterOriginalAlreadyRestored() = runBlocking { world {
        begin(); location.directory.renameTo(tx.discard); tx.previous.renameTo(location.directory)
        tx.recover(FakeSeed("old seed")); assertEquals("old", read()); settled()
    } }
    @Test fun everyInstallationSyncFailurePreservesRecoverableOriginal() = runBlocking {
        for (boundary in 1..12) world {
            write(location.directory, "old"); tx.prepare(); write(tx.staged, "new")
            var calls = 0
            val failing = transaction(location) { if (++calls == boundary) error("Disk sync failed") }
            runCatching { failing.begin("new seed") }
            tx.recover(FakeSeed("old seed")); assertEquals("old", read())
        }
    }
}
