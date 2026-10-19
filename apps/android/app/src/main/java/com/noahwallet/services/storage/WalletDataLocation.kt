package com.noahwallet.services.storage

import java.io.File

class WalletDataLocation(filesDirectory: File, variant: String) {
    val directory = File(filesDirectory, "noah-data-$variant")
    val restoreDirectory = File(filesDirectory, ".noah-data-$variant-restore")
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
