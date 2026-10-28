package com.noahwallet.services

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.noahwallet.services.push.WidgetDataWriter
import com.noahwallet.services.wallet.WalletBalance
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class WidgetIOTest {
    @Test fun writeAndClearAwaitStorageBeforeBroadcastAndKeepMainResponsive() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val balance = WalletBalance(WalletBalance.Ark(10u, 0u, 0u, 0u, 0u, 0u, 0u, 0u, 0u), WalletBalance.Onchain(5u, 3u, 8u))
        val display = WidgetDataWriter.Display(com.noahwallet.formatting.BitcoinAmountUnit.bip177, false, "USD")
        for (clear in listOf(false, true)) {
            val entered = CompletableDeferred<Thread>(); val release = CountDownLatch(1)
            var broadcast = false
            val context = object : ContextWrapper(base) {
                override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                    entered.complete(Thread.currentThread())
                    check(release.await(5, TimeUnit.SECONDS))
                    return super.getSharedPreferences(name, mode)
                }
                override fun sendBroadcast(intent: Intent) {
                    val prefs = base.getSharedPreferences("com.noahwallet.regtest", Context.MODE_PRIVATE)
                    if (clear) assertTrue(prefs.all.isEmpty())
                    else {
                        val stored = com.noahwallet.widgets.WidgetSnapshot.read(prefs)!!
                        assertEquals(18L, stored.balance)
                        assertEquals(0L, stored.arriving)
                        assertNotNull(stored.renewDue)
                    }
                    broadcast = true
                }
            }
            val work = async(Dispatchers.Main) {
                if (clear) WidgetDataWriter.clear(context, "regtest")
                else WidgetDataWriter.write(context, balance, WidgetDataWriter.Extras(emptyList(), 100, 2), { display }, "regtest", WidgetDataWriter.ticket())
            }
            try {
                assertNotSame(Looper.getMainLooper().thread, withTimeout(5000) { entered.await() })
                withContext(Dispatchers.Main) { assertFalse(work.isCompleted); assertFalse(broadcast) }
                release.countDown(); work.await()
                assertTrue(broadcast)
            } finally { release.countDown(); work.await() }
        }
    }
}
