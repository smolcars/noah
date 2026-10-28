package com.noahwallet.ui

import com.noahwallet.services.backup.WalletRestorer
import com.noahwallet.services.storage.WalletDataLocation
import com.noahwallet.services.storage.syncDirectory
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream

/** Metadata only. Written before installation so even death before the engine returns is replayable. */
class RestoreCompletionJournal(location: WalletDataLocation, private val sync: (File) -> Unit = ::syncDirectory) {
    @Serializable private data class Record(val version: Int = 1, val backupId: String?, val snapshotSha256: String?, val typedPhrase: Boolean = false)
    private val directory = location.restoreDirectory
    private val file = File(directory, "completion.json")
    val pending get() = file.exists()
    fun write(outcome: WalletRestorer.Outcome) {
        check(directory.isDirectory || directory.mkdirs())
        sync(directory.parentFile!!)
        val temporary = File(directory, "completion.tmp")
        try {
            FileOutputStream(temporary).use {
                it.write(Json.encodeToString(Record(backupId = outcome.backupId, snapshotSha256 = outcome.snapshotSha256,
                    typedPhrase = outcome.typedPhrase)).toByteArray())
                it.fd.sync()
            }
            check(temporary.renameTo(file))
            sync(directory)
        } finally { temporary.delete() }
    }
    fun read(): WalletRestorer.Outcome? {
        if (!pending) return null
        val record = Json.decodeFromString<Record>(file.readText())
        check(record.version == 1)
        return WalletRestorer.Outcome(record.backupId, record.snapshotSha256, cleanupPending = true, typedPhrase = record.typedPhrase)
    }
    fun clear() { check(!file.exists() || file.delete()); if (directory.exists()) sync(directory) }
}
