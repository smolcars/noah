package com.noahwallet.services.push

/** Re-read the gates for every retry/token refresh; no registration before onboarding completes. */
@Suppress("LongParameterList") // dependency seams: tests inject a fake for each gate
class PushRegistration(
    private val onboarded: () -> Boolean,
    private val serverRegistered: () -> Boolean,
    private val permissionGranted: () -> Boolean,
    private val useUnifiedPush: () -> Boolean,
    private val unifiedEndpoint: () -> String?,
    private val expoToken: suspend () -> String,
    private val registerToken: suspend (String) -> Unit,
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
        val token = if (useUnifiedPush()) unifiedEndpoint() ?: return Outcome.waitingForDistributor else expoToken()
        if (!onboarded() || !serverRegistered()) return Outcome.notReady
        registerToken(token)
        return Outcome.registered
    }
}
