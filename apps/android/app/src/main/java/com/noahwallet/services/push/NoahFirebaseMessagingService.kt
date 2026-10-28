package com.noahwallet.services.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.noahwallet.services.logging.AppLog

class NoahFirebaseMessagingService : FirebaseMessagingService() {
    // Still delivered with getToken() (see PushRuntime); FCM 26's onRegistered comes with that migration.
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onNewToken(token: String) {
        // Fetch the latest token in the worker; never persist a stale callback token in its input.
        PushWork.register(applicationContext)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        PushNotifications.remote(applicationContext, message)
        val body = message.data["body"] ?: return
        try {
            PushWork.enqueue(applicationContext, PushPayload.decode(body),
                expedited = message.priority == RemoteMessage.PRIORITY_HIGH)
            AppLog(this, "push").i("FCM push queued")
        } catch (_: Exception) {
            AppLog(this, "push").w("Rejected invalid FCM push")
        }
    }
}
