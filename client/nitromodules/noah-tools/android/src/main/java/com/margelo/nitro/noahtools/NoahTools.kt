package com.margelo.nitro.noahtools

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.margelo.nitro.core.Promise
import com.margelo.nitro.noahtools.audio.NoahToolsAudio
import com.margelo.nitro.NitroModules
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import android.content.pm.PackageManager
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.margelo.nitro.noahtools.UnifiedPushDistributor
import com.margelo.nitro.noahtools.Variant_NullType_String
import com.margelo.nitro.core.NullType
import org.unifiedpush.android.connector.UnifiedPush

class NoahTools : HybridNoahToolsSpec() {

    private fun resolveWidgetComponent(context: Context, appGroup: String): ComponentName? {
        val providerSimpleName = when (appGroup) {
            "com.noahwallet.regtest" -> "NoahWidgetRegtestProvider"
            "com.noahwallet.signet" -> "NoahWidgetSignetProvider"
            "com.noahwallet.mainnet" -> "NoahWidgetMainnetProvider"
            else -> null
        }

        if (providerSimpleName == null) {
            return null
        }

        val providerClassName = listOf(
            "com.noahwallet.mainnet.widgets.$providerSimpleName",
            "com.noahwallet.widgets.$providerSimpleName",
        ).firstOrNull { className ->
            runCatching {
                Class.forName(className, false, context.classLoader)
            }.isSuccess
        } ?: return null

        // Build an explicit component to ensure the broadcast reaches the widget provider even
        // when the app process is not running.
        return ComponentName(context, providerClassName)
    }

    override fun nativePost(
        url: String,
        body: String,
        headers: Map<String, String>,
        timeoutSeconds: Double
    ): Promise<HttpResponse> {
        return NoahToolsHttp.performNativePost(url, body, headers, timeoutSeconds)
    }

    override fun nativeGet(
        url: String,
        headers: Map<String, String>,
        timeoutSeconds: Double
    ): Promise<HttpResponse> {
        return NoahToolsHttp.performNativeGet(url, headers, timeoutSeconds)
    }

    override fun getAppVariant(): String {
        return NoahToolsLogging.performGetAppVariant()
    }

    override fun getAppLogs(): Promise<Array<String>> {
        return NoahToolsLogging.performGetAppLogs()
    }

    override fun createBackup(mnemonic: String): Promise<String> {
        return NoahToolsBackup.performCreateBackup(mnemonic)
    }

    override fun restoreBackup(encryptedData: String, mnemonic: String): Promise<Boolean> {
        return NoahToolsBackup.performRestoreBackup(encryptedData, mnemonic)
    }

    override fun nativeLog(level: String, tag: String, message: String) {
        NoahToolsLogging.performNativeLog(level, tag, message)
    }

    override fun playAudio(filePath: String): Promise<Unit> {
        return NoahToolsAudio.performPlayAudio(filePath)
    }

    override fun pauseAudio() {
        NoahToolsAudio.performPauseAudio()
    }

    override fun stopAudio() {
        NoahToolsAudio.performStopAudio()
    }

    override fun resumeAudio() {
        NoahToolsAudio.performResumeAudio()
    }

    override fun seekAudio(positionSeconds: Double) {
        NoahToolsAudio.performSeekAudio(positionSeconds)
    }

    override fun getAudioDuration(): Double {
        return NoahToolsAudio.performGetAudioDuration()
    }

    override fun getAudioPosition(): Double {
        return NoahToolsAudio.performGetAudioPosition()
    }

    override fun isAudioPlaying(): Boolean {
        return NoahToolsAudio.performIsAudioPlaying()
    }

