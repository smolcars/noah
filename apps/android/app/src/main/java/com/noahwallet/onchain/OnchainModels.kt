package com.noahwallet.onchain

import java.util.UUID
import kotlinx.serialization.Serializable

@Serializable
enum class OnchainAction(val title: String) {
    board("Board to Ark"), refresh("Refresh VTXOs"), startExit("Start Emergency Exit"),
    progress("Progress Exits"), claim("Claim Exits"), cancel("Cancel Exit"),
}

@Serializable
data class OnchainQuote(
    val action: OnchainAction, val walletID: String, val ids: List<String> = emptyList(),
    val destination: String? = null, val amount: Long = 0, val fee: Long = 0,
    val chainFee: Long = 0, val confirmed: Long = 0, val feeRate: Long? = null,
    val createdAt: Long = System.currentTimeMillis(), val automatic: Boolean = false,
    val boardAll: Boolean = false, val id: String = UUID.randomUUID().toString(),
) {
    val net get() = ExitRules.received(amount, fee)
    val totalFee get() = fee + chainFee
    val fundable get() = confirmed >= chainFee
    fun matches(other: OnchainQuote) = copy(id = "", createdAt = 0, ids = ids.sorted()) ==
        other.copy(id = "", createdAt = 0, ids = other.ids.sorted())
}

enum class OnchainFailure(val text: String) {
    unavailable("Wait for the wallet to be ready, then refresh."),
    changed("The wallet or fees changed, or the quote expired. Review again before confirming."),
    invalid("Check the amount, selected VTXOs, and destination. There may not be enough confirmed funds."),
    uncertain("The operation may have been submitted. Check its status before repeating it. Other recovery operations remain available."),
    busy("This operation overlaps an unresolved attempt. Check its status before repeating it."),
    cancellationRefused("This exit can no longer be canceled. Its final transaction may already be broadcast. Refresh its status."),
    claimFeeChanged("The built claim fee differs from the estimate. Nothing was broadcast. Review the claim again."),
    constructionFailed("The transaction could not be constructed. Nothing was broadcast. Review again."),
}
class OnchainException(val reason: OnchainFailure) : Exception(reason.text)
internal fun fail(reason: OnchainFailure): Nothing = throw OnchainException(reason)

@Serializable
data class OnchainReceipt(val message: String, val txid: String? = null, val onchainFee: Long? = null) {
    companion object {
        fun boardingMessage(fee: Long?) = "Boarding submitted. Funds become spendable after the board completes." +
            (fee?.let { " Actual onchain fee: $it sats." } ?: " Actual onchain fee is not yet available.")
    }
}

data class VtxoModel(
    val id: String, val amount: Long, val expiry: Long, val state: String, val kind: String,
    val exitDepth: Long, val exitWeight: Long, val registered: Boolean, val serverPubkey: String,
    val exitDelta: Long, val anchorPoint: String, val point: String, val expiring: Boolean, val tip: Long,
) {
    val expired get() = state != "Locked" && expiry <= tip
    val status get() = when { state == "Locked" -> "Locked"; expired -> "Expired"; expiring -> "Expiring"; state == "Spendable" -> "Active"; else -> state }
    val eligible get() = ExitRules.eligible(state, amount)
    val refreshable get() = state == "Spendable"
}
data class ExitModel(val id: String, val amount: Long, val state: String, val details: ExitDetails,
    val history: List<String>, val historyDetails: List<ExitDetails>) {
    val claimable get() = state == "Claimable"
    val cancelable get() = ExitRules.cancelable(state, details.transactions?.lastOrNull()?.status?.kind)
    val timeline get() = ExitTimeline.build(history, historyDetails, state, details)
}
data class OnchainOverview(val walletID: String, val vtxos: List<VtxoModel>, val exits: List<ExitModel>,
    val confirmed: Long, val tip: Long, val pendingTotal: Long, val claimableHeight: Long?)
