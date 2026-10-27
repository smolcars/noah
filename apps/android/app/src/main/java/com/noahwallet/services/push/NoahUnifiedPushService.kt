package com.noahwallet.services.push

import com.noahwallet.services.logging.AppLog
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

class NoahUnifiedPushService : PushService() {
    override fun onMessage(message: PushMessage, instance: String) {
        if (instance != UnifiedPushRegistration(this).instance) return
        try {
            val content = message.content.toString(Charsets.UTF_8)
            // Distributors never display messages, so receipts are shown here, without opening a wallet.
            val visible = PushPayload.visible(content)
            if (visible != null) PushNotifications.visible(this, visible)
            else PushWork.enqueue(this, PushPayload.decode(content), expedited = false)
        } catch (_: Exception) { AppLog(this, "push").w("Rejected invalid UnifiedPush message") }
    }

    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        val registration = UnifiedPushRegistration(this)
        if (instance != registration.instance) return
        val changed = registration.endpoint() != endpoint.url
        registration.saveEndpoint(endpoint.url)
        if (changed) PushWork.register(this)
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) {
        AppLog(this, "push").w("UnifiedPush registration failed")
    }

    override fun onUnregistered(instance: String) {
        val registration = UnifiedPushRegistration(this)
        if (instance == registration.instance) registration.clearEndpoint()
        AppLog(this, "push").w("UnifiedPush distributor unregistered")
    }
}
