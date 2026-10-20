package com.noahwallet.send

import com.noahwallet.services.wallet.ChainStatus
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class SendConflictTest {
    private val wireTxid = (0..31).joinToString("") { "%02x".format(it) }
    private val point = SendInput((0..31).reversed().joinToString("") { "%02x".format(it) }, 0x12345678u)
    private fun hex(witness: Boolean = false, vout: String = "78563412") = "02000000" + (if (witness) "0001" else "") +
        "01" + wireTxid + vout + "00ffffffff01010000000000000000" + (if (witness) "00" else "") + "00000000"
    private fun original() = transaction().copy(txHex = hex())
    private fun competing(confirmed: Boolean = true) = transaction("replacement", confirmed).copy(txHex = hex())
    private fun quote() = chainQuote.copy(prepared = chainQuote.prepared!!.copy(inputs = listOf(point)))
    private fun roundTrip(value: SendAttempt) = Json.decodeFromString<SendAttempt>(Json { encodeDefaults = true }.encodeToString(value))

    @Test fun confirmedCompetingInputSurvivesEvictionRestartRefreshAndRequiresOverride() = runBlocking {
        for (evicted in listOf(false, true)) for (hasReceipt in listOf(false, true)) {
            val wallet = FakeSendWallet(); val journal = TestJournal()
            journal.attempt = roundTrip(SendAttempt(walletIdentity = wallet.owner, payment = chainPayment, quote = quote(), broadcastingTxid = "exact",
                receipt = if (hasReceipt) SendReceipt(1000, txid = "exact", proof = SendReceipt.Proof.preparedTxid, broadcastRetryPending = true) else null))
            wallet.reconciliation = { SendReconciliation.onchain(it, (if (evicted) emptyList() else listOf(original())) + competing()) }
            val subject = controller(wallet, journal, this); subject.reloadForWalletChange(); subject.checkStatus()
            assertEquals(SendController.State.uncertain, subject.state)
            assertEquals(SendReceipt.Status.conflicted, subject.attempt!!.receipt!!.status)
            assertEquals("replacement", subject.attempt!!.receipt!!.confirmedConflictTxid)
            assertTrue(subject.overrideWarning.contains("confirmed different transaction (replacement)"))
            subject.acknowledge(); assertTrue(subject.locked); assertTrue(subject.canOverride)
            wallet.reconciliation = { error("offline") }
            val restart = controller(wallet, journal, this); restart.reloadForWalletChange(); restart.checkStatus()
            assertEquals(subject.attempt, restart.attempt); assertEquals(subject.overrideWarning, restart.overrideWarning)
            restart.startNewPaymentAfterHistoryCheck(true); assertFalse(restart.locked)
            assertEquals(subject.attempt, journal.audits.single().originalEvidence)
            assertTrue(journal.audits.single().warning.contains("replacement")); assertEquals(0, wallet.calls)
        }
    }
    @Test fun evictionDowngradeSurvivesLaterStatusErrorAndCannotBeAcknowledged() = runBlocking {
        assertDowngradeSurvivesStatusLookup(conflict = false)
    }
    @Test fun confirmedConflictDowngradeSurvivesLaterStatusErrorAndCannotBeAcknowledged() = runBlocking {
        assertDowngradeSurvivesStatusLookup(conflict = true)
    }
    private suspend fun CoroutineScope.assertDowngradeSurvivesStatusLookup(conflict: Boolean) {
        for (network in listOf(null) + ChainStatus.Kind.entries) {
            val wallet = FakeSendWallet(); val journal = TestJournal()
            val lastKnown = ChainStatus(ChainStatus.Kind.confirmed, 100)
            journal.attempt = roundTrip(SendAttempt(walletIdentity = wallet.owner, payment = chainPayment, quote = quote(),
                receipt = SendReceipt(1000, txid = "exact", status = SendReceipt.Status.confirmed,
                    proof = SendReceipt.Proof.preparedTxid, chainStatus = lastKnown)))
            val trace = mutableListOf<String>()
            wallet.reconciliation = { saved -> SendReconciliation.onchainStatus(saved,
                sync = { trace.add("sync") },
                transactions = { trace.add("transactions"); if (conflict) listOf(competing()) else emptyList() },
                offboardHistory = { error("Not an offboard") }, status = { txid ->
                    trace.add("status:$txid")
                    if (network == null) error("offline")
                    ChainStatus(network, 200)
                }) }
            val subject = controller(wallet, journal, this)
            subject.reloadForWalletChange(); subject.checkStatus()
            assertEquals(listOf("sync", "transactions", "status:exact"), trace)
            val receipt = journal.attempt!!.receipt!!
            assertEquals(if (conflict) SendReceipt.Status.conflicted else SendReceipt.Status.unlocated, receipt.status)
            assertEquals(if (conflict) "replacement" else null, receipt.confirmedConflictTxid)
            assertEquals(if (network == null) lastKnown else ChainStatus(network, 200), receipt.chainStatus)
            assertNull(receipt.statusError)
            assertEquals(if (network == null) ChainStatus.errorText else null, subject.message)
            assertEquals(journal.attempt, subject.attempt)
            subject.acknowledge(); assertTrue(subject.locked)
            val restart = controller(wallet, journal, this); restart.reloadForWalletChange()
            assertEquals(receipt, restart.attempt!!.receipt)
            restart.acknowledge(); assertTrue(restart.locked)
            // A later successful lookup clears only the warning, never the BDK downgrade.
            wallet.reconciliation = { SendReconciliation.withChainStatus(SendResolution.Paid(it.receipt!!),
                SendSource.onchain, ChainStatus(ChainStatus.Kind.notFound)) }
            restart.checkStatus(); assertNull(journal.attempt!!.receipt!!.statusError)
            assertEquals(receipt.status, journal.attempt!!.receipt!!.status)
            restart.acknowledge(); assertTrue(restart.locked)
            assertEquals(0, wallet.calls)
        }
    }
    @Test fun onlyDifferentConfirmedWellFormedSharedOutpointsProveConflict() {
        val saved = SendAttempt(payment = chainPayment, broadcastingTxid = "exact", quote = quote())
        val otherEvidence = listOf(competing(false), competing().copy(txHex = "0200000001"),
            competing().copy(txHex = hex(vout = "79563412")), competing().copy(txid = "exact"))
        for (other in otherEvidence) {
            val receipt = (SendReconciliation.onchain(saved, listOf(original(), other)) as SendResolution.Paid).receipt
            assertNull(receipt.confirmedConflictTxid); assertEquals(SendReceipt.Status.submitted, receipt.status); assertTrue(receipt.broadcastRetryPending)
        }
        assertEquals(SendResolution.Uncertain, SendReconciliation.onchain(saved, emptyList()))
        assertEquals(SendResolution.Uncertain, SendReconciliation.onchain(saved.copy(quote = chainQuote), listOf(competing())))
        val malformed = quote().copy(prepared = quote().prepared!!.copy(inputs = listOf(SendInput("bad", 0u))))
        assertEquals(SendResolution.Uncertain, SendReconciliation.onchain(saved.copy(quote = malformed), listOf(competing())))
    }
    @Test fun legacyQuotesCanUseCachedOriginalButNeverOtherInputIndexes() {
        val saved = SendAttempt(payment = chainPayment, broadcastingTxid = "exact", quote = chainQuote)
        assertEquals("replacement", (SendReconciliation.onchain(saved, listOf(original(), competing())) as SendResolution.Paid).receipt.confirmedConflictTxid)
        assertNull((SendReconciliation.onchain(saved, listOf(original(), competing().copy(txHex = hex(vout = "79563412")))) as SendResolution.Paid).receipt.confirmedConflictTxid)
    }
    @Test fun consensusInputsUseReversedTxidAndUnsignedIndexForWitnessAndLegacy() {
        for (witness in listOf(false, true)) assertEquals(listOf(point), SendTransaction.parse(hex(witness)).inputs)
        assertTrue(runCatching { SendTransaction.parse(hex().dropLast(2)) }.isFailure)
        assertTrue(runCatching { SendTransaction.parse("zz" + hex().drop(2)) }.isFailure)
    }
    @Test fun versionOneReceiptMigratesWithoutInventingInputsAndWriteFailureStaysLocked() = runBlocking {
        for (fail in listOf(false, true)) {
            val wallet = FakeSendWallet(); val journal = TestJournal()
            val receipt = SendReceipt(1000, txid = "exact", proof = SendReceipt.Proof.preparedTxid, broadcastRetryPending = true)
            journal.attempt = SendAttempt(walletIdentity = wallet.owner, safetyVersion = 1, payment = chainPayment, quote = chainQuote, receipt = receipt)
            journal.fail = fail
            val subject = controller(wallet, journal, this); subject.reloadForWalletChange()
            assertTrue(subject.locked)
            if (fail) { assertEquals(SendController.State.storageBlocked, subject.state); assertEquals(1, journal.attempt!!.safetyVersion) }
            else {
                assertEquals(2, journal.attempt!!.safetyVersion); assertEquals(receipt, subject.attempt!!.receipt)
                assertNull(subject.attempt!!.quote!!.prepared!!.inputs)
            }
        }
    }
    @Test fun failedMigrationRetainsOriginalEvidenceAndRetryPersistsWithoutPaying() = runBlocking {
        for (version in listOf(0, 1)) {
            val wallet = FakeSendWallet(); val journal = TestJournal()
            val original = SendAttempt(walletIdentity = wallet.owner, safetyVersion = version, payment = chainPayment, quote = chainQuote,
                receipt = SendReceipt(1000, txid = "exact", proof = SendReceipt.Proof.preparedTxid, broadcastRetryPending = true))
            journal.attempt = original; journal.fail = true
            var reads = 0
            val subject = SendController(SendController.Dependencies(read = { check(++reads == 1); journal.attempt }, write = journal::write) { it(wallet) }, this)
            subject.reloadForWalletChange()
            assertEquals(SendController.State.storageBlocked, subject.state)
            assertEquals(original, subject.attempt); assertEquals(original, journal.attempt); assertTrue(subject.canOverride)
            subject.acknowledge(); subject.submit(chainPayment, chainQuote)
            if (version == 0) subject.checkStatus()
            assertEquals(SendController.State.storageBlocked, subject.state); assertEquals(original, subject.attempt)
            journal.fail = false; subject.checkStatus()
            assertEquals(if (version == 0) SendController.State.uncertain else SendController.State.succeeded, subject.state); assertTrue(subject.locked)
            assertEquals(2, journal.attempt!!.safetyVersion); assertEquals(subject.attempt, journal.attempt)
            if (version == 0) assertEquals(listOf("exact"), subject.attempt!!.possibleTxids)
            else assertEquals(original.receipt, subject.attempt!!.receipt)
            assertEquals(0, wallet.prepareCalls); assertEquals(0, wallet.calls)
            assertEquals(1, reads)
        }
    }
    @Test fun blockedMigrationOverrideRequiresConfirmationAuditAndSuccessfulRemoval() = runBlocking {
        for (failure in listOf("audit", "remove", "none")) {
            val wallet = FakeSendWallet(); val journal = TestJournal()
            val original = SendAttempt(walletIdentity = wallet.owner, safetyVersion = 1, payment = chainPayment, quote = chainQuote,
                receipt = SendReceipt(1000, txid = "exact", proof = SendReceipt.Proof.preparedTxid, broadcastRetryPending = true))
            journal.attempt = original
            val subject = SendController(SendController.Dependencies(read = { journal.attempt }, write = {
                if (it != null || failure == "remove") error("disk")
                assertEquals(original, journal.audits.single().originalEvidence)
                journal.write(it)
            }, auditOverride = {
                if (failure == "audit") error("audit")
                journal.audits.add(it)
            }) { it(wallet) }, this)
            subject.reloadForWalletChange(); assertTrue(subject.canOverride)
            subject.startNewPaymentAfterHistoryCheck(false); assertTrue(journal.audits.isEmpty()); assertEquals(original, subject.attempt)
            assertTrue(subject.overrideWarning.contains("spend twice")); assertTrue(subject.overrideWarning.contains("future broadcast retries"))
            subject.startNewPaymentAfterHistoryCheck(true)
            assertEquals(failure != "none", subject.locked)
            if (failure != "none") { assertEquals(original, subject.attempt); assertEquals(original, journal.attempt) }
            else {
                assertNull(journal.attempt)
                val audit = journal.audits.single()
                assertEquals("exact", audit.txid); assertEquals(SendOverrideAudit.warningText, audit.warning)
                assertEquals("storageBlocked", audit.status); assertEquals(SendOverrideAudit.confirmationText, audit.confirmation)
            }
            assertEquals(0, wallet.prepareCalls); assertEquals(0, wallet.calls)
        }
    }
}
