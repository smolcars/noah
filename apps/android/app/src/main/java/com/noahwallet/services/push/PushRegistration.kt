package com.noahwallet.services.push

import com.noahwallet.api.generated.models.RegisterPushTokenProvider

/** Re-read the gates for every retry/token refresh; no registration before onboarding completes. */
@Suppress("LongParameterList") // dependency seams: tests inject a fake for each gate
class PushRegistration(
    private val onboarded: () -> Boolean,
    private val serverRegistered: () -> Boolean,
    private val permissionGranted: () -> Boolean,
    private val useUnifiedPush: () -> Boolean,
    private val unifiedEndpoint: () -> String?,
    private val fcmToken: suspend () -> String,
    private val registerToken: suspend (String, RegisterPushTokenProvider?) -> Unit,
) {
    enum class Outcome {
        notReady, permissionDenied, waitingForDistributor, registered;

        fun requireCompleted() {
            if (this != registered) throw com.noahwallet.services.server.ServerFailure("Push notification registration did not complete.")
        }
    }

    suspend fun register(): Outcome {
        if (!onboarded() || !serverRegistered()) return Outcome.notReady
        if (!permissionGranted()) return Outcome.permissionDenied
        val unified = useUnifiedPush()
        val token = if (unified) unifiedEndpoint() ?: return Outcome.waitingForDistributor else fcmToken()
        if (!onboarded() || !serverRegistered()) return Outcome.notReady
        registerToken(token, if (unified) null else RegisterPushTokenProvider.FCM)
        return Outcome.registered
    }
}