    override fun updateWidgetData(
        totalBalance: Double,
        onchainBalance: Double,
        offchainBalance: Double,
        pendingBalance: Double,
        closestExpiryBlocks: Double,
        expiryThreshold: Double,
        appGroup: String
    ) {
        val context = NitroModules.applicationContext ?: return
        val prefs = context.getSharedPreferences(appGroup, Context.MODE_PRIVATE)
        val widgetComponent = resolveWidgetComponent(context, appGroup) ?: return

        prefs.edit().apply {
            putLong("totalBalance", totalBalance.toLong())
            putLong("onchainBalance", onchainBalance.toLong())
            putLong("offchainBalance", offchainBalance.toLong())
            putLong("pendingBalance", pendingBalance.toLong())
            putLong("closestExpiryBlocks", closestExpiryBlocks.toLong())
            putLong("expiryThreshold", expiryThreshold.toLong())
            putLong("lastUpdated", System.currentTimeMillis())
            apply()
        }

        // Trigger widget update with an explicit broadcast so Android delivers it even when the
        // app is in the background.
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val appWidgetIds = appWidgetManager.getAppWidgetIds(widgetComponent)

        if (appWidgetIds.isNotEmpty()) {
            val intent = Intent("com.noahwallet.action.WIDGET_DATA_CHANGED").apply {
                component = widgetComponent
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, appWidgetIds)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            }
            context.sendBroadcast(intent)
        }
    }

    override fun isGooglePlayServicesAvailable(): Boolean {
        val context = NitroModules.applicationContext ?: return false
        val googleApiAvailability = GoogleApiAvailability.getInstance()
        val resultCode = googleApiAvailability.isGooglePlayServicesAvailable(context)
        return resultCode == ConnectionResult.SUCCESS
    }

    override fun registerUnifiedPush() {
        val context = NitroModules.applicationContext ?: return

        val distributors = UnifiedPush.getDistributors(context)
        val savedDistributor = UnifiedPush.getSavedDistributor(context)

        // Prefer a saved distributor, otherwise pick ntfy if present, otherwise fall back to first available.
        val distributorToUse = savedDistributor
            ?: distributors.firstOrNull { it.contains("ntfy", ignoreCase = true) }
            ?: distributors.firstOrNull()

        if (distributorToUse == null) {
            // No distributor installed (e.g. ntfy missing)
            return
        }

        UnifiedPush.saveDistributor(context, distributorToUse)
        val instance = when (context.packageName) {
            "com.noahwallet.regtest" -> "noah-regtest"
            "com.noahwallet.signet" -> "noah-signet"
            else -> "noah-mainnet"
        }

        val message = "Noah registering for UnifiedPush ($instance)"
        UnifiedPush.register(context, instance, message, null)
    }

    override fun getUnifiedPushEndpoint(): String {
        val context = NitroModules.applicationContext ?: return ""
        val prefs = context.getSharedPreferences("noah_unified_push", Context.MODE_PRIVATE)

        val instance = when (context.packageName) {
            "com.noahwallet.regtest" -> "noah-regtest"
            "com.noahwallet.signet" -> "noah-signet"
            else -> "noah-mainnet"
        }

        // Prefer instance-specific key, fall back to the legacy flat key.
        return prefs.getString("endpoint_$instance", prefs.getString("endpoint", "")) ?: ""
    }

    override fun getUnifiedPushDistributors(): Array<UnifiedPushDistributor> {
        val context = NitroModules.applicationContext ?: return emptyArray()
        val saved = UnifiedPush.getSavedDistributor(context)
        val connected = UnifiedPush.getAckDistributor(context)

        return UnifiedPush.getDistributors(context).map { id ->
            val label = resolveAppLabel(context, id) ?: id
            UnifiedPushDistributor(
                id = id,
                name = label,
                isSaved = (id == saved),
                isConnected = (id == connected)
            )
        }.toTypedArray()
    }

    override fun setUnifiedPushDistributor(distributorId: Variant_NullType_String?) {
        val context = NitroModules.applicationContext ?: return
        distributorId?.match(
            { _: NullType -> UnifiedPush.removeDistributor(context) },
            { id: String -> UnifiedPush.saveDistributor(context, id) }
        )
    }

    private fun resolveAppLabel(context: android.content.Context, packageId: String): String? {
        return try {
            val pm = context.packageManager
            val info = pm.getPackageInfo(packageId, PackageManager.PackageInfoFlags.of(0))
            val appInfo = info.applicationInfo ?: return null
            pm.getApplicationLabel(appInfo)?.toString()
        } catch (e: Exception) {
            null
        }
    }

    override fun storeNativeMnemonic(mnemonic: String): Promise<Unit> {
        return Promise.async {
            val context = NitroModules.applicationContext ?: return@async
            val variant = when (context.packageName) {
                "com.noahwallet.regtest" -> "regtest"
                "com.noahwallet.signet" -> "signet"
                else -> "mainnet"
            }

            try {
                val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)

                val prefs = EncryptedSharedPreferences.create(
                    "noah_native_secrets",
                    masterKeyAlias,
                    context,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )

                prefs.edit()
                    .putString("mnemonic_$variant", mnemonic)
                    .apply()
            } catch (e: Exception) {
                throw Exception("Failed to store native mnemonic: ${e.message}", e)
            }
        }
    }

    override fun storeNativeServerAccessToken(token: String): Promise<Unit> {
        return Promise.async {
            val context = NitroModules.applicationContext ?: return@async
            val variant = when (context.packageName) {
                "com.noahwallet.regtest" -> "regtest"
                "com.noahwallet.signet" -> "signet"
                else -> "mainnet"
            }

            try {
                val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)

                val prefs = EncryptedSharedPreferences.create(
                    "noah_native_secrets",
                    masterKeyAlias,
                    context,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )

                val trimmedToken = token.trim()
                prefs.edit().apply {
                    if (trimmedToken.isEmpty()) {
                        remove("server_access_token_$variant")
                    } else {
                        putString("server_access_token_$variant", trimmedToken)
                    }
                }.apply()
            } catch (e: Exception) {
                throw Exception("Failed to store native server access token: ${e.message}", e)
            }
        }
    }
}
