package com.noahwallet.services.backup

import com.noahwallet.api.generated.client.*
import com.noahwallet.api.generated.models.*
import com.noahwallet.services.server.value
import com.noahwallet.services.wallet.WalletSession
import io.github.niteshbalusu11.bark.WalletSnapshotInfo
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File

class BackupService(
    private val session: WalletSession, private val client: HttpClient,
    private val readMnemonic: suspend () -> String, private val scratch: ScratchSpace,
) {
    data class Outcome(val backupId: String?, val snapshotSha256: String, val uploaded: Boolean)
    suspend fun registerBackup() { V0BackupSettingsClient(client).updateBackupSettings(BackupSettingsPayload(true)).value() }
    suspend fun performBackup(lastUploaded: String?, onUploadRequired: () -> Unit = {}): Outcome =
        scratch.withFolder("backup") { folder ->
            val snapshotFile = File(folder, "snapshot.sqlite")
            val (snapshot, mnemonic) = session.withOpen { it.createSnapshot(snapshotFile) to readMnemonic() }
            if (snapshot.sha256 == lastUploaded) return@withFolder Outcome(null, snapshot.sha256, false)
            onUploadRequired()
            val sealed = withContext(Dispatchers.IO) {
                BackupArchive.seal(snapshotFile, manifest(snapshot), mnemonic, File(folder, "backup.noahbackup"), folder)
            }
            val upload = V0BackupV2UploadClient(client).initiateBackupObjectUpload(InitiateBackupUploadPayload(sealed.sha256, sealed.sizeBytes, 2)).value()
            val request = Request.Builder().url(upload.uploadUrl)
                .header("Content-Length", sealed.sizeBytes.toString())
                .header("Content-Type", "application/octet-stream")
                .header("x-amz-checksum-sha256", upload.checksumSha256)
                .put(sealed.file.asRequestBody("application/octet-stream".toMediaType())).build()
            BoundedDownload.run(BoundedDownload.client.newCall(request)) {
                check(it.isSuccessful) { "Backup upload failed: ${it.code}" }
            }
            V0BackupV2CompleteClient(client).completeBackupObjectUpload(CompleteBackupUploadPayload(upload.backupId)).value()
            Outcome(upload.backupId, snapshot.sha256, true)
        }
    /** An export the caller shares, then discards; the launch sweep removes it if the process dies first. */
    class Export(val archive: BackupArchive.EncryptedFile, private val folder: File, private val scratch: ScratchSpace) {
        fun discard() = scratch.discard(folder)
    }
    /**
     * Seals into a fresh scratch folder this operation owns, never a caller-chosen path: the
     * placeholder-then-rename publication is only safe where no other writer exists and a process
     * death's leftovers get swept.
     */
    suspend fun exportEncryptedBackup(fileName: String): Export {
        require(fileName.isNotBlank() && '/' !in fileName && fileName != "." && fileName != "..") { """Invalid export name""" }
        val shared = scratch.makeFolder("share-backup")
        var exported = false
        try {
            val archive = scratch.withFolder("export") { folder ->
                val snapshotFile = File(folder, "snapshot.sqlite")
                val (snapshot, mnemonic) = session.withOpen { it.createSnapshot(snapshotFile) to readMnemonic() }
                withContext(Dispatchers.IO) {
                    BackupArchive.seal(snapshotFile, manifest(snapshot), mnemonic, File(shared, fileName), folder)
                }
            }
            return Export(archive, shared, scratch).also { exported = true }
        } finally {
            if (!exported) scratch.discard(shared)
        }
    }
    companion object {
        fun manifest(snapshot: WalletSnapshotInfo) = BackupArchive.Manifest(2, BackupArchive.timestamp(), snapshot.sha256,
            snapshot.network, snapshot.walletFingerprint, snapshot.serverPubkey, snapshot.mailboxPubkey, snapshot.schemaVersion.toInt())
    }
}
