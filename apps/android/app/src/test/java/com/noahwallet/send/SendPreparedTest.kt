package com.noahwallet.send

import io.github.niteshbalusu11.bark.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SendPreparedTest {
    @Test fun exactValuesAndTwoMinuteExpiry() {
        assertEquals(143L, chainQuote.prepared!!.vsize); assertEquals(1, chainQuote.prepared.inputCount)
        val prepared = chainQuote.prepared.copy(reviewedAt = 1_000)
        assertTrue(prepared.isCurrent(120_999)); assertFalse(prepared.isCurrent(121_000)); assertFalse(prepared.isCurrent(999))
        assertEquals(2uL, SendFlow.sdkFeeRate(375u)); assertEquals(10uL, SendFlow.sdkFeeRate(2500u)); assertTrue(SendFlow.sdkFeeRate(ULong.MAX_VALUE) > 0u)
    }
    @Test fun allBroadcastOutcomesAndWrongTxid() {
        val accepted = SendOnchainSubmission.receipt(BroadcastResult("exact", BroadcastOutcome.Accepted), chainQuote)
        assertEquals(1000L, accepted.amount); assertEquals(10L, accepted.fee); assertFalse(accepted.broadcastRetryPending)
        val failed = SendOnchainSubmission.receipt(BroadcastResult("exact", BroadcastOutcome.Failed("private")), chainQuote)
        assertTrue(failed.broadcastRetryPending)
        assertTrue(runCatching { SendOnchainSubmission.receipt(BroadcastResult("exact", BroadcastOutcome.NotAttempted("private")), chainQuote) }.exceptionOrNull() is SendNotSubmitted)
        assertTrue(runCatching { SendOnchainSubmission.receipt(BroadcastResult("wrong", BroadcastOutcome.Accepted), chainQuote) }.exceptionOrNull() is SendFailure)
        // Only boards report it today; if a send ever did, it would stay unresolved, never shown as sent.
        val uncertain = runCatching { SendOnchainSubmission.receipt(BroadcastResult("exact", BroadcastOutcome.Uncertain("private")), chainQuote) }.exceptionOrNull()
        assertEquals(SendFailure.Reason.unresolved, (uncertain as SendFailure).reason)
    }
    @Test fun typedErrorsAreCallSpecificAndUnknownStaysUncertain() {
        for (code in BarkErrorCode.entries) assertEquals(code.name, code in listOf(BarkErrorCode.PREPARED_TRANSACTION_NOT_FOUND, BarkErrorCode.PREPARED_INPUTS_UNAVAILABLE, BarkErrorCode.OTHER), SendOnchainSubmission.notSubmitted(BarkError("sensitive", code)) != null)
        assertNull(SendOnchainSubmission.notSubmitted(Exception("insufficient funds")))
    }
    @Test fun aReorgedOrUnverifiableParentAtFirstBroadcastReadsAsBeforeSubmission() {
        // SDK 0.7.0 re-checks confirmed-only inputs before recording: a parent back in the mempool, or no chain source.
        assertEquals(SendFailure.Reason.fundsChanged, SendOnchainSubmission.notSubmitted(BarkError("prepared input parent is no longer confirmed", BarkErrorCode.PREPARED_INPUTS_UNAVAILABLE)))
        val unreachable = SendOnchainSubmission.notSubmitted(BarkError("connection refused", BarkErrorCode.OTHER))
        assertEquals(SendFailure.Reason.constructionFailed, unreachable)
        assertTrue(unreachable!!.message.contains("No payment was submitted"))
    }
    @Test fun exactCrashTxidOnlyAndMissingHistoryNeverUnlocks() {
        val attempt = SendAttempt(payment = chainPayment, broadcastingTxid = "exact", quote = chainQuote)
        assertEquals(SendResolution.Uncertain, SendReconciliation.onchain(attempt, emptyList()))
        assertEquals(SendResolution.Uncertain, SendReconciliation.onchain(attempt, listOf(transaction("lookalike"))))
        val receipt = (SendReconciliation.onchain(attempt, listOf(transaction())) as SendResolution.Paid).receipt
        assertTrue(receipt.broadcastRetryPending); assertEquals(10L, receipt.fee)
        assertEquals(SendReceipt.Status.unlocated, (SendReconciliation.onchain(attempt.copy(receipt = receipt), emptyList()) as SendResolution.Paid).receipt.status)
    }
    @Test fun crashAndFailedReceiptWriteStayLockedUntilConfirmation() = runBlocking {
        for (failReceipt in listOf(false, true)) {
            val wallet = FakeSendWallet(); val journal = TestJournal()
            if (failReceipt) {
                journal.failReceipt = true; wallet.payBlock = { SendOnchainSubmission.receipt(BroadcastResult("exact", BroadcastOutcome.Failed("rpc")), chainQuote) }
                val subject = controller(wallet, journal, this); subject.submit(chainPayment, chainQuote); coroutineContext.job.children.toList().joinAll()
                assertTrue(subject.message!!.contains("could not be saved"))
            } else journal.attempt = SendAttempt(walletIdentity = wallet.owner, payment = chainPayment, broadcastingTxid = "exact", quote = chainQuote)
            journal.failReceipt = false; assertNull(journal.attempt!!.receipt)
            wallet.reconciliation = { SendReconciliation.onchain(it, listOf(transaction())) }
            val restart = controller(wallet, journal, this); restart.reloadForWalletChange(); restart.checkStatus()
            assertTrue(restart.attempt!!.receipt!!.broadcastRetryPending); restart.acknowledge(); assertTrue(restart.locked); assertTrue(restart.canOverride)
            wallet.reconciliation = { SendReconciliation.onchain(it, listOf(transaction(confirmed = true))) }; restart.checkStatus()
            assertEquals(SendReceipt.Status.confirmed, restart.attempt!!.receipt!!.status); restart.acknowledge(); assertFalse(restart.locked)
        }
    }
    @Test fun explicitNotAttemptedAllowsNewReview() = runBlocking {
        val wallet = FakeSendWallet().apply { payBlock = { throw SendNotSubmitted(SendFailure.Reason.constructionFailed) } }; val journal = TestJournal()
        val subject = controller(wallet, journal, this); subject.submit(chainPayment, chainQuote); coroutineContext.job.children.toList().joinAll()
        assertEquals(SendController.State.failed, subject.state); subject.acknowledge(); assertFalse(subject.locked)
    }
}
