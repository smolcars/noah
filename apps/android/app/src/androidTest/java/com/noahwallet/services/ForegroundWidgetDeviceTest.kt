package com.noahwallet.services

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.noahwallet.BuildConfig
import com.noahwallet.MainActivity
import com.noahwallet.services.push.WidgetDataWriter
import com.noahwallet.services.storage.*
import com.noahwallet.services.wallet.*
import io.github.niteshbalusu11.bark.generateMnemonic
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ForegroundWidgetDeviceTest {
    @Test fun fundedForegroundRefreshPublishesTheSnapshot() = runBlocking {
        val url = InstrumentationRegistry.getArguments().getString("noahWidgetFundingURL")
        assumeTrue(url != null)
        check(BuildConfig.APP_VARIANT == "regtest")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.noBackupFilesDir, "widget-${UUID.randomUUID()}").apply { mkdirs() }
        val session = WalletSession("regtest", WalletDataLocation(root, "regtest"))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val admission = ForegroundSyncAdmission()
            scenario.onActivity { it.lifecycle.addObserver(admission) }
            try {
                session.createNew(generateMnemonic(), "widget-test")
                val invoice = session.bolt11Invoice(20_000u)
                val connection = URI(url!!).toURL().openConnection() as HttpURLConnection
                try {
                    connection.requestMethod = "POST"; connection.doOutput = true; connection.readTimeout = 120_000
                    connection.outputStream.use { it.write(org.json.JSONObject().put("invoice", invoice).toString().toByteArray()) }
                    check(connection.responseCode == 200) { "Regtest invoice payment failed" }
                } finally { connection.disconnect() }
                val deadline = System.nanoTime() + 120_000_000_000L
                while (System.nanoTime() < deadline) {
                    session.claimLightningReceives(); session.sync()
                    if (session.summary()!!.spendableSats > 0uL) break
                    delay(1000)
                }
                val caller = currentCoroutineContext()
                fun checkStage() { caller.checkWalletAdmission(); admission.checkActive() }
                var published = false
                ForegroundWidgetRefresh.read(session::summary, session::widgetExtras, ::checkStage) { balance, extras ->
                    assertTrue(balance.ark.spendable > 0uL)
                    // A new wallet's whole balance arrived inside the window; a fresh VTXO isn't due for Renew.
                    assertEquals(balance.totalSats.toLong(), com.noahwallet.widgets.WidgetWave.changes(extras.rows!!, emptyMap()).sumOf { it.sats })
                    assertNull(extras.renewBlocks)
                    WidgetDataWriter.write(context, balance, extras,
                        { WidgetDataWriter.Display(com.noahwallet.formatting.BitcoinAmountUnit.bip177, false, "USD") }, "regtest",
                        com.noahwallet.services.push.WidgetDataWriter.ticket())
                    val stored = com.noahwallet.widgets.WidgetSnapshot.read(context.getSharedPreferences("com.noahwallet.regtest", 0))!!
                    assertEquals(balance.totalSats.toLong(), stored.daily!!.last())
                    assertEquals(0L, stored.daily.first())
                    published = true
                }
                assertTrue(published)
            } finally { session.close(); root.deleteRecursively() }
        }
    }
}
