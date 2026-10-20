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
import kotlinx.serialization.json.*
import com.noahwallet.recurring.RecurringStore
import com.noahwallet.services.push.PushRuntime
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
        val nativeSeedFile = File(context.noBackupFilesDir, "noah-native-seed-regtest.bin")
        val nativeSeedBefore = nativeSeedFile.takeIf(File::exists)?.readBytes()
        fun nativeSeedUnchanged(): Boolean = nativeSeedBefore?.contentEquals(nativeSeedFile.takeIf(File::exists)?.readBytes())
            ?: !nativeSeedFile.exists()
        val before = legacyFiles.map { it.takeIf(File::exists)?.readBytes() }
        val old = KeychainPassword.mnemonic(context, "regtest").read()
        assertTrue("RN seed must be readable", old is SecretResult.Found)
        val oldValue = (old as SecretResult.Found).value
        assertTrue("RN seed must have 12 or 24 words", oldValue.trim().split(Regex("\\s+")).size in setOf(12, 24))
        val seed = SeedStorage(context, "regtest").read()
        assertTrue("Migration must retain the RN seed", seed is SecretResult.Found && seed.value == oldValue)
        assertTrue("Seed reads must not write before DB fingerprint validation", nativeSeedUnchanged())
        for ((index, file) in legacyFiles.withIndex()) {
            val after = file.takeIf(File::exists)?.readBytes()
            assertTrue("Legacy storage must remain untouched", before[index]?.contentEquals(after) ?: (after == null))
        }
        assertTrue("RN wallet DB missing", WalletDataLocation(context.filesDir, "regtest").databaseExists)
        assertEquals(LaunchState.existingWallet, LaunchState.current(context))
        // Import a disposable copy independently; never map the live RN files.
        val comparisonRoot = File(context.cacheDir, "upgrade-rn-settings").apply { deleteRecursively(); mkdirs() }
        val comparisonContext = object : android.content.ContextWrapper(context) {
            override fun getFilesDir() = File(comparisonRoot, "files").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(comparisonRoot, "no_backup").apply { mkdirs() }
        }
        File(context.filesDir, "mmkv").copyRecursively(File(comparisonContext.filesDir, "mmkv"))
        val rnSettings = SettingsStorage(comparisonContext)
        val settings = SettingsStorage(context)
        settings.validateStores()
        val envelopesBefore = RNStoreCodec.versions.keys.associateWith(settings::string)
        val schedulesBefore = settings.string(RecurringStore.key)?.let {
            Json.parseToJsonElement(it).jsonObject.getValue("state").jsonObject["schedules"]
        }
        val mailboxBefore = PersistedServerState.decode(settings.string(PersistedServerState.key)).isMailboxAuthorizationEnabled
        val funded = args.getString("funded") == "1"
        if (funded) {
            assertEquals("RN language preference changed", "sv", LegacyLocale.read(context))
            assertEquals(true, PersistedServerState.decode(settings.string(PersistedServerState.key)).isEmailPromptDismissed)
            assertEquals("Android upgrade", SettingsPreferences.state(settings.string("profile-storage"))["displayName"]?.jsonPrimitive?.content)
            assertEquals("sats", SettingsPreferences.state(settings.string("profile-storage"))["bitcoinAmountUnit"]?.jsonPrimitive?.content)
            assertEquals("EUR", SettingsPreferences.state(settings.string("profile-storage"))["preferredCurrency"]?.jsonPrimitive?.content)
            assertEquals(true, SettingsPreferences.state(settings.string("privacy-storage"))["isHomeBalanceHidden"]?.jsonPrimitive?.boolean)
            assertEquals(false, SettingsPreferences.transaction(settings.string("transaction-storage"))["isAutoBoardingEnabled"]?.jsonPrimitive?.boolean)
            assertTrue("RN schedules must never import", RecurringStore(settings).load().schedules.isEmpty())
            assertTrue("RN baseline must contain a recurring upgrade notice", rnSettings.nativeFile.load().recurringNoticePending)
            // The runner checks the visible notice before instrumentation, then restarts
            // Home and requires it to be absent. Do not require an already consumed flag.
            assertFalse("Recurring notice must be consumed once by Home", settings.nativeFile.load().recurringNoticePending)
            for (key in listOf("profile-storage", "privacy-storage", "transaction-storage", "esplora-storage")) {
                assertEquals("Imported RN preference differs: $key", rnSettings.string(key), settings.string(key))
            }
            assertEquals("Biometrics flag differs", rnSettings.persistedState<PersistedWalletState>(PersistedWalletState.key)?.isBiometricsEnabled,
                settings.persistedState<PersistedWalletState>(PersistedWalletState.key)?.isBiometricsEnabled)
            assertTrue("Native import marker missing", settings.nativeFile.exists)
        }
        val state = settings.persistedState<PersistedWalletState>(PersistedWalletState.key)
        assertEquals("RN initialized flag missing", true, state?.isInitialized)
        // Launch and instrumentation must share the owner of bark's datadir lock.
        val session = WalletSession.shared(context)
        fun databaseHashes(): Map<String, String> = WalletDataLocation(context.filesDir, "regtest").directory
            .walkTopDown().filter { it.isFile }.associate { file -> file.name to MessageDigest.getInstance("SHA-256")
                .digest(file.readBytes()).joinToString("") { "%02x".format(it) } }
        val databaseBefore = databaseHashes()
        val vector = List(11) { StoredMnemonic.wordList[0] }.plus(StoredMnemonic.wordList[3]).joinToString(" ")
        val foreign = if (vector != oldValue) vector else List(23) { StoredMnemonic.wordList[0] }
            .plus(StoredMnemonic.wordList[102]).joinToString(" ")
        assertTrue("Foreign seed must be rejected before writes", runCatching { session.ensureOpen(foreign, false, "upgrade-test") }.isFailure)
        assertTrue("DB changed after seed fingerprint mismatch", databaseBefore == databaseHashes())
        assertTrue("Fingerprint mismatch must never write seed", nativeSeedUnchanged())
        assertTrue("Invalid seed must be rejected before wallet open", runCatching { session.ensureOpen("", false, "upgrade-test") }.isFailure)
        assertTrue("Invalid seed changed DB", databaseBefore == databaseHashes())
        session.ensureOpen(oldValue, true, "upgrade-test")
        SeedStorage(context, "regtest").save(oldValue)
        assertTrue("Native seed must persist after DB validation", File(context.noBackupFilesDir, "noah-native-seed-regtest.bin").isFile)
        val summary = requireNotNull(session.summary())
        assertEquals(Network.REGTEST, summary.network)
        assertEquals("Wallet identity changed", fingerprint, summary.fingerprint)
        assertEquals("History differs from RN", movements, summary.movementCount)
        assertEquals("Spendable balance differs from RN", spendable, summary.spendableSats)
        assertEquals(spendable, summary.balance.offchainSats)
        assertEquals(0uL, summary.balance.pendingSats)
        if (funded) {
            val expectedOnchain = requireNotNull(args.getString("expectedOnchain")?.toULongOrNull())
            assertEquals("Confirmed onchain balance differs", expectedOnchain, summary.balance.onchain.confirmed)
            assertEquals(expectedOnchain, summary.balance.onchain.total)
            assertEquals(spendable + expectedOnchain, summary.balance.totalSats)
            val history = session.transactionHistory()
            assertEquals("RN movement identities changed", args.getString("expectedMovementIds"),
                history.mapNotNull { it.movementId }.sorted().joinToString(","))
            assertTrue("RN Ark receive missing", history.any { it.direction == "incoming" && it.amount == 100000L })
            assertTrue("RN Ark send missing", history.any { it.direction == "outgoing" && it.amount == 5000L })
            assertTrue("Confirmed onchain movement missing", history.any { it.hasConfirmation == true && it.balanceChangeSat == expectedOnchain.toLong() })
            assertTrue("Settings changed while opening wallet", envelopesBefore == RNStoreCodec.versions.keys.associateWith(settings::string))
            assertTrue("Recurring schedule list must remain empty", schedulesBefore == settings.string(RecurringStore.key)?.let {
                Json.parseToJsonElement(it).jsonObject.getValue("state").jsonObject["schedules"]
            })
        }
        args.getString("widgetInfoSha256")?.let { expected ->
            val provider = android.appwidget.AppWidgetManager.getInstance(context).installedProviders.single {
                it.provider.className == "com.noahwallet.widgets.NoahWidgetRegtestProvider" && it.provider.packageName == context.packageName
            }
            val metadata = listOf(provider.provider.flattenToString(), provider.minWidth, provider.minHeight,
                provider.minResizeWidth, provider.minResizeHeight, provider.updatePeriodMillis, provider.resizeMode,
                provider.widgetCategory, context.resources.getResourceEntryName(provider.initialLayout)).joinToString("|")
            val actual = MessageDigest.getInstance("SHA-256").digest(metadata.toByteArray()).joinToString("") { "%02x".format(it) }
            assertTrue("Widget provider metadata changed", actual == expected)
            assertTrue("Placed widget missing", android.appwidget.AppWidgetManager.getInstance(context).getAppWidgetIds(provider.provider).isNotEmpty())
            val shortcut = context.getSystemService(android.content.pm.ShortcutManager::class.java).pinnedShortcuts
                .single { it.id == "noah-upgrade-check" }
            assertEquals("com.noahwallet.MainActivity", shortcut.activity?.className)
        }
        val expo = ExpoInstallationID(context)
        val expectedHash = args.getString("expoIdSha256")
        if (expectedHash != null && expectedHash != "absent") {
            val id = expo.read()
            assertNotNull("Existing Expo ID missing", id)
            val hash = MessageDigest.getInstance("SHA-256").digest(id!!.toByteArray())
                .joinToString("") { "%02x".format(it) }
            assertTrue("Expo ID changed", expectedHash == hash)
        }
        // Exercise the first real native foreground launch, including recurring automation gates.
        InstrumentationRegistry.getInstrumentation().startActivitySync(
            android.content.Intent(context, com.noahwallet.MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        if (funded) {
            kotlinx.coroutines.withTimeout(20000) {
                while (RecurringStore(settings).load().binding == null) kotlinx.coroutines.delay(100)
            }
            val afterLaunch = requireNotNull(session.summary())
            assertEquals("Launch executed a wallet movement", movements, afterLaunch.movementCount)
            assertEquals(spendable, afterLaunch.spendableSats)
            assertEquals(args.getString("expectedOnchain")!!.toULong(), afterLaunch.balance.onchain.confirmed)
            assertTrue("Native launch must not recreate RN schedules", schedulesBefore == settings.string(RecurringStore.key)?.let {
                Json.parseToJsonElement(it).jsonObject.getValue("state").jsonObject["schedules"]
            })
            for (key in listOf("profile-storage", "privacy-storage", "transaction-storage")) {
                assertTrue("Native launch changed saved preferences", envelopesBefore[key] == settings.string(key))
            }
            assertEquals(true, PersistedServerState.decode(settings.string(PersistedServerState.key)).isEmailPromptDismissed)
            assertEquals("Mailbox preference changed", mailboxBefore, PersistedServerState.decode(settings.string(PersistedServerState.key)).isMailboxAuthorizationEnabled)
        }
        args.getString("expoTokenSha256")?.let { expected ->
            assertTrue("Real Expo token expectation missing", expected.matches(Regex("[0-9a-f]{64}")))
            var actual: String? = null
            val outcome = PushRuntime(context, AppServices.shared(context)).register { token ->
                actual = MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }
            }
            assertEquals(com.noahwallet.services.push.PushRegistration.Outcome.registered, outcome)
            assertTrue("Expo token changed", actual == expected)
        }
        val finalSummary = requireNotNull(session.summary())
        File(context.filesDir, "noah-upgrade-result.json").writeText(buildJsonObject {
            put("loginKey", com.noahwallet.services.server.ServerClient.loginKey("regtest", oldValue))
            put("fingerprint", finalSummary.fingerprint)
            put("movements", finalSummary.movementCount)
            put("spendable", finalSummary.spendableSats.toString())
            put("onchainConfirmed", finalSummary.balance.onchain.confirmed.toString())
            put("language", LegacyLocale.read(context))
            put("displayUnit", SettingsPreferences.state(settings.string("profile-storage"))["bitcoinAmountUnit"] ?: JsonNull)
            put("currency", SettingsPreferences.state(settings.string("profile-storage"))["preferredCurrency"] ?: JsonNull)
            put("hiddenBalance", SettingsPreferences.state(settings.string("privacy-storage"))["isHomeBalanceHidden"] ?: JsonNull)
            put("biometrics", state?.isBiometricsEnabled)
            put("autoBoarding", SettingsPreferences.transaction(settings.string("transaction-storage"))["isAutoBoardingEnabled"] ?: JsonNull)
            put("recurringSchedules", RecurringStore(settings).load().schedules.size)
            put("expoIdSha256", args.getString("expoIdSha256"))
            put("expoTokenSha256", args.getString("expoTokenSha256"))
        }.toString())
        comparisonRoot.deleteRecursively()
        val id = expo.getOrCreate()
        assertTrue("Expo UUID changed across reads", id == ExpoInstallationID(context).getOrCreate())
    }
}
