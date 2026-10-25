package com.noahwallet.onchain

import java.util.UUID
import io.github.niteshbalusu11.bark.*
import kotlinx.serialization.Serializable

@Serializable
enum class OnchainAction(val title: String) {
    board("Board to Ark"), refresh("Refresh VTXOs"), startExit("Start Emergency Exit"),
    progress("Progress Exits"), claim("Claim Exits"), cancel("Cancel Exit"),
}

@Serializable
data class OnchainQuote(
    val action: OnchainAction, val walletID: String, val ids: List<String> = emptyList(),
    val destination: String? = null, val amount: Long = 0, val fee: Long? = 0,
    val chainFee: Long? = 0, val confirmed: Long = 0, val feeRate: Long? = null,
    val createdAt: Long = System.currentTimeMillis(), val automatic: Boolean = false,
    val destinationIsMine: Boolean? = null, val unknownFeeConsent: String? = null,
    val boardAll: Boolean = false, val preparedBoard: BoardPrepared? = null, val id: String = UUID.randomUUID().toString(),
    /** An exit's transactions still to broadcast, shared ones once (bark's estimate). */
    val transactions: Long? = null,
) {
    fun current(now: Long = System.currentTimeMillis()) = now >= createdAt && now - createdAt <
        (if (preparedBoard != null) BoardPrepared.lifetimeMillis else 30_000L)
    val feesUnknown get() = fee == null && chainFee == null && action in setOf(OnchainAction.startExit, OnchainAction.claim)
    val hasUnknownFeeConsent get() = feesUnknown && unknownFeeConsent == id
    val totalLeaving get() = amount + checkNotNull(chainFee)
    val reserveLeft get() = confirmed - totalLeaving
    val externalDestinationWarning get() = if (action == OnchainAction.claim && destinationIsMine == false)
        "This address isn't in your Noah wallet. The claimed funds will be sent there." else null
    val net get() = ExitRules.received(amount, checkNotNull(fee))
    val totalFee get() = checkNotNull(fee) + checkNotNull(chainFee)
    val fundable get() = chainFee?.let { confirmed >= it } ?: false
    fun matches(other: OnchainQuote) = copy(id = "", createdAt = 0, ids = ids.sorted(), unknownFeeConsent = null) ==
        other.copy(id = "", createdAt = 0, ids = other.ids.sorted(), unknownFeeConsent = null)
}

@Serializable
data class BoardPrepared(val id: String, val txid: String, val vsize: Long, val inputs: List<BoardInput>) {
    companion object { const val lifetimeMillis = 120_000L }
}
@Serializable data class BoardInput(val txid: String, val vout: UInt)

enum class OnchainFailure(val text: String) {
    unavailable("Wait for the wallet to be ready, then refresh."),
    changed("The wallet or fees changed, or the quote expired. Review again before confirming."),
    invalid("Check the amount, selected VTXOs, and destination. There may not be enough confirmed funds."),
    uncertain("The operation may have been submitted. Check its status before repeating it. Other recovery operations remain available."),
    busy("This operation overlaps an unresolved attempt. Check its status before repeating it."),
    cancellationRefused("This exit can no longer be canceled. Its final transaction may already be broadcast. Refresh its status."),
    claimFeeChanged("The built claim fee differs from the estimate. Nothing was broadcast. Review the claim again."),
    insufficientFunds("There are not enough confirmed onchain funds for this amount and its fees. Reduce the amount or wait for confirmation."),
    boardNotAttempted("Boarding was not submitted. Review again when the wallet is ready."),
    belowMinimum("The amount Ark receives is below its minimum. Increase the boarding amount."),
    tooManyInputs("There are too many small coins for this board. Try a smaller amount."),
    reserved("These coins are unavailable or reserved by another payment in review. Close that review, then try again."),
    notSynced("Sync the wallet once before preparing a board."),
    constructionFailed("The transaction could not be constructed. Nothing was broadcast. Review again."),
    keepFailed("Couldn't record that these coins stay on-chain, so nothing was sent. Try again."),
}
class OnchainException(val reason: OnchainFailure) : Exception(reason.text)
internal fun fail(reason: OnchainFailure): Nothing = throw OnchainException(reason)

@Serializable
data class OnchainReceipt(val message: String, val txid: String? = null, val onchainFee: Long? = null,
    val broadcastRetryPending: Boolean = false, val chainStatus: com.noahwallet.services.wallet.ChainStatus? = null,
    @kotlinx.serialization.Transient val statusError: String? = null) {
    @Serializable data class BoardAmounts(val arkReceives: Long, val totalLeaving: Long)
    fun boardAmounts(quote: OnchainQuote) = BoardAmounts(quote.net, quote.amount + (onchainFee ?: checkNotNull(quote.chainFee)))
    companion object {
        const val broadcastFailedMessage = "Boarding submitted but unconfirmed. Noah will keep trying the broadcast. Do not board these funds again."
        /** Move exit forward's receipt when bark progressed every exit without an error. */
        const val progressedMessage = "Exits progressed. Transactions may have been broadcast or fee-bumped."
        fun boardingMessage(fee: Long?) = "Boarding submitted. Funds become spendable after the board completes." +
            (fee?.let { " Actual onchain fee: $it sats." } ?: " Actual onchain fee is not yet available.")
    }
}

enum class VtxoStatus(val label: String) { active("Active"), expiring("Expiring"), expired("Expired"), locked("Locked") }

