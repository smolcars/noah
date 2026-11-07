package com.noahwallet.services

import android.app.Notification
import android.app.NotificationManager
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
