package com.noahwallet.send

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SendLoadingTest {
    @Test fun pendingJournalKeepsSynchronousSubmissionLockAndCannotOverride() = runBlocking {
        val loaded = CompletableDeferred<SendAttempt?>()
        val entered = CompletableDeferred<Unit>()
        val wallet = FakeSendWallet()
        val writes = mutableListOf<SendAttempt?>()
        val controller = SendController(SendController.Dependencies(
            read = { entered.complete(Unit); loaded.await() }, write = { writes.add(it) },
            reservation = { it(wallet) }), this)
        controller.invalidateForWalletChange()
        val reload = launch { controller.reloadForWalletChange() }
        entered.await()
        assertTrue(controller.locked)
        assertTrue(controller.checking)
        assertNull(controller.submit(arkPayment, FakeSendWallet.quote))
        controller.acknowledge(); controller.startNewPaymentAfterHistoryCheck(true)
        assertTrue(writes.isEmpty())
        assertFalse(controller.canOverride)
        loaded.complete(SendAttempt(walletIdentity = wallet.owner, payment = arkPayment))
        reload.join()
        assertEquals(SendController.State.uncertain, controller.state)
        assertNull(controller.submit(arkPayment, FakeSendWallet.quote))
        assertEquals(0, wallet.calls)
    }

    @Test fun invalidatedEmptyReadCannotUnlockANewerWallet() = runBlocking {
        val loaded = CompletableDeferred<SendAttempt?>()
        val entered = CompletableDeferred<Unit>()
        val controller = SendController(SendController.Dependencies(
            read = { entered.complete(Unit); loaded.await() }, write = {},
            reservation = { it(FakeSendWallet()) }), this)
        controller.invalidateForWalletChange()
        val reload = launch { controller.reloadForWalletChange() }
        entered.await()
        controller.invalidateForWalletChange()
        loaded.complete(null); reload.join()
        assertEquals(SendController.State.storageBlocked, controller.state)
        assertNull(controller.submit(arkPayment, FakeSendWallet.quote))
    }
    @Test fun pendingRemovalRetainsLockAndCannotOverrideOrSubmit() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = java.util.concurrent.CountDownLatch(1)
        val wallet = FakeSendWallet()
        var saved: SendAttempt? = SendAttempt(walletIdentity = wallet.owner, payment = arkPayment,
            receipt = SendReceipt(1000, proof = SendReceipt.Proof.submitReturnedArkSuccess))
        val controller = SendController(SendController.Dependencies(read = { saved }, write = {
            if (it == null) { entered.complete(Unit); check(release.await(5, java.util.concurrent.TimeUnit.SECONDS)) }
            saved = it
        }, reservation = { it(wallet) }), this)
        controller.reloadForWalletChange()
        val clearing = async { controller.acknowledge() }
        try {
            withTimeout(5000) { entered.await() }
            assertTrue(controller.locked); assertTrue(controller.checking)
            assertFalse(controller.canOverride)
            assertNull(controller.submit(arkPayment, FakeSendWallet.quote))
            assertNotNull(saved)
            release.countDown(); clearing.await()
            assertFalse(controller.locked); assertNull(saved)
            assertEquals(0, wallet.calls)
        } finally { release.countDown(); clearing.await() }
    }

    @Test fun pendingOverrideAuditCannotRemoveEvidenceOrUnlock() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = java.util.concurrent.CountDownLatch(1)
        val wallet = FakeSendWallet()
        var saved: SendAttempt? = SendAttempt(walletIdentity = wallet.owner, payment = arkPayment)
        val controller = SendController(SendController.Dependencies(read = { saved }, write = { saved = it },
            auditOverride = { entered.complete(Unit); check(release.await(5, java.util.concurrent.TimeUnit.SECONDS)); error("audit failed") },
            reservation = { it(wallet) }), this)
        controller.reloadForWalletChange()
        val override = async { controller.startNewPaymentAfterHistoryCheck(true) }
        try {
            withTimeout(5000) { entered.await() }
            assertTrue(controller.locked); assertTrue(controller.checking)
            assertNotNull(saved); assertFalse(controller.canOverride)
            assertNull(controller.submit(arkPayment, FakeSendWallet.quote))
            release.countDown(); override.await()
            assertTrue(controller.locked); assertNotNull(saved)
            assertEquals(0, wallet.calls)
        } finally { release.countDown(); override.await() }
    }

}