data class VtxoModel(
    val id: String, val amount: Long, val expiry: Long, val state: String, val kind: String,
    val exitDepth: Long, val exitWeight: Long, val registered: Boolean, val serverPubkey: String,
    val exitDelta: Long, val anchorPoint: String, val point: String, val expiring: Boolean, val tip: Long,
) {
    val expired get() = state != "Locked" && expiry <= tip
    val statusID get() = when { state == "Locked" -> VtxoStatus.locked; expired -> VtxoStatus.expired; expiring -> VtxoStatus.expiring; state == "Spendable" -> VtxoStatus.active; else -> null }
    val status get() = statusID?.label ?: state
    val eligible get() = ExitRules.eligible(state, amount)
    val refreshable get() = state == "Spendable"
    /** Due for Home's Renew: in the wallet's expiring set, spendable (so not locked) and not expired yet. */
    val renewable get() = expiring && refreshable && !expired
}
/**
 * [packages]: bark's transactions for the exit, root first; [childFees]: what this wallet paid each fee child (by child
 * txid); [nextPackageFee]: the fee the transaction waiting for its child needs at today's fast rate, when one waits.
 */
data class ExitModel(val id: String, val amount: Long, val state: String, val details: ExitDetails,
    val history: List<String>, val historyDetails: List<ExitDetails>, val packages: List<ExitPackage> = emptyList(),
    val childFees: Map<String, Long> = emptyMap(), val nextPackageFee: Long? = null) {
    val claimable get() = state == "Claimable"
    val cancelable get() = ExitRules.cancelable(state, details.transactions?.lastOrNull()?.status?.kind)
    val timeline get() = ExitTimeline.build(history, historyDetails, state, details)
}
data class OnchainOverview(val walletID: String, val vtxos: List<VtxoModel>, val exits: List<ExitModel>,
    val confirmed: Long, val tip: Long, val pendingTotal: Long, val claimableHeight: Long?)

/** Only estimator errors carry a validated, fee-less review. */
class ExitEstimateUnavailable(val review: OnchainQuote) : Exception() {
    companion object {
        fun allowsFallback(error: Exception): Boolean = when (error) {
            // Transport and other untyped failures stay Other (RN parity); SDK 0.7.0 types a fee-rate overflow and an unreachable chain.
            is BarkError -> error.code == BarkErrorCode.OTHER || error.code == BarkErrorCode.INVALID_FEE_RATE || error.code in ExitErrors.chain
            is OnchainException -> error.reason == OnchainFailure.unavailable
            is java.net.SocketException, is java.net.SocketTimeoutException, is java.net.UnknownHostException,
            is java.io.InterruptedIOException, is kotlinx.coroutines.TimeoutCancellationException -> true
            else -> false
        }
    }
    fun preserving(original: OnchainQuote, now: Long = System.currentTimeMillis()): ExitEstimateUnavailable {
        val scope = original.copy(fee = null, chainFee = null, feeRate = null, transactions = null)
        if (!original.current(now) || !review.feesUnknown || !scope.matches(review)) fail(OnchainFailure.changed)
        return ExitEstimateUnavailable(review.copy(id = original.id, createdAt = original.createdAt, unknownFeeConsent = null))
    }
}

/** SDK 0.7.0's typed exit errors, read by code, never by message. */
internal object ExitErrors {
    /** bark can never exit the VTXO, so there's nothing to retry. */
    val never = setOf(BarkErrorCode.EXIT_NON_STANDARD_VTXO, BarkErrorCode.EXIT_DUST_LIMIT, BarkErrorCode.EXIT_VTXO_ALREADY_SPENT,
        BarkErrorCode.EXIT_VTXO_ALREADY_EXITED, BarkErrorCode.EXIT_VTXO_SWEPT, BarkErrorCode.EXIT_UNKNOWN_VTXO,
        BarkErrorCode.EXIT_CYCLIC_TRANSACTIONS, BarkErrorCode.EXIT_MISSING_ANCHOR_OUTPUT)
    /** The chain source is unavailable: try again later. */
    val chain = setOf(BarkErrorCode.EXIT_TRANSACTION_RETRIEVAL_FAILURE, BarkErrorCode.EXIT_TIP_RETRIEVAL_FAILURE,
        BarkErrorCode.EXIT_BLOCK_RETRIEVAL_FAILURE, BarkErrorCode.EXIT_ANCESTOR_RETRIEVAL_FAILURE)
    /** The reason shown: the exit spec's words where they fit. */
    fun reason(code: BarkErrorCode) = when (code) {
        BarkErrorCode.EXIT_VTXO_ALREADY_SPENT -> "Already spent"
        BarkErrorCode.EXIT_VTXO_SWEPT -> "Swept"
        else -> "This VTXO can't be exited."
    }
    /** [error] as a refusal, naming bark's VTXO or else the only one of [ids]; null when it's not a refusal. */
    fun refusal(error: Exception, ids: List<String>): ExitNotPossible? = (error as? BarkError)?.takeIf { it.code in never }
        ?.let { ExitNotPossible(it.vtxoId ?: ids.singleOrNull(), reason(it.code)) }
    /** VTXOs bark refused to exit while the app runs, with their reason: Choose VTXOs leaves them out of every selection. */
    val refused = androidx.compose.runtime.mutableStateMapOf<String, String>()
}
/** bark can never exit [vtxoId] (unnamed when null); the message is the reason shown. No estimate fallback is offered. */
class ExitNotPossible(val vtxoId: String?, reason: String) : Exception(reason)
