package com.margelo.nitro.noahtools

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

import androidx.core.app.NotificationCompat
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
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

    private fun buildAuthHeaders(
        clazz: Class<*>,
        instance: Any,
        k1: String
    ): Map<String, String> {
        return try {
            val peakKeyPair = clazz.getMethod("peakKeyPair", Integer.TYPE)
            val keyPairResult = peakKeyPair.invoke(instance, 0) ?: return emptyMap()
            val publicKey = keyPairResult.javaClass.getMethod("getPublicKey").invoke(keyPairResult) as? String
                ?: return emptyMap()

            val signMessage = clazz.getMethod("signMessage", String::class.java, Integer.TYPE)
            val sig = signMessage.invoke(instance, k1, 0) as? String ?: return emptyMap()

            mapOf(
                "Content-Type" to "application/json",
                "x-auth-k1" to k1,
                "x-auth-sig" to sig,
                "x-auth-key" to publicKey
            )
        } catch (e: Exception) {
            NoahToolsLogging.performNativeLog("error", "NoahPushService", "Failed to build auth headers: ${e.message}")
            emptyMap()
        }
    }

    private fun postJson(
        baseUrl: String,
        endpoint: String,
        body: JSONObject,
        headers: Map<String, String>
    ): Boolean {
        return try {
            val url = "$baseUrl/v0$endpoint"
            val response = runBlocking {
                performNativePost(
                    url,
                    body.toString(),
                    headers,
                    30.0
                ).await()
            }
            response.status in 200.0..299.0
        } catch (e: Exception) {
            NoahToolsLogging.performNativeLog("error", "NoahPushService", "HTTP post failed: ${e.message}")
            false
        }
    }

    private fun reportJobStatus(
        clazz: Class<*>,
        instance: Any,
        server: String,
        reportType: String,
        status: String,
        errorMessage: String?,
        k1: String?
    ) {
        if (k1.isNullOrEmpty()) return
        val headers = buildAuthHeaders(clazz, instance, k1)
        if (headers.isEmpty()) return

        val payload = JSONObject()
            .put("report_type", reportType)
            .put("status", status)
            .put("error_message", errorMessage)
            .put("k1", k1)

        val ok = postJson(server, "/report_job_status", payload, headers)
        if (!ok) {
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
            val k1 = json.optNullableString("k1")
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
                    handleMaintenance(this, clazz, nativeInstance, server, k1)
                }

                "lightning_invoice_request" -> {
                    handleLightningInvoiceRequest(this, clazz, nativeInstance, json, server, k1)
                }

                "heartbeat" -> {
                    handleHeartbeat(clazz, nativeInstance, json, server, k1)
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
        k1: String?
    ) {
        try {
            ensureWalletLoaded(clazz, instance, context)
            clazz.getMethod("maintenanceWithOnchainDelegated").invoke(instance)
            NoahToolsLogging.performNativeLog(
                "info",
                "NoahPushService",
                "maintenanceWithOnchainDelegated() completed"
            )
            if (server != null) {
                reportJobStatus(clazz, instance, server, "maintenance", "success", null, k1)
            }
        } catch (e: Exception) {
            NoahToolsLogging.performNativeLog("error", "NoahPushService", "Maintenance handling failed: ${e.message}")
            if (server != null) {
                reportJobStatus(clazz, instance, server, "maintenance", "failure", e.message, k1)
            }
        }
    }

    private fun handleLightningInvoiceRequest(
        context: Context,
        clazz: Class<*>,
        instance: Any,
        json: JSONObject,
        server: String?,
        k1: String?
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

            if (server != null && k1 != null) {
                val headers = buildAuthHeaders(clazz, instance, k1)
                if (headers.isNotEmpty()) {
                    val payload = JSONObject()
                        .put("invoice", bolt11)
                        .put("transaction_id", txId)
                        .put("k1", k1)

                    val ok = postJson(server, "/lnurlp/submit_invoice", payload, headers)
                    if (!ok) {
                        NoahToolsLogging.performNativeLog("warn", "NoahPushService", "submit_invoice failed")
                        return
                    }
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
        clazz: Class<*>,
        instance: Any,
        json: JSONObject,
        server: String?,
        k1: String?
    ) {
        if (server == null || k1.isNullOrEmpty()) return
        val notificationId = json.optString("notification_id")
        val headers = buildAuthHeaders(clazz, instance, k1)
        if (headers.isEmpty()) return

        val payload = JSONObject()
            .put("notification_id", notificationId)
            .put("k1", k1)

        val ok = postJson(server, "/heartbeat_response", payload, headers)
        if (!ok) {
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
                String::class.java,
                String::class.java,
                String::class.java,
                String::class.java,
                String::class.java,
                String::class.java,
                Integer::class.javaObjectType,
                java.lang.Long::class.java,
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

    private fun loadBarkConfig(context: Context, appVariant: String, configConstructor: Constructor<*>): Any? {
        val configJson = readConfigJson(context) ?: return null
        val variantJson = configJson.optJSONObject(appVariant)

        if (variantJson == null) {
            NoahToolsLogging.performNativeLog("error", "NoahPushService", "Config for variant $appVariant not found")
            return null
        }

        return try {
            configConstructor.newInstance(
                variantJson.optNullableString("ark"),
                readServerAccessTokenFromStorage(context, appVariant),
                variantJson.optNullableString("esplora"),
                variantJson.optNullableString("bitcoind"),
                variantJson.optNullableString("bitcoindCookie"),
                variantJson.optNullableString("bitcoindUser"),
                variantJson.optNullableString("bitcoindPass"),
                variantJson.optNullableInt("vtxoRefreshExpiryThreshold"),
                variantJson.optNullableLong("fallbackFeeRate"),
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

    private fun readServerAccessTokenFromStorage(context: Context, appVariant: String): String? {
        if (appVariant != "mainnet") return null

        return try {
            val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)

            val prefs = EncryptedSharedPreferences.create(
                "noah_native_secrets",
                masterKeyAlias,
                context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )

            prefs.getString("server_access_token_$appVariant", null)?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            NoahToolsLogging.performNativeLog(
                "error",
                "NoahPushService",
                "Error retrieving server access token from native storage: ${e.message}"
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

    private fun JSONObject.optNullableInt(key: String): Int? {
        if (!has(key) || isNull(key)) return null
        return optInt(key)
    }

    private fun JSONObject.optNullableLong(key: String): Long? {
        if (!has(key) || isNull(key)) return null
        return optLong(key)
    }
}
