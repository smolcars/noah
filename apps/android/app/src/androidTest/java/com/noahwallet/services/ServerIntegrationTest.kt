package com.noahwallet.services

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.noahwallet.BuildConfig
import com.noahwallet.api.generated.client.*
import com.noahwallet.api.generated.models.RegisterPayload
import com.noahwallet.services.server.*
import com.noahwallet.services.storage.*
import io.github.niteshbalusu11.bark.generateMnemonic
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import java.util.UUID

/** Opt in with -e noahServerIT 1, on a dedicated emulator against the existing stack. */
@RunWith(AndroidJUnit4::class)
class ServerIntegrationTest {
    @Test fun loginRegistrationLastLoginAndRejectedTokenRecovery() = runBlocking {
        assumeTrue(BuildConfig.APP_VARIANT == "regtest")
        assumeTrue(InstrumentationRegistry.getArguments().getString("noahServerIT") == "1")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val variant = "server-test-${UUID.randomUUID()}"
        val item = KeychainPassword.serverAuthToken(context, variant)
        val endpoint = ServerEndpoint.baseURL("regtest", InstrumentationRegistry.getArguments().getString("noahServerURL"))
        val public = ServerClient.make(endpoint)
        val mnemonic = generateMnemonic()
        var logins = 0
        fun provider() = AuthTokenProvider(
            readToken = { (item.read() as? SecretResult.Found)?.value },
            saveToken = item::saveAuthToken, clearToken = item::clearAuthToken,
            readMnemonic = { mnemonic }, login = { logins++; ServerClient.login(public, "regtest", it) },
        )
        var stage = "login"
        try {
            val tokens = provider()
            assertFalse("Server token must have a usable lifetime", JWT.needsRefresh(tokens.token()))
            assertTrue("JWT persisted in RN item", item.read() is SecretResult.Found)
            ServerClient.make(endpoint, tokens).use { client ->
                val device = DeviceInfo.current()
                // A new server user may register before acquiring an Ark address (same as iOS IT).
                stage = "registration"
                val response = V0RegisterClient(client).register(RegisterPayload(deviceInfo = device.registrationPayload())).value()
                val values = mutableMapOf<String, String>()
                val registration = ServerRegistration(client, ServerStateStorage(values::get, { k, v -> values[k] = v }),
                    { error("Already registered") }, warn = {})
                registration.apply(response, device)
                stage = "version check"
                registration.ensureRegistered()
                assertTrue("Server assigned a Lightning identity", V0UserInfoClient(client).getUserInfo().value().lightningAddress.contains('@'))
                stage = "last login"
                registration.reportLastLogin()
                assertEquals(true, registration.state.isRegisteredWithServer)
                assertNotNull(registration.state.lastDeviceReportAt)
                assertEquals(1, logins)
            }
            // Plausible future exp, invalid signature. It must trigger one live re-login.
            stage = "rejected token recovery"
            item.saveAuthToken("e30.eyJleHAiOjQxMDI0NDQ4MDB9.bad")
            ServerClient.make(endpoint, provider()).use { client ->
                V0UserInfoClient(client).getUserInfo().value()
            }
            assertEquals(2, logins)
        } catch (_: Exception) {
            // Generated/native exceptions may contain request bodies, signatures or tokens.
            fail("Live server integration failed at $stage (details intentionally suppressed)")
        } finally {
            public.close()
            item.clearAuthToken()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(item.service)
        }
    }
}
