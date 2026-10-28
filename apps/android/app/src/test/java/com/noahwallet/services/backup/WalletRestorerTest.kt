package com.noahwallet.services.backup

import com.noahwallet.services.server.ServerClient
import com.noahwallet.services.storage.*
import com.noahwallet.services.testRounds
import com.noahwallet.services.wallet.*
import io.github.niteshbalusu11.bark.*
import io.ktor.client.engine.mock.*
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class WalletRestorerTest {
    companion object {
        private val validSeed = List(11) { StoredMnemonic.wordList[0] }.plus(StoredMnemonic.wordList[3]).joinToString(" ")
    }
    private class Boom : Exception()
    private class Handles : WalletSession.Handles {
        override fun notifications(): WalletSession.Notifications = error("unused")
        override suspend fun history(): List<Movement> = error("unused")
        override suspend fun receiveRequest(): ReceiveRequest = error("unused")
        override suspend fun receiveInvoice(request: ReceiveRequest, amountSat: Long, description: String): ReceiveRequest = error("unused")
        override suspend fun initializeAddress() = Unit
        override suspend fun peekPublicKey(index: UInt): String = error("unused")
        override suspend fun peekAddress(index: UInt): String = error("unused")
        override suspend fun summary(): WalletSession.Summary = error("unused")
        override suspend fun sync() = Unit
        override suspend fun maintenanceDelegated() = Unit
        override suspend fun bolt11Invoice(amountSats: ULong): String = error("unused")
        override suspend fun claimLightningReceives() = Unit
        override suspend fun createSnapshot(destination: File): WalletSnapshotInfo = error("unused")
        var stopFails = false
        override suspend fun stopDaemonWait() { if (stopFails) throw Boom() }
        override fun close() = Unit
    }
    private class World {
        val root = Files.createTempDirectory("restorer").toFile()
        val location = WalletDataLocation(File(root, "device-b"), "regtest")
        val seed = FakeSeed("old seed")
        val handles = Handles()
        var initialized: Boolean? = null
        var markerFails = false
        var openerFails = false
        var validationFails = false
        var reportedSize: Long? = null
        var reportedHash: String? = null
        var network = "regtest"
        var legacy = false
        var newestCorrupt = false
        var listFails = false
        var verifyGate: CompletableDeferred<Unit>? = null
        val entered = CompletableDeferred<Unit>()
        val session = WalletSession("regtest", location, inspect = { WalletDatabase.Properties("regtest", "abcd") },
            opener = { _, _, daemon, _, create ->
                assertFalse(daemon); assertFalse(create); entered.complete(Unit); verifyGate?.await()
                if (openerFails) throw Boom()
                handles
            })
        val tx = transaction(location)
        init { location.directory.mkdirs(); location.database.writeText("old wallet") }
        fun restorer(transaction: RestoreTransaction = tx): WalletRestorer {
            val source = File(root, "snapshot").apply { writeText("new wallet") }
            val encrypted = File(root, "backup")
            if (!encrypted.exists()) BackupArchive.seal(source,
                BackupArchive.Manifest(2, "x", "sha", network, "abcd", null, null, 42), validSeed, encrypted, root, testRounds) { complete, target ->
                Files.createLink(target.toPath(), complete.toPath())
            }
            val legacyFile = File(root, "legacy")
            if (legacy) writeLegacy(legacyFile)
            val info =
                """{"backup_id":"b1","created_at":"x","encrypted_sha256":"${reportedHash ?: BackupArchive.sha256(encrypted)}",""" +
                    """"encrypted_size":${reportedSize ?: encrypted.length()},"format_version":2}"""
            return WalletRestorer("regtest", session, location, seed,
                makeClient = { ServerClient.make("http://fake", engine = MockEngine { request -> serve(request, info, legacyFile) }) },
                transaction = transaction,
                validate = { _, _ -> if (validationFails) throw Boom() },
                download = { url, destination, limit -> download(if (url == "http://legacy") legacyFile else encrypted, url, destination, limit) },
                beginOnboarding = {
                    if (markerFails) throw Boom()
                    initialized = false
                }, rounds = testRounds)
        }
        private fun writeLegacy(legacyFile: File) {
            val bytes = java.io.ByteArrayOutputStream()
            java.util.zip.ZipOutputStream(bytes).use { zip ->
                zip.putNextEntry(java.util.zip.ZipEntry("backup_staging/noah-data-regtest/db.sqlite"))
                zip.write("new wallet".toByteArray()); zip.closeEntry()
            }
            legacyFile.writeBytes(java.util.Base64.getEncoder().encode(BackupCrypto.encrypt(bytes.toByteArray(), validSeed,
                BackupCrypto.Version.legacyDirectory, testRounds)))
        }
        private fun MockRequestHandleScope.serve(request: HttpRequestData, info: String, legacyFile: File): HttpResponseData {
            val body = when (request.url.encodedPath) {
                "/v0/backup/v2/list" -> {
                    if (listFails) return respond("{}", HttpStatusCode.InternalServerError)
                    if (legacy) "[]" else if (newestCorrupt) "[${info.replace("b1", "bad")},$info]" else "[$info]"
                }
                "/v0/backup/v2/download" -> {
                    val bodyText = (request.body as io.ktor.http.content.TextContent).text
                    val url = if (bodyText.contains("bad")) "http://bad" else "http://download"
                    """{"backup":$info,"download_url":"$url"}"""
                }
                "/v0/backup/download_url" -> """{"backup_size":${legacyFile.length()},"download_url":"http://legacy"}"""
                else -> error("Unexpected request")
            }
            return respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        private fun download(file: File, url: String, destination: File, limit: Long) {
            if (file.length() > limit || url == "http://bad") throw WalletRestorer.Failure(WalletRestorer.Reason.downloadMismatch)
            file.copyTo(destination)
        }
        fun previous() {
            assertEquals("old wallet", location.database.readText()); assertTrue("Previous seed differs", seed.value == "old seed")
            assertTrue(location.restoreDirectory.listFiles().orEmpty().isEmpty())
        }
    }
    @Test fun failedOnboardingMarkerPreservesWalletAndAllowsRestoreRetry() = runBlocking { world {
        markerFails = true
        assertTrue(runCatching { restorer().restore(validSeed) }.isFailure)
        previous(); assertNull(initialized)
        markerFails = false
        restorer().restore(validSeed)
        assertEquals(false, initialized)
        assertTrue(seed.value == validSeed); assertEquals("new wallet", location.database.readText())
    } }
    @Test fun restorePersistsUnfinishedOnboardingBeforeInstallingBackupOrPhrase() = runBlocking { world {
        var recorded: WalletRestorer.Outcome? = null
        val result = restorer().restore(validSeed, beforeInstall = {
            assertEquals(false, initialized)
            assertEquals("old wallet", location.database.readText())
            assertTrue(seed.value == "old seed")
            recorded = it
        }, progress = { step ->
            if (step == WalletRestorer.Step.loading || step == WalletRestorer.Step.finalizing) {
                assertEquals(false, initialized)
            }
        })
        // The completion receipt, written before installing, is the outcome the restore returns.
        assertEquals(result, recorded)
        // A process ending before RestoreCompletion sees an explicit unfinished marker.
        assertEquals(false, initialized)
    } }
    private suspend fun world(block: suspend World.() -> Unit) {
        val world = World()
        // A hung restore fails rather than stalling the suite.
        try { withTimeout(60_000) { world.block() } } finally { world.root.deleteRecursively() }
    }
    @Test fun stagedDeletionUnlockRestoreAndDeleteUsesRealRestorePipeline() = runBlocking {
        for (pending in listOf(true, false)) world {
            seed.value = validSeed
            location.writeDeletionIdentity(seed.read()) {}
            check(location.directory.renameTo(location.deletionDirectory))
            val lifecycle = WalletLifecycle(session, location, setSuspended = {}, deregister = {}, readSeed = seed::read,
                deleteSeed = { seed.value = null }, clearToken = {}, clearSettings = {}, clearWidget = {}, sweepScratch = {},
                syncFiles = {}, syncIdentityDirectory = {})
            seed.unavailable = true
            assertEquals(RestoreTransaction.Recovery.waitingForSecret,
                lifecycle.recoverRestoreAndDeletion { RestoreTransaction.Recovery.nothingPending })
            assertTrue(location.deletionMarker.exists()); assertTrue(location.deletionDirectory.exists())
            seed.unavailable = false
            if (pending) lifecycle.recoverRestoreAndDeletion { RestoreTransaction.Recovery.nothingPending }
            else check(location.deletionMarker.delete()) // Previous app discarded it but left staging and the seed.
            seed.onSave = { assertFalse(location.deletionDirectory.exists()) }
            lifecycle.withWalletMutation { restorer().restore(validSeed) }
            assertEquals("new wallet", location.database.readText()); assertTrue("Saved seed differs", validSeed == seed.value)
            assertFalse(location.deletionDirectory.exists())
            lifecycle.delete()
            assertNull(seed.value); assertFalse(location.directory.exists()); assertFalse(location.deletionMarker.exists())
        }
    }

    @Test fun failedCompletionReceiptLeavesOriginalWalletUntouched() = runBlocking { world {
        assertTrue(runCatching { restorer().restore(validSeed, beforeInstall = { throw Boom() }) }.exceptionOrNull() is Boom)
        previous()
    } }
    @Test fun legacyUsesSameReservedTransactionAndSeedCommit() = runBlocking { world {
        legacy = true
        val outcome = restorer().restore(validSeed)
        assertNull(outcome.backupId); assertNull(outcome.snapshotSha256)
        assertEquals("new wallet", location.database.readText()); assertTrue("Saved seed differs", validSeed == seed.value)
        assertFalse(location.restorePending)
    } }
    @Test fun corruptNewestFallsBackToRetainedBackup() = runBlocking { world {
        newestCorrupt = true
        val outcome = restorer().restore(validSeed)
        assertEquals("b1", outcome.backupId); assertEquals("new wallet", location.database.readText())
    } }
    @Test fun serverFailureNeverFallsBackToLegacyOrChangesWallet() = runBlocking { world {
        listFails = true
        assertTrue(runCatching { restorer().restore(validSeed) }.isFailure); previous()
    } }
    @Test fun failedOpenRollsBack() = runBlocking { world {
        openerFails = true; assertTrue(runCatching { restorer().restore(validSeed) }.exceptionOrNull() is Boom); previous()
    } }
    @Test fun holdsSessionForWholeRestoreAndCancellationWaitsForNative() = runBlocking { world {
        openerFails = true; val gate = CompletableDeferred<Unit>(); verifyGate = gate
        val restorer = restorer()
        val restore = launch { runCatching { restorer.restore(validSeed) } }
        entered.await(); restore.cancel(); delay(20)
        assertFalse(restore.isCompleted); assertEquals("new wallet", location.database.readText()); assertTrue(location.restorePending)
        assertTrue(runCatching { session.reserve() }.isFailure)
        assertTrue(runCatching { session.ensureOpen("old seed", false, "test") }.isFailure)
        assertTrue(runCatching { session.close() }.isFailure)
        assertTrue(runCatching { restorer.restore(validSeed) }.isFailure)
        assertNull(session.summary()); gate.complete(Unit); restore.join(); previous()
    } }
    @Test fun successCommitsAndReleasesSession() = runBlocking { world {
        val result = restorer().restore(validSeed)
        assertEquals("b1", result.backupId); assertFalse(result.cleanupPending)
        assertTrue("Saved seed differs", validSeed == seed.value); assertEquals("new wallet", location.database.readText())
        assertFalse(session.isOpen); session.release(session.reserve()); assertFalse(location.restorePending)
    } }
    @Test fun failedSeedWriteRollsBack() = runBlocking { world {
        seed.fail = true; assertTrue(runCatching { restorer().restore(validSeed) }.isFailure); previous()
    } }
    @Test fun writeLandedDespiteErrorCommits() = runBlocking { world {
        seed.fail = true; seed.lands = true; restorer().restore(validSeed)
        assertTrue("Saved seed differs", validSeed == seed.value)
        assertEquals("new wallet", location.database.readText())
        assertFalse(location.restorePending)
    } }
    @Test fun unknownSeedWaitsThenRecovers() = runBlocking { world {
        seed.fail = true; seed.lockAfterWrite = true
        val error = runCatching { restorer().restore(validSeed) }.exceptionOrNull() as WalletRestorer.Failure
        assertEquals(WalletRestorer.Reason.seedStateUnknown, error.reason); assertTrue(location.restorePending)
        assertEquals(RestoreTransaction.Recovery.waitingForSecret,
            WalletRestorer.recoverInterruptedRestore(session, location, seed, tx))
        seed.unavailable = false
        assertEquals(RestoreTransaction.Recovery.rolledBack, WalletRestorer.recoverInterruptedRestore(session, location, seed, tx)); previous()
    } }
    @Test fun unknownSeedThatLandedFinishesOnRecovery() = runBlocking { world {
        seed.fail = true; seed.lands = true; seed.lockAfterWrite = true
        assertTrue(runCatching { restorer().restore(validSeed) }.isFailure); seed.unavailable = false
        assertEquals(RestoreTransaction.Recovery.completed, WalletRestorer.recoverInterruptedRestore(session, location, seed, tx))
        assertEquals("new wallet", location.database.readText()); assertTrue("Saved seed differs", validSeed == seed.value)
    } }
    /** Review finding: two launches (UI + push, or two push jobs) recovering at once both settle. */
    @Test fun concurrentLaunchRecoveriesBothSucceed() = runBlocking { world {
        seed.fail = true; seed.lands = true; seed.lockAfterWrite = true
        assertTrue(runCatching { restorer().restore(validSeed) }.isFailure); seed.unavailable = false
        assertTrue(location.restorePending)
        val inside = CompletableDeferred<Unit>()
        val proceed = java.util.concurrent.CountDownLatch(1)
        val gated = transaction(location) { inside.complete(Unit); proceed.await() }
        val recovery = RestoreRecovery { WalletRestorer.recoverInterruptedRestore(session, location, seed, gated) }
        val first = async(Dispatchers.Default) { recovery.settle() }
        inside.await()
        val second = async(Dispatchers.Default) { runCatching { recovery.settle() } }
        try { delay(20); assertFalse("waits for the first instead of failing on its reservation", second.isCompleted) }
        finally { proceed.countDown() }
        assertEquals(RestoreTransaction.Recovery.completed, first.await())
        assertEquals(RestoreTransaction.Recovery.nothingPending, second.await().getOrThrow())
        assertEquals("new wallet", location.database.readText()); assertFalse(location.restorePending)
    } }
    /** Review finding: a queued no-journal recovery must not reserve the session another launch is opening. */
    @Test fun recoveryWithoutJournalNeverBlocksAnOpen() = runBlocking { world {
        repeat(30) {
            tx.prepare(); repeat(300) { File(tx.work, "plain-$it").writeText("plain") }
            val start = java.util.concurrent.CountDownLatch(1)
            val recovery = async(Dispatchers.IO) { start.await(); WalletRestorer.recoverInterruptedRestore(session, location, seed, tx) }
            val open = async(Dispatchers.IO) { start.await(); runCatching { session.ensureOpen("old seed", false, "test") } }
            start.countDown()
            assertEquals(RestoreTransaction.Recovery.nothingPending, recovery.await())
            assertTrue("open must not find the session busy", open.await().isSuccess)
            session.close()
        }
    } }
    @Test fun unreadableSeedTouchesNothing() = runBlocking { world {
        seed.unavailable = true; assertTrue(runCatching { restorer().restore(validSeed) }.isFailure)
        seed.unavailable = false; previous()
    } }
    @Test fun sizeHashNetworkAndValidationFailuresPreserveOriginal() = runBlocking {
        for (kind in 0..3) world {
            when (kind) { 0 -> reportedSize = 64; 1 -> reportedHash = "0"; 2 -> network = "signet"; 3 -> validationFails = true }
            assertTrue(runCatching { restorer().restore(validSeed) }.isFailure); previous()
        }
    }
    @Test fun cleanupFailureNeverUndoesCommittedSeed() = runBlocking { world {
        var failCleanup = false
        val faulty = transaction(location) { if (failCleanup) throw Boom() }
        seed.onSave = { failCleanup = true }
        val result = restorer(faulty).restore(validSeed)
        assertTrue(result.cleanupPending)
        assertTrue("Saved seed differs", validSeed == seed.value)
        assertEquals("new wallet", location.database.readText()); assertTrue(location.restorePending)
        WalletRestorer.recoverInterruptedRestore(session, location, seed, tx)
        assertFalse(location.restorePending); assertEquals("new wallet", location.database.readText())
    } }
    @Test fun launchWhileOpeningSweepsOnlyAbandonedRestoreWork() = runBlocking { world {
        openerFails = true; verifyGate = CompletableDeferred(); tx.prepare(); File(tx.work, "plain").writeText("plain")
        val opening = async { runCatching { session.ensureOpen("old seed", false, "test") } }; entered.await()
        assertEquals(RestoreTransaction.Recovery.nothingPending, WalletRestorer.recoverInterruptedRestore(session, location, seed, tx))
        assertFalse(tx.work.exists()); verifyGate!!.complete(Unit); opening.await()
    } }
    @Test fun launchLeavesActiveRestoreWorkAlone() = runBlocking { world {
        tx.prepare(); val hold = session.reserve()
        assertEquals(RestoreTransaction.Recovery.nothingPending, WalletRestorer.recoverInterruptedRestore(session, location, seed, tx))
        assertTrue(tx.work.exists()); session.release(hold)
    } }
    @Test fun failedVerificationTeardownQuarantinesFilesUntilClosed() = runBlocking { world {
        handles.stopFails = true
        val failure = runCatching { restorer().restore(validSeed) }.exceptionOrNull() as WalletSession.OpenError
        assertEquals(WalletSession.OpenFailure.teardownFailed, failure.reason)
        assertTrue(location.restorePending); assertFalse(session.isOpen)
        assertEquals("new wallet", location.database.readText()); assertTrue("Previous seed differs", seed.value == "old seed")
        assertTrue(runCatching { session.reserve() }.isFailure)
        handles.stopFails = false; session.close()
        WalletRestorer.recoverInterruptedRestore(session, location, seed, tx); previous()
    } }
}
