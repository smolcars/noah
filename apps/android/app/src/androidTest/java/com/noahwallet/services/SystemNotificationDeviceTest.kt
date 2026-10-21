package com.noahwallet.services

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.messaging.RemoteMessage
import com.noahwallet.services.push.PushNotifications
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Presentation only: never creates/opens a wallet or executes a claim. */
@RunWith(AndroidJUnit4::class)
class SystemNotificationDeviceTest {
    @Test fun recurringWakePublishesWidgetFixture() = kotlinx.coroutines.runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val balance = com.noahwallet.services.wallet.WalletBalance(
            com.noahwallet.services.wallet.WalletBalance.Ark(1234u, 0u, 0u, 0u, 0u, 0u, 0u, 0u, 0u),
            com.noahwallet.services.wallet.WalletBalance.Onchain(0u, 0u, 0u))
        val jobs = com.noahwallet.services.push.PushJobs(com.noahwallet.services.push.PushJobs.Dependencies(
            ensureWalletOpen = {}, maintenance = {}, createInvoice = { error("Unused") }, submitInvoice = { _, _ -> },
            claimLightning = {}, reportMaintenance = { _, _ -> }, heartbeat = {}, announceReceived = { _, _, _ -> },
            refreshWidget = { com.noahwallet.services.push.WidgetDataWriter.write(context, balance, 123, "regtest") },
            log = {}, backup = {}, reportBackup = { _, _ -> }, recurring = {},
        ))
        jobs.handle(com.noahwallet.api.generated.models.NotificationDataRecurringPaymentDue(1))
        assertEquals(1234L, context.getSharedPreferences("com.noahwallet.regtest", Context.MODE_PRIVATE).getLong("totalBalance", -1))
        assertEquals(123L, context.getSharedPreferences("com.noahwallet.regtest", Context.MODE_PRIVATE).getLong("closestExpiryBlocks", -1))
    }

    @Test fun receiptsAreVisibleAndOpenNoah() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(PushNotifications.permissionGranted(context))
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.cancelAll()
        PushNotifications.received(context, 1234, "1.23", "system-presentation")
        PushNotifications.remote(context, RemoteMessage.Builder("noah").setMessageId("system-ark")
            .addData("title", "Payment received").addData("message", "You received 1234 sats via Ark.")
            .addData("body", "{}").build())
        val deadline = android.os.SystemClock.elapsedRealtime() + 5000
        while (manager.activeNotifications.size < 2 && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
        val receipts = manager.activeNotifications
        assertEquals(2, receipts.size)
        assertTrue(receipts.all { it.notification.contentIntent != null })
        assertTrue(receipts.any { it.notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("via Ark") })
    }
}
