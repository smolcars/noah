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
        var failPreparation = false
        var failStatusRead = false
        var detailCalls = 0
        val discarded = mutableListOf<OnchainQuote>()
        val overview = OnchainOverview("wallet", emptyList(), emptyList(), 100_000, 100, 0, null)
        fun dependencies() = OnchainModel.Dependencies(
            { ready }, { overview }, { action, ids, amount, destination ->
                entered?.complete(Unit); gate?.await(); if (failPreparation) fail(OnchainFailure.notSynced); OnchainQuote(action, "wallet", ids, destination, amount)
            }, { calls++; "submitted" }, { attempts }, { id -> attempts = attempts.filter { it.id != id } },
            { checks++; if (failStatusRead) error("transport"); false }, {}, { id -> detailCalls++; ExitModel(id, 1, "Canceled", ExitDetails(), listOf("Start", "Canceled"), emptyList()) }, { receiptRefreshes++ }, discard = { discarded.add(it) })
    }
    @Test fun lateReviewCannotInstallAfterInputsOrReadinessChange() = runBlocking {
        val f = Fake().apply { gate = CompletableDeferred(); entered = CompletableDeferred() }
        val m = OnchainModel(f.dependencies())
        val request = launch { m.review(OnchainAction.board, amount = 30_000) }
        f.entered!!.await(); m.invalidate(); f.gate!!.complete(Unit); request.join()
        assertNull(m.quote); assertEquals(0, f.calls); assertEquals(1, f.discarded.size)
        f.ready = false; m.review(OnchainAction.board); assertNull(m.quote)
    }
    @Test fun latePreparationFailureCannotOverwriteBackgroundOrWalletChange() = runBlocking {
        val f = Fake().apply { gate = CompletableDeferred(); entered = CompletableDeferred(); failPreparation = true }
        val m = OnchainModel(f.dependencies())
        val request = launch { m.review(OnchainAction.board, amount = 30_000) }
        f.entered!!.await(); m.unavailable(); f.gate!!.complete(Unit); request.join()
        assertNull(m.quote); assertNull(m.error); assertEquals(0, f.calls)
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
    @Test fun automaticStatusTransportErrorRemainsVisibleAlongsideLastKnownReceipt() = runBlocking {
        val f = Fake().apply {
            failStatusRead = true
            attempts = listOf(OnchainOperationJournal.Attempt(OnchainQuote(OnchainAction.board, "wallet"),
                OnchainReceipt("Submitted", "tx", chainStatus = com.noahwallet.services.wallet.ChainStatus(com.noahwallet.services.wallet.ChainStatus.Kind.inMempool))))
        }
        val m = OnchainModel(f.dependencies()); m.load()
        assertEquals(com.noahwallet.services.wallet.ChainStatus.errorText, m.error)
        assertEquals("In mempool", m.attempts.single().receipt!!.chainStatus!!.text)
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
    @Test fun backgroundCounterCountsOverlapsAndDrainsOnFailure() = runBlocking {
        val first = CompletableDeferred<Unit>(); val second = CompletableDeferred<Unit>()
        val entered1 = CompletableDeferred<Unit>(); val entered2 = CompletableDeferred<Unit>()
        val a = launch { com.noahwallet.services.wallet.WalletBackgroundActivity.during { entered1.complete(Unit); first.await() } }
        val b = launch { runCatching { com.noahwallet.services.wallet.WalletBackgroundActivity.during { entered2.complete(Unit); second.await(); error("failed push") } } }
        entered1.await(); entered2.await(); assertTrue(com.noahwallet.services.wallet.WalletBackgroundActivity.isRunning)
        first.complete(Unit); a.join(); assertTrue(com.noahwallet.services.wallet.WalletBackgroundActivity.isRunning)
        second.complete(Unit); b.join(); assertFalse(com.noahwallet.services.wallet.WalletBackgroundActivity.isRunning)
    }

    @Test fun claimDestinationPrefillsRestoresAndIgnoresLateOwnership() = runBlocking {
        val f = Fake()
        val gate = CompletableDeferred<Boolean>()
        val entered = CompletableDeferred<Unit>()
        val deps = OnchainModel.Dependencies({ f.ready }, { f.overview }, { _, _, _, _ -> error("unused") },
            { error("must not submit") }, { emptyList() }, {}, { false }, {}, { null }, {},
            claimAddress = { "fresh-wallet-address" }, claimOwnership = { address ->
                if (address == "cold-storage") { entered.complete(Unit); gate.await() } else true
            })
        val m = OnchainModel(deps)
        m.useWalletClaimAddress(); assertEquals("fresh-wallet-address", m.claimDestination)
        m.checkClaimDestination(); assertEquals(true, m.claimDestinationIsMine)
        m.editClaimDestination("cold-storage")
        val pending = launch { m.checkClaimDestination() }
        entered.await(); m.useWalletClaimAddress(); gate.complete(false); pending.join()
        assertEquals("fresh-wallet-address", m.claimDestination); assertNull(m.claimDestinationIsMine)
        m.checkClaimDestination(); assertEquals(true, m.claimDestinationIsMine)
        m.editClaimDestination("cold-storage"); m.checkClaimDestination(); assertEquals(false, m.claimDestinationIsMine)
        f.ready = false; m.checkClaimDestination(); assertNull(m.claimDestinationIsMine); assertNotNull(m.claimDestinationError)
    }

    @Test fun delayedClaimAddressDoesNotOverwriteUserEdit() = runBlocking {
        withTimeout(5_000) {
            for (fails in listOf(false, true)) {
                val fetched = CompletableDeferred<String>()
                val entered = CompletableDeferred<Unit>()
                val f = Fake()
                val m = OnchainModel(claimDependencies(f, { entered.complete(Unit); fetched.await() }))
                val pending = launch { m.useWalletClaimAddress() }
                entered.await()
                m.editClaimDestination("cold-storage")
                if (fails) fetched.completeExceptionally(IllegalStateException("address fetch failed"))
                else fetched.complete("fresh-wallet-address")
                pending.join()
                assertEquals("cold-storage", m.claimDestination)
                assertEquals(fails, m.claimDestinationError != null)
                assertEquals(0, f.calls)
            }
        }
    }

    @Test fun failedClaimAddressPreservesInputBlocksEmptyReviewAndRetries() = runBlocking {
        for (typed in listOf("", "cold-storage")) {
            val f = Fake()
            var fetches = 0
            val m = OnchainModel(claimDependencies(f, {
                fetches++
                if (fetches <= 2) error("address fetch failed")
                "fresh-wallet-address"
            }))
            m.editClaimDestination(typed)
            repeat(2) {
                m.useWalletClaimAddress()
                assertEquals(typed, m.claimDestination)
                assertEquals("Couldn't load a Noah wallet address. Try again when the wallet is ready.", m.claimDestinationError)
                assertNull(m.quote)
                m.confirm()
                assertEquals(0, f.calls)
            }
            if (typed.isEmpty()) {
                m.review(OnchainAction.claim, listOf("a"), destination = m.claimDestination)
                assertNull(m.quote); assertNotNull(m.error)
                m.confirm(); assertEquals(0, f.calls)
            }
            m.useWalletClaimAddress()
            assertEquals(3, fetches)
            assertEquals("fresh-wallet-address", m.claimDestination)
            assertNull(m.claimDestinationError)
        }
    }

    private fun claimDependencies(f: Fake, address: suspend () -> String) = OnchainModel.Dependencies(
        { f.ready }, { f.overview }, { action, ids, _, destination ->
            if (destination.isNullOrBlank()) fail(OnchainFailure.invalid)
            OnchainQuote(action, "wallet", ids, destination)
        }, { f.calls++; "submitted" }, { emptyList() }, {}, { false }, {}, { null }, {},
        claimAddress = address, claimOwnership = { false })

}
