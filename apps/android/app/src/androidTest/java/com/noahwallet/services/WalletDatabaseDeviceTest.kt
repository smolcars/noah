package com.noahwallet.services

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.noahwallet.services.storage.WalletDataLocation
import com.noahwallet.services.wallet.WalletSession
import com.noahwallet.services.wallet.WalletDatabase
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WalletDatabaseDeviceTest {
    @Test fun readOnlyPreflightRejectsMissingEmptyForeignAndWrongNetworkWithoutChanges() = runBlocking {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "preflight-${UUID.randomUUID()}")
        val location = WalletDataLocation(root, "regtest")
        var opened = false
        val session = WalletSession("regtest", location, opener = { _, _, _, _, _ -> opened = true; error("Unexpected SDK call") })
        suspend fun reject(reason: WalletSession.OpenFailure) {
            val before = location.database.takeIf { it.exists() }?.readBytes()
            val files = location.directory.list()?.sorted()
            val failure = runCatching { session.openExisting("unused", false, "test") }.exceptionOrNull()
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
}
