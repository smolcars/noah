package com.noahwallet.onchain

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class OnchainModelTest {
    private class Fake {
        var ready = true
        var calls = 0
        var checks = 0
        var receiptRefreshes = 0
        var gate: CompletableDeferred<Unit>? = null
        var entered: CompletableDeferred<Unit>? = null
        var attempts = emptyList<OnchainOperationJournal.Attempt>()
        var detailCalls = 0
        val overview = OnchainOverview("wallet", emptyList(), emptyList(), 100_000, 100, 0, null)
        fun dependencies() = OnchainModel.Dependencies(
            { ready }, { overview }, { action, ids, amount, destination ->
                entered?.complete(Unit); gate?.await(); OnchainQuote(action, "wallet", ids, destination, amount)
            }, { calls++; "submitted" }, { attempts }, { id -> attempts = attempts.filter { it.id != id } },
            { checks++; false }, {}, { id -> detailCalls++; ExitModel(id, 1, "Canceled", ExitDetails(), listOf("Start", "Canceled"), emptyList()) }, { receiptRefreshes++ })
    }
    @Test fun lateReviewCannotInstallAfterInputsOrReadinessChange() = runBlocking {
        val f = Fake().apply { gate = CompletableDeferred(); entered = CompletableDeferred() }
        val m = OnchainModel(f.dependencies())
        val request = launch { m.review(OnchainAction.board, amount = 30_000) }
        f.entered!!.await(); m.invalidate(); f.gate!!.complete(Unit); request.join()
        assertNull(m.quote); assertEquals(0, f.calls)
        f.ready = false; m.review(OnchainAction.board); assertNull(m.quote)
    }
    @Test fun suspensionAfterReviewPreventsSubmissionAndClearsDetails() = runBlocking {
        val f = Fake(); val m = OnchainModel(f.dependencies(), "canceled")
        m.load(); assertNotNull(m.exit)
        m.review(OnchainAction.board, amount = 30_000); assertNotNull(m.quote)
        f.ready = false; m.confirm(); m.load()
        assertEquals(0, f.calls); assertNull(m.overview); assertNull(m.exit); assertNull(m.quote)
    }
    @Test fun loadReconcilesRetainedOriginalsAndNeverAutomaticallySubmits() = runBlocking {
        val f = Fake()
        f.attempts = listOf(OnchainOperationJournal.Attempt(OnchainQuote(OnchainAction.board, "wallet"), overrideAudit = OnchainOperationJournal.OverrideAudit(1)))
        val m = OnchainModel(f.dependencies())
        repeat(3) { m.load() }
        assertEquals(3, f.checks); assertEquals(3, f.receiptRefreshes); assertEquals(0, f.calls)
        m.checkStatus(f.attempts.single().id)
        assertTrue(m.message!!.contains("automatically as your wallet syncs")); assertEquals(0, f.calls)
    }
    @Test fun directTerminalDetailWorksWithEmptyOverviewAndAcknowledgementStaysHidden() = runBlocking {
        val f = Fake(); val m = OnchainModel(f.dependencies(), "terminal")
        m.load(); assertEquals("Canceled", m.exit!!.state); assertEquals(1, f.detailCalls)
        f.attempts = listOf(OnchainOperationJournal.Attempt(OnchainQuote(OnchainAction.board, "wallet"), OnchainReceipt("late"), overrideAudit = OnchainOperationJournal.OverrideAudit(1, acknowledgedAt = 2)))
        m.load(); assertTrue(m.attempts.isEmpty())
        m.review(OnchainAction.claim, listOf("different"), destination = "address"); assertNotNull(m.quote)
        assertTrue(m.attempts.isEmpty())
    }
    @Test fun exactlyOneUserConfirmationSubmitsAndStatusReadsDoNot() = runBlocking {
        val f = Fake(); val m = OnchainModel(f.dependencies())
        m.review(OnchainAction.board, amount = 30_000); assertEquals(0, f.calls)
        m.confirm(); m.confirm(); m.load(); assertEquals(1, f.calls)
    }
    @Test fun automaticPromptHonorsToggleAttemptReadinessAndBlocking() {
        for (enabled in listOf(false, true)) for (attempted in listOf(false, true)) for (ready in listOf(false, true)) for (blocking in listOf(false, true)) {
            assertEquals(enabled && !attempted && ready && !blocking, AutoBoardRules.shouldPrompt(enabled, attempted, ready, 100_000, blocking))
        }
        assertFalse(AutoBoardRules.shouldPrompt(true, false, true, 19_999, false))
    }
    @Test fun startAndManualProgressReviewsCarryRNConsentAndEstimatedFees() {
        val start = reviewExplanation(OnchainQuote(OnchainAction.startExit, "wallet"))
        assertTrue(start.contains("automatically in the background")); assertTrue(start.contains("paying fees from your onchain balance"))
        assertTrue(start.contains("estimates")); assertTrue(start.contains("final exit transaction is broadcast"))
        assertTrue(reviewExplanation(OnchainQuote(OnchainAction.progress, "wallet")).startsWith("This may broadcast or fee-bump Bitcoin transactions required by the emergency exit process."))
    }
}
