package com.noahwallet.onchain

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class OnchainModelTest {
    @Test fun fallbackNeedsSeparateReviewAndIsInvalidatedBySelectionOrDestinationChanges() = runBlocking {
        for (action in listOf(OnchainAction.startExit, OnchainAction.claim)) {
            var estimates = 0; var submits = 0
            val deps = Fake().dependencies()
            val model = OnchainModel(OnchainModel.Dependencies(deps.ready, deps.overview,
                { a, ids, _, destination -> estimates++; throw ExitEstimateUnavailable(OnchainQuote(a, "wallet", ids, destination, 30_000, fee = null, chainFee = null)) },
                { assertTrue(it.hasUnknownFeeConsent); submits++; "submitted" }, deps.attempts, deps.acknowledge, deps.check, deps.override, deps.detail, deps.refreshReceipts))
            model.review(action, listOf("a"), destination = "address")
            assertNotNull(model.estimateFallback); assertNull(model.quote)
            model.confirm(); model.load(); assertEquals(0, submits); assertEquals(1, estimates)
            model.editClaimDestination("new destination"); model.continueWithoutEstimate(); assertNull(model.quote)
            model.review(action, listOf("b"), destination = "new destination")
            model.continueWithoutEstimate()
            assertEquals(listOf("b"), model.quote!!.ids)
            model.confirm(); model.confirm(); assertEquals(1, submits); assertEquals(2, estimates)
        }
    }
    /** exit.md, Testing: a second hold (or the accessibility action) while a start or claim runs submits nothing more. */
    @Test fun holdingAgainNeverStartsOrClaimsTwice() = runBlocking {
        for (action in listOf(OnchainAction.startExit, OnchainAction.claim)) {
            var submits = 0
            val running = CompletableDeferred<Unit>()
            val model = OnchainModel(OnchainModel.Dependencies({ true }, { error("offline") },
                { a, ids, _, destination -> OnchainQuote(a, "wallet", ids.sorted(), destination, 30_000, fee = 262, chainFee = 0) },
                { submits++; running.await(); "submitted" }, { emptyList() }, {}, { false }, {}, { null }, {}))
            model.review(action, listOf("a"), destination = "address")
            val first = launch { model.confirm() }
            yield()
            assertTrue(model.busy)
            model.confirm(); model.confirm()
            running.complete(Unit); first.join()
            model.confirm()
            assertEquals(action.name, 1, submits)
            assertNull("the reviewed quote is spent", model.quote)
        }
    }
    @Test fun renewalReviewsAreSubmittedThroughTheLockHeldRenewalCheck() = runBlocking {
        val submitted = mutableListOf<String>()
        val model = OnchainModel(OnchainModel.Dependencies({ true }, { error("offline") },
            { action, ids, _, _ -> OnchainQuote(action, "wallet", ids.sorted()) }, { submitted.add("execute"); "submitted" },
            { emptyList() }, {}, { false }, {}, { null }, {}, executeRenewal = { submitted.add("renewal"); fail(OnchainFailure.changed) }))
        model.review(OnchainAction.refresh, listOf("a"), renewal = true)
        model.confirm()
        assertEquals(listOf("renewal"), submitted)
        assertEquals("the lock-held check's failure is the review's", OnchainFailure.changed.text, model.error)
        // A review that isn't Renew, or one that replaced it, goes the usual way.
        model.review(OnchainAction.refresh, listOf("a"), renewal = true)
        model.review(OnchainAction.refresh, listOf("a"))
        model.confirm()
        assertEquals(listOf("renewal", "execute"), submitted)
    }
    @Test fun aRefusedExitShowsBarksReasonWithoutAFallback() = runBlocking {
        for (action in listOf(OnchainAction.startExit, OnchainAction.claim)) {
            var refuseAtSubmit = false
            val model = OnchainModel(OnchainModel.Dependencies({ true }, { error("offline") },
                { a, ids, _, destination -> if (!refuseAtSubmit) throw ExitNotPossible("a", "Swept"); OnchainQuote(a, "wallet", ids, destination, 30_000, fee = 262, chainFee = 0) },
                { throw ExitNotPossible(null, "This VTXO can't be exited.") }, { emptyList() }, {}, { false }, {}, { null }, {}))
            model.review(action, listOf("a"), destination = "address")
            assertEquals("Swept", model.error); assertNull(model.estimateFallback); assertNull(model.quote)
            refuseAtSubmit = true
            model.review(action, listOf("a"), destination = "address")
            model.confirm()
            assertEquals("not the uncertain copy", "This VTXO can't be exited.", model.error); assertNull(model.estimateFallback)
        }
    }
    @Test fun aCheckThatProvesAnUncertainBoardNeverHappenedSaysSo() = runBlocking {
        val uncertain = OnchainOperationJournal.Attempt(OnchainQuote(OnchainAction.board, "wallet"), txid = "board-tx", uncertainBoard = true)
        var saved = listOf(uncertain)
        var proven = false
        val model = OnchainModel(OnchainModel.Dependencies({ true }, { error("offline") }, { _, _, _, _ -> error("unused") }, { "unused" },
            { saved }, {}, { if (proven) saved = emptyList(); proven }, {}, { null }, {}))
        model.checkStatus(uncertain.id)
        assertEquals("Status remains uncertain. Noah checks again automatically as your wallet syncs.", model.message)
        proven = true
        model.checkStatus(uncertain.id)
        assertEquals(OnchainFailure.boardNotAttempted.text, model.message)
        assertTrue(model.attempts.isEmpty())
    }
    @Test fun failedPreparationOffersNoFallbackAndSuccessfulQuoteStaysNormal() = runBlocking {
        val fake = Fake(); val model = OnchainModel(fake.dependencies())
        fake.failPreparation = true; model.review(OnchainAction.startExit, listOf("a"))
        assertNull(model.estimateFallback); model.continueWithoutEstimate(); assertNull(model.quote)
        fake.failPreparation = false; model.review(OnchainAction.startExit, listOf("a"))
        assertNotNull(model.quote); assertNull(model.estimateFallback); assertFalse(model.quote!!.hasUnknownFeeConsent)
    }
    @Test fun confirmationFallbackKeepsOriginalReview() = runBlocking {
        for (action in listOf(OnchainAction.startExit, OnchainAction.claim)) {
            val original = OnchainQuote(action, "wallet", listOf("a"), amount = 30_000, createdAt = System.currentTimeMillis() - 29_000)
            val model = OnchainModel(OnchainModel.Dependencies({ true }, { error("offline") }, { _, _, _, _ -> original },
                { throw ExitEstimateUnavailable(original.copy(id = "fresh", createdAt = System.currentTimeMillis(), fee = null, chainFee = null, feeRate = null)) },
                { emptyList() }, {}, { false }, {}, { null }, {}))
            model.review(action, original.ids); model.confirm()
            assertEquals(original.id, model.estimateFallback!!.id)
            assertEquals(original.createdAt, model.estimateFallback!!.createdAt)
            model.continueWithoutEstimate(); assertTrue(model.quote!!.hasUnknownFeeConsent)
        }
    }
    @Test fun retryDeadlineSavedUnderAFastClockIsCappedToOneBackoff() = runBlocking {
        var disk: String? = null; var now = 1_000_000_000L
        val store = AutoBoardFunding({ disk }, { disk = it })
        val funding = AutoBoardFunding.Snapshot("wallet", setOf("deposit:0"))
        store.release(funding, now + 365L * 24 * 3600 * 1000) // Saved while the clock ran a year ahead.
        assertEquals(now + 30_000, store.retryDeadline("wallet", now))
        // The cap is durable, so later reads keep it instead of opening a fresh window.
        assertEquals(now + 30_000, AutoBoardFunding({ disk }, { disk = it }).retryDeadline("wallet", now + 10_000))
        suspend fun offer() = AutoBoardReview(AutoBoardFunding({ disk }, { disk = it })) { now }
            .prepare({ true }, { funding }, { OnchainQuote(OnchainAction.board, "wallet") }, {})
        now += 29_999; assertNull(offer())
        now += 1; assertNotNull(offer())
    }

    @Test fun unsubmittedConfirmReleasesOnlyNewFundingAndAppliesBackoff() = runBlocking {
        for (changed in listOf(false, true)) {
            var disk: String? = null; var now = 0L
            val store = AutoBoardFunding({ disk }, { disk = it }); val review = AutoBoardReview(store) { now }
            store.markHandled(AutoBoardFunding.Snapshot("wallet", setOf("old:0")))
            val funding = AutoBoardFunding.Snapshot("wallet", setOf("old:0", "new:0"))
            val quote = checkNotNull(review.prepare({ true }, { funding }, { OnchainQuote(OnchainAction.board, "wallet") }, {}))
            review.failedConfirm(quote, changed) { true }
            assertTrue(store.hasUnhandled(funding)); assertFalse(store.hasUnhandled(AutoBoardFunding.Snapshot("wallet", setOf("old:0"))))
            suspend fun offer() = AutoBoardReview(AutoBoardFunding({ disk }, { disk = it })) { now }
                .prepare({ true }, { funding }, { quote.copy(id = "fresh") }, {})
            assertEquals(if (changed) 0L else 30_000L, store.retryDeadline("wallet"))
            assertEquals(0L, store.retryDeadline("other-wallet"))
            if (!changed) { now = 29_999; assertNull(offer()); now = 30_000 }
            assertEquals("fresh", offer()!!.id)
        }
    }
    @Test fun failedRetryWriteKeepsFundingHandledAndOtherWalletIndependent() {
        var disk: String? = null
        var writable = true
        val store = AutoBoardFunding({ disk }, { check(writable); disk = it })
        val funding = AutoBoardFunding.Snapshot("wallet", setOf("deposit:0"))
        store.markHandled(funding)
        val before = disk; writable = false
        assertTrue(runCatching { store.release(funding, 30_000) }.isFailure)
        assertEquals(before, disk); assertFalse(store.hasUnhandled(funding))
        assertTrue(store.hasUnhandled(funding.copy(walletID = "other")))
    }
    @Test fun uncertainSubmittedInterruptedAndDismissedReviewsKeepFundingHandled() = runBlocking {
        for (outcome in listOf("uncertain", "submitted", "interrupted", "dismissed")) {
            var disk: String? = null
            val store = AutoBoardFunding({ disk }, { disk = it }); val review = AutoBoardReview(store)
            val funding = AutoBoardFunding.Snapshot("wallet", setOf("deposit:0"))
            val quote = checkNotNull(review.prepare({ true }, { funding }, { OnchainQuote(OnchainAction.board, "wallet") }, {}))
            if (outcome != "dismissed") runCatching { review.failedConfirm(quote, false) {
                if (outcome == "interrupted") throw CancellationException()
                false
            } }
            assertFalse(outcome, store.hasUnhandled(funding))
        }
    }
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
        var overviewReads = 0
        var overviewGate: CompletableDeferred<Unit>? = null
        val discarded = mutableListOf<OnchainQuote>()
        var coin: suspend (String) -> VtxoModel = { error("unused") }
        var settle: suspend () -> Boolean = { false }
        val overview = OnchainOverview("wallet", emptyList(), emptyList(), 100_000, 100, 0, null)
        fun dependencies() = OnchainModel.Dependencies(
            { ready }, { overviewReads++; overviewGate?.await(); overview }, { action, ids, amount, destination ->
                entered?.complete(Unit); gate?.await(); if (failPreparation) fail(OnchainFailure.notSynced); OnchainQuote(action, "wallet", ids, destination, amount)
            }, { calls++; "submitted" }, { attempts }, { id -> attempts = attempts.filter { it.id != id } },
            { checks++; if (failStatusRead) error("transport"); false }, {}, { id -> detailCalls++; ExitModel(id, 1, "Canceled", ExitDetails(), listOf("Start", "Canceled"), emptyList()) }, { receiptRefreshes++ }, settle = { settle() }, discard = { discarded.add(it) }, coin = { coin(it) })
    }
    @Test fun aFailedCoinLookupIsUnknownAndEachCompletedReadRetriesIt() = runBlocking {
        val f = Fake(); val m = OnchainModel(f.dependencies())
        f.coin = { error("The VTXO with id spent cannot be found") }
        assertTrue("a failure is never read as no record", m.coin("spent").isFailure)
        // Cancellation still propagates, so a cancelled lookup writes nothing.
        f.coin = { throw CancellationException("left") }
        assertTrue(runCatching { m.coin("spent") }.exceptionOrNull() is CancellationException)
        // The detail's lookup is keyed on the revision, which each completed overview read moves.
        val before = m.revision
        m.load(); assertEquals(before + 1, m.revision)
        f.ready = false; m.load(); assertEquals("a read that failed retries nothing", before + 1, m.revision)
    }
    @Test fun aBrieflyBusyWalletKeepsTheLastStateAndReadsOnceReady() = runBlocking {
        val f = Fake(); val m = OnchainModel(f.dependencies())
        m.load()
        // After an app switch the foreground sync holds the wallet for a moment: nothing clears, no error, one read once it's ready.
        f.settle = { f.ready = true; true }
        f.ready = false; m.load()
        assertEquals(2, f.overviewReads); assertNotNull(m.overview); assertNull(m.error)
        // Unavailable for a real reason (suspended, say): cleared and said so, as before.
        f.settle = { false }
        f.ready = false; m.load()
        assertEquals(2, f.overviewReads); assertNull(m.overview); assertEquals(OnchainFailure.unavailable.text, m.error)
    }
    @Test fun aSyncStartingDuringAReadReadsAgainInsteadOfClearing() = runBlocking {
        val f = Fake(); val m = OnchainModel(f.dependencies())
        m.load()
        val gate = CompletableDeferred<Unit>(); f.overviewGate = gate
        f.settle = { f.overviewGate = null; f.ready = true; true }
        val loading = launch { m.load() }
        while (f.overviewReads < 2) yield()
        f.ready = false; gate.complete(Unit); loading.join()
        assertEquals(3, f.overviewReads); assertNotNull(m.overview); assertNull(m.error)
    }
    @Test fun onlyABusyOrOpeningWalletSettles() {
        assertTrue(ExitRules.settling(true, true, false, true)); assertTrue(ExitRules.settling(true, false, false, false))
        assertFalse(ExitRules.settling(true, false, true, true)); assertFalse(ExitRules.settling(false, false, false, false))
        assertFalse(ExitRules.settling(true, true, false, false))
    }
    @Test fun aWalletChangeReadsTheOverviewAgainAtOnce() = runBlocking {
        val m = OnchainModel(Fake().dependencies())
        val change = androidx.compose.runtime.mutableLongStateOf(0L)
        suspend fun bump() { change.longValue++; androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications(); repeat(5) { yield() } }
        val watching = launch { m.reloadOnChanges { change.longValue } }
        repeat(5) { yield() }
        assertEquals("the page's own first read covers the current value", 0, m.revision)
        bump(); assertEquals(1, m.revision)
        bump(); assertEquals(2, m.revision)
        // An open review isn't swept away by a background change.
        m.review(OnchainAction.board, amount = 30_000); assertNotNull(m.quote)
        bump(); assertEquals(2, m.revision); assertNotNull(m.quote)
        watching.cancel()
    }
    private suspend fun until(condition: () -> Boolean) { repeat(100) { if (condition()) return; yield() }; fail("never happened") }
    @Test fun aChangeDuringThePagesFirstReadReadsAgain() = runBlocking {
        val f = Fake().apply { overviewGate = CompletableDeferred() }
        val m = OnchainModel(f.dependencies())
        val change = androidx.compose.runtime.mutableLongStateOf(0L)
        val page = launch { m.keepLoaded { change.longValue } }
        until { f.overviewReads == 1 }
        change.longValue++; androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        repeat(5) { yield() }
        f.overviewGate!!.complete(Unit)
        until { m.revision == 2 }
        assertEquals(2, f.overviewReads)
        page.cancel()
    }
    @Test fun readsAskedForDuringAReadBecomeOneMoreRead() = runBlocking {
        val f = Fake().apply { overviewGate = CompletableDeferred() }
        val m = OnchainModel(f.dependencies())
        val periodic = launch { m.load() }
        until { f.overviewReads == 1 }
        // A pull to refresh and a wallet change arrive mid-read: neither waits, and neither is lost.
        m.load(); m.load()
        f.overviewGate!!.complete(Unit); periodic.join()
        assertEquals(2, f.overviewReads); assertEquals(2, m.revision)
        m.load()
        assertEquals(3, f.overviewReads)
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
        assertEquals("In mempool. Waiting for confirmation.", m.attempts.single().receipt!!.chainStatus!!.text)
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
            assertEquals(enabled && attempted && ready && !blocking, AutoBoardRules.shouldPrompt(enabled, attempted, ready, 100_000, blocking))
        }
        assertFalse(AutoBoardRules.shouldPrompt(true, true, true, 19_999, false))
    }
    @Test fun fundingDecisionsMatchSharedFixture() {
        for (test in com.noahwallet.fixtures.SharedFixtures.load("auto-board-funding")) {
            val input = test.input.jsonObject
            val store = AutoBoardFunding({ input.getValue("handled").toString() }, {})
            val snapshot = AutoBoardFunding.Snapshot(input.getValue("walletID").jsonPrimitive.content,
                input.getValue("outpoints").jsonArray.map { it.jsonPrimitive.content }.toSet())
            assertEquals(test.name, test.expected.jsonPrimitive.boolean, store.hasUnhandled(snapshot))
        }
    }
    @Test fun threeDepositsPromptOnceAcrossEveryReviewEndingAndRelaunch() {
        for (ending in listOf("confirm", "dismiss", "expire", "background-after-presentation", "navigation-after-presentation", "submit-failure")) {
            var disk: String? = null
            fun reopened() = AutoBoardFunding({ disk }, { disk = it })
            repeat(3) { deposit ->
                val funding = AutoBoardFunding.Snapshot("wallet", (0..deposit).map { "deposit-$it:0" }.toSet())
                assertTrue(ending, reopened().hasUnhandled(funding))
                // Production claims only when the active review is presented; every later ending consumes it.
                assertTrue(reopened().markHandled(funding))
                repeat(3) { assertFalse(ending, reopened().hasUnhandled(funding)); assertFalse(reopened().markHandled(funding)) }
            }
        }
    }
    @Test fun failedPreparationLeavesFundingUnhandledAndRetriesAfterBackoff() = runBlocking {
        var disk: String? = null
        var now = 0L
        val store = AutoBoardFunding({ disk }, { disk = it })
        val review = AutoBoardReview(store) { now }
        val funding = AutoBoardFunding.Snapshot("wallet", setOf("deposit:0"))
        var preparations = 0
        var rejected = true
        suspend fun check() = review.prepare({ true }, { funding }, {
            preparations++
            if (rejected) fail(OnchainFailure.belowMinimum)
            OnchainQuote(OnchainAction.board, "wallet")
        }, {})
        assertTrue(runCatching { check() }.isFailure)
        assertTrue(store.hasUnhandled(funding)); assertNull(disk)
        now = 29_999; assertNull(check()); assertEquals(1, preparations)
        rejected = false; now = 30_000
        assertNotNull(check()); assertEquals(2, preparations)
        assertFalse(store.hasUnhandled(funding))
    }
    @Test fun backgroundDisabledAndInvalidatedPreparationNeverEvaluateOrClaimFunding() = runBlocking {
        for (stopAt in listOf("before", "funding", "quote")) for (reason in listOf("background", "disabled", "navigation")) {
            var allowed = stopAt != "before"
            var reads = 0; var preparations = 0; var discards = 0
            val funding = AutoBoardFunding.Snapshot("wallet", setOf("deposit:0"))
            val store = AutoBoardFunding({ error("Must not evaluate funding after admission ends") }, { error("Must not consume") })
            val normalStore = AutoBoardFunding({ null }, { error("Must not consume") })
            val review = AutoBoardReview(if (stopAt == "quote") normalStore else store)
            val quote = review.prepare({ allowed }, {
                reads++; if (stopAt == "funding") allowed = false; funding
            }, {
                preparations++; allowed = false; OnchainQuote(OnchainAction.board, "wallet")
            }, { discards++ })
            assertNull(reason, quote)
            assertEquals(if (stopAt == "before") 0 else 1, reads)
            assertEquals(if (stopAt == "quote") 1 else 0, preparations)
            assertEquals(if (stopAt == "quote") 1 else 0, discards)
        }
    }
    @Test fun summaryRefreshPreservesAnOpenReviewWithoutReadingOrConsumingFunding() = runBlocking {
        var disk: String? = null
        val store = AutoBoardFunding({ disk }, { disk = it })
        val review = AutoBoardReview(store)
        val funding = AutoBoardFunding.Snapshot("wallet", setOf("deposit:0"))
        var plan: OnchainQuote? = null
        val held = checkNotNull(review.prepare({ plan == null }, { funding }, { OnchainQuote(OnchainAction.board, "wallet") }, {},
            present = { assertFalse(store.hasUnhandled(funding)); plan = it }))
        assertEquals(held, plan)
        val saved = disk
        repeat(3) {
            // The production caller keeps the existing plan and refuses a new check on summary refresh.
            assertNull(review.prepare({ plan == null }, { error("Open review must survive") }, { error("Must not reprepare") }, {}, present = { plan = it }))
            assertEquals(held, plan); assertTrue(held.current()); assertEquals(saved, disk)
        }
    }
    @Test fun fundingWriteFailureAndUnreadableStateCannotAdmitAReview() {
        val funding = AutoBoardFunding.Snapshot("wallet", setOf("a:0"))
        assertTrue(runCatching { AutoBoardFunding({ null }, { error("disk") }).markHandled(funding) }.isFailure)
        for (raw in listOf("broken", "[]", "{\"wallet\":[1]}", "{\"wallet\":null}"))
            assertTrue(runCatching { AutoBoardFunding({ raw }, {}).hasUnhandled(funding) }.isFailure)
    }
    @Test fun startAndManualProgressReviewsCarryRNConsentAndEstimatedFees() {
        val start = reviewExplanation(OnchainQuote(OnchainAction.startExit, "wallet"))
        assertTrue(start.contains("in the background when the system lets it")); assertTrue(start.contains("paying fees from your onchain balance"))
        assertFalse(reviewExplanation(OnchainQuote(OnchainAction.startExit, "wallet", fee = null, chainFee = null)).contains("estimates"))
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
