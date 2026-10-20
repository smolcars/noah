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
    suspend fun detail(id: String): ExitModel? {
        val status = wallet.status(id) ?: return null
        return exitModel(status)
    }
    private fun exitModel(status: ExitTransactionStatus): ExitModel {
        val current = exitState(status.state)
        val past = status.history.orEmpty().map(::exitState)
        return ExitModel(status.vtxoId, status.amountSats.toLong(), current.first, current.second, past.map { it.first }, past.map { it.second })
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
        destination: String? = null, automatic: Boolean = false): OnchainQuote {
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
                    val fee = wallet.exitFee(ids, null)
                    quote = quote.copy(fee = fee.claimFeeSats.toLong(), chainFee = fee.exitBroadcastFeeSats.toLong(), feeRate = fee.feeRateSatPerVb.toLong())
                }
            }
            OnchainAction.progress, OnchainAction.claim -> {
                val eligible = wallet.exits().filter { if (action == OnchainAction.claim) it.isClaimable else exitState(it.state).first !in ExitTimeline.terminal }
                val selected = eligible.filter { it.vtxoId in ids }
                if (ids.isEmpty() || selected.size != ids.size || (action == OnchainAction.progress && eligible.map { it.vtxoId }.toSet() != ids.toSet())) fail(OnchainFailure.changed)
                if (action == OnchainAction.claim && destination.isNullOrBlank()) fail(OnchainFailure.invalid)
                if (action == OnchainAction.claim) quote = quote.copy(destinationIsMine = wallet.destinationIsMine(checkNotNull(destination)))
                val fee = wallet.exitFee(ids, destination)
                quote = quote.copy(amount = selected.sumOf { it.amountSats.toLong() }, fee = fee.claimFeeSats.toLong(),
                    chainFee = if (action == OnchainAction.claim) 0 else fee.exitBroadcastFeeSats.toLong(),
                    feeRate = if (action == OnchainAction.claim) null else fee.feeRateSatPerVb.toLong())
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
    suspend fun execute(reviewed: OnchainQuote): String {
        if (!ready() || !reviewed.current(now()) || reviewed.walletID != wallet.walletID) fail(OnchainFailure.changed)
        if (reviewed.action == OnchainAction.board) {
            if (reviewed.preparedBoard == null) fail(OnchainFailure.changed)
            if (reviewed.automatic && (wallet.confirmed() != reviewed.confirmed || reviewed.reserveLeft < BoardingRules.reserve)) fail(OnchainFailure.changed)
            wallet.claimBoard(reviewed)
        } else {
            val fresh = quote(reviewed.action, reviewed.ids, reviewed.amount, reviewed.destination, reviewed.automatic)
            if (!ready() || !reviewed.matches(fresh) || !reviewed.current(now())) fail(OnchainFailure.changed)
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
                    if (result.broadcast is BroadcastOutcome.NotAttempted) throw BoardNotSubmitted(OnchainFailure.constructionFailed)
                    val retry = result.broadcast is BroadcastOutcome.Failed
                    OnchainReceipt(if (retry) "Boarding submitted, but network delivery is unconfirmed. Noah will keep retrying the broadcast. Do not board again."
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
                    if (claim.feeSats.toLong() != reviewed.fee) fail(OnchainFailure.claimFeeChanged)
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
