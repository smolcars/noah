package com.noahwallet.services.storage

import java.io.File

class WalletDataLocation(filesDirectory: File, variant: String) {
    val directory = File(filesDirectory, "noah-data-$variant")
    val restoreDirectory = File(filesDirectory, ".noah-data-$variant-restore")
    val deletionMarker = File(filesDirectory, ".noah-data-$variant-deleting")
    val deletionDirectory = File(filesDirectory, ".noah-data-$variant-deleting-data")
    val instanceIdentity = File(directory, ".noah-wallet-instance")

    fun writeDeletionIdentity(syncDirectory: (File) -> Unit = ::syncDirectory) {
        val identity = java.util.UUID.randomUUID().toString()
        if (directory.exists()) {
            java.io.FileOutputStream(instanceIdentity).use { it.write(identity.toByteArray(Charsets.UTF_8)); it.fd.sync() }
            syncDirectory(directory)
        }
        java.io.FileOutputStream(deletionMarker).use { it.write(identity.toByteArray(Charsets.UTF_8)); it.fd.sync() }
    }

    fun deletionMatchesWallet(): Boolean {
        val marker = deletionMarker.readBytes()
        val legacy = marker.contentEquals(byteArrayOf(1))
        val identity = marker.toString(Charsets.UTF_8)
        if (!legacy && runCatching { java.util.UUID.fromString(identity) }.isFailure) return false
        if (!directory.exists()) return true
        // Legacy markers cannot establish ownership of a populated datadir.
        return !legacy && instanceIdentity.exists() && instanceIdentity.readText() == identity
    }

    val restorePending: Boolean get() = File(restoreDirectory, "journal.json").exists()
    val database = File(directory, "db.sqlite")
    // A partial datadir still belongs to the user; unreadable directories fail closed.
    val hasAnyData: Boolean get() = directory.exists() &&
        (directory.list()?.isNotEmpty() ?: error("Cannot inspect wallet directory"))

    internal fun removeAttemptFiles(datadirExisted: Boolean): Boolean = try {
        if (!datadirExisted) !directory.exists() || directory.deleteRecursively()
        else directory.listFiles()?.all { it.deleteRecursively() } == true
    } catch (_: Exception) { false }

    val databaseExists: Boolean get() = database.exists()
}

/** Directory entries must be durable before deleting the recovery secret. */
internal fun syncDirectory(directory: File) {
    val descriptor = android.system.Os.open(directory.path, android.system.OsConstants.O_RDONLY, 0)
    try { android.system.Os.fsync(descriptor) } finally { android.system.Os.close(descriptor) }
}
