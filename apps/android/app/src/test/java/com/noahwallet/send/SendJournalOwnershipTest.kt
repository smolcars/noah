package com.noahwallet.send

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SendJournalOwnershipTest {
    @Test fun delayedClearRetainsReservationUntilRemovalCompletes() = runBlocking {
        val wallet = FakeSendWallet()
        var saved: SendAttempt? = SendAttempt(walletIdentity = wallet.owner, payment = arkPayment,
            receipt = SendReceipt(1000, proof = SendReceipt.Proof.submitReturnedArkSuccess))
        val entered = CompletableDeferred<Unit>(); val release = CountDownLatch(1)
        val drained = Mutex()
        val c = SendController(SendController.Dependencies(read = { saved }, write = {
            if (it == null) { entered.complete(Unit); check(release.await(5, TimeUnit.SECONDS)) }
            saved = it
        }, reservation = { body -> drained.withLock { body(wallet) } }), this)
        c.reloadForWalletChange()
        val clearing = async { c.acknowledge() }
        var replacement: Job? = null
        try {
            withTimeout(5000) { entered.await() }
            replacement = launch {
                // Lifecycle must drain counted work before replacing the datadir.
                drained.withLock {
                    c.invalidateForWalletChange(); saved = null
                    wallet.owner = SendIdentity("wallet-b", "REGTEST")
                }
                c.reloadForWalletChange()
                c.submit(arkPayment, FakeSendWallet.quote.copy(walletIdentity = wallet.owner))!!.join()
            }
            yield()
            assertFalse("Replacement must wait for the old journal removal", replacement.isCompleted)
            assertEquals("wallet-a", wallet.owner.fingerprint)
            release.countDown(); clearing.await(); replacement.join()
            assertEquals(wallet.owner, saved!!.walletIdentity)
            assertNotNull(saved!!.receipt)
        } finally { release.countDown(); clearing.await(); replacement?.join() }
    }
    @Test fun delayedClearAndOverrideValidateGenerationAndIdentityBeforeAnyMutation() = runBlocking {
        for (override in listOf(false, true)) for (boundary in listOf("reservation", "identity")) for (invalidate in listOf(false, true)) {
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            var hold = false
            val wallet = object : FakeSendWallet() {
                override suspend fun identity(): SendIdentity {
                    if (hold && boundary == "identity") { entered.complete(Unit); release.await() }
                    return owner
                }
            }
            var saved: SendAttempt? = SendAttempt(walletIdentity = wallet.owner, payment = arkPayment,
                receipt = if (override) null else SendReceipt(1000, proof = SendReceipt.Proof.submitReturnedArkSuccess))
            var mutations = 0
            val c = SendController(SendController.Dependencies(read = { saved }, write = { mutations++; saved = it },
                auditOverride = { mutations++ }, quarantineUnreadable = { mutations++ }, reservation = { body ->
                    if (hold && boundary == "reservation") { entered.complete(Unit); release.await() }
                    body(wallet)
                }), this)
            c.reloadForWalletChange(); hold = true
            val clearing = async { if (override) c.startNewPaymentAfterHistoryCheck(true) else c.acknowledge() }
            try {
                withTimeout(5000) { entered.await() }
                if (invalidate) c.invalidateForWalletChange()
                wallet.owner = SendIdentity("replacement", "REGTEST")
                val replacement = SendAttempt(walletIdentity = wallet.owner, payment = arkPayment)
                saved = replacement
                release.complete(Unit); clearing.await()
                assertEquals("$override / $boundary / $invalidate", 0, mutations)
                assertSame(replacement, saved); assertTrue(c.locked)
            } finally { release.complete(Unit); clearing.await() }
        }
    }

    @Test fun invalidatedPreparationCannotWriteOrSubmitIntoTheNextGeneration() = runBlocking {
        val wallet = FakeSendWallet(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var saved: SendAttempt? = null
        val c = SendController(SendController.Dependencies(read = { saved }, write = { saved = it }, reservation = { it(wallet) }), this)
        wallet.prepareBlock = { entered.complete(Unit); release.await(); wallet.snapshot }
        val job = c.submit(arkPayment, FakeSendWallet.quote)!!
        entered.await(); c.invalidateForWalletChange()
        val replacement = SendAttempt(walletIdentity = SendIdentity("replacement", "REGTEST"), payment = arkPayment)
        saved = replacement
        release.complete(Unit); job.join()
        assertSame(replacement, saved); assertEquals(0, wallet.calls)
        assertEquals(SendController.State.storageBlocked, c.state)
    }
}
