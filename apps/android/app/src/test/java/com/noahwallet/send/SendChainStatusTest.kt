package com.noahwallet.send

import com.noahwallet.services.wallet.ChainStatus
import io.github.niteshbalusu11.bark.TransactionStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SendChainStatusTest {
    @Test fun offboardStatusDoesNotReadOrSyncBdk() = runBlocking {
        for (kind in ChainStatus.Kind.entries) {
            val original = receipt(SendSource.offchain).receipt
            val saved = SendAttempt(payment = chainPayment.copy(source = SendSource.offchain), receipt = original)
            val result = SendReconciliation.onchainStatus(saved, sync = { error("BDK sync unavailable") },
                transactions = { error("BDK graph unavailable") }, offboardHistory = { emptyList() }, status = { txid ->
                    assertEquals("exact", txid); ChainStatus(kind, 123)
                }) as SendResolution.Paid
            assertEquals(kind, result.receipt.chainStatus!!.kind)
            assertEquals("exact", result.receipt.txid)
            assertEquals(original.proof, result.receipt.proof)
            assertEquals(if (kind == ChainStatus.Kind.confirmed) SendReceipt.Status.confirmed else SendReceipt.Status.submitted, result.receipt.status)
        }
    }
    @Test fun bdkSyncStillGatesCachedEvidenceAndKeepsReorgAndRetryRules() = runBlocking {
        val saved = SendAttempt(payment = chainPayment, receipt = receipt(SendSource.onchain, SendReceipt.Status.confirmed).receipt)
        var synced = false
        var lookedUp = false
        val failed = runCatching {
            SendReconciliation.onchainStatus(saved, sync = { error("BDK sync unavailable") },
                transactions = { error("Must not read stale graph") }, offboardHistory = { error("Not an offboard") },
                status = { lookedUp = true; ChainStatus(ChainStatus.Kind.confirmed, 123) })
        }
        assertTrue(failed.isFailure); assertFalse(lookedUp)
        val result = SendReconciliation.onchainStatus(saved, sync = { synced = true },
            transactions = { assertTrue(synced); listOf(transaction()) }, offboardHistory = { error("Not an offboard") },
            status = { ChainStatus(ChainStatus.Kind.notFound) }) as SendResolution.Paid
        assertEquals(SendReceipt.Status.submitted, result.receipt.status)
        assertTrue(result.receipt.broadcastRetryPending)
    }
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
        assertTrue(absent.chainStatus!!.text.contains("Do not submit it again."))
        val mempool = withStatus(source, ChainStatus.from(TransactionStatus.InMempool), SendResolution.Paid(absent))
        assertEquals("In mempool. Waiting for confirmation.", mempool.chainStatus!!.text)
        val confirmed = withStatus(source, ChainStatus.from(TransactionStatus.Confirmed(123u)), SendResolution.Paid(mempool))
        assertEquals(SendReceipt.Status.confirmed, confirmed.status)
        assertEquals("Confirmed at block 123.", confirmed.chainStatus!!.text)
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
        val conflict = withStatus(SendSource.offchain, ChainStatus(ChainStatus.Kind.confirmed, 123),
            receipt(SendSource.offchain, SendReceipt.Status.conflicted))
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
