package com.noahwallet.ui

import com.noahwallet.services.sharing.SettingsShareFiles
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SettingsShareFilesTest {
    @Test fun sharesSurviveOwnerRecreationAndExpireWithTheirExactGrant() {
        val root = kotlin.io.path.createTempDirectory().toFile()
        try {
            val source = File(root, "wallet.noahbackup").apply { writeText("ciphertext") }
            val directory = File(root, "exports")
            val revoked = mutableListOf<File>()
            val original = SettingsShareFiles(directory, revoked::add)
            val shared = original.retain(source)
            source.delete()
            val recreated = SettingsShareFiles(directory, revoked::add)
            recreated.sweep(shared.lastModified() + SettingsShareFiles.retentionMillis - 1)
            assertTrue(shared.exists()); assertEquals("ciphertext", shared.readText()); assertTrue(revoked.isEmpty())
            recreated.sweep(shared.lastModified() + SettingsShareFiles.retentionMillis)
            assertFalse(shared.exists()); assertEquals(listOf(shared), revoked)
            val log = File(root, "noah.log").apply { writeText("redacted") }
            val failedShare = recreated.retain(log)
            recreated.discard(failedShare)
            assertFalse(failedShare.exists()); assertEquals(failedShare, revoked.last())
            val plaintext = File(root, "snapshot.sqlite").apply { writeText("plaintext") }
            assertTrue(runCatching { recreated.retain(plaintext) }.isFailure)
        } finally { root.deleteRecursively() }
    }
}
