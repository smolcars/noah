package com.noahwallet.onchain

internal class OnchainOperations(
    private val wallet: OnchainWalletAccess, private val journal: OnchainOperationJournal,
    private val history: ExitHistoryStore, private val ready: () -> Boolean = { true },
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun overview(threshold: UInt): OnchainOverview {
        val tip = wallet.tip()
        val expiring = wallet.expiring(threshold)
        val vtxos = wallet.vtxos().map { vtxoModel(it, tip, expiring) }
        history.remember(wallet.exits().map { it.vtxoId } + wallet.legacyExitIDs(), wallet.walletID)
        val exits = history.ids(wallet.walletID).mapNotNull { detail(it) }
        return OnchainOverview(wallet.walletID, vtxos, exits, wallet.confirmed(), tip, wallet.pendingTotal(), wallet.claimableHeight())
    }
    suspend fun detail(id: String): ExitModel? {
        val status = wallet.status(id) ?: return null
        val current = exitState(status.state)
        val past = status.history.orEmpty().map(::exitState)
        history.remember(listOf(id), wallet.walletID)
        return ExitModel(id, wallet.vtxo(id).amountSats.toLong(), current.first, current.second, past.map { it.first }, past.map { it.second })
    }
    suspend fun autoBoardQuote(): OnchainQuote? {
        val minimum = wallet.minimum() ?: fail(OnchainFailure.unavailable)
        val balance = wallet.confirmed()
        if (balance < BoardingRules.threshold(minimum)) return null
        val chainFee = BoardingRules.onchainFee(wallet.regularSatPerKwu())
        var grossMinimum = BoardingRules.threshold(minimum)
        repeat(5) {
            val fee = wallet.boardFee(grossMinimum)
            if (fee.netAmountSats.toLong() < BoardingRules.threshold(minimum)) grossMinimum += BoardingRules.threshold(minimum) - fee.netAmountSats.toLong()
        }
        if (balance < grossMinimum + chainFee + BoardingRules.reserve) return null
        val gross = balance - chainFee - BoardingRules.reserve
        val fee = wallet.boardFee(gross)
        if (BoardingRules.autoAmount(balance, minimum, chainFee, fee.feeSats.toLong()) == null) return null
        return quote(OnchainAction.board, amount = gross, automatic = true)
    }
    suspend fun quote(action: OnchainAction, ids: List<String> = emptyList(), amount: Long = 0,
        destination: String? = null, automatic: Boolean = false): OnchainQuote {
        if (!ready()) fail(OnchainFailure.unavailable)
        val balance = wallet.confirmed()
        var quote = OnchainQuote(action, wallet.walletID, ids.sorted(), destination, amount, confirmed = balance, automatic = automatic, createdAt = now())
        if (ids.distinct().size != ids.size) fail(OnchainFailure.invalid)
        when (action) {
            OnchainAction.board -> {
                val minimum = wallet.minimum() ?: fail(OnchainFailure.unavailable)
                val chainFee = BoardingRules.onchainFee(wallet.regularSatPerKwu())
                if (balance <= chainFee) fail(OnchainFailure.invalid)
                val gross = if (amount == 0L) balance - chainFee else amount
                if (gross <= 0 || gross > 2_100_000_000_000_000L || gross > balance - chainFee) fail(OnchainFailure.invalid)
                val fee = wallet.boardFee(gross)
                if (fee.netAmountSats.toLong() < minimum) fail(OnchainFailure.invalid)
                if (automatic && BoardingRules.autoAmount(balance, minimum, chainFee, fee.feeSats.toLong()) != gross) fail(OnchainFailure.changed)
                quote = quote.copy(amount = fee.grossAmountSats.toLong(), fee = fee.feeSats.toLong(), chainFee = chainFee, boardAll = amount == 0L)
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
        if (!ready() || !ExitRules.current(reviewed.createdAt, now())) fail(OnchainFailure.changed)
        val fresh = quote(reviewed.action, reviewed.ids, if (reviewed.boardAll) 0 else reviewed.amount, reviewed.destination, reviewed.automatic)
        if (!ready() || !reviewed.matches(fresh) || !ExitRules.current(reviewed.createdAt, now())) fail(OnchainFailure.changed)
        if (reviewed.action in setOf(OnchainAction.startExit, OnchainAction.claim, OnchainAction.cancel)) history.remember(reviewed.ids, reviewed.walletID)
        return journal.perform(reviewed) { recordTransaction ->
            when (reviewed.action) {
                OnchainAction.board -> {
                    val board = wallet.board(reviewed.amount, reviewed.boardAll)
                    recordTransaction(board.txid)
                    // Optional fee failure cannot turn a returned submission into an uncertain outcome.
                    val fee = try { wallet.transactions().find { it.txid == board.txid }?.onchainFeeSats?.toLong() }
                        catch (_: Exception) { null }
                    OnchainReceipt(OnchainReceipt.boardingMessage(fee), board.txid, fee)
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
        }
    }
    suspend fun check(id: String): Boolean = checkOnchainAttempt(id, journal, object : OnchainAttemptReads {
        override val walletID = wallet.walletID
        override suspend fun transactionIDs() = wallet.transactions().map { it.txid }.toSet()
        override suspend fun vtxoState(id: String) = vtxoState(wallet.vtxo(id).state)
        override suspend fun exitState(id: String) = wallet.status(id)?.let { exitState(it.state).first }
    })
    suspend fun refreshReceiptFees() {
        if (journal.loadAll().none { it.quote.walletID == wallet.walletID && it.quote.action == OnchainAction.board && it.receipt?.txid != null && it.receipt.onchainFee == null }) return
        journal.refreshBoardFees(wallet.walletID, wallet.transactions().mapNotNull { tx -> tx.onchainFeeSats?.let { tx.txid to it.toLong() } }.toMap())
    }
}
