package com.noahwallet.services

import android.content.Context
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.noahwallet.BuildConfig
import com.noahwallet.api.generated.client.*
import com.noahwallet.api.generated.models.DeleteBackupObjectPayload
import com.noahwallet.services.backup.*
import com.noahwallet.services.launch.LaunchState
import com.noahwallet.services.server.value
import com.noahwallet.services.storage.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import javax.crypto.Cipher

/** Opt-in: run only on a disposable regtest emulator. UI drives the destructive actions. */
@RunWith(AndroidJUnit4::class)
class SettingsLifecycleDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun enabled(stage: String) = assumeTrue(BuildConfig.APP_VARIANT == "regtest" &&
        InstrumentationRegistry.getArguments().getString("noahSettingsIT") == stage)

    @Test fun publicFundingAddress() = runBlocking {
        enabled("address")
        val services = AppServices.shared(context)
        services.ensureWalletOpen()
        File(context.cacheDir, "settings-public-address.txt").writeText(services.session.peekAddress(0u))
    }

    @Test fun fundedBackupsAndExport() = runBlocking {
        enabled("backup")
        val services = AppServices.shared(context)
        services.ensureWalletOpen(); services.session.sync()
        assertTrue(services.session.summary()!!.balance.totalSats > 0u)
        services.registration.setBackupEnabled(true)
        assertEquals(true, services.registration.state.isBackupEnabled)
        services.backupCoordinator.flush(BackupCoordinator.Reason.manual, false)
        val backups = V0BackupV2ListClient(services.client).listBackupObjects().value()
        assertTrue(backups.isNotEmpty())
        for (backup in backups) {
            V0BackupV2DeleteClient(services.client).deleteBackupObject(DeleteBackupObjectPayload(backup.backupId)).value()
            services.backupCoordinator.forgetUploadedBackup(backup.backupId)
        }
        assertTrue(V0BackupV2ListClient(services.client).listBackupObjects().value().isEmpty())
        services.registration.setBackupEnabled(false)
        services.backupCoordinator.flush(BackupCoordinator.Reason.manual, false)
        assertTrue(V0BackupV2ListClient(services.client).listBackupObjects().value().isNotEmpty())
        val phrase = (SeedStorage(context, "regtest").read() as SecretResult.Found).value
        val export = services.backupService.exportEncryptedBackup("export.noahbackup")
        try {
            val file = export.archive.file
            assertEquals(2, file.inputStream().use { it.read() })
            services.scratch.withFolder("settings-export-test") { folder ->
                val opened = BackupArchive.open(file, phrase, File(folder, "opened"))
                assertEquals("regtest", opened.manifest.network)
            }
        } finally { export.discard() }
        assertFalse(export.archive.file.exists())
        services.registration.setBackupEnabled(true)
    }

    @Test fun suspendedRejectsPushOpenAndBackupAfterRelaunch() = runBlocking {
        enabled("suspended")
        val services = AppServices.shared(context)
        assertEquals(true, SettingsStorage(context).persistedState<PersistedWalletState>(PersistedWalletState.key)?.isWalletSuspended)
        assertTrue(runCatching { services.ensureWalletOpen() }.exceptionOrNull() is AppServices.WalletSuspended)
        assertFalse(services.session.isOpen)
        assertTrue(runCatching { services.backupCoordinator.flush(BackupCoordinator.Reason.push, false) }.isFailure)
        val seed = (SeedStorage(context, "regtest").read() as SecretResult.Found).value
        assertTrue(runCatching { services.session.ensureOpen(seed, false, "settings-it") }.isFailure)
    }

    @Suppress("DEPRECATION")
    @Test fun prepareLegacySeedCopiesBeforeUiDeletion() = runBlocking {
        enabled("legacy")
        val seed = (SeedStorage(context, "regtest").read() as SecretResult.Found).value
        val service = "com.noah.mnemonic.regtest"
        val keys = AndroidSecretKeys()
        val key = keys.existing(service) ?: keys.create(service)
        fun encrypt(value: String): String {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
            return Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray()), Base64.NO_WRAP)
        }
        val fields = mapOf("u" to encrypt("noah"), "p" to encrypt(seed), "c" to "KeystoreAESGCM_NoAuth")
        context.rnKeychain.edit { prefs -> fields.forEach { (k, v) -> prefs[stringPreferencesKey("$service:$k")] = v } }
        context.getSharedPreferences("RN_KEYCHAIN", Context.MODE_PRIVATE).edit().apply { fields.forEach { (k, v) -> putString("$service:$k", v) } }.commit()
        val master = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        val fallback = EncryptedSharedPreferences.create("noah_native_secrets", master, context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV, EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
        check(fallback.edit().putString("mnemonic_regtest", seed).commit())
        assertTrue(KeychainPassword.mnemonic(context, "regtest").read() is SecretResult.Found)
        assertTrue(KeychainPassword.serverAuthToken(context, "regtest").read() is SecretResult.Found)
    }

    @Test fun deletedWalletCannotMigrateBackAfterRelaunch() = runBlocking {
        enabled("deleted")
        val location = WalletDataLocation(context.filesDir, "regtest")
        assertFalse(location.directory.exists()); assertFalse(location.restoreDirectory.exists())
        assertEquals(SecretResult.NotFound, SeedStorage(context, "regtest").read())
        assertEquals(SecretResult.NotFound, KeychainPassword.mnemonic(context, "regtest").read())
        assertEquals(SecretResult.NotFound, KeychainPassword.serverAuthToken(context, "regtest").read())
        for (key in listOf("wallet-storage", "server-storage", "backup-storage", "profile-storage", "privacy-storage", "transaction-storage")) assertNull(SettingsStorage(context).string(key))
        assertTrue(context.getSharedPreferences("com.noahwallet.regtest", Context.MODE_PRIVATE).all.isEmpty())
        assertEquals(LaunchState.onboarding, LaunchState.current(context))
    }
}
