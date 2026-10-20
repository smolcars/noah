package com.noahwallet.onchain

import io.github.niteshbalusu11.bark.*
import com.noahwallet.services.wallet.ChainStatus
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class OnchainJournalTest {
    private val json = Json { encodeDefaults = true }
    private fun journal(file: File) = OnchainOperationJournal(OnchainFile(file) {})
    private suspend fun fixture(body: suspend (File, OnchainOperationJournal) -> Unit) {
        val root = Files.createTempDirectory("m6-journal").toFile()
        try { withTimeout(10_000) { body(File(root, "attempt.json"), journal(File(root, "attempt.json"))) } }
        finally { root.deleteRecursively() }
    }
    private fun quote(action: OnchainAction = OnchainAction.board, ids: List<String> = emptyList()) = OnchainQuote(action, "wallet", ids, amount = 30_000, fee = 330)
    private suspend fun uncertain(journal: OnchainOperationJournal, quote: OnchainQuote) {
        assertEquals(OnchainFailure.uncertain, (runCatching { journal.perform(quote) { throw BarkError("unclassified", BarkErrorCode.OTHER) } }.exceptionOrNull() as OnchainException).reason)
    }
    @Test fun savesBeforeNativeAndSurvivesRestartWithoutResubmitting() = runBlocking { fixture { file, journal ->
        val quote = quote()
        var calls = 0
        var observedDurableAttempt = false
        val original = runCatching { journal.perform(quote) {
            assertEquals(quote, journal(file).loadAll().single().quote)
            observedDurableAttempt = true
            throw BarkError("unclassified", BarkErrorCode.OTHER)
        } }.exceptionOrNull()
        assertEquals(OnchainFailure.uncertain, (original as OnchainException).reason)
        assertTrue(observedDurableAttempt)
        val reopened = journal(file)
        assertEquals(quote, reopened.loadAll().single().quote)
        val result = runCatching { reopened.perform(quote.copy(id = "repeat")) { calls++; OnchainReceipt("bad") } }
        assertEquals(OnchainFailure.busy, (result.exceptionOrNull() as OnchainException).reason)
        assertEquals(0, calls)
    } }
    @Test fun persistenceFailurePreventsSubmission() = runBlocking {
        var calls = 0
        val root = Files.createTempDirectory("m6-unwritable").toFile()
        try {
            val j = OnchainOperationJournal(OnchainFile(File(root, "missing/attempt")) {})
            assertTrue(runCatching { j.perform(quote()) { calls++; OnchainReceipt("bad") } }.isFailure)
            assertEquals(0, calls)
        } finally { root.deleteRecursively() }
    }
    @Test fun onlySameOperationAndOverlappingItemsBlock() = runBlocking { fixture { _, journal ->
        val board = quote()
        uncertain(journal, board)
        val refresh = quote(OnchainAction.refresh, listOf("a"))
        uncertain(journal, refresh)
        val attempts = journal.loadAll()
        assertTrue(attempts[0].blocks(OnchainAction.board, emptyList()))
        assertFalse(attempts[0].blocks(OnchainAction.claim, listOf("a")))
        assertTrue(attempts[1].blocks(OnchainAction.refresh, listOf("a", "b")))
        assertFalse(attempts[1].blocks(OnchainAction.refresh, listOf("b")))
        for (action in listOf(OnchainAction.startExit, OnchainAction.progress, OnchainAction.claim, OnchainAction.cancel))
            assertFalse(attempts.any { it.blocks(action, listOf("a")) })
    } }
    @Test fun barkHarmlessRepeatsAreAllowedButClaimsAndProgressRemainBlocked() {
        for (action in OnchainAction.entries) {
            val attempt = OnchainOperationJournal.Attempt(quote(action, listOf("a")))
            assertEquals(action !in setOf(OnchainAction.startExit, OnchainAction.cancel), attempt.blocks(action, listOf("a")))
        }
    }
    @Test fun typedPreBroadcastAllowlistIsClosedForEveryCall() = runBlocking { fixture { _, journal ->
        for (action in OnchainAction.entries) for (code in BarkErrorCode.entries) {
            val error = BarkError("insufficient funds dust: misleading diagnostics", code)
            val expected = action == OnchainAction.board && code in setOf(BarkErrorCode.INSUFFICIENT_FUNDS, BarkErrorCode.DUST)
            assertEquals(expected, OnchainSubmission.preBroadcast(error, action) == OnchainFailure.constructionFailed)
        }
        val q = quote()
        val failure = runCatching { journal.perform(q) { throw BarkError("anything", BarkErrorCode.INSUFFICIENT_FUNDS) } }.exceptionOrNull()
        assertEquals(OnchainFailure.constructionFailed, (failure as OnchainException).reason)
        assertTrue(journal.loadAll().isEmpty())
        uncertain(journal, q)
        assertEquals(1, journal.loadAll().size)
    } }
    @Test fun cachedChecksAreExactAndNeverResolveMissingIDsLockedInputsOrWrongWallet() = runBlocking { fixture { _, journal ->
        val q = quote()
        uncertain(journal, q)
        val trace = mutableListOf<String>()
        val reads = object : OnchainAttemptReads {
            override val walletID = "wallet"
            override suspend fun transactionIDs(): Set<String> { trace.add("transactions"); return setOf("unrelated") }
            override suspend fun vtxoState(id: String): String { trace.add("vtxo:$id"); return "Locked" }
            override suspend fun exitState(id: String): String? { trace.add("exit:$id"); return null }
        }
        assertFalse(checkOnchainAttempt(q.id, journal, reads))
        assertEquals(listOf("transactions"), trace)
        val refresh = quote(OnchainAction.refresh, listOf("a")); uncertain(journal, refresh)
        trace.clear(); assertFalse(checkOnchainAttempt(refresh.id, journal, reads))
        assertEquals(listOf("transactions", "vtxo:a", "exit:a"), trace)
        assertNull(OnchainOperationJournal.Evidence("other", transactionIDs = setOf("tx")).receipt(OnchainOperationJournal.Attempt(q, txid = "tx")))
    } }
    @Test fun evidenceOnlyAcceptsConclusivePerItemStates() {
        val actions = mapOf(OnchainAction.startExit to "Start", OnchainAction.claim to "ClaimInProgress",
            OnchainAction.cancel to "Canceled", OnchainAction.progress to "Claimable", OnchainAction.refresh to "Spent")
        actions.forEach { (action, state) ->
            val a = OnchainOperationJournal.Attempt(quote(action, listOf("a", "b")))
            val good = if (action == OnchainAction.refresh) OnchainOperationJournal.Evidence("wallet", vtxoStates = mapOf("a" to state, "b" to state))
                else OnchainOperationJournal.Evidence("wallet", exitStates = mapOf("a" to state, "b" to state))
            assertNotNull(good.receipt(a))
            assertNull(good.copy(vtxoStates = good.vtxoStates - "b", exitStates = good.exitStates - "b").receipt(a))
        }
    }
    @Test fun overrideRetainsConsentAndLateOriginalAlongsideNewReceipt() = runBlocking { fixture { file, journal ->
        val original = quote()
        uncertain(journal, original)
        journal.overrideUncertain(original.id, 1234)
        var saved = journal(file).loadAll().single()
        assertEquals(original, saved.quote)
        assertEquals(1234L, saved.overrideAudit!!.overriddenAt)
        assertEquals(OnchainOperationJournal.overrideWarning, saved.overrideAudit.warning)
        assertEquals(OnchainOperationJournal.overrideTitle, saved.overrideAudit.title)
        assertEquals(OnchainOperationJournal.overrideConfirmation, saved.overrideAudit.confirmation)
        assertFalse(saved.blocks(OnchainAction.board, emptyList()))
        val fresh = quote()
        journal.perform(fresh) { record -> record("new"); OnchainReceipt("new receipt", "new", 9) }
        // Exact txid persisted before receipt-loss; simulate the original call returning late.
        val all = journal.loadAll().map { if (it.id == original.id) it.copy(txid = "original") else it }
        file.writeText(json.encodeToString(all))
        assertTrue(journal.reconcile(original.id, OnchainOperationJournal.Evidence("wallet", transactionIDs = setOf("original"))))
        journal.refreshBoardFees("wallet", mapOf("original" to 1543, "new" to 99))
        saved = journal(file).loadAll().first()
        assertEquals(1543L, saved.receipt!!.onchainFee)
        assertEquals(9L, journal.loadAll().last().receipt!!.onchainFee)
        journal.acknowledge(original.id, 2345)
        saved = journal(file).loadAll().first()
        assertEquals(2345L, saved.overrideAudit!!.acknowledgedAt)
        assertFalse(saved.blocks(OnchainAction.board, emptyList()))
        assertEquals(2, journal.loadAll().size)
    } }
    @Test fun keepsNewest100OverridesByTimeAndNeverPrunesActiveAttempts() = runBlocking { fixture { file, journal ->
        val active = OnchainOperationJournal.Attempt(quote())
        val audits = (1..102).map { OnchainOperationJournal.Attempt(quote(), overrideAudit = OnchainOperationJournal.OverrideAudit(it.toLong())) }
        file.writeText(json.encodeToString(listOf(active) + audits.reversed()))
        journal.overrideUncertain(active.id, 103)
        val saved = journal(file).loadAll()
        assertEquals(100, saved.size)
        assertEquals(4L, saved.minOf { it.overrideAudit!!.overriddenAt })
        val pending = quote(OnchainAction.claim, listOf("unrelated"))
        uncertain(journal, pending)
        assertEquals(101, journal.loadAll().size)
        assertNotNull(journal.loadAll().find { it.id == pending.id })
    } }
    @Test fun singleAndArrayLegacyJournalsMigrateOnWrite() = runBlocking { fixture { file, journal ->
        val a = OnchainOperationJournal.Attempt(quote())
        for (text in listOf(json.encodeToString(a), json.encodeToString(listOf(a)))) {
            file.writeText(text)
            assertEquals(a, journal.loadAll().single())
            journal.overrideUncertain(a.id, 1)
            assertTrue(file.readText().startsWith("["))
            assertNotNull(journal(file).loadAll().single().overrideAudit)
        }
    } }
    @Test fun boardReceiptMissingFeeUpdatesByExactTxidAndWalletOnly() = runBlocking { fixture { file, journal ->
        val q = quote()
        journal.perform(q) { record -> record("tx"); OnchainReceipt(OnchainReceipt.boardingMessage(null), "tx") }
        journal.refreshBoardFees("other-wallet", mapOf("tx" to 1543))
        assertNull(journal.loadAll().single().receipt!!.onchainFee)
        journal.refreshBoardFees("wallet", mapOf("wrong" to 1543))
        assertNull(journal.loadAll().single().receipt!!.onchainFee)
        journal.refreshBoardFees("wallet", mapOf("tx" to 1543))
        assertEquals(1543L, journal(file).loadAll().single().receipt!!.onchainFee)
        journal.acknowledge(q.id)
        assertTrue(journal(file).loadAll().isEmpty())
    } }
    @Test fun overriddenQuoteCannotReuseTheOriginalAttemptID() = runBlocking { fixture { _, journal ->
        val q = quote(); uncertain(journal, q); journal.overrideUncertain(q.id)
        var calls = 0
        val error = runCatching { journal.perform(q) { calls++; OnchainReceipt("duplicate") } }.exceptionOrNull()
        assertEquals(OnchainFailure.changed, (error as OnchainException).reason)
        assertEquals(0, calls); assertEquals(1, journal.loadAll().size)
    } }
    @Test fun preparedCrashRecoveryUsesExactNetworkPendingBoardOrMovementIDNeverAmount() = runBlocking {
        for (source in listOf("mempool", "confirmed", "pending", "cached", "missing", "lookalike")) fixture { file, journal ->
            val quote = quote().copy(chainFee = 199, preparedBoard = BoardPrepared("local", "exact", 199, listOf(BoardInput("input", 0u))))
            runCatching { journal.perform(quote) { record -> record("exact"); error("process died") } }
            val reopened = journal(file)
            val status = when (source) {
                "mempool" -> ChainStatus(ChainStatus.Kind.inMempool)
                "confirmed" -> ChainStatus(ChainStatus.Kind.confirmed, 123)
                else -> ChainStatus(ChainStatus.Kind.notFound)
            }
            val evidence = OnchainOperationJournal.Evidence("wallet",
                transactionIDs = if (source == "cached") setOf("exact") else setOf("unrelated"),
                chainStatuses = mapOf("exact" to status),
                boardTransactionIDs = if (source == "pending") setOf("exact") else setOf("old-board"))
            val resolved = reopened.reconcile(quote.id, evidence)
            assertEquals(source, source !in listOf("missing", "lookalike"), resolved)
            val receipt = reopened.loadAll().single().receipt
            if (resolved) { assertEquals("exact", receipt!!.txid); assertEquals(199L, receipt.onchainFee); assertEquals(source != "confirmed", receipt.broadcastRetryPending) }
            else assertNull(receipt)
        }
    }
    @Test fun failedBoardKeepsLockOnNotFoundAndMempoolAndTransportErrorsKeepLastKnownStatus() = runBlocking { fixture { _, journal ->
        val quote = quote().copy(chainFee = 199, preparedBoard = BoardPrepared("local", "exact", 199, emptyList()))
        journal.perform(quote) { record -> record("exact"); OnchainReceipt("Noah will keep retrying", "exact", 199, true) }
        val mempool = ChainStatus(ChainStatus.Kind.inMempool)
        for (status in listOf(ChainStatus(ChainStatus.Kind.notFound), mempool)) {
            journal.refreshStatus(quote.id, status)
            assertTrue(journal.loadAll().single().receipt!!.broadcastRetryPending)
            assertTrue(runCatching { journal.acknowledge(quote.id) }.isFailure)
        }
        journal.refreshStatus(quote.id, null, ChainStatus.errorText)
        assertEquals(mempool, journal.loadAll().single().receipt!!.chainStatus)
        assertEquals(ChainStatus.errorText, journal.loadAll().single().receipt!!.statusError)
        journal.refreshStatus(quote.id, ChainStatus(ChainStatus.Kind.confirmed, 200))
        val receipt = journal.loadAll().single().receipt!!
        assertFalse(receipt.broadcastRetryPending); assertNull(receipt.statusError)
        assertEquals("Confirmed at block 200", receipt.chainStatus!!.text)
        journal.acknowledge(quote.id); assertTrue(journal.loadAll().isEmpty())
    } }
    @Test fun claimReceiptUsesNetworkStatusWithoutSyncAndPreservesErrors() = runBlocking { fixture { _, journal ->
        val quote = quote(OnchainAction.claim, listOf("vtxo"))
        journal.perform(quote) { record -> record("claim"); OnchainReceipt("Claim submitted", "claim") }
        var failure = false
        val reads = object : OnchainAttemptReads {
            override val walletID = "wallet"
            override suspend fun transactionIDs() = emptySet<String>()
            override suspend fun transactionStatus(txid: String): ChainStatus { assertEquals("claim", txid); if (failure) error("offline"); return ChainStatus(ChainStatus.Kind.confirmed, 201) }
            override suspend fun vtxoState(id: String) = "Exited"
            override suspend fun exitState(id: String) = "Claimed"
        }
        assertTrue(checkOnchainAttempt(quote.id, journal, reads))
        failure = true; assertTrue(runCatching { checkOnchainAttempt(quote.id, journal, reads) }.isFailure)
        assertEquals(201L, journal.loadAll().single().receipt!!.chainStatus!!.height)
        assertNotNull(journal.loadAll().single().receipt!!.statusError)
    } }

}
