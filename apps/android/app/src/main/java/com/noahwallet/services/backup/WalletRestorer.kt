package com.noahwallet.services.backup

import com.noahwallet.api.generated.client.*
import com.noahwallet.api.generated.models.*
import com.noahwallet.services.server.*
import com.noahwallet.services.storage.*
import com.noahwallet.services.wallet.WalletSession
import io.github.niteshbalusu11.bark.*
import io.ktor.client.HttpClient
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Request

class WalletRestorer(
    private val variant: String, private val session: WalletSession, private val location: WalletDataLocation,
    private val seed: SecretItem, private val makeClient: suspend (String) -> HttpClient,
    private val transaction: RestoreTransaction = RestoreTransaction(location),
    private val validate: suspend (File, BackupArchive.Manifest) -> Unit = { file, manifest ->
        val snapshot = validateWalletSnapshot(file.path,
            WalletSnapshotExpectation(manifest.network, manifest.walletFingerprint, manifest.serverPubkey))
        if (snapshot.sha256 != manifest.snapshotSha256 || snapshot.schemaVersion.toInt() != manifest.barkSchemaVersion ||
            snapshot.serverPubkey != manifest.serverPubkey || snapshot.mailboxPubkey != manifest.mailboxPubkey) {
            throw Failure(Reason.identityMismatch)
        }
    },
    private val download: suspend (String, File, Long) -> Unit = { url, file, limit ->
        try { BoundedDownload.fetch(Request.Builder().url(url).build(), file, limit) }
        catch (_: BoundedDownload.TooLarge) { throw Failure(Reason.downloadMismatch) }
    },
    private val beginOnboarding: () -> Unit = {},
) {
    enum class Reason { wrongNetwork, identityMismatch, noUsableBackup, unsupportedFormat, downloadMismatch,
        secureStorageUnavailable, rollbackFailed, seedStateUnknown }
    class Failure(val reason: Reason) : Exception(reason.name)
    enum class Step { fetching, validating, loading, finalizing }
    /** [typedPhrase]: the user typed the phrase (not the stored-seed recovery), so the wallet counts as backed up. */
    data class Outcome(val backupId: String?, val snapshotSha256: String?, val cleanupPending: Boolean = false, val typedPhrase: Boolean = false)

    suspend fun restore(mnemonic: String, beforeInstall: (Outcome) -> Unit = {},
                        progress: (Step) -> Unit = {}): Outcome = withContext(Dispatchers.IO) {
        if (StoredMnemonic.normalize(mnemonic) == null) throw SeedItem.Unavailable()
        val reservation = session.reserve()
        try {
            check(!location.deletionMarker.exists()) { "Restart Noah to finish deleting the previous wallet" }
            withContext(Dispatchers.IO) {
                if (transaction.recover(seed) == RestoreTransaction.Recovery.waitingForSecret || seed.read() is SecretResult.Unavailable) {
                    throw Failure(Reason.secureStorageUnavailable)
                }
                transaction.prepare()
                try {
                    progress(Step.fetching)
                    val outcome = makeClient(mnemonic).use { fetchAndStage(it, mnemonic, progress) }
                    // Once installation starts, cancellation must not interrupt native verification or seed read-back.
                    withContext(NonCancellable) {
                        try {
                            beginOnboarding()
                            beforeInstall(outcome)
                            transaction.begin(mnemonic)
                            progress(Step.loading)
                            session.verify(reservation, mnemonic, "restore")
                            progress(Step.finalizing)
                            transaction.markCommitting()
                        } catch (failure: Throwable) {
                            // A failed native teardown retains handles: leave the journal for next process recovery.
                            if (failure !is WalletSession.OpenError || failure.reason != WalletSession.OpenFailure.teardownFailed) undo()
                            throw failure
                        }
                        try { seed.save(mnemonic) }
                        catch (failure: Exception) {
                            when (val current = seed.read()) {
                                is SecretResult.Unavailable -> throw Failure(Reason.seedStateUnknown)
                                is SecretResult.Found -> if (current.value != mnemonic) { undo(); throw failure }
                                SecretResult.NotFound -> { undo(); throw failure }
                            }
                        }
                        val finished = runCatching { transaction.finish() }.isSuccess || runCatching { transaction.finish() }.isSuccess
                        outcome.copy(cleanupPending = !finished)
                    }
                } catch (failure: Throwable) {
                    runCatching { transaction.removeScratch() }
                    throw failure
                }
            }
        } finally { session.release(reservation) }
    }
    private fun undo() {
        if (!transaction.isPending) return
        try { transaction.undo() } catch (_: Exception) { throw Failure(Reason.rollbackFailed) }
    }
    private suspend fun fetchAndStage(client: HttpClient, mnemonic: String, progress: (Step) -> Unit): Outcome {
        val backups = try { V0BackupV2ListClient(client).listBackupObjects().value() }
        catch (failure: ServerFailure) { if (failure.status == 404) emptyList() else throw failure }
        if (backups.isEmpty()) {
            val url = V0BackupDownloadUrlClient(client).getDownloadUrl(GetDownloadUrlPayload()).value().downloadUrl
            val file = File(transaction.work, "legacy.txt")
            download(url, file, maxEncryptedBytes / 3 * 4 + 1024)
            BackupArchive.openLegacy(file.readBytes(), mnemonic, variant, transaction.staged)
            return Outcome(null, null)
        }
        var firstError: Exception? = null
        for (backup in backups) {
            if (backup.formatVersion != 2) { firstError = firstError ?: Failure(Reason.unsupportedFormat); continue }
            try { return stageV2(client, backup.backupId, mnemonic, progress) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                transaction.removeScratch(); check(transaction.work.mkdir())
                firstError = firstError ?: failure
            }
        }
        throw firstError ?: Failure(Reason.noUsableBackup)
    }
    private suspend fun stageV2(client: HttpClient, id: String, mnemonic: String, progress: (Step) -> Unit): Outcome {
        val response = V0BackupV2DownloadClient(client).getBackupObjectDownloadUrl(GetBackupObjectDownloadPayload(id)).value()
        val backup = response.backup
        if (backup.formatVersion != 2 || backup.encryptedSize !in 1..maxEncryptedBytes) throw Failure(Reason.downloadMismatch)
        val file = File(transaction.work, "backup.noahbackup")
        download(response.downloadUrl, file, backup.encryptedSize)
        if (file.length() != backup.encryptedSize || BackupArchive.sha256(file) != backup.encryptedSha256.lowercase()) {
            throw Failure(Reason.downloadMismatch)
        }
        progress(Step.validating)
        val opened = BackupArchive.open(file, mnemonic, File(transaction.work, "unpacked"))
        if (opened.manifest.network != if (variant == "mainnet") "bitcoin" else variant) throw Failure(Reason.wrongNetwork)
        withContext(NonCancellable) { validate(opened.snapshot, opened.manifest) }
        check(transaction.staged.mkdir())
        check(opened.snapshot.renameTo(File(transaction.staged, "db.sqlite"))) { "Cannot stage restored snapshot" }
        return Outcome(backup.backupId, opened.manifest.snapshotSha256)
    }
    companion object {
        const val maxEncryptedBytes = 256L * 1024 * 1024
        suspend fun recoverInterruptedRestore(session: WalletSession, location: WalletDataLocation, seed: SecretItem,
            transaction: RestoreTransaction = RestoreTransaction(location)): RestoreTransaction.Recovery = withContext(NonCancellable + Dispatchers.IO) {
            // Without a journal there is nothing to swap: drop abandoned plaintext without reserving,
            // so a launch opening the wallet right after its own recovery never finds the session busy.
            // A journal only appears under a restore's reservation, which `unlessReserved` respects.
            if (!location.restorePending) {
                session.unlessReserved { transaction.removeScratch() }
                return@withContext RestoreTransaction.Recovery.nothingPending
            }
            val reservation = try { session.reserve() }
            catch (failure: WalletSession.OpenError) {
                if (failure.reason != WalletSession.OpenFailure.busy || location.restorePending) throw failure
                session.unlessReserved { transaction.removeScratch() }
                return@withContext RestoreTransaction.Recovery.nothingPending
            }
            try { transaction.recover(seed) } finally { session.release(reservation) }
        }
    }
}

/**
 * One launch recovery at a time. UI startup and headless jobs (FCM, WorkManager) each settle an
 * interrupted restore first; without this, whichever came second hit the reservation the first
 * held and failed. A restore actually in flight still rejects them.
 */
class RestoreRecovery(private val recover: suspend () -> RestoreTransaction.Recovery) {
    private val mutex = Mutex()
    suspend fun settle(): RestoreTransaction.Recovery = mutex.withLock { recover() }
}
