package com.noahwallet.services.backup

import com.noahwallet.api.generated.client.*
import com.noahwallet.api.generated.models.*
import com.noahwallet.services.server.*
import com.noahwallet.services.storage.*
import com.noahwallet.services.wallet.WalletSession
import io.github.niteshbalusu11.bark.*
import io.ktor.client.HttpClient
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Request
import java.io.File

@Suppress("LongParameterList") // dependency seams: tests inject transaction, validation, download and rounds
class WalletRestorer(
    private val variant: String, private val session: WalletSession, private val location: WalletDataLocation,
    private val seed: SecretItem, private val makeClient: suspend (String) -> HttpClient,
    private val transaction: RestoreTransaction = RestoreTransaction(location),
    private val validate: suspend (File, BackupArchive.Manifest) -> Unit = { file, manifest ->
        val snapshot = validateWalletSnapshot(file.path,
            WalletSnapshotExpectation(manifest.network, manifest.walletFingerprint, manifest.serverPubkey))
        val matches = snapshot.sha256 == manifest.snapshotSha256 && snapshot.schemaVersion.toInt() == manifest.barkSchemaVersion &&
            snapshot.serverPubkey == manifest.serverPubkey && snapshot.mailboxPubkey == manifest.mailboxPubkey
        if (!matches) throw Failure(Reason.identityMismatch)
    },
    private val download: suspend (String, File, Long) -> Unit = { url, file, limit ->
        try { BoundedDownload.fetch(Request.Builder().url(url).build(), file, limit) }
        catch (_: BoundedDownload.TooLarge) { throw Failure(Reason.downloadMismatch) }
    },
    private val beginOnboarding: () -> Unit = {},
    private val rounds: Int = BackupCrypto.pbkdf2Rounds,
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
                var installed = false
                try {
                    progress(Step.fetching)
                    val outcome = makeClient(mnemonic).use { fetchAndStage(it, mnemonic, progress) }
                    // Once installation starts, cancellation must not interrupt native verification or seed read-back.
                    withContext(NonCancellable) { install(reservation, mnemonic, outcome, beforeInstall, progress) }
                        .also { installed = true }
                } finally {
                    if (!installed) runCatching { transaction.removeScratch() }
                }
            }
        } finally { session.release(reservation) }
    }
    private suspend fun install(reservation: WalletSession.Reservation, mnemonic: String, outcome: Outcome,
        beforeInstall: (Outcome) -> Unit, progress: (Step) -> Unit): Outcome {
        var verified = false
        var keepJournal = false
        try {
            beginOnboarding()
            beforeInstall(outcome)
            transaction.begin(mnemonic)
            progress(Step.loading)
            session.verify(reservation, mnemonic, "restore")
            progress(Step.finalizing)
            transaction.markCommitting()
            verified = true
        } catch (failure: WalletSession.OpenError) {
            // A failed native teardown retains handles: leave the journal for next process recovery.
            keepJournal = failure.reason == WalletSession.OpenFailure.teardownFailed
            throw failure
        } finally { if (!verified && !keepJournal) undo() }
        saveSeed(mnemonic)
        val finished = runCatching { transaction.finish() }.isSuccess || runCatching { transaction.finish() }.isSuccess
        return outcome.copy(cleanupPending = !finished)
    }
    private suspend fun saveSeed(mnemonic: String) {
        try { seed.save(mnemonic) }
        catch (@Suppress("TooGenericExceptionCaught") failure: Exception) { // whatever the keystore threw, the read-back decides
            val current = seed.read()
            if (current is SecretResult.Unavailable) throw Failure(Reason.seedStateUnknown)
            if ((current as? SecretResult.Found)?.value != mnemonic) { undo(); throw failure }
        }
    }
    private fun undo() {
        if (!transaction.isPending) return
        try { transaction.undo() } catch (_: Exception) { throw Failure(Reason.rollbackFailed) }
    }
    private suspend fun listBackups(client: HttpClient) = try { V0BackupV2ListClient(client).listBackupObjects().value() }
    catch (failure: ServerFailure) { if (failure.status == 404) emptyList() else throw failure }
    private suspend fun fetchAndStage(client: HttpClient, mnemonic: String, progress: (Step) -> Unit): Outcome {
        val backups = listBackups(client)
        if (backups.isEmpty()) {
            val url = V0BackupDownloadUrlClient(client).getDownloadUrl(GetDownloadUrlPayload()).value().downloadUrl
            val file = File(transaction.work, "legacy.txt")
            download(url, file, maxEncryptedBytes / 3 * 4 + 1024)
            BackupArchive.openLegacy(file.readBytes(), mnemonic, variant, transaction.staged, rounds)
            return Outcome(null, null)
        }
        var firstError: Exception? = null
        for (backup in backups) {
            if (backup.formatVersion != 2) { firstError = firstError ?: Failure(Reason.unsupportedFormat); continue }
            try { return stageV2(client, backup.backupId, mnemonic, progress) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (@Suppress("TooGenericExceptionCaught") failure: Exception) { // any unusable backup moves on to the next
                transaction.removeScratch(); check(transaction.work.mkdir())
                firstError = firstError ?: failure
            }
        }
        throw firstError ?: Failure(Reason.noUsableBackup)
    }
    private suspend fun stageV2(client: HttpClient, id: String, mnemonic: String, progress: (Step) -> Unit): Outcome {
        val response = V0BackupV2DownloadClient(client).getBackupObjectDownloadUrl(GetBackupObjectDownloadPayload(id)).value()
        val backup = response.backup
        val file = File(transaction.work, "backup.noahbackup")
        downloadVerified(response.downloadUrl, backup, file)
        progress(Step.validating)
        val opened = BackupArchive.open(file, mnemonic, File(transaction.work, "unpacked"), rounds)
        if (opened.manifest.network != if (variant == "mainnet") "bitcoin" else variant) throw Failure(Reason.wrongNetwork)
        withContext(NonCancellable) { validate(opened.snapshot, opened.manifest) }
        check(transaction.staged.mkdir())
        check(opened.snapshot.renameTo(File(transaction.staged, "db.sqlite"))) { "Cannot stage restored snapshot" }
        return Outcome(backup.backupId, opened.manifest.snapshotSha256)
    }
    private suspend fun downloadVerified(url: String, backup: BackupObjectInfo, file: File) {
        if (backup.formatVersion != 2 || backup.encryptedSize !in 1..maxEncryptedBytes) throw Failure(Reason.downloadMismatch)
        download(url, file, backup.encryptedSize)
        if (file.length() != backup.encryptedSize || BackupArchive.sha256(file) != backup.encryptedSha256.lowercase()) {
            throw Failure(Reason.downloadMismatch)
        }
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
