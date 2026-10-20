package com.noahwallet.services

import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.noahwallet.history.HistoryExportFiles
import com.noahwallet.services.sharing.SettingsShareFiles
import com.noahwallet.services.storage.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opt in only on our disposable emulator; no Ark wallet or server is required. */
@RunWith(AndroidJUnit4::class)
class WalletDeletionDeviceTest {
    @Test fun deletionRemovesRetainedExportsAndRevokesTheirExactReadGrants() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("noahPrivacyIT") == "disposable-api28")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val location = WalletDataLocation(context.filesDir, "regtest")
        val seed = SeedStorage(context, "regtest")
        assertFalse(location.hasAnyData)
        assertEquals(SecretResult.NotFound, seed.read())
        val services = AppServices.shared(context)
        assertTrue(seed.save(io.github.niteshbalusu11.bark.generateMnemonic()) is SecretResult.Found)
        // The closed-session deletion path needs no native wallet; this stands in for its files.
        check(location.directory.mkdirs())
        location.database.writeText("disposable deletion test")
        val source = File(context.cacheDir, "deletion-test.log").apply { writeText("redacted") }
        val retained = SettingsShareFiles.live(context).retain(source)
        source.delete()
        val csv = HistoryExportFiles.live(context).create("Date,Amount\n")
        val recipient = "com.android.documentsui"
        val uid = context.packageManager.getApplicationInfo(recipient, 0).uid
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        val uris = listOf(retained, csv).map { FileProvider.getUriForFile(context, "${context.packageName}.files", it) }
        uris.forEach {
            context.grantUriPermission(recipient, it, flags)
            assertEquals(PackageManager.PERMISSION_GRANTED, context.checkUriPermission(it, -1, uid, flags))
        }
        services.lifecycle.delete()
        assertFalse(retained.exists()); assertFalse(csv.exists())
        uris.forEach { assertEquals(PackageManager.PERMISSION_DENIED, context.checkUriPermission(it, -1, uid, flags)) }
        assertFalse(location.directory.exists()); assertFalse(location.deletionMarker.exists())
        assertEquals(SecretResult.NotFound, seed.read())
    }
}
