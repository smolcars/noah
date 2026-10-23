package com.noahwallet.send

import com.noahwallet.fixtures.SharedFixtures
import io.github.niteshbalusu11.bark.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

internal open class FakeSendWallet : SendWallet {
    var owner = SendIdentity("wallet-a", "REGTEST")
    var snapshot = SendSnapshot(walletIdentity = owner)
    var calls = 0; var prepareCalls = 0; var discards = 0
    var payBlock: suspend () -> SendReceipt = { SendReceipt(1000, proof = SendReceipt.Proof.submitReturnedArkSuccess) }
    var prepareBlock: suspend () -> SendSnapshot = { snapshot }
    var reconciliation: suspend (SendAttempt) -> SendResolution = { SendResolution.Uncertain }
    override suspend fun identity() = owner
    var quoteBlock: suspend (SendPayment) -> SendQuote = { quote }
    override suspend fun quote(payment: SendPayment) = quoteBlock(payment)
    override suspend fun prepare(payment: SendPayment, quote: SendQuote): SendSnapshot { prepareCalls++; return prepareBlock() }
    override suspend fun pay(payment: SendPayment, quote: SendQuote, snapshot: SendSnapshot): SendReceipt { calls++; return payBlock() }
    override suspend fun reconcile(attempt: SendAttempt) = reconciliation(attempt)
    override suspend fun discard(quote: SendQuote, includingClaimed: Boolean) { discards++ }
    override suspend fun discardAll() {}
    companion object { val quote = SendQuote(1000, 10, 1010, walletIdentity = SendIdentity("wallet-a", "REGTEST")) }
}
internal class TestJournal {
    val audits = mutableListOf<SendOverrideAudit>()
    var attempt: SendAttempt? = null; var fail = false; var failReceipt = false
    fun write(value: SendAttempt?) { if (fail || failReceipt && value?.receipt != null) error("disk"); attempt = value?.let { Json.decodeFromString<SendAttempt>(Json { encodeDefaults = true }.encodeToString(it)) } }
}
internal fun controller(wallet: FakeSendWallet, journal: TestJournal, scope: CoroutineScope) = SendController(SendController.Dependencies({ journal.attempt }, journal::write, auditOverride = { journal.audits.add(it) }) { body -> withContext(NonCancellable) { body(wallet) } }, scope)
internal val arkPayment = SendPayment("ark-address", "ark", 1000)
internal val chainPayment = SendPayment("chain-address", "onchain", 1000, SendSource.onchain)
internal val chainQuote = FakeSendWallet.quote.copy(prepared = SendPrepared("process-local", "exact", 143, 1))
internal fun invoice(): String = SharedFixtures.load("send-utils").map { it.input.jsonObject.getValue("destination").jsonPrimitive.content }.first { PaymentEncoding.invoice(it) != null }
/** A Lightning send's effective balance is its HTLCs, amount plus fee, as bark 0.8 records it. */
internal fun movement(method: String = "lightning", amount: Long = 1000, hash: String? = PaymentEncoding.invoice(invoice())!!.hash, status: String = "successful", id: UInt = 1u) = Movement(
    id, status, if (method == "ark") "bark.arkoor" else "bark.lightning_send", "send", "{}", -amount, if (method == "ark") -amount else -amount - 83, 83u,
    listOf(MovementDestination(PaymentMethod(method, "ark-address"), amount.toULong())), emptyList(), emptyList(), emptyList(), emptyList(), "2099-01-01T00:00:00Z", "2099-01-01T00:00:00Z", null, hash, null, null)
internal fun transaction(id: String = "exact", confirmed: Boolean = false) = WalletTransaction(id, "", 10u, -1010, if (confirmed) BlockRef(1u, "block") else null, false)

