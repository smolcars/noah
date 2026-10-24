package com.noahwallet.onchain

import com.noahwallet.services.wallet.WalletSession
import io.github.niteshbalusu11.bark.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal class BoardNotSubmitted(val reason: OnchainFailure) : Exception()
/** The SDK's BroadcastOutcome.Uncertain: bark may have recorded the board, so the journal keeps it locked. */
internal class BoardUncertain : Exception()
internal fun boardFailure(error: BarkError): OnchainFailure = when (error.code) {
    BarkErrorCode.BELOW_MINIMUM_BOARD_AMOUNT -> OnchainFailure.belowMinimum
    BarkErrorCode.TOO_MANY_INPUTS -> OnchainFailure.tooManyInputs
    BarkErrorCode.PREPARED_INPUTS_UNAVAILABLE -> OnchainFailure.reserved
    BarkErrorCode.PREPARED_BOARD_CHANGED, BarkErrorCode.PREPARED_TRANSACTION_NOT_FOUND -> OnchainFailure.changed
    BarkErrorCode.UNSUPPORTED_OPERATION -> OnchainFailure.notSynced
    BarkErrorCode.INSUFFICIENT_FUNDS -> OnchainFailure.insufficientFunds
    BarkErrorCode.DUST, BarkErrorCode.INVALID_AMOUNT, BarkErrorCode.INVALID_FEE_RATE -> OnchainFailure.constructionFailed
    else -> OnchainFailure.constructionFailed
}
internal suspend fun WalletSession.discardBoardReview(quote: OnchainQuote) = withContext(NonCancellable) {
    quote.preparedBoard?.let { prepared ->
        // Close also drains session-owned preparations if the wallet has already become unavailable.
        try { withOpen { it.m6.discardBoard(prepared.id) } } catch (_: Exception) { }
    }
}
