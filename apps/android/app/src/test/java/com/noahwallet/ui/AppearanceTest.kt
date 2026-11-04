package com.noahwallet.ui

import android.app.Application
import android.app.UiModeManager
import android.content.ContextWrapper
import com.noahwallet.services.storage.SettingsStorage
import com.noahwallet.services.wallet.EsploraSettings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/** docs/native-rewrite/design/appearance.md, Tests: parsing, resolution, the reveal's turn and the stored value's life. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AppearanceTest {
    @Test fun missingOrUnknownIsAutomaticAndEachNameParses() {
        for (stored in listOf(null, "", "Dark", "LIGHT", "system", " dark", "auto")) assertEquals(stored, Appearance.automatic, Appearance.parse(stored))
        for (appearance in Appearance.entries) assertEquals(appearance, Appearance.parse(appearance.name))
        // The strings iOS stores too.
        assertEquals(listOf("automatic", "light", "dark"), Appearance.entries.map { it.name })
        assertEquals("appearance", Appearance.key)
    }

    @Test fun automaticFollowsThePhoneAndTheOthersDont() {
        assertTrue(Appearance.automatic.dark(phoneDark = true))
        assertFalse(Appearance.automatic.dark(phoneDark = false))
        for (phone in listOf(true, false)) {
            assertFalse(Appearance.light.dark(phone))
            assertTrue(Appearance.dark.dark(phone))
        }
    }

    @Test fun thePlatformGetsTheSameChoice() {
        assertEquals(UiModeManager.MODE_NIGHT_AUTO, Appearance.automatic.nightMode)
        assertEquals(UiModeManager.MODE_NIGHT_NO, Appearance.light.nightMode)
        assertEquals(UiModeManager.MODE_NIGHT_YES, Appearance.dark.nightMode)
    }

    @Test fun aRevealRunsOnlyWhenTheColoursChange() {
        // Automatic → Light while the phone is light changes nothing.
        assertEquals(false, Appearance.turns(Appearance.automatic, Appearance.light, phoneDark = false))
        assertEquals(true, Appearance.turns(Appearance.automatic, Appearance.dark, phoneDark = false))
        assertEquals(true, Appearance.turns(Appearance.dark, Appearance.automatic, phoneDark = false))
        assertEquals(false, Appearance.turns(Appearance.dark, Appearance.automatic, phoneDark = true))
        assertEquals(true, Appearance.turns(Appearance.light, Appearance.dark, phoneDark = null))
        // The phone's mode unknown (hidden under Noah's own from API 31, or on a schedule): only the theme can tell.
        assertNull(Appearance.turns(Appearance.dark, Appearance.automatic, phoneDark = null))
        assertNull(Appearance.turns(Appearance.automatic, Appearance.light, phoneDark = null))
        for (appearance in Appearance.entries) assertEquals(false, Appearance.turns(appearance, appearance, null))
    }

    /** Delete wallet clears the settings store; Appearance is the phone's, so it stays (with log protection). */
    @Test fun aSettingsResetKeepsTheChoice() = withStorage { open ->
        val settings = open()
        settings.set("dark", Appearance.key)
        settings.set("""{"state":{"bitcoinAmountUnit":"sats"},"version":0}""", "profile-storage")
        settings.removeAll()
        assertEquals("dark", settings.string(Appearance.key))
        assertNull(settings.string("profile-storage"))
        assertEquals("dark", open().string(Appearance.key))
        assertEquals(setOf(Appearance.key), settings.nativeFile.load().values.keys - setOf(EsploraSettings.key, EsploraSettings.historyUnavailableKey))
    }

    @Test fun aResetDoesntInventAChoice() = withStorage { open ->
        val settings = open()
        settings.removeAll()
        assertNull(settings.string(Appearance.key))
        assertEquals(Appearance.automatic, Appearance.parse(settings.string(Appearance.key)))
    }

    private fun withStorage(test: (open: () -> SettingsStorage) -> Unit) {
        val root = Files.createTempDirectory("appearance").toFile()
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(root, "no-backup").apply { mkdirs() }
        }
        try { test { SettingsStorage(context, sync = {}) } } finally { root.deleteRecursively() }
    }
}
