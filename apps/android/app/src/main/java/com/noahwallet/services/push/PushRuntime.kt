package com.noahwallet.services.push

import android.content.Context
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailabilityLight
import com.google.firebase.messaging.FirebaseMessaging
import com.noahwallet.BuildConfig
import com.noahwallet.api.generated.client.*
import com.noahwallet.api.generated.models.*
import com.noahwallet.services.AppServices
import com.noahwallet.services.logging.AppLog
import com.noahwallet.services.server.*
import com.noahwallet.services.storage.ExpoInstallationID
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class PushRuntime(private val context: Context, private val services: AppServices) {
    private val log = AppLog(context, "push")
    private val client = services.client
    val jobs = PushJobs(PushJobs.Dependencies(
        ensureWalletOpen = services::ensureWalletOpen,
        recurring = { com.noahwallet.recurring.RecurringRuntime.shared(context).execute(it) },
        maintenance = services.session::maintenanceDelegated,
        progressExits = { services.session.progressPendingExits { log.w("Background exit progress failed: $it") } },
        backup = { services.backupCoordinator.flush(com.noahwallet.services.backup.BackupCoordinator.Reason.push, requireEnabled = false) },
        reportBackup = { k1, success ->
            val payload = ReportJobStatusPayload(errorMessage = if (success) null else "Wallet backup failed",
                notificationK1 = k1, reportType = ReportType.BACKUP, status = if (success) ReportStatus.SUCCESS else ReportStatus.FAILURE)
            val delays = listOf(250L, 1000L, 2000L)
            for (attempt in 0..delays.size) {
                try { V0ReportJobStatusClient(client).reportJobStatus(payload).value(); break }
                catch (failure: ServerFailure) {
                    if (failure.status != 404 || attempt == delays.size) throw failure
                    delay(delays[attempt])
                }
            }
        },
        createInvoice = services.session::bolt11Invoice,
        submitInvoice = { id, invoice -> V0LnurlpSubmitInvoiceClient(client).submitInvoice(SubmitInvoicePayload(invoice, id)).value() },
        claimLightning = services.session::claimLightningReceives,
        reportMaintenance = { k1, success ->
            val payload = ReportJobStatusPayload(
                errorMessage = if (success) null else "Wallet maintenance failed",
                notificationK1 = k1, reportType = ReportType.MAINTENANCE,
                status = if (success) ReportStatus.SUCCESS else ReportStatus.FAILURE,
            )
            // RN tolerates the server's push-send / job-row-insert race.
            val delays = listOf(250L, 1000L, 2000L)
            for (attempt in 0..delays.size) {
                try {
                    V0ReportJobStatusClient(client).reportJobStatus(payload).value()
                    log.i("Maintenance status reported")
                    break
                } catch (error: ServerFailure) {
                    if (error.status != 404 || attempt == delays.size) throw error
                    delay(delays[attempt])
                }
            }
        },
        heartbeat = { id ->
            V0HeartbeatResponseClient(client).heartbeatResponse(HeartbeatResponsePayload(id)).value()
            log.i("Heartbeat response accepted")
        },
        announceReceived = { sats, usd, hash -> PushNotifications.received(context, sats, usd, hash) },
        refreshWidget = { admitted ->
            // withOpen shields its block from cancellation, so check the caller's admission explicitly.
            services.session.withOpen { handles ->
                admitted()
                val ticket = WidgetDataWriter.ticket()
                val summary = handles.summary()
                admitted()
                val extras = WidgetDataWriter.extras(handles, BuildConfig.APP_VARIANT, summary.balance)
                admitted()
                WidgetDataWriter.write(context, summary.balance, extras, services::widgetDisplay, BuildConfig.APP_VARIANT, ticket)
            }
        }, log = log::i,
    ))

    suspend fun register(serverRegistrationPending: Boolean = false, onToken: (String) -> Unit = {}): PushRegistration.Outcome {
        PushNotifications.ensureChannels(context)
        val outcome = PushRegistration(
            onboarded = services::isOnboarded,
            serverRegistered = { serverRegistrationPending || services.registration.state.isRegisteredWithServer == true },
            permissionGranted = { PushNotifications.permissionGranted(context) },
            useUnifiedPush = { GoogleApiAvailabilityLight.getInstance().isGooglePlayServicesAvailable(context) != ConnectionResult.SUCCESS },
            unifiedEndpoint = { UnifiedPushRegistration(context).register() },
            expoToken = {
                val messaging = FirebaseMessaging.getInstance()
                messaging.isAutoInitEnabled = true
                // FCM 26 deprecates getToken() for register()/onRegistered; migrating token delivery is separate work.
                @Suppress("DEPRECATION")
                val token = suspendCancellableCoroutine { continuation ->
                    messaging.token.addOnSuccessListener { continuation.resume(it) }
                        .addOnFailureListener { continuation.resumeWithException(it) }
                }
                log.i("FCM token obtained")
                // A separate unauthenticated client prevents Noah's bearer from reaching Expo.
                ServerClient.make("https://exp.host").use {
                    ExpoPushToken(it).fetch(ExpoPushToken.request(ExpoInstallationID(context).getOrCreate(), context.packageName, token))
                }.also { log.i("Expo token obtained") }
            },
            registerToken = { V0RegisterPushTokenClient(client).registerPushToken(RegisterPushToken(it)).value(); onToken(it) },
        ).register()
        if (outcome == PushRegistration.Outcome.registered) log.i("Push token registered with Noah server")
        return outcome
    }
}
