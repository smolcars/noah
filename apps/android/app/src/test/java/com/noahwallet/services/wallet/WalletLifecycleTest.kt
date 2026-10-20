package com.noahwallet.services.wallet

import com.noahwallet.services.storage.WalletDataLocation
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class WalletLifecycleTest {
    private class World {
        val root = Files.createTempDirectory("lifecycle").toFile()
        val location = WalletDataLocation(root, "regtest")
        var suspended = false
        var seed = true
        var token = true
        var settings = true
        var widget = true
        var deregisterFails = false
        var seedFails = false
        var syncFails = false
        var settleBackground: suspend () -> Unit = {}
        var finishBackground: () -> Unit = {}
        var sweepScratch: () -> Unit = { events.add("scratch") }
        val events = mutableListOf<String>()
        val session = WalletSession("regtest", location, inspect = { WalletDatabase.Properties("regtest", "abcd") },
            isSuspended = { suspended }, opener = { _, _, _, _, _ -> error("Open refused") })
        fun lifecycle(session: WalletSession = this.session) = WalletLifecycle(session, location,
            setSuspended = { suspended = it; events.add("flag:$it") },
            deregister = { events.add("deregister"); if (deregisterFails) error("Offline") },
            deleteSeed = {
                assertFalse(location.directory.exists()); assertFalse(location.restoreDirectory.exists())
                assertTrue(runCatching { session.reserve() }.isFailure)
                assertTrue(events.contains("sync"))
                if (seedFails) error("Seed delete failed")
                seed = false; events.add("seed")
            }, clearToken = { assertFalse(seed); token = false; events.add("token") },
            clearSettings = { assertFalse(token); settings = false; events.add("settings") },
            clearWidget = { assertFalse(settings); widget = false; events.add("widget") },
            sweepScratch = { assertTrue(location.directory.exists() || location.deletionMarker.exists()); sweepScratch() },
            isSuspended = { suspended },
            settleBackground = { settleBackground() },
            finishBackground = { finishBackground() },
            syncIdentityDirectory = {},
            syncFiles = { if (syncFails) error("Directory sync failed"); events.add("sync") })
        fun files() { location.directory.mkdirs(); location.database.writeText("fake"); location.restoreDirectory.mkdirs() }
        fun clean() { root.deleteRecursively() }
    }

    @Test fun suspensionPersistsBeforeWaitingForOpenAndResumeClears() = runBlocking {
        val world = World()
        try {
            world.files()
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val session = WalletSession("regtest", world.location, inspect = { WalletDatabase.Properties("regtest", "abcd") },
                opener = { _, _, _, _, _ -> entered.complete(Unit); release.await(); error("Open failed") })
            val opening = async { runCatching { session.ensureOpen("unused", false, "test") } }
            entered.await()
            val closing = async { world.lifecycle(session).suspend() }
            withTimeout(2000) { while (!world.suspended) yield() }
            assertFalse(closing.isCompleted)
            release.complete(Unit); opening.await(); closing.await()
            assertFalse(session.isOpen)
            world.lifecycle(session).resume(); assertFalse(world.suspended)
        } finally { world.clean() }
    }

    @Test fun failedSuspendRollsBackFlag() = runBlocking {
        val world = World()
        try {
            val hold = world.session.reserve()
            assertTrue(runCatching { world.lifecycle().suspend() }.isFailure)
            assertFalse(world.suspended)
            world.session.release(hold)
        } finally { world.clean() }
    }

    @Test fun deleteFilesBeforeSeedAndStateDespiteServerFailure() = runBlocking {
        val world = World()
        try {
            world.files(); world.deregisterFails = true
            world.lifecycle().delete()
            assertEquals(listOf("flag:true", "scratch", "sync", "deregister", "sync", "sync", "seed", "token", "settings", "widget", "sync"), world.events)
            assertFalse(world.seed); assertFalse(world.token); assertFalse(world.settings); assertFalse(world.widget)
            val hold = world.session.reserve(); world.session.release(hold)
        } finally { world.clean() }
    }

    @Test fun restoreJournalRefusesDeletionAndReleasesReservation() = runBlocking {
        val world = World()
        try {
            world.files(); File(world.location.restoreDirectory, "journal.json").writeText("{}")
            assertTrue(runCatching { world.lifecycle().delete() }.exceptionOrNull() is WalletLifecycle.RestorePending)
            assertTrue(world.location.database.exists()); assertTrue(world.seed); assertTrue(world.token); assertTrue(world.settings)
            assertFalse(world.events.contains("scratch"))
            val hold = world.session.reserve(); world.session.release(hold)
        } finally { world.clean() }
    }

    @Test fun failedFileSyncPreservesSeedAndAllLocalState() = runBlocking {
        val world = World()
        try {
            world.files(); world.syncFails = true
            assertTrue(runCatching { world.lifecycle().delete() }.isFailure)
            assertTrue(world.seed); assertTrue(world.token); assertTrue(world.settings)
            assertTrue(world.location.database.exists())
            assertTrue(world.location.deletionMarker.exists())
            world.syncFails = false
            world.lifecycle().recoverDeletion()
            assertFalse(world.seed); assertFalse(world.location.deletionMarker.exists())
            val hold = world.session.reserve(); world.session.release(hold)
        } finally { world.clean() }
    }

    @Test fun undeletablePlaintextPreservesSeedAndRetrySyncsScratchDirectories() = runBlocking {
        val world = World()
        val scratchRoot = File(world.root, "scratch").apply { mkdirs() }
        val folder = File(scratchRoot, "abandoned").apply { mkdir() }
        val snapshot = File(folder, "snapshot.sqlite").apply { writeText("plaintext") }
        val synced = mutableListOf<File>()
        val scratch = com.noahwallet.services.backup.ScratchSpace(scratchRoot) { synced.add(it) }
        try {
            world.files(); world.sweepScratch = scratch::sweepForWalletDeletion
            check(folder.setWritable(false, false))
            assertTrue(runCatching { world.lifecycle().delete() }.isFailure)
            assertTrue(snapshot.exists()); assertTrue(world.seed); assertTrue(world.token); assertTrue(world.settings)
            assertTrue(world.location.database.exists()); assertFalse(world.suspended)
            assertFalse(world.location.deletionMarker.exists())
            assertTrue(synced.isEmpty())
            check(folder.setWritable(true, true))
            world.lifecycle().delete()
            assertFalse(snapshot.exists()); assertFalse(world.seed)
            assertEquals(listOf(scratchRoot, world.root), synced)
        } finally { folder.setWritable(true, true); world.clean() }
    }

    @Test fun failedSeedDeletionKeepsTokenAndSettings() = runBlocking {
        val world = World()
        try {
            world.files(); world.seedFails = true
            assertTrue(runCatching { world.lifecycle().delete() }.isFailure)
            assertTrue(world.seed); assertTrue(world.token); assertTrue(world.settings)
            assertTrue(world.location.deletionMarker.exists())
            world.seedFails = false
            world.lifecycle().recoverDeletion()
            assertFalse(world.seed); assertFalse(world.token); assertFalse(world.settings)
            assertFalse(world.location.deletionMarker.exists())
            val hold = world.session.reserve(); world.session.release(hold)
        } finally { world.clean() }
    }

    @Test fun deletionDrainsExportBeforeScratchAndDatabaseAndRemovesRetainedShare() = runBlocking {
        val world = World()
        val scratch = com.noahwallet.services.backup.ScratchSpace(File(world.root, "scratch")) {}
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        try {
            world.files()
            val retained = scratch.makeFolder("share-backup")
            File(retained, "wallet.noahbackup").writeText("encrypted")
            val export = async {
                scratch.withFolder("export") { folder ->
                    File(folder, "snapshot.sqlite").writeText("plaintext")
                    entered.complete(Unit); release.await()
                    assertTrue(world.location.database.exists())
                    assertTrue(world.seed)
                }
            }
            entered.await()
            world.settleBackground = { scratch.drainForWalletDeletion() }
            world.finishBackground = scratch::finishWalletDeletion
            world.sweepScratch = scratch::sweepForWalletDeletion
            val deleting = async { world.lifecycle().delete() }
            withTimeout(2000) { while (!world.suspended) yield() }
            assertFalse(deleting.isCompleted); assertTrue(world.location.database.exists())
            assertFalse(world.location.deletionMarker.exists())
            withTimeout(2000) {
                while (runCatching { scratch.makeFolder("late-export").also(scratch::discard) }.isSuccess) yield()
            }
            release.complete(Unit); export.await(); deleting.await()
            assertFalse(retained.exists()); assertFalse(world.seed)
        } finally { release.complete(Unit); world.clean() }
    }

    @Test fun relaunchFinishesEveryDestructiveBoundary() = runBlocking {
        for (stage in 0..5) {
            val world = World()
            try {
                world.files()
                world.location.writeDeletionIdentity {}
                if (stage >= 1) check(world.location.directory.renameTo(world.location.deletionDirectory))
                if (stage >= 2) check(File(world.location.deletionDirectory, ".noah-wallet-instance").delete())
                if (stage >= 3) check(world.location.deletionDirectory.deleteRecursively())
                if (stage >= 4) world.seed = false
                if (stage >= 5) { world.token = false; world.settings = false }
                world.lifecycle().recoverDeletion()
                assertFalse(world.seed); assertFalse(world.token); assertFalse(world.settings)
                assertFalse(world.location.directory.exists()); assertFalse(world.location.deletionMarker.exists())
                val events = world.events.toList()
                world.lifecycle().recoverDeletion()
                assertEquals(events, world.events)
            } finally { world.clean() }
        }
    }

    @Test fun differentAndLegacyMarkersNeverDeleteCurrentWallet() = runBlocking {
        val world = World()
        try {
            world.files()
            for (marker in listOf(java.util.UUID.randomUUID().toString().toByteArray(), byteArrayOf(1), "invalid".toByteArray())) {
                world.location.deletionMarker.writeBytes(marker)
                world.lifecycle().recoverDeletion()
                assertTrue(world.location.database.exists()); assertTrue(world.seed)
                assertFalse(world.location.deletionMarker.exists())
                assertFalse(world.events.contains("flag:true"))
            }
            world.location.writeDeletionIdentity {}
            world.location.deletionMarker.writeText(java.util.UUID.randomUUID().toString())
            world.lifecycle().recoverDeletion()
            assertTrue(world.location.database.exists()); assertTrue(world.seed)
            world.location.database.delete()
            world.location.deletionMarker.writeBytes(byteArrayOf(1))
            world.lifecycle().recoverDeletion()
            assertTrue(world.seed)
            world.lifecycle().delete()
            assertFalse(world.seed)
        } finally { world.clean() }
    }

    @Test fun legacyMarkerFinishesOnlyWithNoWalletDirectory() = runBlocking {
        val world = World()
        try {
            world.location.deletionMarker.writeBytes(byteArrayOf(1))
            world.lifecycle().recoverDeletion()
            assertFalse(world.seed); assertFalse(world.location.deletionMarker.exists())
        } finally { world.clean() }
    }

    @Test fun suspendedSessionRejectsDelayedOpen() = runBlocking {
        val world = World()
        try {
            world.files(); world.suspended = true
            val failure = runCatching { world.session.ensureOpen("unused", false, "test") }.exceptionOrNull()
            assertEquals(WalletSession.OpenFailure.suspended, (failure as WalletSession.OpenError).reason)
        } finally { world.clean() }
    }
}
