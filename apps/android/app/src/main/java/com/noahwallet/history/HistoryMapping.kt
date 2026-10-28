package com.noahwallet.history

import io.github.niteshbalusu11.bark.ExitState
import io.github.niteshbalusu11.bark.ExitTransactionStatus
import io.github.niteshbalusu11.bark.ExitTxStatus
import io.github.niteshbalusu11.bark.Movement
import io.github.niteshbalusu11.bark.MovementDestination
import io.github.niteshbalusu11.bark.WalletTransaction
import java.util.Date
import java.util.Locale
import kotlin.math.abs

object HistoryMapping {
    private val kinds = mapOf("bark.board:board" to "onboard", "bark.offboard:offboard" to "offboard",
        "bark.round:offboard" to "offboard", "bark.offboard:send_onchain" to "send-onchain",
        "bark.round:send_onchain" to "send-onchain", "bark.arkoor:receive" to "arkoor-receive",
        "bark.exit:start" to "exit", "bark.lightning_receive:receive" to "lightning-receive", "bark.round:refresh" to "refresh")
    private fun subsystemID(m: Movement): String? = "${m.subsystemName.lowercase(Locale.ROOT)}:${m.subsystemKind.lowercase(Locale.ROOT)}"
        .takeIf { it in kinds || it in listOf("bark.arkoor:send", "bark.lightning_send:send") }
    fun isFailedOrCanceled(status: String) = status in listOf("failed", "canceled")
    /** Every movement that changed or meant to change the balance, failed ones included; free renewals stay out. */
    fun include(m: Movement) = kinds[subsystemID(m)] in listOf("onboard", "offboard") ||
        m.intendedBalanceSats != 0L || m.effectiveBalanceSats != 0L
    private fun sum(destinations: List<MovementDestination>) = destinations.sumOf { it.amountSats.toLong() }
    fun boardAmount(m: Movement) = listOf(abs(m.intendedBalanceSats), abs(m.effectiveBalanceSats),
        sum(m.sentToAddresses), sum(m.receivedOnAddresses)).firstOrNull { it > 0 } ?: 0L
    fun transactionID(m: Movement, metadata: HistoryMetadata, outgoing: Boolean): String {
        val txid = metadata.offboardTxid ?: metadata.chainAnchor?.replace(Regex(":\\d+$"), "")
        if (!txid.isNullOrEmpty()) return txid
        val candidates = if (outgoing) m.inputVtxoIds + m.outputVtxoIds + m.exitedVtxoIds
        else m.outputVtxoIds + m.inputVtxoIds + m.exitedVtxoIds
        return candidates.firstOrNull(String::isNotEmpty) ?: "movement-${m.id}"
    }
    fun transaction(m: Movement, now: Date = Date()): HistoryTransaction {
        val subsystem = subsystemID(m); val kind = kinds[subsystem]
        val outgoing = outgoing(m, subsystem, kind)
        val destinations = if (outgoing) m.sentToAddresses else m.receivedOnAddresses
        val routed = sum(destinations)
        val amount = if (kind in listOf("onboard", "offboard")) boardAmount(m) else if (routed > 0) routed else abs(m.effectiveBalanceSats)
        val metadata = HistoryMetadata.parse(m.metadataJson)
        val first = destinations.firstOrNull()?.paymentMethod
        val repeat = if (outgoing && m.sentToAddresses.size == 1)
            RepeatPayment.resolve(metadata.repeatPayment, first?.value, first?.methodType, amount) else null
        val type = type(metadata.repeatPayment != null || first?.methodType == "lightning-address", destinations, subsystem, kind, outgoing)
        val date = HistoryRules.parseDate(m.createdAt) ?: now
        return HistoryTransaction("movement-${m.id}", type, amount, HistoryRules.isoDate(date), if (outgoing) "outgoing" else "incoming",
            sortTimestamp = date.time.toDouble(), source = "ark", txid = transactionID(m, metadata, outgoing),
            destination = repeat?.destination ?: first?.value ?: "", movementId = m.id, movementStatus = m.status, movementKind = kind,
            subsystemName = subsystem?.let { m.subsystemName.lowercase(Locale.ROOT) },
            subsystemKind = subsystem?.let { m.subsystemKind.lowercase(Locale.ROOT) },
            metadataJson = m.metadataJson, intendedBalanceSat = m.intendedBalanceSats, effectiveBalanceSat = m.effectiveBalanceSats,
            offchainFeeSat = m.offchainFeeSats.toLong(), onchainFeeSat = metadata.onchainFeeSat, chainAnchor = metadata.chainAnchor,
            sentTo = m.sentToAddresses.map(::destination), receivedOn = m.receivedOnAddresses.map(::destination),
            inputVtxos = m.inputVtxoIds, outputVtxos = m.outputVtxoIds, exitedVtxos = m.exitedVtxoIds, repeatPayment = repeat,
            preimage = metadata.paymentPreimage?.takeIf { m.status == "successful" && it.isNotEmpty() })
    }
    private fun outgoing(m: Movement, subsystem: String?, kind: String?) = when (kind) {
        "offboard", "send-onchain", "exit" -> true
        "onboard", "arkoor-receive", "lightning-receive" -> false
        else -> subsystem in listOf("bark.arkoor:send", "bark.lightning_send:send") || m.effectiveBalanceSats < 0
    }

