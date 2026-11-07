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
@Config(sdk = [35])
class PushNotificationsTest {
    @org.junit.Before fun allowNotifications() {
        org.robolectric.Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
    }
    // Only the receipt's sound differs before notification channels (API 26).
    @Test
    @Config(sdk = [25, 35])
    fun receiptHasSoundLocalizedAmountAndAnAppTapDestination() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("gt_store", Context.MODE_PRIVATE).edit()
            .putString(com.noahwallet.services.storage.LegacyLocale.key, "es").commit()
        PushNotifications.received(context, 1234, "1.23", "presentation-test")
        val posted = context.getSystemService(NotificationManager::class.java).activeNotifications.single().notification
        if (android.os.Build.VERSION.SDK_INT < 26) {
            // Before channels (API 26) a notification's sound is in its deprecated defaults.
            @Suppress("DEPRECATION")
            val defaults = posted.defaults
            assertTrue(defaults and Notification.DEFAULT_SOUND != 0)
        } else assertNotNull(context.getSystemService(NotificationManager::class.java).getNotificationChannel(posted.channelId).sound)
        assertTrue(posted.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("Has recibido"))
        assertTrue(posted.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("US$1.23"))
        assertNotNull(posted.contentIntent)
        assertEquals("com.noahwallet.MainActivity", org.robolectric.Shadows.shadowOf(posted.contentIntent).savedIntent.component?.className)
        assertEquals(com.noahwallet.history.PaymentTap(paymentHash = "presentation-test"), tap(posted))
    }

    private fun tap(posted: Notification) =
        com.noahwallet.history.PaymentTap.of(org.robolectric.Shadows.shadowOf(posted.contentIntent).savedIntent)

    private val arkData = """{"notification_type":"ark_payment_received","vtxo_ids":["aa:0","bb:1"]}"""

    @Test fun eachNotificationOpensItsOwnPayment() {
        val context = RuntimeEnvironment.getApplication()
        PushNotifications.received(context, 1, null, "first")
        PushNotifications.received(context, 2, null, "second")
        val taps = context.getSystemService(NotificationManager::class.java).activeNotifications.map { tap(it.notification) }
        assertEquals(setOf("first", "second"), taps.map { it?.paymentHash }.toSet())
    }

    @Test fun firebaseArkReceiptOpensItsPayment() {
        val context = RuntimeEnvironment.getApplication()
        PushNotifications.remote(context, com.google.firebase.messaging.RemoteMessage.Builder("noah")
            .setMessageId("ark-tap").addData("title", "Payment received").addData("message", "You received 1 sat via Ark.")
            .addData("body", arkData).build())
        val posted = context.getSystemService(NotificationManager::class.java).activeNotifications.single().notification
        assertEquals(com.noahwallet.history.PaymentTap(vtxoIds = listOf("aa:0", "bb:1")), tap(posted))
    }

    @Test fun unifiedPushArkReceiptOpensItsPayment() {
        val service = org.robolectric.Robolectric.buildService(NoahUnifiedPushService::class.java).create().get()
        val content = kotlinx.serialization.json.JsonObject(mapOf(
            "notification_type" to kotlinx.serialization.json.JsonPrimitive("visible_notification"),
            "title" to kotlinx.serialization.json.JsonPrimitive("Payment received"),
            "body" to kotlinx.serialization.json.JsonPrimitive("You received 1 sat via Ark."),
            "data" to kotlinx.serialization.json.JsonPrimitive(arkData))).toString()
        service.onMessage(org.unifiedpush.android.connector.data.PushMessage(content.toByteArray(), false), UnifiedPushRegistration(service).instance)
        val posted = service.getSystemService(NotificationManager::class.java).activeNotifications.single().notification
        assertEquals(com.noahwallet.history.PaymentTap(vtxoIds = listOf("aa:0", "bb:1")), tap(posted))
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
        assertNull("older servers' empty data opens the app as before", tap(posted))
    }

    @Test fun unifiedPushServiceDisplaysArkReceiptWithoutOpeningAWallet() {
        val service = org.robolectric.Robolectric.buildService(NoahUnifiedPushService::class.java).create().get()
        val content = """{"notification_type":"visible_notification","title":"Payment received","body":"You received 1234 sats via Ark."}"""
        service.onMessage(org.unifiedpush.android.connector.data.PushMessage(content.toByteArray(), false), UnifiedPushRegistration(service).instance)
        val posted = service.getSystemService(NotificationManager::class.java).activeNotifications.single().notification
        assertEquals("Payment received", posted.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("You received 1234 sats via Ark.", posted.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals(com.noahwallet.notifications.NotificationChannels.DEFAULT, posted.channelId)
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
