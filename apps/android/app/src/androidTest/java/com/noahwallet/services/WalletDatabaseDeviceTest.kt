package com.noahwallet.services

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.os.Build
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.noahwallet.services.storage.WalletDataLocation
import com.noahwallet.services.wallet.WalletDatabase
import com.noahwallet.services.wallet.WalletSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WalletDatabaseDeviceTest {
    @Test fun readOnlyPreflightRejectsMissingEmptyForeignAndWrongNetworkWithoutChanges() = runBlocking {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "preflight-${UUID.randomUUID()}")
        val location = WalletDataLocation(root, "regtest")
        var opened = false
        val session = WalletSession("regtest", location, opener = { _, _, _, _ -> opened = true; error("Unexpected SDK call") })
        suspend fun reject(reason: WalletSession.OpenFailure) {
            val before = location.database.takeIf { it.exists() }?.readBytes()
            val files = location.directory.list()?.sorted()
            val failure = runCatching { session.openExisting("unused", "test") }.exceptionOrNull()
            assertEquals(reason, (failure as? WalletSession.OpenError)?.reason)
            assertFalse(opened)
            assertArrayEquals(before, location.database.takeIf { it.exists() }?.readBytes())
            assertEquals(files, location.directory.list()?.sorted())
        }
        try {
            reject(WalletSession.OpenFailure.walletDatabaseMissing); assertFalse(root.exists())
            location.directory.mkdirs(); location.database.writeBytes(byteArrayOf())
            reject(WalletSession.OpenFailure.notABarkWallet)
            SQLiteDatabase.openOrCreateDatabase(location.database, null).use { it.execSQL("CREATE TABLE other (value TEXT)") }
            reject(WalletSession.OpenFailure.notABarkWallet)
            SQLiteDatabase.openDatabase(location.database.path, null, SQLiteDatabase.OPEN_READWRITE).use {
                it.execSQL("CREATE TABLE bark_properties (network TEXT, fingerprint TEXT)")
            }
            reject(WalletSession.OpenFailure.notABarkWallet)
            SQLiteDatabase.openDatabase(location.database.path, null, SQLiteDatabase.OPEN_READWRITE).use {
                it.execSQL("INSERT INTO bark_properties VALUES ('signet', 'abcd1234')")
            }
            assertEquals(WalletDatabase.Properties("signet", "abcd1234"), WalletDatabase.properties(location.database))
            reject(WalletSession.OpenFailure.networkMismatch)
        } finally { root.deleteRecursively() }
    }

    /** Bark's own SQLite deletes -wal/-shm on a clean close; such a wallet must pass the check. */
    @Test fun cleanlyClosedWalDatabasePassesWithoutCreatingSidecars() {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "wal-${UUID.randomUUID()}")
        val location = WalletDataLocation(root, "regtest")
        try {
            location.directory.mkdirs()
            SQLiteDatabase.openOrCreateDatabase(location.database, null).use { db ->
                db.rawQuery("PRAGMA journal_mode=WAL", null).use { it.moveToFirst() }
                db.execSQL("CREATE TABLE bark_properties (network TEXT, fingerprint TEXT)")
                db.execSQL("INSERT INTO bark_properties VALUES ('regtest', 'abcd1234')")
                db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
            }
            // Whatever the framework left, reproduce bark's clean close.
            File(location.database.path + "-wal").delete()
            File(location.database.path + "-shm").delete()
            val bytes = location.database.readBytes()
            assertEquals(2, bytes[18].toInt()) // still a WAL-mode file

            assertEquals(WalletDatabase.Properties("regtest", "abcd1234"), WalletDatabase.properties(location.database))
            assertFalse(File(location.database.path + "-shm").exists())
            assertFalse(File(location.database.path + "-wal").exists())
            assertArrayEquals(bytes, location.database.readBytes())
        } finally { root.deleteRecursively() }
    }

    /** A crash can leave -wal without -shm; the check must not recreate the index beside it. */
    @Test fun walWithoutIndexIsRefusedWithoutTouchingTheFolder() {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "walnoshm-${UUID.randomUUID()}")
        val location = WalletDataLocation(root, "regtest")
        try {
            location.directory.mkdirs()
            location.database.writeBytes(ByteArray(4096))
            File(location.database.path + "-wal").writeBytes(ByteArray(32))
            val before = location.directory.list()?.sorted()
            assertThrows(WalletDatabase.InspectionError::class.java) { WalletDatabase.properties(location.database) }
            assertEquals(before, location.directory.list()?.sorted())
        } finally { root.deleteRecursively() }
    }

    @Test fun copiesLeftByAKilledInspectionAreSwept() {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "sweep-${UUID.randomUUID()}")
        val location = WalletDataLocation(root, "regtest")
        val scratch = File(System.getProperty("java.io.tmpdir"), "noah-preflight")
        try {
            location.directory.mkdirs()
            SQLiteDatabase.openOrCreateDatabase(location.database, null).use {
                it.execSQL("CREATE TABLE bark_properties (network TEXT, fingerprint TEXT)")
                it.execSQL("INSERT INTO bark_properties VALUES ('regtest', 'abcd1234')")
            }
            listOf("-wal", "-shm", "-journal").forEach { File(location.database.path + it).delete() }
            scratch.mkdirs()
            File(scratch, "wallet.sqlite").writeBytes(ByteArray(16)) // a killed run's copy
            assertEquals("regtest", WalletDatabase.properties(location.database).network)
            assertTrue(scratch.listFiles().orEmpty().isEmpty())
        } finally { root.deleteRecursively() }
    }

    @Test fun corruptDatabaseIsReportedNeverDeleted() {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "corrupt-${UUID.randomUUID()}")
        val location = WalletDataLocation(root, "regtest")
        try {
            location.directory.mkdirs()
            val garbage = ByteArray(8192) { (it * 31).toByte() }
            location.database.writeBytes(garbage)
            assertThrows(WalletDatabase.InspectionError::class.java) { WalletDatabase.properties(location.database) }
            assertTrue(location.database.exists())
            assertArrayEquals(garbage, location.database.readBytes())
        } finally { root.deleteRecursively() }
    }

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
