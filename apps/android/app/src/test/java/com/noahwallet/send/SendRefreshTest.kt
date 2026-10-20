package com.noahwallet.send

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SendRefreshTest {
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
                assertTrue(subject.message!!.contains("showing the last known status"))
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
