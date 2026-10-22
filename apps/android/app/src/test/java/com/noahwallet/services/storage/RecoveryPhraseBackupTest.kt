package com.noahwallet.services.storage

import android.app.Application
import android.content.ContextWrapper
import com.noahwallet.fixtures.SharedFixtures
import com.noahwallet.ui.OnboardingNavigation
import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The backup flag is a new native key; missing means not backed up. Existing and migrated wallets
 * still open Home (never onboarding or the phrase) and show the prompt; only restores count as backed up.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RecoveryPhraseBackupTest {
    private val root = Files.createTempDirectory("backup-flag").toFile()
    private val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun getFilesDir() = File(root, "files").apply { mkdirs() }
        override fun getNoBackupFilesDir() = File(root, "no-backup").apply { mkdirs() }
    }
    @After fun cleanUp() { root.deleteRecursively() }

    /** What a launch with seed + wallet database sees after importing [values] from RN. */
    private fun storage(values: Map<String, String?>): SettingsStorage {
        NativeSettingsFile(File(context.noBackupFilesDir, "settings.native.json"), sync = {}).save(SettingsStorage.importValues(values))
        return SettingsStorage(context, sync = {})
    }
    private fun SettingsStorage.wallet() = persistedState<PersistedWalletState>(PersistedWalletState.key)
    private fun SettingsStorage.opensHome() = PersistedWalletState.onboarded(databaseExists = true) { wallet() }
    private fun SettingsStorage.showsPrompt() = !RecoveryPhraseBackup.isBackedUp(this)

    private val rnWallet = SharedFixtures.load("rn-store-compatibility").first { it.name == "RN wallet-storage" }
        .input.jsonObject.getValue("raw").jsonPrimitive.content

    @Test fun rnMigratedWalletOpensHomeWithThePrompt() {
        val settings = storage(mapOf(PersistedWalletState.key to rnWallet, "profile-storage" to """{"state":{"displayName":"Maya"},"version":0}"""))
        assertTrue(settings.wallet()?.isInitialized == true)
        assertTrue(settings.opensHome())
        assertTrue(settings.showsPrompt())
        assertNull(settings.string(RecoveryPhraseBackup.key))
        // The import keeps RN's wallet key as it was.
        assertEquals(rnWallet, settings.string(PersistedWalletState.key))
    }

    @Test fun missingClearedOrDamagedImportsStillOpenHomeWithThePrompt() {
        val cases = SharedFixtures.load("mmkv-launch-state").associate { it.name to it.expected.jsonObject.getValue("outcome").jsonPrimitive.content }
        val imports = mapOf(
            "initialized upgrade" to mapOf(PersistedWalletState.key to rnWallet),
            "RN has no MMKV files" to emptyMap(),
            "empty RN MMKV" to emptyMap(),
            "cleared RN wallet settings" to mapOf("profile-storage" to """{"state":{},"version":0}"""),
            "damaged RN snapshot" to mapOf(PersistedWalletState.key to null),
            "unreadable wallet envelope" to mapOf(PersistedWalletState.key to "{not json"),
            "explicit unfinished RN onboarding" to mapOf(PersistedWalletState.key to """{"state":{"isInitialized":false},"version":0}"""),
            "missing initialized field" to mapOf(PersistedWalletState.key to """{"state":{},"version":0}"""),
        )
        assertEquals(cases.keys, imports.keys)
        for ((name, values) in imports) {
            root.resolve("no-backup").deleteRecursively()
            val settings = storage(values)
            val home = settings.opensHome()
            assertEquals(name, cases.getValue(name) == "existingWallet", home)
            // Only the explicit unfinished RN onboarding resumes onboarding, now at Name, never the phrase.
            if (!home) assertEquals(name, "name", OnboardingNavigation.resumeStep)
            assertTrue(name, settings.showsPrompt())
        }
    }

    @Test fun earlierNativeWalletOpensHomeWithThePrompt() {
        val settings = SettingsStorage(context, sync = {})
        settings.setPersistedState(PersistedWalletState(isInitialized = true, isWalletLoaded = true, staticVtxoPubkey = "key"), PersistedWalletState.key, 0)
        val relaunched = SettingsStorage(context, sync = {})
        assertTrue(relaunched.opensHome())
        assertTrue(relaunched.showsPrompt())
    }

    @Test fun restoredWalletOpensHomeWithoutThePrompt() {
        val settings = SettingsStorage(context, sync = {})
        settings.beginOnboarding()
        assertTrue(settings.completeRestore())
        RecoveryPhraseBackup.restored(settings, typedPhrase = true)
        val relaunched = SettingsStorage(context, sync = {})
        assertTrue(relaunched.opensHome())
        assertFalse(relaunched.showsPrompt())
    }

    @Test fun storedSeedRecoveryKeepsTheEarlierAnswer() {
        // "Recovery phrase found" restores from the keychain; nobody typed the phrase.
        val settings = SettingsStorage(context, sync = {})
        RecoveryPhraseBackup.set(settings, false)
        assertTrue(settings.completeRestore())
        RecoveryPhraseBackup.restored(settings, typedPhrase = false)
        assertTrue(SettingsStorage(context, sync = {}).showsPrompt())
        RecoveryPhraseBackup.set(settings, true)
        RecoveryPhraseBackup.restored(settings, typedPhrase = false)
        assertFalse(SettingsStorage(context, sync = {}).showsPrompt())
    }

    @Test fun newWalletShowsThePromptUntilConfirmedAndDeletionClearsIt() {
        val settings = SettingsStorage(context, sync = {})
        // A stale answer from an earlier wallet never hides a new wallet's prompt.
        RecoveryPhraseBackup.set(settings, true)
        RecoveryPhraseBackup.set(settings, false)
        settings.setPersistedState(PersistedWalletState(isInitialized = false), PersistedWalletState.key, 0)
        assertFalse(settings.opensHome())
        assertTrue(SettingsStorage(context, sync = {}).showsPrompt())
        RecoveryPhraseBackup.set(settings, true)
        assertFalse(SettingsStorage(context, sync = {}).showsPrompt())
        settings.removeAll()
        assertNull(SettingsStorage(context, sync = {}).string(RecoveryPhraseBackup.key))
    }

    @Test fun onlyAnExplicitTrueCountsAsBackedUp() {
        assertFalse(RecoveryPhraseBackup.isBackedUp(null))
        assertFalse(RecoveryPhraseBackup.isBackedUp(""))
        assertFalse(RecoveryPhraseBackup.isBackedUp("{"))
        assertFalse(RecoveryPhraseBackup.isBackedUp("""{"backedUp":"true"}"""))
        assertFalse(RecoveryPhraseBackup.isBackedUp(RecoveryPhraseBackup.encode(false)))
        assertTrue(RecoveryPhraseBackup.isBackedUp(RecoveryPhraseBackup.encode(true)))
    }
}