    /** The row's payment type, as RN named it; [lightningAddress] when Noah paid a Lightning address. */
    private fun type(lightningAddress: Boolean, destinations: List<MovementDestination>, subsystem: String?, kind: String?, outgoing: Boolean) = when {
        lightningAddress -> "Lnurl"
        kind == "lightning-receive" -> "Bolt11"
        destinations.any { it.paymentMethod.methodType == "bitcoin" } -> "Onchain"
        kind == "arkoor-receive" || kind == "refresh" || subsystem == "bark.arkoor:send" -> "Arkoor"
        subsystem == "bark.lightning_send:send" -> "Bolt11"
        kind in listOf("onboard", "offboard", "send-onchain", "exit") -> "Onchain"
        outgoing && destinations.isNotEmpty() -> "Arkoor"
        else -> "Onchain"
    }

    /** A recurring payment the journal recorded as paid, by the movement or payment hash its receipt names. */
    data class RecurringLink(val recurring: HistoryTransaction.Recurring, val movementId: UInt?, val paymentHash: String?)

    /** Joins outgoing rows to the recurring payments they paid; rows that match none stay ordinary sends. */
    fun joinRecurring(rows: List<HistoryTransaction>, links: List<RecurringLink>): List<HistoryTransaction> =
        if (links.isEmpty()) rows else rows.map { row ->
            val link = links.firstOrNull { link -> row.direction == "outgoing" &&
                ((link.movementId != null && link.movementId == row.movementId) || (link.paymentHash != null && link.paymentHash == row.paymentHash)) }
            if (link == null) row else row.copy(recurring = link.recurring)
        }
    /**
     * Each exit fee child, by txid, to the VTXO its exit is for: every child txid in bark's own exit records, current and
     * past, never amounts. The wallet lists only transactions that moved its coins, so a match is a fee it paid.
     */
    fun exitFees(exits: List<ExitTransactionStatus>): Map<String, String> = exits.flatMap { exit ->
        val past = (exit.history.orEmpty() + exit.state).filterIsInstance<ExitState.Processing>().flatMap { it.transactions }.mapNotNull { tx ->
            when (val status = tx.status) { is ExitTxStatus.AwaitingConfirmation -> status.childTxid; is ExitTxStatus.Confirmed -> status.childTxid
                else -> null }
        }
        (exit.transactions.mapNotNull { it.childTxid } + past).map { it to exit.vtxoId }
    }.toMap()
    private fun destination(d: MovementDestination) = HistoryTransaction.Destination(d.paymentMethod.value, d.amountSats.toLong(), d.paymentMethod.methodType)
    fun transaction(chain: WalletTransaction, blockTime: Date? = null, exitFeeFor: String? = null) = HistoryTransaction(
        "onchain-wallet-${chain.txid}", "Onchain", abs(chain.balanceChangeSats), HistoryRules.isoDate(blockTime ?: Date(0)),
        if (chain.balanceChangeSats < 0) "outgoing" else "incoming",
        dateLabel = if (blockTime != null) null else chain.confirmation?.let { "Confirmed at block ${it.height}" } ?: "Unconfirmed",
        sortTimestamp = blockTime?.time?.toDouble(), sortHeight = chain.confirmation?.height, source = "onchain-wallet",
        txid = chain.txid, txHex = chain.txHex, onchainFeeSat = chain.onchainFeeSats?.toLong(), balanceChangeSat = chain.balanceChangeSats,
        hasOnchainFee = chain.onchainFeeSats != null, hasConfirmation = chain.confirmation != null,
        confirmationHeight = chain.confirmation?.height, confirmationHash = chain.confirmation?.hash, exitFeeFor = exitFeeFor)
}
