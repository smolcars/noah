package com.noahwallet.services.push

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25, 35])
class PushNotificationsTest {
    @org.junit.Before fun allowNotifications() {
        org.robolectric.Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
    }
    @Test fun receiptHasSoundLocalizedAmountAndAnAppTapDestination() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("gt_store", Context.MODE_PRIVATE).edit()
            .putString(com.noahwallet.services.storage.LegacyLocale.key, "es").commit()
        PushNotifications.received(context, 1234, "1.23", "presentation-test")
        val posted = context.getSystemService(NotificationManager::class.java).activeNotifications.single().notification
        if (android.os.Build.VERSION.SDK_INT < 26) assertTrue(posted.defaults and Notification.DEFAULT_SOUND != 0)
        else assertNotNull(context.getSystemService(NotificationManager::class.java).getNotificationChannel(posted.channelId).sound)
        assertTrue(posted.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("Has recibido"))
        assertTrue(posted.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("US$1.23"))
        assertNotNull(posted.contentIntent)
        assertEquals("com.noahwallet.MainActivity", org.robolectric.Shadows.shadowOf(posted.contentIntent).savedIntent.component?.className)
    }

    @Test fun firebaseServiceDisplaysExpoArkReceiptWithoutOpeningAWallet() {
        val service = org.robolectric.Robolectric.buildService(NoahFirebaseMessagingService::class.java).create().get()
        val message = com.google.firebase.messaging.RemoteMessage.Builder("noah")
            .setMessageId("ark-visible").addData("title", "Payment received")
            .addData("message", "You received 1234 sats via Ark.").addData("body", "{}").build()
        service.onMessageReceived(message)
        val posted = service.getSystemService(NotificationManager::class.java).activeNotifications.single().notification
        assertEquals("Payment received", posted.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("You received 1234 sats via Ark.", posted.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertNotNull(posted.contentIntent)
    }

    @Test fun silentPushDoesNotDisplayAReceipt() {
        val context = RuntimeEnvironment.getApplication()
        PushNotifications.remote(context, com.google.firebase.messaging.RemoteMessage.Builder("noah")
            .addData("body", "{\"notification_type\":\"heartbeat\",\"notification_id\":\"test\"}").build())
        assertTrue(context.getSystemService(NotificationManager::class.java).activeNotifications.isEmpty())
    }

    @Test fun firebaseNotificationEnvelopeUsesTheSamePresentation() {
        val context = RuntimeEnvironment.getApplication()
        val bundle = android.os.Bundle().apply {
            putString("gcm.n.e", "1"); putString("gcm.n.title", "Payment received")
            putString("gcm.n.body", "You received 1234 sats via Ark.")
        }
        PushNotifications.remote(context, com.google.firebase.messaging.RemoteMessage(bundle))
        val posted = context.getSystemService(NotificationManager::class.java).activeNotifications.single().notification
        assertEquals("You received 1234 sats via Ark.", posted.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
    }
}
