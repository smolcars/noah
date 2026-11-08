package com.noahwallet.services

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.os.Build
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.noahwallet.services.wallet.WalletDatabase
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WalletDatabaseDeviceTest {
    /** Bark's on-chain wallet (BDK) creates STRICT tables, which the framework SQLite before Android 14 can't parse. */
    @Test fun readsWalletPropertiesNextToStrictTables() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(context.cacheDir, "wallet-database-test-${UUID.randomUUID()}").apply { check(mkdirs()) }
        try {
            val database = walletWithStrictTable(File(folder, "db.sqlite"))
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // The failure this guards against, on a copy: the framework's default handler deletes "corrupt" files.
                assertThrows(SQLiteException::class.java) { readWithFramework(database.copyTo(File(folder, "framework.sqlite"))) }
            }
            assertEquals(WalletDatabase.Properties("regtest", "73c5da0a"), WalletDatabase.properties(database))
        } finally { folder.deleteRecursively() }
    }

    private fun walletWithStrictTable(file: File) = file.also {
        BundledSQLiteDriver().open(it.path).use { db ->
            listOf(
                "CREATE TABLE bark_properties (id TEXT PRIMARY KEY, network TEXT NOT NULL, fingerprint TEXT NOT NULL)",
                "INSERT INTO bark_properties VALUES ('1', 'regtest', '73c5da0a')",
                "CREATE TABLE bdk_schemas (name TEXT PRIMARY KEY NOT NULL, version INTEGER NOT NULL) STRICT",
            ).forEach { sql -> db.prepare(sql).use { statement -> statement.step() } }
        }
    }

    private fun readWithFramework(file: File) =
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY) { }.use { db ->
            db.rawQuery("SELECT network FROM bark_properties", null).use { it.moveToFirst() }
        }
}
