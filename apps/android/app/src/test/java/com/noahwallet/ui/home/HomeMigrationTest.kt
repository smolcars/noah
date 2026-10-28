package com.noahwallet.ui.home

import android.app.Application
import android.content.ContextWrapper
import com.noahwallet.fixtures.SharedFixtures
import com.noahwallet.history.HistoryRules
import com.noahwallet.history.HistoryTransaction
import com.noahwallet.services.storage.*
import com.noahwallet.services.wallet.WalletBalance
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/**
 * Existing and migrated wallets open Home exactly as before (the launch rules are RecoveryPhraseBackupTest's);
 * the redesign only decides which Home they see: RN-migrated with history → Everyday with the backup nudge,
 * restored → no backup nudge, new and empty → Fresh, suspended → only the suspended state.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class HomeMigrationTest {
    private val root = Files.createTempDirectory("home-migration").toFile()
    private val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun getFilesDir() = File(root, "files").apply { mkdirs() }
        override fun getNoBackupFilesDir() = File(root, "no-backup").apply { mkdirs() }
    }
    @After fun cleanUp() { root.deleteRecursively() }

    private fun storage(values: Map<String, String?>): SettingsStorage {
        NativeSettingsFile(File(context.noBackupFilesDir, "settings.native.json"), sync = {}).save(SettingsStorage.importValues(values))
        return SettingsStorage(context, sync = {})
    }
    private val rnWallet = SharedFixtures.load("rn-store-compatibility").first { it.name == "RN wallet-storage" }
        .input.jsonObject.getValue("raw").jsonPrimitive.content

    private val lenient = Json { ignoreUnknownKeys = true }

    /** RN-era bark movements, mapped as the wallet maps them today. */
    private val rnHistory: List<HistoryTransaction> = SharedFixtures.load("transaction-history")
        .filter { it.input.jsonObject["operation"]?.jsonPrimitive?.content == "movement" && it.expected.jsonObject["included"]?.jsonPrimitive?.boolean == true }
        .map { lenient.decodeFromJsonElement<HistoryTransaction>(it.expected.jsonObject.getValue("transaction")) }
        .let(HistoryRules::unified)

    private val balance = WalletBalance(WalletBalance.Ark(50_000u, 0u, 0u, 0u, 0u, 0u, 0u, 0u, 0u), WalletBalance.Onchain(0u, 0u, 0u))
    private val empty = WalletBalance(WalletBalance.Ark(0u, 0u, 0u, 0u, 0u, 0u, 0u, 0u, 0u), WalletBalance.Onchain(0u, 0u, 0u))

    private fun home(settings: SettingsStorage, total: WalletBalance, rows: List<HistoryTransaction>): Pair<HomeRules.State, HomeRules.Nudge?> {
        val suspended = settings.persistedState<PersistedWalletState>(PersistedWalletState.key)?.isWalletSuspended == true
        val state = HomeRules.state(suspended, total.totalSats, historyLoading = false, historyError = null, hasRows = rows.isNotEmpty())
        // The email prompt and update warning come from the server; leave them out to see the backup nudge's place.
        return state to HomeRules.nudge(emptyList(), null, null, RecoveryPhraseBackup.isBackedUp(settings), emailPrompt = false, updateRequired = false)
    }

    @Test fun rnMigratedWalletWithHistoryOpensEverydayWithTheBackupNudge() {
        assertTrue(rnHistory.isNotEmpty())
        val settings = storage(mapOf(PersistedWalletState.key to rnWallet, "profile-storage" to """{"state":{"displayName":"Maya"},"version":0}"""))
        assertTrue(PersistedWalletState.onboarded(databaseExists = true) { settings.persistedState(PersistedWalletState.key) })
        assertEquals(HomeRules.State.everyday to HomeRules.Nudge.BackUp, home(settings, balance, rnHistory))
        // No new persisted key: Home reads only the existing backup flag (absent means not backed up).
        assertNull(settings.string(RecoveryPhraseBackup.key))
    }

    @Test fun restoredWalletOpensWithoutTheBackupNudge() {
        val settings = storage(mapOf(PersistedWalletState.key to rnWallet))
        RecoveryPhraseBackup.set(settings, true)
        val (state, nudge) = home(SettingsStorage(context, sync = {}), balance, rnHistory)
        assertEquals(HomeRules.State.everyday, state)
        assertNull(nudge)
    }

    @Test fun newEmptyWalletOpensFresh() {
        val settings = SettingsStorage(context, sync = {})
        settings.setPersistedState(PersistedWalletState(isInitialized = true, isWalletLoaded = true, staticVtxoPubkey = "key"), PersistedWalletState.key, 0)
        assertEquals(HomeRules.State.fresh to HomeRules.Nudge.BackUp, home(SettingsStorage(context, sync = {}), empty, emptyList()))
    }

    @Test fun suspendedWalletShowsOnlyTheSuspendedState() {
        val settings = SettingsStorage(context, sync = {})
        settings.setPersistedState(PersistedWalletState(isInitialized = true, isWalletLoaded = true, isWalletSuspended = true), PersistedWalletState.key, 0)
        val (state, _) = home(SettingsStorage(context, sync = {}), balance, rnHistory)
        assertEquals(HomeRules.State.suspended, state)
    }
}
