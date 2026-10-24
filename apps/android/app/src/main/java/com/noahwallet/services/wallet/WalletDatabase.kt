package com.noahwallet.services.wallet

import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import java.io.File
import com.noahwallet.services.backup.ScratchSpace

/** Read-only check before opening; OnchainWallet.openExisting repeats it in the SDK (defence in depth). */
object WalletDatabase {
    data class Properties(val network: String, val fingerprint: String)
    class InspectionError : Exception("""Cannot read bark wallet properties""")

    /** The framework's default handler deletes a database it finds corrupt. Never on a wallet. */
    private val keepDatabase = DatabaseErrorHandler { }

    /** Plaintext copies live only here; every inspection empties it first (crash leftovers). */
    private val scratch = ScratchSpace(File(System.getProperty("java.io.tmpdir"), "noah-preflight"))
    fun sweepAbandoned() = scratch.sweepAbandoned()
    fun sweepForWalletDeletion() = scratch.sweepForWalletDeletion()

    /** Callers (the session's open path) inspect one database at a time. */
    fun properties(database: File): Properties = try {
        val wal = File(database.path + "-wal").exists()
        val shm = File(database.path + "-shm").exists()
        // A WAL without its index needs recovery by a writer; like the SDK, refuse rather than
        // let Android's SQLite recreate files next to the wallet.
        if (wal && !shm) throw InspectionError()
        // Android's SQLite creates -shm next to a WAL database even for a read-only connection.
        // With no -wal/-journal sidecar nothing is mid-transaction, so read a private copy and
        // leave the wallet's folder exactly as it was. With both WAL files present a read-only
        // connection sees the committed frames and creates nothing.
        val hasSidecar = wal || File(database.path + "-journal").exists()
        if (hasSidecar) read(database) else inspectCopy(database)
    } catch (_: Exception) {
        throw InspectionError()
    }

    private fun inspectCopy(database: File): Properties {
        scratch.sweepAbandoned()
        return scratch.withFolderBlocking("inspect") { folder ->
            val copy = File(folder, "wallet.sqlite")
            database.copyTo(copy)
            read(copy)
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
