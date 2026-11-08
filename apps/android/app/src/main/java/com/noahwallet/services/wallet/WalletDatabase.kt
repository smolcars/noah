package com.noahwallet.services.wallet

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import com.noahwallet.services.backup.ScratchSpace
import java.io.File

/** Read-only check before opening; OnchainWallet.openExisting repeats it in the SDK (defence in depth). */
object WalletDatabase {
    data class Properties(val network: String, val fingerprint: String)
    class InspectionError(cause: Throwable? = null) : Exception("""Cannot read bark wallet properties""", cause)

    /** Plaintext copies live only here; every inspection empties it first (crash leftovers). */
    private val scratch = ScratchSpace(File(System.getProperty("java.io.tmpdir"), "noah-preflight"))
    fun sweepAbandoned() = scratch.sweepAbandoned()
    fun sweepForWalletDeletion() = scratch.sweepForWalletDeletion()

    /** Callers (the session's open path) inspect one database at a time. */
    @Suppress("TooGenericExceptionCaught") // any failure means unreadable; the cause is kept for the log
    fun properties(database: File): Properties = try {
        val wal = File(database.path + "-wal").exists()
        val shm = File(database.path + "-shm").exists()
        // A WAL without its index needs recovery by a writer; like the SDK, refuse rather than
        // let SQLite recreate files next to the wallet.
        if (wal && !shm) throw InspectionError()
        // SQLite creates -shm next to a WAL database even for a read-only connection.
        // With no -wal/-journal sidecar nothing is mid-transaction, so read a private copy and
        // leave the wallet's folder exactly as it was. With both WAL files present a read-only
        // connection sees the committed frames and creates nothing.
        val hasSidecar = wal || File(database.path + "-journal").exists()
        if (hasSidecar) read(database) else inspectCopy(database)
    } catch (failure: Exception) {
        throw failure as? InspectionError ?: InspectionError(failure)
    }

    private fun inspectCopy(database: File): Properties {
        scratch.sweepAbandoned()
        return scratch.withFolderBlocking("inspect") { folder ->
            val copy = File(folder, "wallet.sqlite")
            database.copyTo(copy)
            read(copy)
        }
    }

    /**
     * A bundled SQLite, as bark has: Android 13 and older ship SQLite before 3.37, which can't parse the STRICT tables
     * bark's on-chain wallet (BDK) creates and reports every wallet as corrupt.
     */
    private fun read(file: File): Properties =
        BundledSQLiteDriver().open(file.path, SQLITE_OPEN_READONLY).use { db ->
            db.prepare("SELECT network, fingerprint FROM bark_properties LIMIT 1").use { row ->
                if (!row.step() || row.isNull(0) || row.isNull(1)) throw InspectionError()
                Properties(row.getText(0), row.getText(1))
            }
        }
}
