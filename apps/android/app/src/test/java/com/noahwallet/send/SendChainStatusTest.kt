package com.noahwallet.send

import com.noahwallet.services.wallet.ChainStatus
import io.github.niteshbalusu11.bark.TransactionStatus
import org.junit.Assert.*
import org.junit.Test

class SendChainStatusTest {
    private fun receipt(source: SendSource, status: SendReceipt.Status = SendReceipt.Status.submitted) =
        SendResolution.Paid(SendReceipt(10_000, txid = "exact", status = status,
            proof = if (source == SendSource.offchain) SendReceipt.Proof.submitReturnedTxid else SendReceipt.Proof.preparedTxid,
            broadcastRetryPending = source == SendSource.onchain))
    private fun withStatus(source: SendSource, status: ChainStatus, original: SendResolution = receipt(source)) =
        (SendReconciliation.withChainStatus(original, source, status) as SendResolution.Paid).receipt
    @Test fun offboardMovesFromNotSeenToMempoolToConfirmedAndBackOnReorg() {
        val source = SendSource.offchain
        val absent = withStatus(source, ChainStatus.from(TransactionStatus.NotFound))
        assertEquals(SendReceipt.Status.submitted, absent.status)
        assertEquals("Not yet seen by the network", absent.chainStatus!!.text)
        val mempool = withStatus(source, ChainStatus.from(TransactionStatus.InMempool), SendResolution.Paid(absent))
        assertEquals("In mempool", mempool.chainStatus!!.text)
        val confirmed = withStatus(source, ChainStatus.from(TransactionStatus.Confirmed(123u)), SendResolution.Paid(mempool))
        assertEquals(SendReceipt.Status.confirmed, confirmed.status)
        assertEquals("Confirmed at block 123", confirmed.chainStatus!!.text)
        val reorged = withStatus(source, ChainStatus.from(TransactionStatus.NotFound), SendResolution.Paid(confirmed))
        assertEquals(SendReceipt.Status.submitted, reorged.status)
        assertEquals("exact", reorged.txid)
        assertEquals(SendReceipt.Proof.submitReturnedTxid, reorged.proof)
    }
    @Test fun bdkNetworkEvidenceNeverClearsRetryOrUnlocatedAndConflictRules() {
        for (status in ChainStatus.Kind.entries) for (existing in SendReceipt.Status.entries) {
            val original = receipt(SendSource.onchain, existing)
            val updated = withStatus(SendSource.onchain, ChainStatus(status, 123), original)
            assertEquals(existing, updated.status)
            assertTrue(updated.broadcastRetryPending)
        }
        val conflict = withStatus(SendSource.offchain, ChainStatus(ChainStatus.Kind.confirmed, 123), receipt(SendSource.offchain, SendReceipt.Status.conflicted))
        assertEquals(SendReceipt.Status.conflicted, conflict.status)
    }
    @Test fun notFoundCannotManufactureReceiptOrResolveUnknownAttempt() {
        for (source in SendSource.entries) {
            assertEquals(SendResolution.Uncertain, SendReconciliation.withChainStatus(SendResolution.Uncertain, source, ChainStatus(ChainStatus.Kind.notFound)))
            for (kind in listOf(ChainStatus.Kind.notFound, ChainStatus.Kind.inMempool)) {
                val unlocated = withStatus(source, ChainStatus(kind), receipt(source, SendReceipt.Status.unlocated))
                assertEquals(SendReceipt.Status.unlocated, unlocated.status)
            }
        }
    }
}
