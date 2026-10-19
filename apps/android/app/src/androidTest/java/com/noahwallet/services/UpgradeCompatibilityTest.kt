package com.noahwallet.services

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.noahwallet.BuildConfig
import com.noahwallet.services.launch.LaunchState
import com.noahwallet.services.storage.*
import com.noahwallet.services.wallet.WalletSession
import io.github.niteshbalusu11.bark.Network
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Only run over an RN-created regtest wallet. No secret is printed, even on assertion failure. */
@RunWith(AndroidJUnit4::class)
class UpgradeCompatibilityTest {
    @Test fun rnWalletSurvivesInPlaceUpgrade() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("noahUpgradeCheck") == "1")
        assertEquals("regtest", BuildConfig.APP_VARIANT)
        val fingerprint = requireNotNull(args.getString("expectedFingerprint")?.takeIf { it.isNotEmpty() }) {
            "RN fingerprint expectation is required"
        }
        val movements = requireNotNull(args.getString("expectedMovements")?.toIntOrNull()?.takeIf { it >= 0 }) {
            "RN movement count expectation is required"
        }
        val spendable = requireNotNull(args.getString("expectedSpendable")?.toULongOrNull()) {
            "RN spendable expectation is required"
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val legacyFiles = listOf(
            File(context.filesDir, "datastore/RN_KEYCHAIN.preferences_pb"),
            File(context.applicationInfo.dataDir, "shared_prefs/RN_KEYCHAIN.xml"),
            File(context.applicationInfo.dataDir, "shared_prefs/noah_native_secrets.xml"),
        )
        val before = legacyFiles.map { it.takeIf(File::exists)?.readBytes() }
        val old = KeychainPassword.mnemonic(context, "regtest").read()
        assertTrue("RN seed must be readable", old is SecretResult.Found)
        val oldValue = (old as SecretResult.Found).value
        assertTrue("RN seed must have 12 or 24 words", oldValue.trim().split(Regex("\\s+")).size in setOf(12, 24))
        val seed = SeedStorage(context, "regtest").read()
        assertTrue("Migration must retain the RN seed", seed is SecretResult.Found && seed.value == oldValue)
        assertTrue("Native seed must persist", File(context.noBackupFilesDir, "noah-native-seed-regtest.bin").isFile)
        for ((index, file) in legacyFiles.withIndex()) {
            val after = file.takeIf(File::exists)?.readBytes()
            assertTrue("Legacy storage must remain untouched", before[index]?.contentEquals(after) ?: (after == null))
        }
        assertTrue("RN wallet DB missing", WalletDataLocation(context.filesDir, "regtest").databaseExists)
        assertEquals(LaunchState.existingWallet, LaunchState.current(context))
        val state = SettingsStorage(context).persistedState<PersistedWalletState>(PersistedWalletState.key)
        assertEquals("RN initialized flag missing", true, state?.isInitialized)
        // Launch and instrumentation must share the owner of bark's datadir lock.
        val session = WalletSession.shared(context)
        session.ensureOpen(oldValue, true, "upgrade-test")
        val summary = requireNotNull(session.summary())
        assertEquals(Network.REGTEST, summary.network)
        assertEquals("Wallet identity changed", fingerprint, summary.fingerprint)
        assertEquals("History differs from RN", movements, summary.movementCount)
        assertEquals("Spendable balance differs from RN", spendable, summary.spendableSats)
        val expo = ExpoInstallationID(context)
        val expectedHash = args.getString("expoIdSha256")
        if (expectedHash != null && expectedHash != "absent") {
            val id = expo.read()
            assertNotNull("Existing Expo ID missing", id)
            val hash = MessageDigest.getInstance("SHA-256").digest(id!!.toByteArray())
                .joinToString("") { "%02x".format(it) }
            assertTrue("Expo ID changed", expectedHash == hash)
        }
        val id = expo.getOrCreate()
        assertEquals(id, ExpoInstallationID(context).getOrCreate())
    }
}
