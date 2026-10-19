package com.noahwallet.services.wallet

import android.database.sqlite.SQLiteDatabase
import java.io.File

/** Inspect before OnchainWallet.default can migrate or add BDK tables. */
object WalletDatabase {
    data class Properties(val network: String, val fingerprint: String)
    class InspectionError : Exception("Cannot read bark wallet properties")

    fun properties(database: File): Properties = try {
        SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT network, fingerprint FROM bark_properties LIMIT 1", null).use { row ->
                if (!row.moveToFirst() || row.isNull(0) || row.isNull(1)) throw InspectionError()
                Properties(row.getString(0), row.getString(1))
            }
        }
    } catch (_: Exception) {
        throw InspectionError()
    }
}
