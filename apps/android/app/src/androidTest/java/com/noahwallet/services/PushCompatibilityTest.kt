package com.noahwallet.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.noahwallet.services.push.*
import com.noahwallet.services.wallet.WalletBalance
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.unifiedpush.android.connector.UnifiedPush
import java.io.File

class TestDistributor : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Unit
}

@RunWith(AndroidJUnit4::class)
class PushCompatibilityTest {
    // Keep RN-shaped fixtures separate from the wallet's real preferences and connector database.
    private val context = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
        override fun getApplicationContext(): Context = this
        override fun getDatabasePath(name: String) = File(noBackupFilesDir, "push-compat/$name")
        override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("push-compat-$name", mode)
    }
    private val distributor = InstrumentationRegistry.getInstrumentation().context.packageName

    @Test fun rnConnectorDatabaseAndLegacyPreferencesRemainReadable() {
        val database = context.getDatabasePath("unifiedpush-connector")
        database.parentFile!!.mkdirs()
        database.delete()
        SQLiteDatabase.openOrCreateDatabase(database, null).use { db ->
            val fixture = InstrumentationRegistry.getInstrumentation().context.assets.open("unifiedpush-connector.sql").bufferedReader().use { it.readText() }
                .lineSequence().filterNot { it.startsWith("--") }.joinToString("\n")
            fixture.replace("__DISTRIBUTOR__", distributor).split(';').filter { it.isNotBlank() }.forEach { db.execSQL(it) }
        }
        context.getSharedPreferences("unifiedpush.connector", Context.MODE_PRIVATE).edit().clear()
            .putString("unifiedpush.distributor", distributor)
            .putBoolean("unifiedpush.distributor_ack", true)
            .putStringSet("unifiedpush.instances", setOf("noah-regtest"))
            .putString("noah-regtest/unifiedpush.connector", "fixture-regtest-token")
            .putString("noah-regtest/unifiedpush.message", "RN legacy registration").commit()
        val prefs = context.getSharedPreferences("noah_unified_push", Context.MODE_PRIVATE)
        prefs.edit().clear().putString("endpoint", "https://example.test/legacy")
            .putString("endpoint_noah-regtest", "https://example.test/regtest").commit()
        assertEquals(distributor, UnifiedPush.getSavedDistributor(context))
        assertEquals(distributor, UnifiedPush.getAckDistributor(context))
        // Since connector 3.3, the legacy registration migrates on a background thread.
        val deadline = System.currentTimeMillis() + 5_000
        var registrations = connectorTokens(database)
        while ("noah-regtest" !in registrations && System.currentTimeMillis() <= deadline) {
            Thread.sleep(50)
            registrations = connectorTokens(database)
        }
        assertEquals(mapOf("noah-mainnet" to "fixture-mainnet-token", "noah-signet" to "fixture-signet-token",
            "noah-regtest" to "fixture-regtest-token"), registrations)
        assertEquals("https://example.test/regtest", UnifiedPushRegistration.endpoint(prefs, "noah-regtest"))
        assertEquals("https://example.test/legacy", UnifiedPushRegistration.endpoint(prefs, "noah-mainnet"))
        val registration = UnifiedPushRegistration(context, "regtest")
        assertEquals("noah-regtest", registration.instance)
        registration.saveEndpoint("https://example.test/rotated")
        assertEquals("https://example.test/rotated", prefs.getString("endpoint_noah-regtest", null))
        assertEquals("https://example.test/rotated", prefs.getString("endpoint", null))
    }

    private fun connectorTokens(database: File): Map<String, String> =
        SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT instance, connectorToken FROM tokens ORDER BY instance", null).use { cursor ->
                buildMap { while (cursor.moveToNext()) put(cursor.getString(0), cursor.getString(1)) }
            }
        }

    @Test fun channelSettingsSurviveAndWidgetPreferencesMatchProviderReads() = kotlinx.coroutines.runBlocking {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel("default") == null) {
            manager.createNotificationChannel(NotificationChannel("default", "User channel", NotificationManager.IMPORTANCE_LOW))
        }
        val before = manager.getNotificationChannel("default")
        PushNotifications.ensureChannels(context)
        assertEquals(before.importance, manager.getNotificationChannel("default").importance)
        assertEquals(before.name, manager.getNotificationChannel("default").name)
        val balance = WalletBalance(WalletBalance.Ark(10u, 0u, 0u, 0u, 0u, 0u, 0u, 0u, 0u), WalletBalance.Onchain(5u, 3u, 8u))
        WidgetDataWriter.write(context, balance, -2, "regtest")
        val prefs = context.getSharedPreferences("com.noahwallet.regtest", Context.MODE_PRIVATE)
        assertEquals(18L, prefs.getLong("totalBalance", -1))
        assertEquals(8L, prefs.getLong("onchainBalance", -1))
        assertEquals(10L, prefs.getLong("offchainBalance", -1))
        assertEquals(3L, prefs.getLong("pendingBalance", -1))
        assertEquals(-2L, prefs.getLong("closestExpiryBlocks", -1))
        assertEquals(24L, prefs.getLong("expiryThreshold", -1))
    }
}
