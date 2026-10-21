package com.noahwallet.send

import com.noahwallet.history.HistoryTransaction
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Read-only presentation. History supplies proof without changing the durable Send receipt. */
internal class SendSuccessDetails(receipt: SendReceipt, payment: SendPayment, history: List<HistoryTransaction>, completedAt: Date) {
    class Proof(val label: String, val value: String)
    val paidVia = when (payment.method) { "ark" -> "Ark"; "onchain" -> "Onchain"; else -> "Lightning" }
    val destination = payment.destination
    private val movement = history.firstOrNull { row ->
        row.direction == "outgoing" && (receipt.movementID?.let { row.movementId == it }
            ?: receipt.txid?.let { it.isNotEmpty() && row.txid == it } ?: false)
    }
    val proof: Proof? = receipt.txid?.takeIf { it.isNotEmpty() }?.let { Proof("Transaction ID", it) }
        ?: movement?.preimage?.takeIf { it.isNotEmpty() && movement.movementStatus == "successful" && payment.method in listOf("lightning", "lnurl", "offer") }
            ?.let { Proof("Payment preimage", it) }
    val completedAt: Date = movement?.timestamp?.takeIf { it.time > 0 } ?: completedAt

    fun formattedCompletedAt(locale: Locale): String = SimpleDateFormat("dd MMM yyyy", locale).format(completedAt) +
        " · " + DateFormat.getTimeInstance(DateFormat.SHORT, locale).format(completedAt)

    companion object {
        fun truncate(value: String): String = if (value.codePointCount(0, value.length) <= 32) value else
            value.substring(0, value.offsetByCodePoints(0, 14)) + "…" + value.substring(value.offsetByCodePoints(value.length, -10))
    }
}
