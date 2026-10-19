package com.noahwallet.services.wallet

import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import java.io.File

/** Read-only check before opening; OnchainWallet.openExisting repeats it in the SDK (defence in depth). */
object WalletDatabase {
    data class Properties(val network: String, val fingerprint: String)
    class InspectionError : Exception("Cannot read bark wallet properties")

    /** The framework's default handler deletes a database it finds corrupt. Never on a wallet. */
    private val keepDatabase = DatabaseErrorHandler { }

    fun properties(database: File): Properties = try {
        // Android's SQLite creates -shm next to a WAL database even for a read-only connection.
        // With no -wal/-journal sidecar nothing is mid-transaction, so read a private copy and
        // leave the wallet's folder exactly as it was. With a sidecar the index already exists
        // and a read-only connection sees the committed WAL frames.
        val hasSidecar = listOf("-wal", "-journal").any { File(database.path + it).exists() }
        if (hasSidecar) read(database) else inspectCopy(database)
    } catch (_: Exception) {
        throw InspectionError()
    }

    private fun inspectCopy(database: File): Properties {
        val copy = File.createTempFile("wallet-preflight-", ".sqlite")
        try {
            database.copyTo(copy, overwrite = true)
            return read(copy)
        } finally {
            listOf("", "-wal", "-shm", "-journal").forEach { File(copy.path + it).delete() }
        }
    }

    private fun read(file: File): Properties =
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY, keepDatabase).use { db ->
            db.rawQuery("SELECT network, fingerprint FROM bark_properties LIMIT 1", null).use { row ->
                if (!row.moveToFirst() || row.isNull(0) || row.isNull(1)) throw InspectionError()
                Properties(row.getString(0), row.getString(1))
            }
        }
}
