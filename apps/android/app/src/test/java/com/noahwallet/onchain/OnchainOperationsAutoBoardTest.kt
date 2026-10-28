package com.noahwallet.onchain

import io.github.niteshbalusu11.bark.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Auto-board funding: coins claimed to this phone, confirmed funding and automatic sizing. */
internal class OnchainOperationsAutoBoardTest : OnchainOperationsFixture() {
    @Test fun aDepositAfterAClaimToThisPhoneIsOfferedWithoutTheClaim() = runBlocking { fixture { wallet, ops, _, _ ->
        val store = ledger()
        wallet.fundingTransactions = listOf(claimTx, depositTx); wallet.fundingUtxos = listOf(claimed, deposit); wallet.balance = 88_375
        val funding = checkNotNull(ops.autoBoardFunding(store::kept))
        assertTrue("the deposit is new", store.hasUnhandled(funding))
        val quote = checkNotNull(ops.autoBoardQuote(store::kept))
        // The deposit less the on-chain fee, the change output and today's 5,000 reserve: never the claim's 28,375.
        assertEquals(60_000L - 1_543 - 43 - 5_000, quote.amount)
        assertEquals("the claim's value stays on-chain beside the reserve", 28_375L + 5_000, quote.reserveLeft)
        assertFalse("prepared by amount, so the rest is change", quote.boardAll)
        // Submitting rechecks the same weighing under the wallet's locks.
        ops.execute(quote, funding, kept = store::kept)
        assertEquals(listOf("submit:${quote.preparedBoard!!.id}"), wallet.calls.filter { it.startsWith("submit:") })
    } }
    @Test fun withOnlyTheClaimOnChainAutoBoardNeverAsks() = runBlocking { fixture { wallet, ops, _, _ ->
        val store = ledger()
        wallet.fundingTransactions = listOf(claimTx); wallet.fundingUtxos = listOf(claimed); wallet.balance = 28_375
        assertNull(ops.autoBoardFunding(store::kept))
        var quoted = 0
        assertNull(AutoBoardReview(store).prepare({ true }, { ops.autoBoardFunding(store::kept) }, { quoted++; ops.autoBoardQuote(store::kept) }, {}))
        assertEquals(0, quoted)
        assertNull(ops.autoBoardQuote(store::kept)); assertTrue("nothing was even prepared", wallet.preparations.isEmpty())
        // A small deposit beside it: the balance passes 20,000, but what auto-board may move doesn't.
        val small = OnchainUtxo.Local(OutPoint("deposit", 0u), 10_000u, 100u)
        wallet.fundingTransactions = listOf(claimTx, depositTx.copy(balanceChangeSats = 10_000))
        wallet.fundingUtxos = listOf(claimed, small)
        wallet.balance = 38_375
        assertNull(ops.autoBoardFunding(store::kept))
        assertNotNull("weighing the claim too would have asked", ops.autoBoardFunding())
    } }
    @Test fun onceAClaimedCoinIsSpentItNoLongerHoldsValueBack() = runBlocking { fixture { wallet, ops, _, _ ->
        val store = ledger()
        // The claimed coin was spent (moved with Max, say): only the deposit is left, and all of it may move.
        wallet.fundingTransactions = listOf(claimTx, depositTx); wallet.fundingUtxos = listOf(deposit); wallet.balance = 60_000
        assertEquals(60_000L, AutoBoardFunding.boardable(60_000, wallet.fundingUtxos, store.kept("wallet")))
        assertEquals(60_000L - 1_543 - 43 - 5_000, checkNotNull(ops.autoBoardQuote(store::kept)).amount)
        // Unconfirmed, it isn't in the confirmed balance yet, so there's nothing to subtract for it either.
        assertEquals(60_000L, AutoBoardFunding.boardable(60_000, listOf(deposit, OnchainUtxo.Local(OutPoint("claim", 0u), 28_375u, null)), setOf("claim:0")))
        assertEquals(31_625L, AutoBoardFunding.boardable(60_000, listOf(deposit, claimed), setOf("claim:0")))
    } }
    @Test fun theClaimedCoinsArePassedAsExclusionsAndAPreparationIsMadeOnce() = runBlocking { fixture { wallet, ops, _, _ ->
        val store = ledger()
        wallet.fundingTransactions = listOf(claimTx, depositTx); wallet.fundingUtxos = listOf(claimed, deposit); wallet.balance = 88_375
        val quote = checkNotNull(ops.autoBoardQuote(store::kept))
        val full = 60_000L - 1_543 - 43 - 5_000
        assertEquals("the drain probe, then one preparation", listOf(60_000L - 1_543, full), wallet.preparations.map { it.grossSats.toLong() })
        assertEquals("bark never spends the claimed coin in either", listOf(setOf("claim:0"), setOf("claim:0")), wallet.excludedBy)
        assertEquals(full, quote.amount)
        assertEquals("only the offer stays prepared", setOf(quote.preparedBoard!!.id), wallet.bound.keys)
        // Manual Move to Ark passes nothing, so you can still move everything.
        wallet.discardBoard(quote.preparedBoard.id)
        assertEquals(88_375L - 1_543, ops.quote(OnchainAction.board).amount)
        assertEquals(emptySet<String>(), wallet.excludedBy.last())
    } }
    @Test fun aPreparationThatStillSpendsAClaimedCoinIsNeverOffered() = runBlocking { fixture { wallet, ops, _, _ ->
        val store = ledger()
        wallet.fundingTransactions = listOf(claimTx, depositTx); wallet.fundingUtxos = listOf(claimed, deposit); wallet.balance = 88_375
        // The input check stays as cheap defence: were bark to ignore the exclusion, nothing is offered or asked again.
        wallet.boardInputs = listOf(OutPoint("claim", 0u))
        assertNull(ops.autoBoardQuote(store::kept))
        assertEquals(2, wallet.preparations.size)
        assertTrue("both preparations are discarded", wallet.bound.isEmpty())
    } }
    @Test fun claimedCoinsAreKeptBesideTheHandledLedgerInOneWrite() {
        var disk: String? = null; var writes = 0
        val store = AutoBoardFunding({ disk }, { disk = it; writes++ })
        store.keep("wallet", setOf("claim:0"))
        assertEquals(1, writes)
        assertEquals(setOf("claim:0"), store.kept("wallet")); assertTrue(store.kept("other").isEmpty())
        assertFalse(store.hasUnhandled(AutoBoardFunding.Snapshot("wallet", setOf("claim:0"))))
        // Releasing a declined review keeps them.
        store.release(AutoBoardFunding.Snapshot("wallet", setOf("deposit:0")), 1)
        assertEquals(setOf("claim:0"), store.kept("wallet"))
        // A ledger saved before kept coins existed still reads.
        disk = """{"handled":{"wallet":["deposit:0"]},"retryAfter":{}}"""
        assertTrue(store.kept("wallet").isEmpty()); assertFalse(store.hasUnhandled(AutoBoardFunding.Snapshot("wallet", setOf("deposit:0"))))
    }
    @Test fun confirmedFundingExcludesPendingExitUnknownAndBoardChange() = runBlocking { fixture { wallet, ops, _, _ ->
        wallet.fundingTransactions = listOf(
            WalletTransaction("deposit", "", null, 150_529, null, false),
            WalletTransaction("board-change", "", 155u, -140_000, null, false))
        wallet.fundingUtxos = listOf(OnchainUtxo.Local(OutPoint("deposit", 1u), 150_529u, 100u),
            OnchainUtxo.Local(OutPoint("pending", 0u), 150_529u, null),
            OnchainUtxo.Local(OutPoint("board-change", 1u), 5_000u, 100u),
            OnchainUtxo.Local(OutPoint("unknown", 0u), 100_000u, 100u), OnchainUtxo.Exit("exit", 100_000u, 100u))
        assertEquals(setOf("deposit:1"), ops.autoBoardFunding()!!.outpoints)
        wallet.pendingBoard = true
        assertNull(ops.autoBoardFunding())
    } }
    @Test fun autoAdmissionReconcilesConfirmedReceiptWithoutDoneButKeepsUncertainty() = runBlocking { fixture { wallet, ops, journal, _ ->
        val quote = ops.quote(OnchainAction.board, amount = 30_000)
        ops.execute(quote)
        assertNull(ops.autoBoardFunding())
        wallet.chainStatus = TransactionStatus.Confirmed(100u)
        assertNotNull(ops.autoBoardFunding())
        assertNotNull(journal.loadAll().single().receipt)
        val next = ops.quote(OnchainAction.board, amount = 30_000)
        wallet.submitOutcome = BroadcastOutcome.Uncertain("checkpoint write failed")
        assertTrue(runCatching { ops.execute(next) }.isFailure)
        wallet.chainStatus = TransactionStatus.NotFound
        wallet.fundingTransactions = emptyList()
        assertNull(ops.autoBoardFunding())
    } }
    @Test fun overlappingManualAndAutomaticPreparationsOnlySubmitOnce() = runBlocking { fixture { wallet, ops, journal, _ ->
        val automatic = checkNotNull(ops.autoBoardQuote())
        val manual = ops.quote(OnchainAction.board, amount = 30_000)
        ops.execute(manual)
        assertTrue(runCatching { ops.execute(automatic) }.isFailure)
        assertEquals(1, wallet.calls.count { it.startsWith("submit:") })
        assertEquals(1, journal.loadAll().size)
    } }
    @Test fun maxUsesExactConfirmedDrainAndJournalsTxidBeforeSubmit() = runBlocking { fixture { wallet, ops, journal, _ ->
        val quote = ops.quote(OnchainAction.board)
        assertTrue(quote.boardAll)
        assertEquals(98_457L, quote.amount)
        assertEquals(98_127L, quote.net)
        assertEquals(1543L, quote.chainFee)
        assertEquals(100_000L, quote.totalLeaving)
        assertEquals(199L, quote.preparedBoard!!.vsize)
        assertEquals(1, quote.preparedBoard.inputs.size)
        wallet.beforeSubmit = {
            val durable = kotlinx.serialization.json.Json.decodeFromString<List<OnchainOperationJournal.Attempt>>(wallet.attemptFile.readText()).single()
            assertEquals("board-tx", durable.txid)
            assertNull(durable.receipt)
        }
        wallet.feesUnavailable = true
        ops.execute(quote)
        assertEquals(listOf("submit:prepared-0"), wallet.calls)
        assertEquals("board-tx", journal.loadAll().single().txid)
        assertEquals(1543L, journal.loadAll().single().receipt!!.onchainFee)
        assertEquals(listOf("prepared-0"), wallet.discarded)
        assertEquals(1, wallet.preparations.size)
    } }
    @Test fun automaticQuoteRepreparesOnceAndShowsExactReserve() = runBlocking { fixture { wallet, ops, _, _ ->
        val quote = checkNotNull(ops.autoBoardQuote())
        assertEquals(93_414L, quote.amount)
        assertEquals(5000L, quote.reserveLeft)
        assertTrue(quote.automatic)
        assertFalse(quote.boardAll)
        assertEquals(2, wallet.preparations.size)
        assertEquals(listOf("prepared-0"), wallet.discarded)
        assertTrue(wallet.calls.isEmpty())
        wallet.discardBoard(quote.preparedBoard!!.id)
        wallet.balance = 25_537
        assertNull(ops.autoBoardQuote())
        assertTrue(wallet.bound.isEmpty())
        assertTrue(wallet.calls.isEmpty())
    } }
    @Test fun automaticBoardAcceptsAddedFundsButRejectsAReducedBalance() = runBlocking {
        fixture { wallet, ops, _, _ ->
            val quote = checkNotNull(ops.autoBoardQuote())
            wallet.balance -= 1
            assertTrue(runCatching { ops.execute(quote) }.isFailure)
            assertTrue(wallet.calls.isEmpty())
        }
        fixture { wallet, ops, _, _ ->
            val quote = checkNotNull(ops.autoBoardQuote())
            wallet.balance += 10_000
            ops.execute(quote)
            assertEquals(listOf("submit:${quote.preparedBoard!!.id}"), wallet.calls)
        }
    }
    @Test fun automaticSizingStartsFundableWithManyInputsAndHighFees() = runBlocking { fixture { wallet, ops, _, _ ->
        wallet.chainRate = 2500; wallet.exactFee = 8570; wallet.inputCount = 15
        assertTrue(runCatching { ops.quote(OnchainAction.board, amount = 92_920) }.isFailure)
        assertTrue(wallet.preparations.isEmpty())
        val quote = checkNotNull(ops.autoBoardQuote())
        assertEquals(86_000L, quote.amount); assertEquals(9000L, quote.chainFee)
        assertEquals(5000L, quote.reserveLeft); assertEquals(15, quote.preparedBoard!!.inputs.size)
        assertEquals(2, wallet.preparations.size); assertEquals(listOf("prepared-0"), wallet.discarded)
        assertEquals(setOf(quote.preparedBoard.id), wallet.bound.keys)
        wallet.discardBoard(quote.preparedBoard.id)
        assertTrue(wallet.bound.isEmpty()); assertTrue(wallet.calls.isEmpty())
    } }
    @Test fun automaticSizingInsufficientBalanceNeverLeavesAPreparation() = runBlocking { fixture { wallet, ops, _, _ ->
        wallet.balance = 25_000
        assertNull(ops.autoBoardQuote()); assertTrue(wallet.preparations.isEmpty())
        wallet.balance = 30_000; wallet.exactFee = 30_000
        assertTrue(runCatching { ops.autoBoardQuote() }.isFailure)
        assertTrue(wallet.preparations.isEmpty()); assertTrue(wallet.bound.isEmpty())
        wallet.balance = 26_000; wallet.exactFee = 1543
        assertNull(ops.autoBoardQuote())
        assertEquals(listOf("prepared-0"), wallet.discarded); assertTrue(wallet.bound.isEmpty())
    } }
    @Test fun automaticSizingChecksExactNetAndReserveEdges() = runBlocking {
        for (excess in listOf(0L, 1L)) fixture { wallet, ops, _, _ ->
            wallet.extraFixedFee = excess
            val quote = ops.autoBoardQuote()
            if (excess == 0L) {
                assertEquals(5000L, quote!!.reserveLeft)
                wallet.discardBoard(quote.preparedBoard!!.id)
            } else assertNull(quote)
            assertEquals(listOf("prepared-0", "prepared-1"), wallet.discarded)
            assertTrue(wallet.bound.isEmpty()); assertTrue(wallet.calls.isEmpty())
        }
        for (balance in listOf(26_915L, 26_916L)) fixture { wallet, ops, _, _ ->
            wallet.balance = balance
            val quote = ops.autoBoardQuote()
            if (balance == 26_916L) {
                assertEquals(20_000L, quote!!.net); assertEquals(5000L, quote.reserveLeft)
                wallet.discardBoard(quote.preparedBoard!!.id)
            } else assertNull(quote)
            assertTrue(wallet.bound.isEmpty())
        }
    }
    @Test fun automaticSizingDiscardsProbeBeforeReducedPreparationFailure() = runBlocking { fixture { wallet, ops, _, _ ->
        wallet.extraFixedFee = 6000
        assertTrue(runCatching { ops.autoBoardQuote() }.isFailure)
        assertEquals(1, wallet.preparations.size)
        assertEquals(listOf("prepared-0"), wallet.discarded)
        assertTrue(wallet.bound.isEmpty()); assertTrue(wallet.calls.isEmpty())
    } }
    @Test fun automaticSizingDiscardsOnErrorCancellationAndLostReadiness() = runBlocking {
        for (stage in listOf(1, 2)) for (outcome in listOf("error", "cancel", "not-ready")) fixture { wallet, _, journal, _ ->
            var ready = true
            val ops = OnchainOperations(wallet, journal, ready = { ready })
            wallet.afterBind = {
                if (wallet.preparations.size == stage) when (outcome) {
                    "error" -> error("bind failed")
                    "cancel" -> throw CancellationException("interrupted")
                    else -> ready = false
                }
            }
            val result = runCatching { ops.autoBoardQuote() }
            if (outcome == "not-ready") assertNull(result.getOrThrow()) else assertTrue(result.isFailure)
            assertEquals(wallet.preparations.map { it.id }, wallet.discarded)
            assertTrue(wallet.bound.isEmpty()); assertTrue(wallet.calls.isEmpty()); assertTrue(journal.loadAll().isEmpty())
        }
        for (stage in listOf(1, 2)) fixture { wallet, ops, _, _ ->
            val owner = Job()
            wallet.afterPrepare = { if (wallet.preparations.size == stage) owner.cancel() }
            assertTrue(runCatching { withContext(owner) { ops.autoBoardQuote() } }.exceptionOrNull() is CancellationException)
            assertEquals(wallet.preparations.map { it.id }, wallet.discarded)
            assertTrue(wallet.bound.isEmpty())
        }
    }
    @Test fun staleChangedFeesBalanceIdentityAndInputsPreventAllMutations() = runBlocking { fixture { wallet, ops, journal, _ ->
        val quote = ops.quote(OnchainAction.board, amount = 30_000)
        val invalid = listOf(quote.copy(reviewedAt = checkNotNull(quote.reviewedAt) - 120_000), quote.copy(walletID = "other"),
            quote.copy(fee = checkNotNull(quote.fee) + 1), quote.copy(chainFee = checkNotNull(quote.chainFee) + 1), quote.copy(confirmed = quote.confirmed + 1))
        for (q in invalid) assertTrue(runCatching { ops.execute(q) }.isFailure)
        val exit = ops.quote(OnchainAction.startExit, listOf("a"))
        wallet.items = emptyList()
        assertTrue(runCatching { ops.execute(exit) }.isFailure)
        assertTrue(wallet.calls.isEmpty()); assertTrue(journal.loadAll().isEmpty())
    } }
    @Test fun estimateNeverStartsOrProgressesAndStartReceiptExplainsDaemon() = runBlocking { fixture { wallet, ops, journal, history ->
        val quote = ops.quote(OnchainAction.startExit, listOf("a"))
        assertEquals(7630L, quote.chainFee); assertEquals(1295L, quote.fee)
        assertTrue(wallet.calls.isEmpty()); assertTrue(wallet.states.isEmpty())
        val message = ops.execute(quote)
        assertTrue(message.contains("in the background when the system lets it")); assertEquals(listOf("start"), wallet.calls)
        assertFalse(history.exists()); assertNotNull(journal.loadAll().single().receipt)
    } }
    @Test fun duplicateIDsDustAndPartialProgressAreRefused() = runBlocking { fixture { wallet, ops, _, _ ->
        assertTrue(runCatching { ops.quote(OnchainAction.refresh, listOf("a", "a")) }.isFailure)
        wallet.items = listOf(Wallet.vtxo("a", 329u))
        assertTrue(runCatching { ops.quote(OnchainAction.startExit, listOf("a")) }.isFailure)
        wallet.active = listOf("a", "b").map { ExitVtxo(it, 30_000u, ExitState.Start(100u), false) }
        assertTrue(runCatching { ops.quote(OnchainAction.progress, listOf("a")) }.isFailure)
    } }
}
