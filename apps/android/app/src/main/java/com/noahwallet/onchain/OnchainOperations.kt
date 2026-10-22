package com.noahwallet.onchain

import com.noahwallet.services.wallet.ChainStatus
import io.github.niteshbalusu11.bark.*
import kotlinx.coroutines.*

internal class OnchainOperations(
    private val wallet: OnchainWalletAccess, private val journal: OnchainOperationJournal,
    private val ready: () -> Boolean = { true },
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun overview(threshold: UInt): OnchainOverview {
        val tip = wallet.tip()
        val expiring = wallet.expiring(threshold)
        val vtxos = wallet.vtxos().map { vtxoModel(it, tip, expiring) }
        // SDK persisted rows cover live and terminal exits, including wallets imported without Noah's index.
        val exits = wallet.allExits().map(::exitModel)
        return OnchainOverview(wallet.walletID, vtxos, exits, wallet.confirmed(), tip, wallet.pendingTotal(), wallet.claimableHeight())
    }
    private suspend fun renewable(ids: List<String>, threshold: UInt): Boolean {
        val tip = wallet.tip()
        val expiring = wallet.expiring(threshold)
        val due = wallet.vtxos().map { vtxoModel(it, tip, expiring) }.filter { it.renewable }.map { it.id }.toSet()
        return ids.isNotEmpty() && ids.all { it in due }
    }
    suspend fun detail(id: String): ExitModel? {
        val status = wallet.status(id) ?: return null
        return exitModel(status)
    }
    private fun exitModel(status: ExitTransactionStatus): ExitModel {
        val current = exitState(status.state)
        val past = status.history.orEmpty().map(::exitState)
        return ExitModel(status.vtxoId, status.amountSats.toLong(), current.first, current.second, past.map { it.first }, past.map { it.second })
    }
    suspend fun autoBoardFunding(): AutoBoardFunding.Snapshot? {
        if (!ready()) fail(OnchainFailure.unavailable)
        for (attempt in journal.loadAll().filter { it.quote.walletID == wallet.walletID && it.blocks(OnchainAction.board, emptyList()) })
            check(attempt.id)
        if (journal.loadAll().any { it.blocks(OnchainAction.board, emptyList()) } || wallet.hasPendingBoards()) return null
        return AutoBoardFunding.confirmed(wallet.walletID, wallet.utxos(), wallet.transactions())
    }
    suspend fun autoBoardQuote(): OnchainQuote? {
        if (!ready()) fail(OnchainFailure.unavailable)
        val minimum = wallet.minimum() ?: fail(OnchainFailure.unavailable)
        val balance = wallet.confirmed()
        if (balance <= BoardingRules.reserve + BoardingRules.threshold(minimum)) return null
        val probe = quote(OnchainAction.board, automatic = true)
        val candidate = try {
            // Fixed-amount boarding adds a 43-vB Taproot change output to the confirmed-input drain.
            probe.amount - BoardingRules.reserve - 43 * checkNotNull(probe.feeRate)
        } finally { withContext(NonCancellable) { wallet.discardBoard(checkNotNull(probe.preparedBoard).id) } }
        if (!wallet.boardCleanupComplete(checkNotNull(probe.preparedBoard).id)) fail(OnchainFailure.uncertain)
        currentCoroutineContext().ensureActive()
        if (candidate < BoardingRules.threshold(minimum) || !ready()) return null
        val prepared = quote(OnchainAction.board, amount = candidate, automatic = true)
        var reviewed = false
        try {
            currentCoroutineContext().ensureActive()
            if (prepared.reserveLeft < BoardingRules.reserve || prepared.net < BoardingRules.threshold(minimum) || !ready()) return null
            reviewed = true
            return prepared
        } finally {
            if (!reviewed) withContext(NonCancellable) { wallet.discardBoard(checkNotNull(prepared.preparedBoard).id) }
        }
    }
    suspend fun quote(action: OnchainAction, ids: List<String> = emptyList(), amount: Long = 0,
        destination: String? = null, automatic: Boolean = false, estimate: Boolean = true): OnchainQuote {
        if (!ready()) fail(OnchainFailure.unavailable)
        val balance = wallet.confirmed()
        var quote = OnchainQuote(action, wallet.walletID, ids.sorted(), destination, amount, confirmed = balance, automatic = automatic, createdAt = now())
        if (ids.distinct().size != ids.size) fail(OnchainFailure.invalid)
        when (action) {
            OnchainAction.board -> {
                if (amount < 0 || amount > 2_100_000_000_000_000L) fail(OnchainFailure.invalid)
                val rate = ((wallet.regularSatPerKwu() + 249) / 250).coerceAtLeast(1).toULong()
                val prepared = try { wallet.prepareBoard(amount, amount == 0L, rate) }
                    catch (error: BarkError) { throw OnchainException(boardFailure(error)) }
                try {
                    currentCoroutineContext().ensureActive()
                    if (!ready() || prepared.grossSats > Long.MAX_VALUE.toULong() ||
                        prepared.netSats + prepared.arkFeeSats != prepared.grossSats) fail(OnchainFailure.changed)
                    quote = quote.copy(amount = prepared.grossSats.toLong(), fee = prepared.arkFeeSats.toLong(),
                        chainFee = prepared.onchainFeeSats.toLong(), boardAll = amount == 0L, feeRate = rate.toLong(),
                        preparedBoard = BoardPrepared(prepared.id, prepared.txid, prepared.vsize.toLong(), prepared.inputs.map { BoardInput(it.txid, it.vout) }))
                    wallet.bindBoard(quote)
                } catch (error: Exception) {
                    withContext(NonCancellable) { wallet.discardBoard(prepared.id) }
                    throw error
                }
            }
            OnchainAction.refresh, OnchainAction.startExit -> {
                val selected = wallet.vtxos().filter { vtxoState(it.state) == "Spendable" && it.id in ids }
                if (ids.isEmpty() || selected.size != ids.size) fail(OnchainFailure.changed)
                quote = quote.copy(amount = selected.sumOf { it.amountSats.toLong() })
                if (action == OnchainAction.refresh) quote = quote.copy(fee = wallet.refreshFee(ids).feeSats.toLong())
                else {
                    if (selected.any { it.amountSats.toLong() < ExitRules.dust }) fail(OnchainFailure.invalid)
                    quote = exitEstimate(quote, estimate)
                }
            }
            OnchainAction.progress, OnchainAction.claim -> {
                val eligible = wallet.exits().filter { if (action == OnchainAction.claim) it.isClaimable else exitState(it.state).first !in ExitTimeline.terminal }
                val selected = eligible.filter { it.vtxoId in ids }
                if (ids.isEmpty() || selected.size != ids.size || (action == OnchainAction.progress && eligible.map { it.vtxoId }.toSet() != ids.toSet())) fail(OnchainFailure.changed)
                if (action == OnchainAction.claim && destination.isNullOrBlank()) fail(OnchainFailure.invalid)
                if (action == OnchainAction.claim) quote = quote.copy(destinationIsMine = wallet.destinationIsMine(checkNotNull(destination)))
                quote = quote.copy(amount = selected.sumOf { it.amountSats.toLong() })
                quote = exitEstimate(quote, estimate)
            }
            OnchainAction.cancel -> {
                if (ids.size != 1) fail(OnchainFailure.invalid)
                val exit = wallet.exits().find { it.vtxoId == ids[0] } ?: fail(OnchainFailure.changed)
                val status = wallet.status(ids[0]) ?: fail(OnchainFailure.changed)
                val state = exitState(status.state)
                if (!ExitRules.cancelable(state.first, state.second.transactions?.lastOrNull()?.status?.kind)) fail(OnchainFailure.cancellationRefused)
                quote = quote.copy(amount = exit.amountSats.toLong())
            }
        }
        return quote
    }
    private suspend fun exitEstimate(validated: OnchainQuote, estimate: Boolean): OnchainQuote {
        val unknown = validated.copy(fee = null, chainFee = null, feeRate = null)
        val fallbackAllowed = validated.action in setOf(OnchainAction.startExit, OnchainAction.claim)
        if (!estimate && fallbackAllowed) return unknown
        val fee = try { wallet.exitFee(validated.ids, validated.destination) }
        catch (failure: Exception) {
            if (fallbackAllowed && ExitEstimateUnavailable.allowsFallback(failure)) throw ExitEstimateUnavailable(unknown)
            throw failure
        }
        return validated.copy(fee = fee.claimFeeSats.toLong(),
            chainFee = if (validated.action == OnchainAction.claim) 0 else fee.exitBroadcastFeeSats.toLong(),
            feeRate = if (validated.action == OnchainAction.claim) null else fee.feeRateSatPerVb.toLong())
    }
    /**
     * [renewalThreshold]: the review was Home's Renew, so every VTXO must still be due (in the expiring set at this
     * threshold, spendable, not expired), checked last, under the caller's locks, just before submitting.
     */
    suspend fun execute(reviewed: OnchainQuote, funding: AutoBoardFunding.Snapshot? = null, renewalThreshold: UInt? = null): String {
        if (!ready() || !reviewed.current(now()) || reviewed.walletID != wallet.walletID) fail(OnchainFailure.changed)
        if (reviewed.action == OnchainAction.board) {
            if (funding != null && (!reviewed.automatic || funding.walletID != wallet.walletID || autoBoardFunding() != funding)) fail(OnchainFailure.changed)
            if (reviewed.preparedBoard == null) fail(OnchainFailure.changed)
            // Added funds leave at least the reviewed reserve; only a decrease can break it (iOS parity).
            if (reviewed.automatic && (wallet.confirmed() < reviewed.confirmed || reviewed.reserveLeft < BoardingRules.reserve)) fail(OnchainFailure.changed)
            wallet.claimBoard(reviewed)
        } else {
            if (reviewed.feesUnknown && !reviewed.hasUnknownFeeConsent) fail(OnchainFailure.changed)
            val fresh = try {
                quote(reviewed.action, reviewed.ids, reviewed.amount, reviewed.destination, reviewed.automatic,
                    estimate = !reviewed.hasUnknownFeeConsent)
            } catch (failure: ExitEstimateUnavailable) { throw failure.preserving(reviewed, now()) }
            if (!ready() || !reviewed.matches(fresh) || !reviewed.current(now())) fail(OnchainFailure.changed)
            // A sync while this waited for the locks can move the tip past an expiry; the quote above can't tell.
            if (renewalThreshold != null && (reviewed.action != OnchainAction.refresh || !renewable(reviewed.ids, renewalThreshold))) fail(OnchainFailure.changed)
        }
        try { return journal.perform(reviewed) { recordTransaction ->
            when (reviewed.action) {
                OnchainAction.board -> {
                    // This save must complete before the first SDK call that can accept/broadcast the board.
                    recordTransaction(checkNotNull(reviewed.preparedBoard).txid)
                    val result = try { wallet.submitBoard(reviewed) }
                    catch (error: BarkError) {
                        if (error.code in setOf(BarkErrorCode.PREPARED_TRANSACTION_NOT_FOUND, BarkErrorCode.PREPARED_INPUTS_UNAVAILABLE, BarkErrorCode.PREPARED_BOARD_CHANGED))
                            throw BoardNotSubmitted(boardFailure(error))
                        throw error
                    }
                    if (result.txid != reviewed.preparedBoard.txid) fail(OnchainFailure.uncertain)
                    if (result.broadcast is BroadcastOutcome.NotAttempted) throw BoardNotSubmitted(OnchainFailure.boardNotAttempted)
                    val retry = result.broadcast is BroadcastOutcome.Failed
                    OnchainReceipt(if (retry) OnchainReceipt.broadcastFailedMessage
                        else OnchainReceipt.boardingMessage(reviewed.chainFee), result.txid, reviewed.chainFee, retry)
                }
                OnchainAction.refresh -> OnchainReceipt(wallet.refresh(reviewed.ids)?.let { "Refresh submitted to delegated round ${it.id}." } ?: "No refresh round was needed.")
                OnchainAction.startExit -> {
                    wallet.start(reviewed.ids)
                    OnchainReceipt("Exit started. Noah progresses it automatically in the background, paying broadcast and fee-bump fees from your onchain balance.")
                }
                OnchainAction.progress -> {
                    val failures = wallet.progress(reviewed.feeRate).count { it.error != null }
                    OnchainReceipt(if (failures == 0) "Exits progressed. Transactions may have been broadcast or fee-bumped."
                        else "Exit progression returned $failures errors. Transactions may have been broadcast or fee-bumped. Refresh exit details before continuing.")
                }
                OnchainAction.claim -> {
                    val claim = wallet.drain(reviewed.ids, checkNotNull(reviewed.destination))
                    if (!reviewed.hasUnknownFeeConsent && claim.feeSats.toLong() != reviewed.fee) fail(OnchainFailure.claimFeeChanged)
                    // Every error after entering broadcast remains uncertain, including typed BDK errors.
                    val txid = try { wallet.broadcastClaim(claim.psbtBase64) } catch (_: Exception) { fail(OnchainFailure.uncertain) }
                    recordTransaction(txid)
                    OnchainReceipt("Claim submitted. Refresh to check confirmation.", txid)
                }
                OnchainAction.cancel -> {
                    if (!wallet.cancel(reviewed.ids.single())) fail(OnchainFailure.cancellationRefused)
                    OnchainReceipt("Exit canceled. The VTXO remains spendable.")
                }
            }
        } } finally { reviewed.preparedBoard?.let { wallet.discardBoard(it.id, includingClaimed = true) } }
    }
    suspend fun abandonUnsubmittedAutoBoard(quote: OnchainQuote): Boolean {
        if (!quote.automatic || quote.action != OnchainAction.board || quote.walletID != wallet.walletID) return false
        val prepared = quote.preparedBoard ?: return false
        if (journal.loadAll().any { it.id == quote.id || it.quote.preparedBoard?.id == prepared.id ||
                it.txid == prepared.txid || it.quote.preparedBoard?.txid == prepared.txid }) return false
        return wallet.abandonUnsubmittedBoard(quote)
    }
    suspend fun check(id: String): Boolean = checkOnchainAttempt(id, journal, object : OnchainAttemptReads {
        override val walletID = wallet.walletID
        override suspend fun transactionIDs() = wallet.transactions().map { it.txid }.toSet()
        override suspend fun transactionStatus(txid: String) = ChainStatus.from(wallet.transactionStatus(txid))
        override suspend fun boardTransactionIDs() = wallet.boardTransactionIDs()
        override suspend fun vtxoState(id: String) = vtxoState(wallet.vtxo(id).state)
        override suspend fun exitState(id: String) = wallet.status(id)?.let { exitState(it.state).first }
    })
    suspend fun refreshReceiptFees() {
        if (journal.loadAll().none { it.quote.walletID == wallet.walletID && it.quote.action == OnchainAction.board && it.receipt?.txid != null && it.receipt.onchainFee == null }) return
        journal.refreshBoardFees(wallet.walletID, wallet.transactions().mapNotNull { tx -> tx.onchainFeeSats?.let { tx.txid to it.toLong() } }.toMap())
    }
}
