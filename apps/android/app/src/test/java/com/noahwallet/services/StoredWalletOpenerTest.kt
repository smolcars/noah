package com.noahwallet.services

import com.noahwallet.api.generated.models.*
import com.noahwallet.services.push.PushJobs
import com.noahwallet.services.storage.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import javax.crypto.KeyGenerator

class StoredWalletOpenerTest {
    private class Fixture {
        val original = List(11) { StoredMnemonic.wordList[0] }.plus(StoredMnemonic.wordList[3]).joinToString(" ")
        var bytes: ByteArray? = null
        var writes = 0
        var opens = 0
        var walletState: PersistedWalletState? = PersistedWalletState(isInitialized = true)
        var mismatch = false
        private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val seed = SeedStorage(NativeSecretStore("test", object : SecretKeys {
            override fun existing(alias: String) = key
            override fun create(alias: String) = key
        }, object : SecretFile {
            override fun read() = bytes
            override fun writeAtomically(value: ByteArray) { writes++; bytes = value }
            override fun delete() = error("Must not delete seed")
        })) { SecretResult.Found(original) }
        val opener = StoredWalletOpener(seed, { walletState }) {
            assertTrue("Open must use the original credential", it == original)
            opens++
            check(!mismatch) { "Fingerprint mismatch" }
        }
        val jobs = PushJobs(PushJobs.Dependencies(
            ensureWalletOpen = opener::ensureOpen, maintenance = {}, createInvoice = { "invoice" },
            submitInvoice = { _, _ -> }, claimLightning = {}, reportMaintenance = { _, _ -> }, heartbeat = {},
            announceReceived = { _, _, _ -> }, refreshWidget = {}, log = {}, backup = {}, reportBackup = { _, _ -> },
            recurring = {},
        ))
    }

    @Test fun everyBackgroundEntryPointIsReadOnlyBeforeAndAfterMigration() = runBlocking {
        val f = Fixture()
        val notifications = listOf(NotificationDataMaintenance("k1"), NotificationDataLightningInvoiceRequest(1000, "tx"),
            NotificationDataLightningClaimRequest(1, null, "hash"), NotificationDataBackupTrigger("k1"),
            NotificationDataRecurringPaymentDue(1), NotificationDataHeartbeat("id"))
        for (notification in notifications) {
            f.jobs.handle(notification)
            assertEquals("A first post-update push must not create a native seed", 0, f.writes)
            assertNull(f.bytes)
        }
        assertEquals(5, f.opens)
        f.opener.openInForeground()
        assertEquals(1, f.writes)
        val before = f.bytes!!.copyOf()
        for (notification in notifications) f.jobs.handle(notification)
        f.opener.openInForeground()
        assertEquals("Neither later pushes nor repeated foreground opens rewrite an identical copy", 1, f.writes)
        assertTrue(before.contentEquals(f.bytes))
    }

    @Test fun unfinishedOnboardingRejectsAllWalletPushJobsWithoutOpeningOrWritingSeeds() = runBlocking {
        for (state in listOf(PersistedWalletState(isInitialized = false))) {
            val f = Fixture().apply { walletState = state }
            for (notification in listOf(NotificationDataMaintenance("k1"), NotificationDataLightningInvoiceRequest(1000, "tx"),
                NotificationDataLightningClaimRequest(1, null, "hash"), NotificationDataBackupTrigger("k1"), NotificationDataRecurringPaymentDue(1))) {
                assertTrue(runCatching { f.jobs.handle(notification) }.exceptionOrNull() is StoredWalletOpener.OnboardingIncomplete)
            }
            assertEquals(0, f.opens); assertEquals(0, f.writes); assertNull(f.bytes)
            // Foreground onboarding can reuse the existing credential and complete normally.
            f.opener.openInForeground()
            assertEquals(1, f.opens); assertEquals(1, f.writes)
            f.walletState = PersistedWalletState(isInitialized = true)
            f.opener.ensureOpen()
            assertEquals(2, f.opens); assertEquals(1, f.writes)
        }
    }

    @Test fun missingSettingsPermitReadOnlyBackgroundOpen() = runBlocking {
        for (state in listOf(null, PersistedWalletState())) {
            val f = Fixture().apply { walletState = state }
            f.opener.ensureOpen()
            assertEquals(1, f.opens); assertEquals(0, f.writes); assertNull(f.bytes)
        }
    }

    @Test fun failedFingerprintVerificationNeverMigrates() = runBlocking {
        val f = Fixture().apply { mismatch = true }
        assertTrue(runCatching { f.opener.openInForeground() }.isFailure)
        assertEquals(0, f.writes)
        assertNull(f.bytes)
        assertTrue("RN credential survives", (f.seed.read() as? SecretResult.Found)?.value == f.original)
    }
}
