package com.noahwallet.services.backup

import com.noahwallet.services.storage.*
import com.noahwallet.services.wallet.*
import com.noahwallet.services.server.ServerClient
import io.github.niteshbalusu11.bark.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class WalletRestorerTest {
    private class Boom : Exception()
    private class Handles : WalletSession.Handles {
        override fun notifications(): WalletSession.Notifications = error("unused")
        override suspend fun history(): List<Movement> = error("unused")
        override suspend fun receiveRequest(): ReceiveRequest = error("unused")
        override suspend fun receiveInvoice(request: ReceiveRequest, amountSat: Long, description: String): ReceiveRequest = error("unused")
        override suspend fun initializeAddress() { }
        override suspend fun peekPublicKey(index: UInt): String = error("unused")
        override suspend fun peekAddress(index: UInt): String = error("unused")
        override suspend fun summary(): WalletSession.Summary = error("unused")
        override suspend fun sync() { }
        override suspend fun maintenanceDelegated() { }
        override suspend fun bolt11Invoice(amountSats: ULong): String = error("unused")
        override suspend fun claimLightningReceives() { }
        override suspend fun closestExpiryBlocks(): Long? = null
        override suspend fun createSnapshot(destination: File): WalletSnapshotInfo = error("unused")
        var stopFails = false
        override suspend fun stopDaemonWait() { if (stopFails) throw Boom() }
        override fun close() { }
    }
    private class World {
        val root = Files.createTempDirectory("restorer").toFile()
        val location = WalletDataLocation(File(root, "device-b"), "regtest")
        val seed = FakeSeed("old seed")
        val handles = Handles()
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
                BackupArchive.Manifest(2, "x", "sha", network, "abcd", null, null, 42), "new seed", encrypted, root) { complete, target -> Files.createLink(target.toPath(), complete.toPath()); Unit }
            val legacyFile = File(root, "legacy")
            if (legacy) {
                val bytes = java.io.ByteArrayOutputStream()
                java.util.zip.ZipOutputStream(bytes).use { zip ->
                    zip.putNextEntry(java.util.zip.ZipEntry("backup_staging/noah-data-regtest/db.sqlite"))
                    zip.write("new wallet".toByteArray()); zip.closeEntry()
                }
                legacyFile.writeBytes(java.util.Base64.getEncoder().encode(
                    BackupCrypto.encrypt(bytes.toByteArray(), "new seed", BackupCrypto.Version.legacyDirectory)))
            }
            val info = """{"backup_id":"b1","created_at":"x","encrypted_sha256":"${reportedHash ?: BackupArchive.sha256(encrypted)}","encrypted_size":${reportedSize ?: encrypted.length()},"format_version":2}"""
            return WalletRestorer("regtest", session, location, seed,
                makeClient = { ServerClient.make("http://fake", engine = MockEngine { request ->
                    val body = when (request.url.encodedPath) {
                        "/v0/backup/v2/list" -> {
                            if (listFails) return@MockEngine respond("{}", HttpStatusCode.InternalServerError)
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
                    respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }) }, transaction = transaction,
                validate = { _, _ -> if (validationFails) throw Boom() },
                download = { url, destination, limit ->
                    val file = if (url == "http://legacy") legacyFile else encrypted
                    if (file.length() > limit || url == "http://bad") throw WalletRestorer.Failure(WalletRestorer.Reason.downloadMismatch)
                    file.copyTo(destination)
                })
        }
        fun previous() {
            assertEquals("old wallet", location.database.readText()); assertEquals("old seed", seed.value)
            assertTrue(location.restoreDirectory.listFiles().orEmpty().isEmpty())
        }
    }
    private suspend fun world(block: suspend World.() -> Unit) {
        val world = World()
        try { withTimeout(10000) { world.block() } } finally { world.root.deleteRecursively() }
    }
    @Test fun legacyUsesSameReservedTransactionAndSeedCommit() = runBlocking { world {
        legacy = true
        val outcome = restorer().restore("new seed")
        assertNull(outcome.backupId); assertNull(outcome.snapshotSha256)
        assertEquals("new wallet", location.database.readText()); assertEquals("new seed", seed.value)
        assertFalse(location.restorePending)
    } }
    @Test fun corruptNewestFallsBackToRetainedBackup() = runBlocking { world {
        newestCorrupt = true
        val outcome = restorer().restore("new seed")
        assertEquals("b1", outcome.backupId); assertEquals("new wallet", location.database.readText())
    } }
    @Test fun serverFailureNeverFallsBackToLegacyOrChangesWallet() = runBlocking { world {
        listFails = true
        assertTrue(runCatching { restorer().restore("new seed") }.isFailure); previous()
    } }
    @Test fun failedOpenRollsBack() = runBlocking { world {
        openerFails = true; assertTrue(runCatching { restorer().restore("new seed") }.exceptionOrNull() is Boom); previous()
    } }
    @Test fun holdsSessionForWholeRestoreAndCancellationWaitsForNative() = runBlocking { world {
        openerFails = true; val gate = CompletableDeferred<Unit>(); verifyGate = gate
        val restorer = restorer()
        val restore = launch { runCatching { restorer.restore("new seed") } }
        entered.await(); restore.cancel(); delay(20)
        assertFalse(restore.isCompleted); assertEquals("new wallet", location.database.readText()); assertTrue(location.restorePending)
        assertTrue(runCatching { session.reserve() }.isFailure)
        assertTrue(runCatching { session.ensureOpen("old seed", false, "test") }.isFailure)
        assertTrue(runCatching { session.close() }.isFailure)
        assertTrue(runCatching { restorer.restore("new seed") }.isFailure)
        assertNull(session.summary()); gate.complete(Unit); restore.join(); previous()
    } }
    @Test fun successCommitsAndReleasesSession() = runBlocking { world {
        val result = restorer().restore("new seed")
        assertEquals("b1", result.backupId); assertFalse(result.cleanupPending)
        assertEquals("new seed", seed.value); assertEquals("new wallet", location.database.readText())
        assertFalse(session.isOpen); session.release(session.reserve()); assertFalse(location.restorePending)
    } }
    @Test fun failedSeedWriteRollsBack() = runBlocking { world {
        seed.fail = true; assertTrue(runCatching { restorer().restore("new seed") }.isFailure); previous()
    } }
    @Test fun writeLandedDespiteErrorCommits() = runBlocking { world {
        seed.fail = true; seed.lands = true; restorer().restore("new seed")
        assertEquals("new seed", seed.value); assertEquals("new wallet", location.database.readText()); assertFalse(location.restorePending)
    } }
    @Test fun unknownSeedWaitsThenRecovers() = runBlocking { world {
        seed.fail = true; seed.lockAfterWrite = true
        val error = runCatching { restorer().restore("new seed") }.exceptionOrNull() as WalletRestorer.Failure
        assertEquals(WalletRestorer.Reason.seedStateUnknown, error.reason); assertTrue(location.restorePending)
        assertEquals(RestoreTransaction.Recovery.waitingForSecret,
            WalletRestorer.recoverInterruptedRestore(session, location, seed, tx))
        seed.unavailable = false
        assertEquals(RestoreTransaction.Recovery.rolledBack, WalletRestorer.recoverInterruptedRestore(session, location, seed, tx)); previous()
    } }
    @Test fun unknownSeedThatLandedFinishesOnRecovery() = runBlocking { world {
        seed.fail = true; seed.lands = true; seed.lockAfterWrite = true
        assertTrue(runCatching { restorer().restore("new seed") }.isFailure); seed.unavailable = false
        assertEquals(RestoreTransaction.Recovery.completed, WalletRestorer.recoverInterruptedRestore(session, location, seed, tx))
        assertEquals("new wallet", location.database.readText()); assertEquals("new seed", seed.value)
    } }
    /** Review finding: two launches (UI + push, or two push jobs) recovering at once both settle. */
    @Test fun concurrentLaunchRecoveriesBothSucceed() = runBlocking { world {
        seed.fail = true; seed.lands = true; seed.lockAfterWrite = true
        assertTrue(runCatching { restorer().restore("new seed") }.isFailure); seed.unavailable = false
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
    @Test fun unreadableSeedTouchesNothing() = runBlocking { world {
        seed.unavailable = true; assertTrue(runCatching { restorer().restore("new seed") }.isFailure)
        seed.unavailable = false; previous()
    } }
    @Test fun sizeHashNetworkAndValidationFailuresPreserveOriginal() = runBlocking {
        for (kind in 0..3) world {
            when (kind) { 0 -> reportedSize = 64; 1 -> reportedHash = "0"; 2 -> network = "signet"; 3 -> validationFails = true }
            assertTrue(runCatching { restorer().restore("new seed") }.isFailure); previous()
        }
    }
    @Test fun cleanupFailureNeverUndoesCommittedSeed() = runBlocking { world {
        var failCleanup = false
        val faulty = transaction(location) { if (failCleanup) throw Boom() }
        seed.onSave = { failCleanup = true }
        val result = restorer(faulty).restore("new seed")
        assertTrue(result.cleanupPending)
        assertEquals("new seed", seed.value)
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
        val failure = runCatching { restorer().restore("new seed") }.exceptionOrNull() as WalletSession.OpenError
        assertEquals(WalletSession.OpenFailure.teardownFailed, failure.reason)
        assertTrue(location.restorePending); assertFalse(session.isOpen)
        assertEquals("new wallet", location.database.readText()); assertEquals("old seed", seed.value)
        assertTrue(runCatching { session.reserve() }.isFailure)
        handles.stopFails = false; session.close()
        WalletRestorer.recoverInterruptedRestore(session, location, seed, tx); previous()
    } }
}