class SendSafetyTest {
    @Test fun lockIsSynchronousAndJournalPrecedesPay() = runBlocking {
        val journal = TestJournal(); val wallet = FakeSendWallet(); val gate = CompletableDeferred<Unit>()
        wallet.payBlock = { assertEquals(chainQuote, journal.attempt!!.quote); assertEquals("exact", journal.attempt!!.broadcastingTxid); gate.await(); SendReceipt(1000, txid = "exact", fee = 10, proof = SendReceipt.Proof.preparedTxid) }
        val subject = controller(wallet, journal, this)
        subject.submit(chainPayment, chainQuote); assertTrue(subject.locked); subject.submit(chainPayment, chainQuote)
        yield(); assertEquals(1, wallet.calls); gate.complete(Unit); coroutineContext.job.children.toList().joinAll()
        assertEquals(SendController.State.succeeded, subject.state); assertEquals(1, wallet.discards)
    }
    @Test fun failedInitialWritePreventsAnyPaymentAndReleasesReview() = runBlocking {
        val journal = TestJournal().apply { fail = true }; val wallet = FakeSendWallet(); val subject = controller(wallet, journal, this)
        subject.submit(chainPayment, chainQuote); coroutineContext.job.children.toList().joinAll()
        assertEquals(0, wallet.calls); assertFalse(subject.locked); assertEquals(1, wallet.discards)
    }
    @Test fun inspectionFailureStaysLockedAcrossRestart() = runBlocking {
        val journal = TestJournal(); val wallet = FakeSendWallet().apply { prepareBlock = { error("inspection") } }
        val subject = controller(wallet, journal, this); subject.submit(SendPayment(invoice(), "lightning", 1000), FakeSendWallet.quote)
        coroutineContext.job.children.toList().joinAll(); assertTrue(journal.attempt!!.inspectionPending); assertEquals(0, wallet.calls)
        val restarted = controller(wallet, journal, this); restarted.reloadForWalletChange(); assertEquals(SendController.State.uncertain, restarted.state); restarted.acknowledge(); assertTrue(restarted.locked)
    }
    @Test fun cancellationDoesNotAbandonPaymentAndNeverRetries() = runBlocking {
        val journal = TestJournal(); val wallet = FakeSendWallet(); val entered = CompletableDeferred<Unit>(); val gate = CompletableDeferred<Unit>()
        val ownScope = CoroutineScope(Job() + Dispatchers.Unconfined)
        wallet.payBlock = { entered.complete(Unit); gate.await(); SendReceipt(1000, proof = SendReceipt.Proof.submitReturnedArkSuccess) }
        val subject = controller(wallet, journal, ownScope); subject.submit(arkPayment, FakeSendWallet.quote)
        entered.await(); ownScope.cancel(); gate.complete(Unit); ownScope.coroutineContext.job.join()
        assertEquals(SendController.State.succeeded, subject.state); assertEquals(1, wallet.calls)
        val restart = controller(wallet, journal, this); restart.reloadForWalletChange(); assertEquals(SendController.State.succeeded, restart.state); restart.checkStatus(); assertEquals(1, wallet.calls)
    }
    @Test fun unknownErrorsAndWeakHistoryNeverUnlock() = runBlocking {
        val journal = TestJournal(); val wallet = FakeSendWallet().apply { payBlock = { throw BarkError("unknown") }; reconciliation = { SendResolution.Candidates(listOf(1u)) } }
        val subject = controller(wallet, journal, this); subject.submit(arkPayment, FakeSendWallet.quote); coroutineContext.job.children.toList().joinAll()
        assertEquals(SendController.State.uncertain, subject.state); subject.acknowledge(); assertTrue(subject.locked)
        subject.startNewPaymentAfterHistoryCheck(false); assertTrue(subject.locked)
        subject.startNewPaymentAfterHistoryCheck(true); assertFalse(subject.locked); assertNull(journal.attempt)
    }
    @Test fun allReceiptRailsSurviveRestartAndForeignWalletIsDiscarded() = runBlocking {
        val cases = listOf(arkPayment to SendReceipt(1000, proof = SendReceipt.Proof.submitReturnedArkSuccess),
            chainPayment to SendReceipt(1000, txid = "exact", fee = 10, proof = SendReceipt.Proof.preparedTxid),
            chainPayment.copy(source = SendSource.offchain) to SendReceipt(1000, txid = "offboard", proof = SendReceipt.Proof.submitReturnedTxid)) +
            listOf("lightning", "lnurl", "offer").map { method -> SendPayment(invoice(), method, 1000) to SendReceipt(1000,
                paymentHash = PaymentEncoding.invoice(invoice())!!.hash, movementID = 1u, fee = 83, proof = SendReceipt.Proof.actualMovement) }
        for ((payment, receipt) in cases) {
            val wallet = FakeSendWallet(); val journal = TestJournal().apply { attempt = SendAttempt(walletIdentity = wallet.owner, payment = payment, receipt = receipt) }
            val restarted = controller(wallet, journal, this); restarted.reloadForWalletChange(); assertEquals(SendController.State.succeeded, restarted.state)
            wallet.owner = SendIdentity("other", "REGTEST"); restarted.reloadForWalletChange(); assertFalse(restarted.locked); assertNull(journal.attempt)
        }
    }
    @Test fun identityFreeAndUnsafeVersionJournalsAreQuarantined() = runBlocking {
        val wallet = FakeSendWallet(); val journal = TestJournal().apply { attempt = SendAttempt(payment = arkPayment, receipt = SendReceipt(1000, proof = SendReceipt.Proof.submitReturnedArkSuccess)) }
        val subject = controller(wallet, journal, this); subject.reloadForWalletChange(); assertTrue(subject.locked); assertNull(subject.attempt); assertTrue(subject.canOverride)
        journal.attempt = SendAttempt(walletIdentity = wallet.owner, safetyVersion = 0, payment = chainPayment, receipt = SendReceipt(1000, txid = "weak", proof = SendReceipt.Proof.preparedTxid))
        subject.reloadForWalletChange(); assertEquals(SendController.State.uncertain, subject.state); assertNull(subject.attempt!!.receipt); assertEquals(listOf("weak"), subject.attempt!!.possibleTxids)
        assertEquals(0, wallet.calls)
    }
    @Test fun journalRemovalFailuresKeepLocks() = runBlocking {
        val wallet = FakeSendWallet(); val journal = TestJournal().apply { attempt = SendAttempt(walletIdentity = wallet.owner, payment = arkPayment) }
        val subject = controller(wallet, journal, this); subject.reloadForWalletChange(); journal.fail = true
        subject.startNewPaymentAfterHistoryCheck(true); assertTrue(subject.locked)
        wallet.owner = SendIdentity("other", "REGTEST"); subject.reloadForWalletChange(); assertEquals(SendController.State.storageBlocked, subject.state)
    }
    @Test fun reorgRevalidatesConfirmedReceiptAndSyncFailureSurfaces() = runBlocking {
        val wallet = FakeSendWallet(); val journal = TestJournal().apply { attempt = SendAttempt(walletIdentity = wallet.owner, payment = chainPayment,
            receipt = SendReceipt(1000, txid = "exact", status = SendReceipt.Status.confirmed, proof = SendReceipt.Proof.preparedTxid)) }
        wallet.reconciliation = { SendReconciliation.onchain(it, listOf(transaction())) }
        val subject = controller(wallet, journal, this); subject.reloadForWalletChange(); subject.checkStatus(); assertEquals(SendReceipt.Status.submitted, subject.attempt!!.receipt!!.status)
        wallet.reconciliation = { error("sync failed") }; subject.checkStatus(); assertEquals(SendController.State.succeeded, subject.state); assertEquals(SendReceipt.Status.submitted, subject.attempt!!.receipt!!.status); assertTrue(subject.message!!.startsWith("Couldn't check")); assertTrue(subject.locked)
    }
    @Test fun railEvidenceCannotCrossIntoAnotherRail() {
        val m = movement("ark", hash = null)
        assertFalse(SendReconciliation.matches(m, chainPayment, null)); assertFalse(SendReconciliation.matches(m, SendPayment(invoice(), "lightning", 1000), null))
        val attempt = SendAttempt(payment = arkPayment)
        assertTrue(SendReconciliation.ark(attempt, listOf(m)) is SendResolution.Candidates)
        assertFalse(SendReceipt(1000, txid = "exact", proof = SendReceipt.Proof.preparedTxid).isProven(arkPayment))
    }
}
