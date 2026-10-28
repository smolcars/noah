package com.noahwallet.services.wallet

import com.noahwallet.services.backup.*
import com.noahwallet.services.storage.*
import com.noahwallet.ui.RestoreCompletion
import com.noahwallet.ui.RestoreCompletionJournal
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
        var seedValue = "wallet A"
        var seedUnreadable = false
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
        fun lifecycle(session: WalletSession = this.session, drainTimeoutMillis: Long = 30_000) = WalletLifecycle(session, location,
            setSuspended = { suspended = it; events.add("flag:$it") },
            deregister = { events.add("deregister"); if (deregisterFails) error("Offline") },
            readSeed = { if (seedUnreadable) SecretResult.Unavailable(SecretFailure.storageUnreadable) else if (seed)
                SecretResult.Found(seedValue) else SecretResult.NotFound },
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
            syncIdentityDirectory = {}, drainTimeoutMillis = drainTimeoutMillis,
            syncFiles = {
                if (syncFails) error("Directory sync failed")
                events.add("sync")
            })
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

    @Test fun explicitResumeRetriesSettingsAndReceiptBeforeReleasingAutomation() = runBlocking {
        for (failureAt in listOf("none", "settings", "receipt", "receiptRetained")) {
            val world = World()
            try {
                world.files()
                val tx = RestoreTransaction(world.location)
                tx.settingsCompletion.writeText("")
                val receipt = RestoreCompletionJournal(world.location) {}
                receipt.write(WalletRestorer.Outcome("restored-backup", "snapshot-hash", true))
                world.lifecycle().suspend()
                var metadata: Pair<String, String?>? = null
                var tokenCleared = false
                val completion = RestoreCompletion(loadState = { PersistedWalletState(isWalletSuspended = true) },
                    saveState = { assertTrue(it.isInitialized == true); assertTrue(it.isWalletSuspended == true) },
                    seedRestored = { hash, id ->
                        if (failureAt == "receipt") error("Settings write failed")
                        metadata = hash to id
                    }, recover = { RestoreTransaction.Recovery.nothingPending }, openWallet = {},
                    journal = receipt, clearToken = { tokenCleared = true })
                val result = runCatching {
                    world.lifecycle().resume {
                        world.events.add("retry")
                        if (failureAt == "settings") error("Settings write failed")
                        check(tx.settingsCompletion.delete())
                        if (failureAt != "receiptRetained") completion.replay(RestoreTransaction.Recovery.nothingPending, true)
                    }
                }
                val authority = com.noahwallet.recurring.RecurringAutomationAuthority(world.location.directory) { file, bytes ->
                    com.noahwallet.recurring.durableRecurringWrite(file, bytes) {}
                }
                assertTrue(world.events.contains("retry"))
                if (failureAt == "none") {
                    result.getOrThrow()
                    assertFalse(world.suspended); assertFalse(tx.settingsPending); assertFalse(receipt.pending)
                    assertTrue(tokenCleared); assertEquals("snapshot-hash" to "restored-backup", metadata)
                    assertTrue(authority.allowed())
                    assertTrue(world.events.indexOf("retry") < world.events.indexOf("flag:false"))
                } else {
                    assertTrue(result.isFailure)
                    assertTrue(result.exceptionOrNull()!!.message!!.contains("settings can't be saved yet"))
                    assertTrue(world.suspended); assertTrue(receipt.pending)
                    assertFalse(authority.allowed()); assertFalse(world.session.isOpen)
                }
            } finally { world.clean() }
        }
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
            assertEquals(listOf("flag:true", "scratch", "deregister", "seed", "token", "settings", "widget"), world.events.filter { it != "sync" })
            assertTrue(world.events.indexOf("sync") < world.events.indexOf("seed"))
            assertFalse(world.seed); assertFalse(world.token); assertFalse(world.settings); assertFalse(world.widget)
            val hold = world.session.reserve(); world.session.release(hold)
        } finally { world.clean() }
    }

    @Test fun deletionClearsRecurringLedgerAndBootstrapMarkerForRecreation() = runBlocking {
        val world = World()
        try {
            world.files()
            val binding = com.noahwallet.recurring.RecurringStore.Binding(com.noahwallet.send.SendIdentity("wallet", "regtest"),
                java.util.UUID.randomUUID().toString())
            val ledger = File(world.location.directory, "noah-recurring-journal.json.policies")
            var raw: String? = null
            fun store(owner: com.noahwallet.recurring.RecurringStore.Binding) =
                com.noahwallet.recurring.RecurringStore({ raw }, { raw = it }).also { it.protectPolicies(ledger, sync = {}) { owner } }
            store(binding).load()
            assertTrue(ledger.exists()); assertTrue(File("${ledger.path}.initialized").exists())
            world.lifecycle().delete()
            assertFalse(ledger.exists()); assertFalse(File("${ledger.path}.initialized").exists())
            raw = null; world.location.directory.mkdirs()
            val replacement = binding.copy(instance = java.util.UUID.randomUUID().toString())
            assertTrue(store(replacement).load().schedules.isEmpty())
            assertTrue(ledger.readText().contains(replacement.instance))
            assertFalse(ledger.readText().contains(binding.instance))
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
            world.files()
            world.location.writeDeletionIdentity(SecretResult.Found(world.seedValue)) {}
            world.syncFails = true
            assertTrue(runCatching { world.lifecycle().delete() }.isFailure)
            assertTrue(world.seed); assertTrue(world.token); assertTrue(world.settings)
            assertTrue(world.location.directory.exists() || world.location.deletionDirectory.exists())
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
                world.location.writeDeletionIdentity(SecretResult.Found(world.seedValue)) {}
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
                assertEquals(events.filter { it != "sync" }, world.events.filter { it != "sync" })
            } finally { world.clean() }
        }
    }

    @Test fun exportDirectorySyncFailuresKeepDatabaseAndSeedUntilRetry() = runBlocking {
        for (history in listOf(false, true)) {
            val world = World()
            try {
                world.files()
                val exports = File(world.root, "exports").apply { mkdirs() }
                File(exports, "export").writeText("wallet data")
                var failSync = true
                val sync: (File) -> Unit = {
                    assertTrue(it.listFiles()!!.isEmpty())
                    if (failSync) error("Directory sync failed")
                }
                val settings = com.noahwallet.services.sharing.SettingsShareFiles(exports, {}, sync)
                val transactions = com.noahwallet.history.HistoryExportFiles(exports, {}, sync)
                world.sweepScratch = { if (history) transactions.deleteAll() else settings.deleteAll() }
                assertTrue(runCatching { world.lifecycle().delete() }.exceptionOrNull() is WalletLifecycle.TemporaryFiles)
                assertTrue(world.location.database.exists()); assertTrue(world.seed); assertTrue(world.settings)
                assertFalse(world.suspended); assertFalse(world.location.deletionMarker.exists())
                failSync = false
                world.lifecycle().delete()
                assertFalse(world.seed)
            } finally { world.clean() }
        }
    }

    @Test fun timedOutExportKeepsWalletIntactAndCanRetry() = runBlocking {
        val world = World()
        val scratch = com.noahwallet.services.backup.ScratchSpace(File(world.root, "scratch")) {}
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val export = async { scratch.withFolder("export") { folder ->
            File(folder, "snapshot.sqlite").writeText("plaintext")
            entered.complete(Unit); release.await()
        } }
        try {
            world.files(); entered.await()
            world.settleBackground = { scratch.drainForWalletDeletion(20) }
            world.finishBackground = scratch::finishWalletDeletion
            world.sweepScratch = scratch::sweepForWalletDeletion
            assertTrue(runCatching { world.lifecycle().delete() }.exceptionOrNull() is WalletLifecycle.BackupBusy)
            assertTrue(world.location.database.exists()); assertTrue(world.seed); assertTrue(world.settings)
            assertFalse(world.suspended); assertFalse(world.location.deletionMarker.exists())
            scratch.withFolder("retry-export") {}
            release.complete(Unit); export.await()
            world.lifecycle().delete()
            assertFalse(world.seed)
        } finally { release.complete(Unit); export.await(); world.clean() }
    }

    @Test fun timedOutBackgroundSettlementDoesNotCommitDeletion() = runBlocking {
        val world = World()
        try {
            world.files()
            world.settleBackground = { awaitCancellation() }
            assertTrue(runCatching { world.lifecycle(drainTimeoutMillis = 20).delete() }.exceptionOrNull() is WalletLifecycle.BackupBusy)
            assertTrue(world.location.database.exists()); assertTrue(world.seed); assertTrue(world.settings)
            assertFalse(world.suspended); assertFalse(world.location.deletionMarker.exists())
            world.settleBackground = {}
            world.lifecycle().delete(); assertFalse(world.seed)
        } finally { world.clean() }
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
            world.location.writeDeletionIdentity(SecretResult.Found(world.seedValue)) {}
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

    @Test fun absentDatadirRequiresSeedBoundEvidence() = runBlocking {
        for (marker in listOf("bound", "uuid", "one-byte", "invalid")) {
            for (current in listOf("same", "different", "none", "unreadable")) {
                val world = World()
                try {
                    when (marker) {
                        "bound" -> world.location.writeDeletionIdentity(SecretResult.Found("wallet A")) {}
                        "uuid" -> world.location.deletionMarker.writeText(java.util.UUID.randomUUID().toString())
                        "one-byte" -> world.location.deletionMarker.writeBytes(byteArrayOf(1))
                        else -> world.location.deletionMarker.writeText("invalid")
                    }
                    world.seed = current != "none"
                    world.seedValue = if (current == "different") "wallet B" else "wallet A"
                    world.seedUnreadable = current == "unreadable"
                    val before = world.seed
                    val result = world.lifecycle().recoverDeletion()
                    val waiting = marker == "bound" && current == "unreadable"
                    assertEquals(if (waiting) RestoreTransaction.Recovery.waitingForSecret else RestoreTransaction.Recovery.nothingPending, result)
                    val finishes = marker == "bound" && (current == "same" || current == "none")
                    assertEquals(if (finishes) false else before, world.seed)
                    assertEquals(!finishes, world.token); assertEquals(!finishes, world.settings)
                    assertFalse(world.location.directory.exists()); assertEquals(waiting, world.location.deletionMarker.exists())
                    if (!finishes) assertFalse(world.events.contains("flag:true"))
                } finally { world.clean() }
            }
        }
    }

    @Test fun startupSettlesJournalBeforeMatchingMarkerThenReplaysAndOpensUnderOneGate() = runBlocking {
        for (phase in listOf("installing", "rollback", "committed")) {
            val world = World()
            try {
                world.files(); world.location.database.writeText("wallet A data")
                val tx = RestoreTransaction(world.location, sync = {}, rename = { from, to -> check(from.renameTo(to)) }, stopAutomation = {})
                tx.prepare(); check(tx.staged.mkdir()); File(tx.staged, "db.sqlite").writeText("wallet B data")
                File(tx.work, "snapshot.sqlite").writeText("plaintext")
                tx.begin("wallet B")
                if (phase != "installing") tx.markCommitting()
                if (phase == "committed") world.seedValue = "wallet B"
                val secret = object : SecretItem {
                    override suspend fun read() = SecretResult.Found(world.seedValue)
                    override suspend fun save(value: String) = error("Recovery must not write a seed")
                }
                // Matching both live instance and current seed used to enter deletion and throw
                // RestorePending forever, before the journal got a chance to settle.
                world.location.writeDeletionIdentity(secret.read()) {}
                val journal = RestoreCompletionJournal(world.location, sync = {})
                journal.write(WalletRestorer.Outcome("backup", "hash"))
                var initialized = false
                val lifecycle = world.lifecycle()
                val recovery = RestoreRecovery {
                    WalletRestorer.recoverInterruptedRestore(world.session, world.location, secret, tx)
                }
                // UI startup holds the gate through recovery, completion replay and opening;
                // headless recovery acquires the gate before RestoreRecovery too.
                val completionEntered = CompletableDeferred<Unit>(); val releaseCompletion = CompletableDeferred<Unit>()
                val first = async {
                    lifecycle.withWalletMutation {
                        val result = lifecycle.recoverRestoreAndDeletion(walletMutationHeld = true) { recovery.settle() }
                        RestoreCompletion(
                            loadState = { PersistedWalletState() },
                            saveState = { initialized = it.isInitialized == true },
                            seedRestored = { _, _ -> },
                            recover = { lifecycle.recoverRestoreAndDeletion(walletMutationHeld = true) { recovery.settle() } },
                            openWallet = {}, journal = journal,
                        ).replay(result, world.location.databaseExists)
                        assertFalse(journal.pending)
                        assertEquals(phase == "committed", initialized)
                        completionEntered.complete(Unit); releaseCompletion.await()
                        assertTrue(world.location.databaseExists)
                        world.session.release(world.session.reserve()) // Opening may reserve only after recovery.
                        result
                    }
                }
                completionEntered.await()
                val headless = async { lifecycle.recoverRestoreAndDeletion { recovery.settle() } }
                yield(); assertFalse(headless.isCompleted)
                releaseCompletion.complete(Unit)
                assertEquals(if (phase == "committed") RestoreTransaction.Recovery.completed else RestoreTransaction.Recovery.rolledBack, first.await())
                assertEquals(RestoreTransaction.Recovery.nothingPending, headless.await())
                assertEquals(if (phase == "committed") "wallet B data" else "wallet A data", world.location.database.readText())
                assertTrue(world.seed); assertTrue(world.token); assertTrue(world.settings)
                assertFalse(world.suspended); assertFalse(world.events.contains("scratch"))
                assertFalse(world.location.restorePending); assertFalse(world.location.deletionMarker.exists())
                assertFalse(tx.previous.exists()); assertFalse(tx.work.exists())
                world.session.release(world.session.reserve())
                lifecycle.delete(); assertFalse(world.seed) // Still explicitly deletable.
            } finally { world.clean() }
        }
    }

    @Test fun restoreWaitingForSecretRetainsMarkerUntilUnlock() = runBlocking {
        val world = World()
        try {
            world.files()
            val tx = RestoreTransaction(world.location, sync = {}, rename = { from, to -> check(from.renameTo(to)) }, stopAutomation = {})
            tx.prepare(); check(tx.staged.mkdir()); File(tx.staged, "db.sqlite").writeText("wallet B data")
            tx.begin("wallet B"); tx.markCommitting()
            world.location.writeDeletionIdentity(SecretResult.Found("wallet B")) {}
            world.seedUnreadable = true
            val secret = object : SecretItem {
                override suspend fun read(): SecretResult = if (world.seedUnreadable)
                    SecretResult.Unavailable(SecretFailure.storageUnreadable) else SecretResult.Found("wallet B")
                override suspend fun save(value: String) = error("Recovery must not write a seed")
            }
            val lifecycle = world.lifecycle()
            val recovery = RestoreRecovery {
                WalletRestorer.recoverInterruptedRestore(world.session, world.location, secret, tx)
            }
            suspend fun recover() = lifecycle.recoverRestoreAndDeletion { recovery.settle() }
            assertEquals(RestoreTransaction.Recovery.waitingForSecret, recover())
            assertTrue(world.location.restorePending); assertTrue(world.location.deletionMarker.exists())
            assertTrue(world.seed); assertTrue(world.events.isEmpty())
            world.seedUnreadable = false; world.seedValue = "wallet B"
            assertEquals(RestoreTransaction.Recovery.completed, recover())
            assertFalse(world.location.restorePending); assertFalse(world.location.deletionMarker.exists())
            assertEquals("wallet B data", world.location.database.readText()); assertTrue(world.seed)
        } finally { world.clean() }
    }

    @Test fun interruptionAfterRollbackCannotReauthorizeStaleDeletion() = runBlocking {
        val world = World()
        try {
            world.files()
            val tx = RestoreTransaction(world.location, sync = {}, rename = { from, to -> check(from.renameTo(to)) }, stopAutomation = {})
            tx.prepare(); check(tx.staged.mkdir()); File(tx.staged, "db.sqlite").writeText("wallet B data")
            tx.begin("wallet B")
            world.location.writeDeletionIdentity(SecretResult.Found(world.seedValue)) {}
            val secret = object : SecretItem {
                override suspend fun read() = SecretResult.Found(world.seedValue)
                override suspend fun save(value: String) = error("Recovery must not write a seed")
            }
            val lifecycle = world.lifecycle()
            assertTrue(runCatching { lifecycle.recoverRestoreAndDeletion {
                assertFalse(world.location.deletionMarker.exists())
                assertTrue(world.events.contains("sync"))
                assertEquals(RestoreTransaction.Recovery.rolledBack,
                    WalletRestorer.recoverInterruptedRestore(world.session, world.location, secret, tx))
                error("Interrupted after journal removal")
            } }.isFailure)
            val recovery = RestoreRecovery {
                WalletRestorer.recoverInterruptedRestore(world.session, world.location, secret, tx)
            }
            suspend fun recover() = lifecycle.recoverRestoreAndDeletion { recovery.settle() }
            assertEquals(RestoreTransaction.Recovery.nothingPending, recover())
            assertTrue(world.seed); assertTrue(world.location.database.exists()); assertTrue(world.settings)
            assertFalse(world.location.deletionMarker.exists())
        } finally { world.clean() }
    }

    @Test fun restoreWaitsForDeletionDrainThenHoldsGateThroughCompletion() = runBlocking {
        val world = World()
        val drainEntered = CompletableDeferred<Unit>(); val releaseDrain = CompletableDeferred<Unit>()
        val restoreCommitted = CompletableDeferred<Unit>(); val finishCompletion = CompletableDeferred<Unit>()
        try {
            world.files()
            world.settleBackground = { drainEntered.complete(Unit); releaseDrain.await() }
            val lifecycle = world.lifecycle()
            val deletion = async { lifecycle.delete() }
            drainEntered.await()
            var restoreEntered = false
            val restoration = async {
                lifecycle.withWalletMutation {
                    restoreEntered = true
                    assertFalse(world.location.databaseExists)
                    val reservation = world.session.reserve()
                    try { world.files() } finally { world.session.release(reservation) }
                    restoreCommitted.complete(Unit); finishCompletion.await()
                    assertTrue("Completion keeps its installed wallet", world.location.databaseExists)
                }
            }
            yield(); assertFalse(restoreEntered)
            releaseDrain.complete(Unit); deletion.await(); restoreCommitted.await()
            var competingMutationEntered = false
            val competing = async { lifecycle.withWalletMutation { competingMutationEntered = true } }
            yield(); assertFalse(competingMutationEntered)
            finishCompletion.complete(Unit); restoration.await(); competing.await()
            assertTrue(competingMutationEntered)
        } finally { releaseDrain.complete(Unit); finishCompletion.complete(Unit); world.clean() }
    }

    @Test fun lockedDeletionRetriesWithoutLosingStagingThenAllowsRestoreAndDelete() = runBlocking {
        for (stage in 0..3) for (sameSeed in listOf(true, false)) {
            val world = World()
            try {
                world.files()
                world.location.writeDeletionIdentity(SecretResult.Found(world.seedValue)) {}
                if (stage >= 1) check(world.location.directory.renameTo(world.location.deletionDirectory))
                if (stage >= 2) check(File(world.location.deletionDirectory, ".noah-wallet-instance").delete())
                if (stage >= 3) check(world.location.deletionDirectory.deleteRecursively())
                world.seedUnreadable = true
                val lifecycle = world.lifecycle()
                assertEquals(RestoreTransaction.Recovery.waitingForSecret,
                    lifecycle.recoverRestoreAndDeletion { RestoreTransaction.Recovery.nothingPending })
                assertTrue(world.location.deletionMarker.exists())
                assertEquals(stage in 1..2, world.location.deletionDirectory.exists())
                assertTrue(world.seed); assertTrue(world.token); assertTrue(world.settings)
                assertTrue(runCatching { world.location.removeDiscardedDeletionData {} }.isFailure)
                assertTrue(runCatching { lifecycle.delete() }.exceptionOrNull() is WalletLifecycle.WaitingForSecureStorage)
                world.seedUnreadable = false
                if (!sameSeed) world.seedValue = "wallet B"
                lifecycle.recoverRestoreAndDeletion { RestoreTransaction.Recovery.nothingPending }
                assertFalse(world.location.deletionMarker.exists()); assertFalse(world.location.deletionDirectory.exists())
                assertEquals(!sameSeed, world.seed)
                // Recreate the datadir as restore does; its next deletion must never hit old staging.
                world.seed = true; world.files()
                lifecycle.delete()
                assertFalse(world.seed); assertFalse(world.location.directory.exists())
            } finally { world.clean() }
        }
    }

    @Test fun discardedStagingIsCheckedAndSyncedBeforeRestoreOrNewMarker() = runBlocking {
        for (next in listOf("recover", "restore", "delete", "old-marker")) {
            val world = World()
            try {
                world.files()
                if (next == "old-marker") world.location.writeDeletionIdentity(SecretResult.Found(world.seedValue)) {}
                check(world.location.directory.renameTo(world.location.deletionDirectory))
                // A previous launch already discarded the marker, but died before staging cleanup.
                if (next == "restore") {
                    val syncs = mutableListOf<File>()
                    world.location.removeDiscardedDeletionData { syncs.add(it) }
                    assertEquals(listOf(world.root), syncs)
                    assertTrue(world.seed)
                    world.files()
                } else {
                    world.files()
                    if (next == "old-marker") world.location.writeDeletionIdentity(SecretResult.Found(world.seedValue)) {}
                    if (next == "recover") {
                        world.lifecycle().recoverDeletion()
                        assertTrue(world.location.databaseExists); assertTrue(world.seed)
                    } else world.lifecycle().delete()
                }
                assertFalse(world.location.deletionDirectory.exists())
            } finally { world.clean() }
        }
        val world = World()
        try {
            world.files(); check(world.location.directory.renameTo(world.location.deletionDirectory))
            world.files(); world.syncFails = true
            assertTrue(runCatching { world.lifecycle().delete() }.isFailure)
            assertFalse(world.location.deletionMarker.exists()) // Cleanup sync precedes new consent evidence.
            assertTrue(world.location.databaseExists); assertTrue(world.seed)
            world.syncFails = false; world.lifecycle().delete(); assertFalse(world.seed)
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
