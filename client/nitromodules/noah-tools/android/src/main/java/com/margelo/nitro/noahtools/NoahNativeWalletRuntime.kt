package com.margelo.nitro.noahtools

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.margelo.nitro.JNIOnLoad
import java.lang.reflect.Constructor
import org.json.JSONObject

internal object NoahNativeWalletRuntime {
    private const val SECRETS_FILE = "noah_native_secrets"
    private const val CONFIG_FILE = "noah_bark_config.json"
    private const val NITRO_ARK_CLASS = "com.margelo.nitro.nitroark.NitroArkNative"
    private const val NITRO_ARK_CONFIG_CLASS =
        "com.margelo.nitro.nitroark.NitroArkNative\$AndroidBarkConfig"

    @Volatile
    private var isInitialized = false
    private val walletLoadLock = Any()
    private val runtimeInitializationLock = Any()

    data class Handle(
        val clazz: Class<*>,
        val instance: Any
    )

    fun initializeNativeRuntime() {
        if (isInitialized) {
            return
        }

        synchronized(runtimeInitializationLock) {
            if (isInitialized) {
                return
            }

            JNIOnLoad.initializeNativeNitro()
            noahtoolsOnLoad.initializeNative()
            isInitialized = true
        }
    }

    fun appVariant(context: Context): String {
        return when {
            context.packageName.endsWith(".regtest") -> "regtest"
            context.packageName.endsWith(".signet") -> "signet"
            else -> "mainnet"
        }
    }

    fun nativeSecrets(context: Context): SharedPreferences {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        return EncryptedSharedPreferences.create(
            SECRETS_FILE,
            masterKeyAlias,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun readMnemonic(context: Context): String? {
        val variant = appVariant(context)
        return nativeSecrets(context).getString("mnemonic_$variant", null)
    }

    fun readEsploraEndpoint(context: Context): String? {
        val variant = appVariant(context)
        return nativeSecrets(context).getString("esplora_$variant", null)
    }

    fun serverEndpoint(context: Context): String? {
        return readConfigJson(context)
            .optJSONObject(appVariant(context))
            ?.optNullableString("server")
    }

    fun getHandle(): Handle {
        val clazz = Class.forName(NITRO_ARK_CLASS)
        val instance = clazz.getField("INSTANCE").get(null)
            ?: throw IllegalStateException("NitroArkNative instance is unavailable")
        return Handle(clazz, instance)
    }

    fun ensureWalletLoaded(context: Context, handle: Handle = getHandle()) {
        synchronized(walletLoadLock) {
            val isLoaded = handle.clazz.getMethod("isWalletLoaded")
                .invoke(handle.instance) as Boolean
            if (isLoaded) {
                return
            }

            loadWallet(context, handle)
        }
    }

    fun runMaintenanceDelegated(context: Context) {
        initializeNativeRuntime()
        val handle = getHandle()
        ensureWalletLoaded(context, handle)
        handle.clazz.getMethod("maintenanceDelegated").invoke(handle.instance)
    }

    private fun loadWallet(context: Context, handle: Handle) {
        val variant = appVariant(context)
        val mnemonic = readMnemonic(context)
            ?: throw IllegalStateException("Native wallet mnemonic is unavailable")
        val datadir = "${context.filesDir.path}/noah-data-$variant"
        val configClass = Class.forName(NITRO_ARK_CONFIG_CLASS)
        val configConstructor = configClass.getConstructor(
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
        val config = loadBarkConfig(context, variant, configConstructor)

        val loadWalletMethod = handle.clazz.getMethod(
            "loadWallet",
            String::class.java,
            String::class.java,
            Boolean::class.java,
            Boolean::class.java,
            Boolean::class.java,
            Integer::class.javaObjectType,
            configClass
        )

        loadWalletMethod.invoke(
            handle.instance,
            datadir,
            mnemonic,
            variant == "regtest",
            variant == "signet",
            variant == "mainnet",
            null,
            config
        )
        NoahToolsLogging.performNativeLog(
            "info",
            "NoahNativeWalletRuntime",
            "Wallet loaded successfully for background work"
        )
    }

    private fun loadBarkConfig(
        context: Context,
        variant: String,
        configConstructor: Constructor<*>
    ): Any {
        val configJson = readConfigJson(context)
        val variantJson = configJson.optJSONObject(variant)
            ?: throw IllegalStateException("Native wallet config is missing for $variant")

        return configConstructor.newInstance(
            variantJson.requireString("ark"),
            variantJson.requireInt("vtxoRefreshExpiryThreshold"),
            variantJson.requireLong("fallbackFeeRate"),
            null,
            readEsploraEndpoint(context) ?: variantJson.optNullableString("esplora"),
            variantJson.optNullableString("bitcoind"),
            variantJson.optNullableString("bitcoindCookie"),
            variantJson.optNullableString("bitcoindUser"),
            variantJson.optNullableString("bitcoindPass"),
            variantJson.optNullableInt("htlcRecvClaimDelta"),
            variantJson.optNullableInt("vtxoExitMargin"),
            variantJson.optNullableInt("roundTxRequiredConfirmations")
        )
    }

    private fun readConfigJson(context: Context): JSONObject {
        return context.assets.open(CONFIG_FILE).use { input ->
            JSONObject(input.bufferedReader().use { it.readText() })
        }
    }

    private fun JSONObject.optNullableString(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key).takeIf { it.isNotEmpty() }
    }

    private fun JSONObject.requireString(key: String): String =
        requireNotNull(optNullableString(key)) { "Missing native config value '$key'" }

    private fun JSONObject.optNullableInt(key: String): Int? {
        if (!has(key) || isNull(key)) return null
        return optInt(key)
    }

    private fun JSONObject.requireInt(key: String): Int =
        requireNotNull(optNullableInt(key)) { "Missing native config value '$key'" }

    private fun JSONObject.optNullableLong(key: String): Long? {
        if (!has(key) || isNull(key)) return null
        return optLong(key)
    }

    private fun JSONObject.requireLong(key: String): Long =
        requireNotNull(optNullableLong(key)) { "Missing native config value '$key'" }
}
