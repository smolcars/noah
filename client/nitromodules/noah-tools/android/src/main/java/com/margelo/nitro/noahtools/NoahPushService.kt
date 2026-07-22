package com.margelo.nitro.noahtools

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build

import androidx.core.app.NotificationCompat
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.margelo.nitro.noahtools.NoahToolsHttp.performNativeGet
import com.margelo.nitro.noahtools.NoahToolsHttp.performNativePost
import com.margelo.nitro.JNIOnLoad
import com.margelo.nitro.noahtools.noahtoolsOnLoad
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage
import java.lang.reflect.Constructor

class NoahPushService : PushService() {
    private val notificationChannelId = "noah-push-default"
    private val walletLock = Any()
    private val authLock = Any()

    override fun onCreate() {
        super.onCreate()
        // Ensure all native libraries used by Nitro (core + modules) are loaded for background process
        JNIOnLoad.initializeNativeNitro()
        noahtoolsOnLoad.initializeNative()
    }

    private fun ensureNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(notificationChannelId) != null) return

        val channel = NotificationChannel(
            notificationChannelId,
            "Noah notifications",
            NotificationManager.IMPORTANCE_HIGH
        )
        manager.createNotificationChannel(channel)
    }

    private fun getAppVariant(context: Context): String {
        val packageName = context.packageName
        return when {
            packageName.endsWith(".regtest") -> "regtest"
            packageName.endsWith(".signet") -> "signet"
            else -> "mainnet"
        }
    }

    private fun getServerEndpoint(context: Context): String? {
        val configJson = readConfigJson(context) ?: return null
        val variantJson = configJson.optJSONObject(getAppVariant(context)) ?: return null
        return variantJson.optNullableString("server")
    }

    private fun postJson(
        baseUrl: String,
        endpoint: String,
        body: JSONObject,
        headers: Map<String, String>
    ): HttpResponse? {
        return try {
            val url = "$baseUrl/v0$endpoint"
            runBlocking {
                performNativePost(
                    url,
                    body.toString(),
                    headers,
                    30.0
                ).await()
            }
        } catch (e: Exception) {
            NoahToolsLogging.performNativeLog("error", "NoahPushService", "HTTP post failed: ${e.message}")
            null
        }
    }

    private fun getJson(baseUrl: String, endpoint: String): HttpResponse? {
        return try {
            val url = "$baseUrl/v0$endpoint"
            runBlocking {
                performNativeGet(url, emptyMap(), 30.0).await()
            }
        } catch (e: Exception) {
            NoahToolsLogging.performNativeLog("error", "NoahPushService", "HTTP get failed: ${e.message}")
            null
        }
    }

    private fun nativeSecrets(context: Context): SharedPreferences? {
        return try {
            val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            EncryptedSharedPreferences.create(
                "noah_native_secrets",
                masterKeyAlias,
                context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            NoahToolsLogging.performNativeLog(
                "warn",
                "NoahPushService",
                "Unable to access native auth storage: ${e.message}"
            )
            null
        }
    }

    private fun authStorageKey(context: Context, suffix: String): String =
        "server_auth_${getAppVariant(context)}_$suffix"

    private fun clearCachedAccessToken(context: Context) {
        nativeSecrets(context)?.edit()
            ?.remove(authStorageKey(context, "token"))
            ?.remove(authStorageKey(context, "expires_at"))
            ?.remove(authStorageKey(context, "public_key"))
            ?.apply()
    }

    private fun currentPublicKey(clazz: Class<*>, instance: Any): String? {
        return try {
            val peekKeyPair = clazz.getMethod("peekKeyPair", Integer.TYPE)
            val keyPairResult = peekKeyPair.invoke(instance, 0) ?: return null
            keyPairResult.javaClass.getMethod("getPublicKey").invoke(keyPairResult) as? String
        } catch (e: Exception) {
            NoahToolsLogging.performNativeLog(
                "error",
                "NoahPushService",
                "Failed to derive server auth public key: ${e.message}"
            )
            null
        }
    }

    private fun getAccessToken(
        context: Context,
        clazz: Class<*>,
        instance: Any,
        server: String,
        forceRefresh: Boolean = false
    ): String? = synchronized(authLock) {
        val publicKey = currentPublicKey(clazz, instance) ?: return@synchronized null
        val prefs = nativeSecrets(context)
        val nowSeconds = System.currentTimeMillis() / 1000

        if (!forceRefresh && prefs != null) {
            val cachedToken = prefs.getString(authStorageKey(context, "token"), null)
            val cachedPublicKey = prefs.getString(authStorageKey(context, "public_key"), null)
            val expiresAt = prefs.getLong(authStorageKey(context, "expires_at"), 0)

            if (
                !cachedToken.isNullOrEmpty() &&
                cachedPublicKey == publicKey &&
                expiresAt > nowSeconds + AUTH_TOKEN_REFRESH_WINDOW_SECONDS
            ) {
                return@synchronized cachedToken
            }
        }

        val k1Response = getJson(server, "/getk1")
        if (k1Response == null || k1Response.status !in 200.0..299.0) {
            NoahToolsLogging.performNativeLog("warn", "NoahPushService", "Failed to request auth challenge")
            return@synchronized null
        }

        val k1 = try {
            JSONObject(k1Response.body).optNullableString("k1")
        } catch (e: Exception) {
            NoahToolsLogging.performNativeLog("warn", "NoahPushService", "Invalid auth challenge response")
            null
        } ?: return@synchronized null

        val signature = try {
            val signMessage = clazz.getMethod("signMessage", String::class.java, Integer.TYPE)
            signMessage.invoke(instance, k1, 0) as? String
        } catch (e: Exception) {
            NoahToolsLogging.performNativeLog(
                "error",
                "NoahPushService",
                "Failed to sign server auth challenge: ${e.message}"
            )
            null
        } ?: return@synchronized null

        val loginPayload = JSONObject()
            .put("key", publicKey)
            .put("sig", signature)
            .put("k1", k1)
        val loginResponse = postJson(
            server,
            "/auth/login",
            loginPayload,
            mapOf("Content-Type" to "application/json")
        )

        if (loginResponse == null || loginResponse.status !in 200.0..299.0) {
            NoahToolsLogging.performNativeLog("warn", "NoahPushService", "Server auth login failed")
            return@synchronized null
        }

        val loginJson = try {
            JSONObject(loginResponse.body)
        } catch (e: Exception) {
            NoahToolsLogging.performNativeLog("warn", "NoahPushService", "Invalid auth login response")
            return@synchronized null
        }
        val accessToken = loginJson.optNullableString("access_token") ?: return@synchronized null
        val expiresInSeconds = loginJson.optLong("expires_in_seconds", 0)

        if (prefs != null && expiresInSeconds > 0) {
            prefs.edit()
                .putString(authStorageKey(context, "token"), accessToken)
                .putLong(authStorageKey(context, "expires_at"), nowSeconds + expiresInSeconds)
                .putString(authStorageKey(context, "public_key"), publicKey)
                .apply()
        }

        accessToken
    }

    private fun postAuthenticatedJson(
        context: Context,
        clazz: Class<*>,
        instance: Any,
        server: String,
        endpoint: String,
        body: JSONObject
    ): HttpResponse? {
        var accessToken = getAccessToken(context, clazz, instance, server) ?: return null
        var response = postJson(
            server,
            endpoint,
            body,
            mapOf(
                "Content-Type" to "application/json",
                "Authorization" to "Bearer $accessToken"
            )
        )

        if (response?.status == 401.0) {
            clearCachedAccessToken(context)
            accessToken = getAccessToken(context, clazz, instance, server, forceRefresh = true)
                ?: return response
            response = postJson(
                server,
                endpoint,
                body,
                mapOf(
                    "Content-Type" to "application/json",
                    "Authorization" to "Bearer $accessToken"
                )
            )
        }

        return response
    }

    private fun reportJobStatus(
        context: Context,
        clazz: Class<*>,
        instance: Any,
        server: String,
        reportType: String,
        status: String,
        errorMessage: String?,
        notificationK1: String?
    ) {
        if (notificationK1.isNullOrEmpty()) {
            NoahToolsLogging.performNativeLog("warn", "NoahPushService", "Maintenance notification is missing notification_k1")
            return
        }

        val payload = JSONObject()
            .put("notification_k1", notificationK1)
            .put("report_type", reportType)
            .put("status", status)
            .put("error_message", errorMessage)

        var response = postAuthenticatedJson(
            context,
            clazz,
            instance,
            server,
            "/report_job_status",
            payload
        )

        for (retryDelayMillis in JOB_STATUS_RETRY_DELAYS_MILLIS) {
            if (response?.status != 404.0) {
                break
            }

            try {
                Thread.sleep(retryDelayMillis)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }

            response = postAuthenticatedJson(
                context,
                clazz,
                instance,
                server,
                "/report_job_status",
                payload
            )
        }

        if (response == null || response.status !in 200.0..299.0) {
            NoahToolsLogging.performNativeLog(
                "warn",
                "NoahPushService",
                "Failed to report job status: $reportType/$status"
            )
        }
    }

    private fun ensureWalletLoaded(clazz: Class<*>, instance: Any, context: Context) {
        synchronized(walletLock) {
            val isLoadedMethod = clazz.getMethod("isWalletLoaded")
            val loaded = isLoadedMethod.invoke(instance) as Boolean
            if (!loaded) {
                NoahToolsLogging.performNativeLog("info", "NoahPushService", "Wallet not loaded, attempting to load...")
                try {
                    loadWallet(clazz, instance, context)
                } catch (e: Exception) {
                    NoahToolsLogging.performNativeLog(
                        "info",
                        "NoahPushService",
                        "Wallet was loaded by another thread/process, continuing..."
                    )
                    throw e;
                }
            }
        }
    }

    override fun onMessage(message: PushMessage, instance: String) {
        val messageString = String(message.content)
        NoahToolsLogging.performNativeLog("debug", "NoahPushService", "Received message: $messageString")

        try {
            val json = JSONObject(messageString)
            val type = json.optString("notification_type")
            val server = getServerEndpoint(this)

            val clazz = Class.forName("com.margelo.nitro.nitroark.NitroArkNative")
            val nativeInstance = clazz.getField("INSTANCE").get(null) ?: return

            when (type) {
                "maintenance" -> {
                    NoahToolsLogging.performNativeLog(
                        "info",
                        "NoahPushService",
                        "Handling maintenance notification via JNI"
                    )
                    handleMaintenance(
                        this,
                        clazz,
                        nativeInstance,
                        server,
                        json.optNullableString("notification_k1")
                    )
                }

                "lightning_invoice_request" -> {
                    handleLightningInvoiceRequest(this, clazz, nativeInstance, json, server)
                }

                "heartbeat" -> {
                    handleHeartbeat(this, clazz, nativeInstance, json, server)
                }

                else -> NoahToolsLogging.performNativeLog(
                    "warn",
                    "NoahPushService",
                    "Unsupported notification type: $type"
                )
            }
        } catch (e: Exception) {
            NoahToolsLogging.performNativeLog("error", "NoahPushService", "Failed to parse message: ${e.message}")
        }
    }

    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        NoahToolsLogging.performNativeLog("info", "NoahPushService", "New Endpoint: ${endpoint.url}")
        val prefs = getSharedPreferences("noah_unified_push", Context.MODE_PRIVATE)
        // Save per-instance to avoid collisions between app variants, and keep legacy key for older reads.
        prefs.edit()
            .putString("endpoint_${instance}", endpoint.url)
            .putString("endpoint", endpoint.url)
            .apply()
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) {
        NoahToolsLogging.performNativeLog("error", "NoahPushService", "Registration failed: $reason")
    }

    override fun onUnregistered(instance: String) {
        NoahToolsLogging.performNativeLog("info", "NoahPushService", "Unregistered")
    }

    private fun handleMaintenance(
        context: Context,
        clazz: Class<*>,
        instance: Any,
        server: String?,
        notificationK1: String?
    ) {
        try {
            ensureWalletLoaded(clazz, instance, context)
            clazz.getMethod("maintenanceDelegated").invoke(instance)
            NoahToolsLogging.performNativeLog(
                "info",
                "NoahPushService",
                "maintenanceDelegated() completed"
            )
            if (server != null) {
                reportJobStatus(
                    context,
                    clazz,
                    instance,
                    server,
                    "maintenance",
                    "success",
                    null,
                    notificationK1
                )
            }
        } catch (e: Exception) {
            NoahToolsLogging.performNativeLog("error", "NoahPushService", "Maintenance handling failed: ${e.message}")
            if (server != null) {
                reportJobStatus(
                    context,
                    clazz,
                    instance,
                    server,
                    "maintenance",
                    "failure",
                    e.message,
                    notificationK1
                )
            }
        }
    }

    private fun handleLightningInvoiceRequest(
        context: Context,
        clazz: Class<*>,
        instance: Any,
        json: JSONObject,
        server: String?
    ) {
        try {
            ensureWalletLoaded(clazz, instance, context)

            val amountMsat = json.optLong("amount")
            val sats = amountMsat / 1000
            val txId = json.optString("transaction_id")

            val bolt11Method = clazz.getMethod("bolt11Invoice", java.lang.Long.TYPE)
            // JS path converts msat -> sats before calling bolt11Invoice; mirror that here.
            val invoiceResult = bolt11Method.invoke(instance, sats) ?: return
            val bolt11 =
                invoiceResult.javaClass.getMethod("getBolt11Invoice").invoke(invoiceResult) as? String
                    ?: return
            val paymentHash =
                invoiceResult.javaClass.getMethod("getPaymentHash").invoke(invoiceResult) as? String
                    ?: return

            if (server != null) {
                val payload = JSONObject()
                    .put("invoice", bolt11)
                    .put("transaction_id", txId)
                val response = postAuthenticatedJson(
                    context,
                    clazz,
                    instance,
                    server,
                    "/lnurlp/submit_invoice",
                    payload
                )
                if (response == null || response.status !in 200.0..299.0) {
                    NoahToolsLogging.performNativeLog("warn", "NoahPushService", "submit_invoice failed")
                    return
                }
            }

            // Wait for payment and claim (blocking) but off the push-service thread.
            val tryClaim = clazz.getMethod(
                "tryClaimLightningReceive",
                String::class.java,
                Boolean::class.java,
                String::class.java
            )
            Thread {
                try {
                    NoahToolsLogging.performNativeLog(
                        "info",
                        "NoahPushService",
                        "Waiting for lightning payment (async)..."
                    )

                    var claimSucceeded = false
                    for (attempt in 1..30) {
                        try {
                            tryClaim.invoke(instance, paymentHash, false, null)
                            claimSucceeded = true
                            break
                        } catch (e: Exception) {
                            NoahToolsLogging.performNativeLog(
                                "warn",
                                "NoahPushService",
                                "tryClaim attempt $attempt/30 failed: ${e.message}"
                            )
                            if (attempt < 30) {
                                Thread.sleep(800)
                            }
                        }
                    }

                    if (claimSucceeded) {
                        NoahToolsLogging.performNativeLog("info", "NoahPushService", "Lightning payment claimed")

                        // Local notification to inform user only after claim succeeds
                        ensureNotificationChannel(context)
                        val notification = NotificationCompat.Builder(context, notificationChannelId)
                            .setSmallIcon(android.R.drawable.stat_notify_more)
                            .setContentTitle("Lightning Payment Received! ⚡")
                            .setContentText("You received $sats sats")
                            .setPriority(NotificationCompat.PRIORITY_HIGH)
                            .setAutoCancel(true)
                            .build()
                        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        manager.notify((System.currentTimeMillis() % 100000).toInt(), notification)
                    } else {
                        NoahToolsLogging.performNativeLog(
                            "warn",
                            "NoahPushService",
                            "tryClaim failed after 30 attempts, skipping notification"
                        )
                    }
                } catch (e: Exception) {
                    NoahToolsLogging.performNativeLog(
                        "error",
                        "NoahPushService",
                        "Failed while waiting/claiming lightning payment: ${e.message}"
                    )
                }
            }.start()
        } catch (e: Exception) {
            NoahToolsLogging.performNativeLog(
                "error",
                "NoahPushService",
                "Failed to handle lightning invoice request: ${e.message}"
            )
        }
    }

    private fun handleHeartbeat(
        context: Context,
        clazz: Class<*>,
        instance: Any,
        json: JSONObject,
        server: String?
    ) {
        if (server == null) return
        val notificationId = json.optNullableString("notification_id")
        if (notificationId == null) {
            NoahToolsLogging.performNativeLog("warn", "NoahPushService", "Heartbeat notification is missing notification_id")
            return
        }

        try {
            ensureWalletLoaded(clazz, instance, context)
        } catch (e: Exception) {
            NoahToolsLogging.performNativeLog("error", "NoahPushService", "Failed to load wallet for heartbeat: ${e.message}")
            return
        }

        val payload = JSONObject()
            .put("notification_id", notificationId)

        val response = postAuthenticatedJson(
            context,
            clazz,
            instance,
            server,
            "/heartbeat_response",
            payload
        )
        if (response == null || response.status !in 200.0..299.0) {
            NoahToolsLogging.performNativeLog("warn", "NoahPushService", "Failed to respond to heartbeat")
        } else {
            NoahToolsLogging.performNativeLog("debug", "NoahPushService", "Heartbeat response sent")
        }
    }

    private fun loadWallet(clazz: Class<*>, instance: Any, context: Context) {
        val packageName = context.packageName
        val appVariant = when {
            packageName.endsWith(".regtest") -> "regtest"
            packageName.endsWith(".signet") -> "signet"
            else -> "mainnet"
        }

        val mnemonic = readMnemonicFromStorage(context, appVariant)

        if (mnemonic.isNullOrEmpty()) {
            NoahToolsLogging.performNativeLog(
                "error",
                "NoahPushService",
                "Cannot load wallet: Mnemonic not found/decrypted."
            )
            return
        }

        val datadir = "${context.filesDir.path}/noah-data-$appVariant"

        val loadWalletMethod = clazz.getMethod(
            "loadWallet",
            String::class.java,
            String::class.java,
            Boolean::class.java,
            Boolean::class.java,
            Boolean::class.java,
            Integer::class.javaObjectType,
            Class.forName("com.margelo.nitro.nitroark.NitroArkNative\$AndroidBarkConfig")
        )

        val configClass = Class.forName("com.margelo.nitro.nitroark.NitroArkNative\$AndroidBarkConfig")
        val configConstructor = try {
            configClass.getConstructor(
                String::class.java,
                Integer.TYPE,
                java.lang.Long.TYPE,
                String::class.java,
                String::class.java,
                String::class.java,
                String::class.java,
                String::class.java,
                String::class.java,
                Integer::class.javaObjectType,
                Integer::class.javaObjectType,
                Integer::class.javaObjectType
            )
        } catch (e: Exception) {
            NoahToolsLogging.performNativeLog(
                "error",
                "NoahPushService",
                "Unable to find AndroidBarkConfig constructor: ${e.message}"
            )
            return
        }

        val config = loadBarkConfig(context, appVariant, configConstructor) ?: run {
            NoahToolsLogging.performNativeLog(
                "error",
                "NoahPushService",
                "Cannot load wallet: Bark config missing for $appVariant"
            )
            return
        }

        val regtest = appVariant == "regtest"
        val signet = appVariant == "signet"
        val bitcoin = appVariant == "mainnet"

        loadWalletMethod.invoke(instance, datadir, mnemonic, regtest, signet, bitcoin, null, config)
        NoahToolsLogging.performNativeLog("info", "NoahPushService", "Wallet loaded successfully via JNI")
    }

    private fun readMnemonicFromStorage(context: Context, appVariant: String): String? {
        return try {
            val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)

            val prefs = EncryptedSharedPreferences.create(
                "noah_native_secrets",
                masterKeyAlias,
                context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )

            prefs.getString("mnemonic_$appVariant", null)
        } catch (e: Exception) {
            NoahToolsLogging.performNativeLog(
                "error",
                "NoahPushService",
                "Error retrieving mnemonic from native storage: ${e.message}"
            )
            null
        }
    }

    private fun readEsploraFromStorage(context: Context, appVariant: String): String? {
        return try {
            val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)

            val prefs = EncryptedSharedPreferences.create(
                "noah_native_secrets",
                masterKeyAlias,
                context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )

            prefs.getString("esplora_$appVariant", null)
        } catch (e: Exception) {
            NoahToolsLogging.performNativeLog(
                "warn",
                "NoahPushService",
                "Unable to read custom Esplora endpoint: ${e.message}"
            )
            null
        }
    }

    private fun loadBarkConfig(context: Context, appVariant: String, configConstructor: Constructor<*>): Any? {
        val configJson = readConfigJson(context) ?: return null
        val variantJson = configJson.optJSONObject(appVariant)

        if (variantJson == null) {
            NoahToolsLogging.performNativeLog("error", "NoahPushService", "Config for variant $appVariant not found")
            return null
        }

        return try {
            configConstructor.newInstance(
                variantJson.requireString("ark"),
                variantJson.requireInt("vtxoRefreshExpiryThreshold"),
                variantJson.requireLong("fallbackFeeRate"),
                null,
                readEsploraFromStorage(context, appVariant)
                    ?: variantJson.optNullableString("esplora"),
                variantJson.optNullableString("bitcoind"),
                variantJson.optNullableString("bitcoindCookie"),
                variantJson.optNullableString("bitcoindUser"),
                variantJson.optNullableString("bitcoindPass"),
                variantJson.optNullableInt("htlcRecvClaimDelta"),
                variantJson.optNullableInt("vtxoExitMargin"),
                variantJson.optNullableInt("roundTxRequiredConfirmations")
            )
        } catch (e: Exception) {
            NoahToolsLogging.performNativeLog(
                "error",
                "NoahPushService",
                "Failed to construct bark config for $appVariant: ${e.message}"
            )
            null
        }
    }

    private fun readConfigJson(context: Context): JSONObject? {
        return try {
            context.assets.open("noah_bark_config.json").use { input ->
                val jsonString = input.bufferedReader().use { it.readText() }
                JSONObject(jsonString)
            }
        } catch (e: Exception) {
            NoahToolsLogging.performNativeLog(
                "error",
                "NoahPushService",
                "Failed to read bark config file: ${e.message}"
            )
            null
        }
    }

    private fun JSONObject.optNullableString(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key).takeIf { it.isNotEmpty() }
    }

    private fun JSONObject.requireString(key: String): String =
        requireNotNull(optNullableString(key)) { "Missing required config value '$key'." }

    private fun JSONObject.optNullableInt(key: String): Int? {
        if (!has(key) || isNull(key)) return null
        return optInt(key)
    }

    private fun JSONObject.requireInt(key: String): Int =
        requireNotNull(optNullableInt(key)) { "Missing required config value '$key'." }

    private fun JSONObject.optNullableLong(key: String): Long? {
        if (!has(key) || isNull(key)) return null
        return optLong(key)
    }

    private fun JSONObject.requireLong(key: String): Long =
        requireNotNull(optNullableLong(key)) { "Missing required config value '$key'." }

    companion object {
        private const val AUTH_TOKEN_REFRESH_WINDOW_SECONDS = 5 * 60
        private val JOB_STATUS_RETRY_DELAYS_MILLIS = longArrayOf(250L, 1_000L, 2_000L)
    }
}
