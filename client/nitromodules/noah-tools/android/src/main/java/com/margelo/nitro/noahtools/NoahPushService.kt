package com.margelo.nitro.noahtools

import android.content.Context
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

class NoahPushService : PushService() {
    override fun onCreate() {
        super.onCreate()
        NoahNativeWalletRuntime.initializeNativeRuntime()
    }

    override fun onMessage(message: PushMessage, instance: String) {
        val messageString = String(message.content)
        when (NoahPushMessageProcessor(applicationContext).process(messageString, instance)) {
            NoahPushMessageProcessor.Result.COMPLETED,
            NoahPushMessageProcessor.Result.DISCARD -> Unit

            NoahPushMessageProcessor.Result.RETRY -> {
                NoahPushMessageRetryScheduler.enqueue(applicationContext, messageString, instance)
            }
        }
    }

    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        NoahToolsLogging.performNativeLog(
            "info",
            "NoahPushService",
            "New Endpoint: ${endpoint.url}"
        )
        val prefs = getSharedPreferences("noah_unified_push", Context.MODE_PRIVATE)
        // Save per-instance to avoid collisions between app variants, and keep legacy key for older reads.
        prefs.edit()
            .putString("endpoint_${instance}", endpoint.url)
            .putString("endpoint", endpoint.url)
            .apply()
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) {
        NoahToolsLogging.performNativeLog(
            "error",
            "NoahPushService",
            "Registration failed: $reason"
        )
    }

    override fun onUnregistered(instance: String) {
        NoahToolsLogging.performNativeLog("info", "NoahPushService", "Unregistered")
    }
}
