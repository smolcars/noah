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
            sweepScratch = { events.add("scratch") },
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
            assertEquals(listOf("flag:true", "deregister", "scratch", "sync", "seed", "token", "settings", "widget"), world.events)
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
            assertFalse(world.location.directory.exists())
            val hold = world.session.reserve(); world.session.release(hold)
        } finally { world.clean() }
    }

    @Test fun failedSeedDeletionKeepsTokenAndSettings() = runBlocking {
        val world = World()
        try {
            world.files(); world.seedFails = true
            assertTrue(runCatching { world.lifecycle().delete() }.isFailure)
            assertTrue(world.seed); assertTrue(world.token); assertTrue(world.settings)
            val hold = world.session.reserve(); world.session.release(hold)
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
