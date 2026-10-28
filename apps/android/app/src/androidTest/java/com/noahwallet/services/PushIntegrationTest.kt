package com.noahwallet.services

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import com.google.android.gms.tasks.Tasks
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.RemoteMessage
import com.noahwallet.BuildConfig
import com.noahwallet.api.generated.client.V0RegisterClient
import com.noahwallet.api.generated.models.RegisterPayload
import com.noahwallet.services.push.*
import com.noahwallet.services.server.DeviceInfo
import com.noahwallet.services.server.ServerClient
import com.noahwallet.services.server.ServerFailure
import com.noahwallet.services.server.registrationPayload
import com.noahwallet.services.server.value
import com.noahwallet.services.storage.*
import com.noahwallet.services.wallet.WalletConfiguration
import com.noahwallet.services.wallet.WalletCreator
import io.github.niteshbalusu11.bark.derivePublicKeyFromMnemonic
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Dedicated, disposable emulator only: -e noahPushIT 1, build with -PnoahRegtestServerURL=... */
@RunWith(AndroidJUnit4::class)
class PushIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun optIn() {
        assumeTrue(BuildConfig.APP_VARIANT == "regtest")
        assumeTrue(InstrumentationRegistry.getArguments().getString("noahPushIT") == "1")
    }

    @Test fun liveTokenRegistration() = runBlocking {
        optIn()
        var stage = "wallet creation"
        try {
            val marker = File(context.noBackupFilesDir, "push-test-wallet")
            check(marker.isFile || !WalletDataLocation(context.filesDir, "regtest").directory.exists()) {
                "Refusing to reuse a wallet not created by this test"
            }
            marker.writeText("1")
            val services = AppServices.shared(context)
            withContext(Dispatchers.IO) { WalletCreator.live(context).createOrResume() }
            SettingsStorage(context).setPersistedState(PersistedWalletState(isInitialized = true), PersistedWalletState.key, 0)
            stage = "server registration"
            // A fresh SDK wallet has no address at index zero yet. Match ServerIntegrationTest's
            // supported address-less registration; upgraded RN wallets already have that address.
            if (services.registration.state.isRegisteredWithServer != true) {
                val device = DeviceInfo.current()
                val response = V0RegisterClient(services.client).register(RegisterPayload(deviceInfo = device.registrationPayload())).value()
                services.registration.apply(response, device)
            }
            services.registration.ensureRegistered()
            stage = "notification permission (grant POST_NOTIFICATIONS before the test)"
            check(PushNotifications.permissionGranted(context))
            stage = "push registration"
            assertEquals(PushRegistration.Outcome.registered, withTimeout(120_000) { PushRuntime(context, services).register() })
            stage = "Expo identity continuity"
            val installation = ExpoInstallationID(context).getOrCreate()
            // The token PushRuntime registers; FCM 26's register() replacement only hands it to onNewToken.
            @Suppress("DEPRECATION")
            val fcm = withContext(Dispatchers.IO) { Tasks.await(FirebaseMessaging.getInstance().token, 30, TimeUnit.SECONDS) }
            ServerClient.make("https://exp.host").use { client ->
                val request = ExpoPushToken.request(installation, context.packageName, fcm)
                val first = ExpoPushToken(client).fetch(request)
                val second = ExpoPushToken(client).fetch(request.copy(deviceId = ExpoInstallationID(context).getOrCreate().lowercase()))
                check(first == second) { "Expo identity changed" }
            }
            val seed = SeedStorage(context, "regtest").read() as SecretResult.Found
            val key = derivePublicKeyFromMnemonic(seed.value, WalletConfiguration.base("regtest").network, 0u)
            // Only the public identity is exported, so the host can target this test user's push row.
            File(context.noBackupFilesDir, "push-test-public-key").writeText(key)
        } catch (error: Exception) {
            fail("Push integration failed at $stage (${error.javaClass.simpleName}, HTTP ${(error as? ServerFailure)?.status}); sensitive details suppressed")
        }
    }

    @Test fun nativeUnifiedPushRegistration() = runBlocking {
        optIn()
        val endpoint = InstrumentationRegistry.getArguments().getString("noahUnifiedPushEndpoint")
        assumeTrue("Supply a public HTTPS test endpoint", endpoint != null)
        var stage = "wallet creation"
        try {
            val marker = File(context.noBackupFilesDir, "push-test-wallet")
            check(marker.isFile || !WalletDataLocation(context.filesDir, "regtest").directory.exists())
            marker.writeText("1")
            val services = AppServices.shared(context)
            withContext(Dispatchers.IO) { WalletCreator.live(context).createOrResume() }
            SettingsStorage(context).setPersistedState(PersistedWalletState(isInitialized = true), PersistedWalletState.key, 0)
            stage = "server registration"
            if (services.registration.state.isRegisteredWithServer != true) {
                val device = DeviceInfo.current()
                val response = V0RegisterClient(services.client).register(RegisterPayload(deviceInfo = device.registrationPayload())).value()
                services.registration.apply(response, device)
            }
            stage = "UnifiedPush registration"
            val registration = UnifiedPushRegistration(context)
            registration.saveEndpoint(endpoint!!)
            val submit = PushRegistration(services::isOnboarded,
                { services.registration.state.isRegisteredWithServer == true },
                { PushNotifications.permissionGranted(context) }, { true }, registration::endpoint,
                { error("UnifiedPush must not fetch FCM") },
                {
                    com.noahwallet.api.generated.client.V0RegisterPushTokenClient(services.client)
                        .registerPushToken(com.noahwallet.api.generated.models.RegisterPushToken(it)).value()
                })
            assertEquals(PushRegistration.Outcome.registered, withTimeout(30_000) { submit.register() })
            stage = "mailbox authorization"
            services.ensureWalletOpen()
            withTimeout(30_000) { services.registration.setMailboxEnabled(true) }
            // Public identity and address only; no seed or authenticated URL leaves the device.
            val seed = SeedStorage(context, "regtest").read() as SecretResult.Found
            val key = derivePublicKeyFromMnemonic(seed.value, WalletConfiguration.base("regtest").network, 0u)
            File(context.noBackupFilesDir, "push-test-public-key").writeText(key)
            File(context.noBackupFilesDir, "push-test-lightning-address").writeText(services.registration.state.lightningAddress!!)
        } catch (error: Exception) {
            fail("UnifiedPush integration failed at $stage (${error.javaClass.simpleName}, HTTP ${(error as? ServerFailure)?.status}); sensitive details " +
                "suppressed")
        }
    }

    /** Host relays a real server payload to the service callback; this does not emulate a distributor. */
    @Test fun simulateUnifiedPushDelivery() = runBlocking {
        optIn()
        val payload = InstrumentationRegistry.getArguments().getString("noahUnifiedPushPayload")
        assumeTrue("Supply the server's JSON payload", payload != null)
        val service = NoahUnifiedPushService()
        ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", android.content.Context::class.java).apply {
            isAccessible = true
            invoke(service, context)
        }
        val prior = WorkManager.getInstance(context).getWorkInfosByTag("noah-push").get().map { it.id }.toSet()
        service.onMessage(org.unifiedpush.android.connector.data.PushMessage(payload!!.toByteArray(), false), UnifiedPushRegistration(context).instance)
        if (InstrumentationRegistry.getArguments().getString("noahDuplicateFcm") == "1") {
            val fcm = NoahFirebaseMessagingService()
            ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", android.content.Context::class.java).apply {
                isAccessible = true
                invoke(fcm, context)
            }
            fcm.onMessageReceived(RemoteMessage.Builder("test@gcm.googleapis.com").addData("body", payload).build())
            val queued = WorkManager.getInstance(context).getWorkInfosByTag("noah-push").get().filter { it.id !in prior }
            assertEquals("Both transports must coalesce into one claim job", 1, queued.size)
        }
        withTimeout(45_000) {
            while (true) {
                val completed = WorkManager.getInstance(context).getWorkInfosByTag("noah-push").get()
                    .firstOrNull { it.id !in prior && it.state.isFinished }
                if (completed != null) {
                    assertEquals(androidx.work.WorkInfo.State.SUCCEEDED, completed.state)
                    break
                }
                delay(100)
            }
        }
        if (PushPayload.decode(payload) is com.noahwallet.api.generated.models.NotificationDataLightningClaimRequest) {
            val balance = AppServices.shared(context).session.withOpen { it.summary().balance.totalSats }
            assertTrue("Claim must credit the wallet", balance > 0u)
        }
    }

    @Test fun simulateRnMessageThroughFirebaseService() = runBlocking {
        optIn()
        val k1 = InstrumentationRegistry.getArguments().getString("noahPushK1")
        assumeTrue("Supply a pending server job k1 for this test identity", k1 != null)
        val service = NoahFirebaseMessagingService()
        ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", android.content.Context::class.java).apply {
            isAccessible = true
            invoke(service, context)
        }
        val prior = WorkManager.getInstance(context).getWorkInfosByTag("noah-push").get().map { it.id }.toSet()
        val message = RemoteMessage.Builder("798188805132@gcm.googleapis.com")
            .setMessageId(UUID.randomUUID().toString())
            .addData("body", """{"notification_type":"maintenance","notification_k1":"$k1"}""")
            .addData("experienceId", "@noah/noah").build()
        service.onMessageReceived(message)
        withTimeout(90_000) {
            while (true) {
                val completed = WorkManager.getInstance(context).getWorkInfosByTag("noah-push").get()
                    .firstOrNull { it.id !in prior && it.state.isFinished }
                if (completed != null) {
                    assertEquals(androidx.work.WorkInfo.State.SUCCEEDED, completed.state)
                    break
                }
                delay(250)
            }
        }
    }
}
