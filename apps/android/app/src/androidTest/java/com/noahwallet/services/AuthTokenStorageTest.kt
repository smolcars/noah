package com.noahwallet.services

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.noahwallet.BuildConfig
import com.noahwallet.services.server.DeviceInfo
import com.noahwallet.services.storage.*
import java.security.KeyStore
import java.util.UUID
import javax.crypto.KeyGenerator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AuthTokenStorageTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun authTokenRoundTripsWithNewGcmAndExistingRnCbcAlias() = runBlocking {
        for (cbc in listOf(false, true)) {
            val item = KeychainPassword.serverAuthToken(context, "test-${UUID.randomUUID()}")
            try {
                if (cbc) KeyGenerator.getInstance("AES", "AndroidKeyStore").run {
                    init(KeyGenParameterSpec.Builder(item.service, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setKeySize(256).setBlockModes("CBC").setEncryptionPaddings("PKCS7Padding").build())
                    generateKey()
                }
                item.saveAuthToken("first-test-token")
                var result = item.read()
                assertTrue("RN token round trip", result is SecretResult.Found && result.value == "first-test-token")
                item.saveAuthToken("replacement-test-token")
                result = item.read()
                assertTrue("RN token replacement", result is SecretResult.Found && result.value == "replacement-test-token")
                // Clear must remove the preference fallback too, without touching other services.
                val preferences = context.getSharedPreferences("RN_KEYCHAIN", Context.MODE_PRIVATE)
                preferences.edit().putString("${item.service}:p", "stale-fallback").commit()
                item.clearAuthToken()
                assertSame(SecretResult.NotFound, item.read())
                assertFalse(preferences.contains("${item.service}:p"))
                item.saveAuthToken("after-clear")
                result = item.read()
                assertTrue(result is SecretResult.Found && result.value == "after-clear")
            } finally {
                item.clearAuthToken()
                KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(item.service)
            }
        }
    }

    @Test fun deviceMetadataMatchesExpoAndroidSources() {
        val info = DeviceInfo.current()
        assertEquals(Build.MANUFACTURER, info.deviceManufacturer)
        assertEquals(Build.MODEL, info.deviceModel)
        assertEquals(Build.VERSION.BASE_OS.takeIf { it.isNotEmpty() } ?: "Android", info.osName)
        assertEquals(Build.VERSION.RELEASE, info.osVersion)
        assertEquals(BuildConfig.VERSION_NAME, info.appVersion)
        assertEquals(BuildConfig.VERSION_CODE.toString(), info.appBuild)
    }
}
