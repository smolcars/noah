package com.noahwallet.send

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SendRefreshTest {
    @Test fun interruptedStatusLookupRecoversPersistedSubmissionByExactTxid() = runBlocking {
        for (source in SendSource.entries) for (max in listOf(false, true)) {
            val journal = TestJournal()
            val entered = CompletableDeferred<Unit>()
            val owner = CoroutineScope(Job() + Dispatchers.Unconfined)
            val receipt = SendReceipt(1000, txid = "returned-exact", proof = if (source == SendSource.onchain)
                SendReceipt.Proof.preparedTxid else SendReceipt.Proof.submitReturnedTxid)
            val wallet = object : FakeSendWallet() {
                override suspend fun refreshReceipt(payment: SendPayment, receipt: SendReceipt): SendReceipt {
                    assertEquals(receipt, journal.attempt!!.receipt)
                    entered.complete(Unit)
                    awaitCancellation()
                }
            }.apply { payBlock = { receipt } }
            val payment = chainPayment.copy(source = source, max = max)
            val subject = SendController(SendController.Dependencies({ journal.attempt }, journal::write) { it(wallet) }, owner)
            subject.submit(payment, FakeSendWallet.quote)
            entered.await()
            owner.cancel(); owner.coroutineContext.job.join()
            assertEquals(receipt, journal.attempt!!.receipt)
            wallet.reconciliation = { saved ->
                assertEquals("returned-exact", saved.receipt!!.txid)
                SendReconciliation.withChainStatus(SendReconciliation.onchain(saved, emptyList()), source,
                    com.noahwallet.services.wallet.ChainStatus(com.noahwallet.services.wallet.ChainStatus.Kind.inMempool))
            }
            val restarted = controller(wallet, journal, this)
            restarted.reloadForWalletChange(); restarted.checkStatus()
            assertEquals("returned-exact", journal.attempt!!.receipt!!.txid)
            assertEquals(receipt.proof, journal.attempt!!.receipt!!.proof)
            assertEquals("In mempool. Waiting for confirmation.", journal.attempt!!.receipt!!.chainStatus!!.text)
            assertEquals(1, wallet.calls)
        }
    }
    @Test fun receiptWriteFailureSkipsOptionalStatusLookup() = runBlocking {
        val journal = TestJournal().apply { failReceipt = true }
        val wallet = object : FakeSendWallet() {
            override suspend fun refreshReceipt(payment: SendPayment, receipt: SendReceipt): SendReceipt = error("Must persist first")
        }.apply { payBlock = { SendReceipt(1000, txid = "exact", proof = SendReceipt.Proof.submitReturnedTxid) } }
        val subject = controller(wallet, journal, this)
        subject.submit(chainPayment.copy(source = SendSource.offchain), FakeSendWallet.quote)!!.join()
        assertTrue(subject.message!!.startsWith("Payment status could not be saved"))
        assertNull(journal.attempt!!.receipt)
        assertEquals("exact", subject.attempt!!.receipt!!.txid)
        assertEquals(1, wallet.calls)
    }
    @Test fun failedRefreshPreservesReceiptOutcomeAndJournalAndSuccessfulCheckClearsWarning() = runBlocking {
        for (source in SendSource.entries) for (status in listOf(SendReceipt.Status.submitted, SendReceipt.Status.confirmed)) {
            val wallet = FakeSendWallet(); val journal = TestJournal()
            val receipt = SendReceipt(1000, txid = "exact", status = status, fee = 143,
                proof = if (source == SendSource.onchain) SendReceipt.Proof.preparedTxid else SendReceipt.Proof.submitReturnedTxid)
            val saved = SendAttempt(walletIdentity = wallet.owner, payment = chainPayment.copy(source = source), receipt = receipt)
            journal.attempt = saved
            wallet.reconciliation = { error("offline") }
            repeat(2) {
                val subject = controller(wallet, journal, this); subject.reloadForWalletChange(); subject.checkStatus()
                assertEquals(SendController.State.succeeded, subject.state)
                assertEquals(saved, subject.attempt); assertEquals(saved, journal.attempt)
                assertTrue(subject.message!!.contains("Showing the last known status"))
            }
            val subject = controller(wallet, journal, this); subject.reloadForWalletChange(); subject.checkStatus()
            wallet.reconciliation = { SendResolution.Paid(receipt) }; subject.checkStatus(); assertNull(subject.message)
            subject.acknowledge(); assertFalse(subject.locked)
            assertEquals(0, wallet.calls)
        }
    }
    @Test fun failedRefreshNeverUpgradesCrashOrPendingReceiptAndDoneRemainsLocked() = runBlocking {
        for (hasReceipt in listOf(false, true)) {
            val wallet = FakeSendWallet(); val journal = TestJournal()
            journal.attempt = SendAttempt(walletIdentity = wallet.owner, payment = chainPayment, broadcastingTxid = "exact", quote = chainQuote,
                receipt = if (hasReceipt) SendReceipt(1000, txid = "exact", proof = SendReceipt.Proof.preparedTxid, broadcastRetryPending = true) else null)
            wallet.reconciliation = { error("timeout") }
            val original = journal.attempt
            repeat(2) {
                val subject = controller(wallet, journal, this); subject.reloadForWalletChange()
                val state = subject.state
                subject.checkStatus(); assertEquals(state, subject.state); assertEquals(original, journal.attempt)
                assertEquals(original, subject.attempt); subject.acknowledge(); assertTrue(subject.locked)
            }
            assertEquals(0, wallet.calls)
        }
    }
}
