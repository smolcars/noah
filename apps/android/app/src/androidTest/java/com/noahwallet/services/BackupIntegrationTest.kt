package com.noahwallet.services

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.noahwallet.api.generated.client.*
import com.noahwallet.api.generated.models.*
import com.noahwallet.services.backup.*
import com.noahwallet.services.server.*
import com.noahwallet.services.storage.*
import com.noahwallet.services.wallet.*
import io.github.niteshbalusu11.bark.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupIntegrationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private class MemorySeed : SecretItem {
        var value: String? = null
        override suspend fun read() = value?.let { SecretResult.Found(it) } ?: SecretResult.NotFound
        override suspend fun save(value: String) { this.value = value }
    }
    @Test fun liveUploadSkipAndRestore() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("noahBackupIT") == "1")
        val root = File(context.noBackupFilesDir, "backup-it-${UUID.randomUUID()}").apply { mkdirs() }
        val location = WalletDataLocation(root, "regtest")
        val session = WalletSession("regtest", location)
        val restoredLocation = WalletDataLocation(File(root, "device-b"), "regtest")
        val restored = WalletSession("regtest", restoredLocation)
        val mnemonic = generateMnemonic()
        val endpoint = "http://10.0.2.2:3100"
        val public = ServerClient.make(endpoint)
        var token: String? = null
        val tokens = AuthTokenProvider(readToken = { token }, saveToken = { token = it }, clearToken = { token = null },
            readMnemonic = { mnemonic }, login = { ServerClient.login(public, "regtest", it) })
        var stage = "create"
        try {
            session.createNew(mnemonic, false, "backup-it")
            ServerClient.make(endpoint, tokens).use { client ->
                stage = "register"
                V0RegisterClient(client).register(RegisterPayload()).value()
                val service = BackupService(session, client, { mnemonic }, ScratchSpace(File(root, "scratch")))
                service.registerBackup()
                stage = "upload"
                val first = service.performBackup(null)
                assertTrue(first.uploaded); assertNotNull(first.backupId)
                stage = "unchanged snapshot"
                assertFalse(service.performBackup(first.snapshotSha256).uploaded)
                assertTrue(V0BackupV2ListClient(client).listBackupObjects().value().any { it.backupId == first.backupId })
                val original = session.summary()!!
                stage = "restore"
                val seed = MemorySeed()
                val restorer = WalletRestorer("regtest", restored, restoredLocation, seed, makeClient = {
                    // Separate HTTP client, temporary token only; nothing persisted.
                    ServerClient.make(endpoint, tokens)
                })
                val outcome = restorer.restore(mnemonic)
                assertEquals(first.backupId, outcome.backupId); assertEquals(first.snapshotSha256, outcome.snapshotSha256)
                assertFalse(outcome.cleanupPending); assertFalse(restored.isOpen)
                assertTrue("Restored seed matches", seed.value == mnemonic)
                stage = "reopen and compare"
                restored.ensureOpen(mnemonic, false, "backup-it")
                val summary = restored.summary()!!
                assertEquals(original.fingerprint, summary.fingerprint)
                assertEquals(original.movementCount, summary.movementCount)
                assertEquals(original.balance, summary.balance)
                assertTrue(restoredLocation.restoreDirectory.listFiles().orEmpty().isEmpty())
                stage = "remove disposable server backup"
                V0BackupV2DeleteClient(client).deleteBackupObject(DeleteBackupObjectPayload(first.backupId!!)).value()
            }
        } catch (failure: Exception) {
            val errno = (failure as? android.system.ErrnoException)?.errno
            fail("Backup integration failed at $stage: ${failure.javaClass.simpleName}, errno=$errno (provider details suppressed)")
        }
        finally { runCatching { session.close() }; runCatching { restored.close() }; public.close(); root.deleteRecursively() }
    }

    /** Public BIP39 fixture only. Host Node seals this real SQLite snapshot independently. */
    @Test fun prepareNodeSnapshot() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("noahBackupIT") == "1")
        val root = File(context.cacheDir, "backup-node-fixture").apply { deleteRecursively(); mkdirs() }
        val mnemonic = fixturePhrase
        val config = WalletConfiguration.base("regtest").sdkConfig("backup-fixture")
        val location = WalletDataLocation(root, "regtest")
        location.directory.mkdirs()
        var stage = "offline creation"
        try {
            val chain = OnchainWallet.default(Network.REGTEST, mnemonic, config, location.directory.path)
            try {
                val wallet = Wallet.openWithOnchain(Network.REGTEST, mnemonic, config,
                    WalletOpenArgs(runDaemon = false, datadir = location.directory.path, createWithoutServer = true, skipRecovery = true), chain)
                try {
                    stage = "snapshot"
                    val snapshot = wallet.createSnapshot(File(root, "db.sqlite").path)
                    File(root, "manifest.json").writeBytes(BackupArchive.encode(BackupService.manifest(snapshot)))
                    wallet.stopDaemonWait()
                } finally { wallet.close() }
            } finally { chain.close() }
            location.directory.deleteRecursively()
        } catch (_: Exception) { fail("Node fixture preparation failed at $stage") }
    }

    @Test fun restoreNodeMadeSnapshot() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("noahNodeBackupIT") == "1")
        val root = File(context.noBackupFilesDir, "node-restore-${UUID.randomUUID()}").apply { mkdirs() }
        val location = WalletDataLocation(root, "regtest")
        val session = WalletSession("regtest", location)
        var stage = "restore"
        try {
            val restorer = WalletRestorer("regtest", session, location, MemorySeed(),
                makeClient = { ServerClient.make("http://10.0.2.2:3161") })
            val outcome = restorer.restore(fixturePhrase) { stage = it.name }
            assertEquals("node-fixture", outcome.backupId); assertFalse(outcome.cleanupPending)
            stage = "reopen"
            session.ensureOpen(fixturePhrase, false, "node-fixture")
            val summary = session.summary()!!
            stage = "compare"
            val expected = BackupArchive.parseManifest(File(context.cacheDir, "backup-node-fixture/manifest.json").readBytes())
            assertEquals(expected.walletFingerprint, summary.fingerprint); assertEquals(0, summary.movementCount)
        } catch (failure: Exception) { fail("Node-made backup restore failed at $stage: ${failure.javaClass.simpleName}; ${(failure as? WalletRestorer.Failure)?.reason ?: (failure as? WalletSession.OpenError)?.reason ?: (failure as? BackupCrypto.Failure)?.reason}") }
        finally { runCatching { session.close() }; root.deleteRecursively(); File(context.cacheDir, "backup-node-fixture").deleteRecursively() }
    }

    @Test fun atomicArchivePublicationRefusesExistingTargets() {
        val root = File(context.cacheDir, "publication-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val source = File(root, "complete-🔒").apply { writeText("complete archive") }
            val target = File(root, "export-🔒")
            AtomicBackupMove.publish(source, target)
            assertFalse(source.exists()); assertEquals("complete archive", target.readText())
            source.writeText("different archive")
            val failure = runCatching { AtomicBackupMove.publish(source, target) }.exceptionOrNull()
            assertTrue(failure is android.system.ErrnoException)
            assertEquals(android.system.OsConstants.EEXIST, (failure as android.system.ErrnoException).errno)
            assertEquals("complete archive", target.readText()); assertEquals("different archive", source.readText())
        } finally { root.deleteRecursively() }
    }

    @Test fun realFilesystemCrashRecoveryAndSeedDurability() = runBlocking<Unit> {
        val root = File(context.noBackupFilesDir, "tx-it-${UUID.randomUUID()}").apply { mkdirs() }
        val location = WalletDataLocation(root, "regtest")
        val seedFile = AtomicSecretFile(File(root, "seed"))
        try {
            seedFile.writeAtomically(byteArrayOf(1, 2, 3)); assertArrayEquals(byteArrayOf(1, 2, 3), seedFile.read())
            for (committed in listOf(false, true)) {
                location.directory.mkdirs(); location.database.writeText("old")
                val tx = RestoreTransaction(location); tx.prepare(); tx.staged.mkdir(); File(tx.staged, "db.sqlite").writeText("new")
                tx.begin("fixture seed"); tx.markCommitting()
                val seed = MemorySeed().apply { value = if (committed) "fixture seed" else "old seed" }
                assertEquals(if (committed) RestoreTransaction.Recovery.completed else RestoreTransaction.Recovery.rolledBack, tx.recover(seed))
                assertEquals(if (committed) "new" else "old", location.database.readText()); assertFalse(tx.isPending)
            }
        } finally { root.deleteRecursively() }
    }
    companion object {
        private const val fixturePhrase = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    }
}
