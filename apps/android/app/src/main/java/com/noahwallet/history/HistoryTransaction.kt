package com.noahwallet.history

import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@Serializable
data class HistoryTransaction(
    val id: String, val type: String, val amount: Long, val date: String, val direction: String,
    val dateLabel: String? = null, val sortTimestamp: Double? = null, val sortHeight: UInt? = null,
    val source: String? = null, val txid: String? = null, val txHex: String? = null,
    val destination: String? = null, val btcPrice: Double? = null, val movementId: UInt? = null,
    val movementStatus: String? = null, val movementKind: String? = null,
    val subsystemName: String? = null, val subsystemKind: String? = null, val metadataJson: String? = null,
    val intendedBalanceSat: Long? = null, val effectiveBalanceSat: Long? = null,
    val offchainFeeSat: Long? = null, val onchainFeeSat: Long? = null, val chainAnchor: String? = null,
    val sentTo: List<Destination>? = null, val receivedOn: List<Destination>? = null,
    val inputVtxos: List<String>? = null, val outputVtxos: List<String>? = null, val exitedVtxos: List<String>? = null,
    val balanceChangeSat: Long? = null, val hasOnchainFee: Boolean? = null, val hasConfirmation: Boolean? = null,
    val confirmationHeight: UInt? = null, val confirmationHash: String? = null, val repeatPayment: RepeatPayment? = null,
) {
    @Serializable data class Destination(val destination: String, val amount_sat: Long, val paymentMethod: String? = null)
    @Serializable data class Accounting(val direction: String, val amount: Long)
    val isCanceled get() = movementStatus == "canceled"
    val isInternalTransfer get() = movementKind == "onboard"
    val canRepeat get() = direction == "outgoing" && movementStatus == "successful" && repeatPayment != null
    val displayLabel: String get() {
        val label = when (movementKind) {
            "onboard" -> "Board"; "offboard" -> "Offboard"; "exit" -> "Ark Exit"
            else -> when (type) { "Bolt11", "Lnurl" -> "Lightning"; "Arkoor" -> "Ark"; else -> type }
        }
        return if (isCanceled) "Canceled $label" else label
    }
    val accounting get() = when {
        isCanceled -> Accounting("None", 0)
        isInternalTransfer -> Accounting("Transfer", amount)
        direction == "outgoing" -> Accounting("Outgoing", -amount)
        else -> Accounting("Incoming", amount)
    }
    val movementKindLabel get() = when (movementKind) {
        "arkoor-receive" -> "Ark Receive"; "onboard" -> "Board"; "offboard" -> "Offboard"
        "send-onchain" -> "Onchain Send"; "exit" -> "Ark Exit"; "lightning-receive" -> "Lightning Receive"
        else -> movementKind
    }
    val confirmationLabel get() = if (dateLabel == null) null else if (hasConfirmation == true)
        "Confirmed at block ${confirmationHeight ?: 0u}" else "Unconfirmed"
    val timestamp get() = HistoryRules.parseDate(date)
    val statusLabel get() = movementStatus?.replaceFirstChar { it.titlecase(Locale.ROOT) }
    val receiptStatus get() = if (movementStatus == "successful" || hasConfirmation == true) "Completed"
        else statusLabel ?: if (source == "onchain-wallet" || balanceChangeSat != null) "Unconfirmed" else "Recorded"
    fun explorerURL(variant: String): String? {
        if (type != "Onchain" && source != "onchain-wallet" && balanceChangeSat == null) return null
        val id = txid?.substringBefore(':')?.takeIf { it.isNotEmpty() } ?: return null
        return "https://mempool.space/${if (variant == "mainnet") "" else "signet/"}tx/" +
            java.net.URLEncoder.encode(id, "UTF-8").replace("+", "%20")
    }
}

enum class HistoryFilter {
    All, Lightning, Ark, Onchain;
    fun includes(row: HistoryTransaction) = when (this) {
        All -> true; Lightning -> row.type in listOf("Bolt11", "Lnurl"); Ark -> row.type == "Arkoor"; Onchain -> row.type == "Onchain"
    }
}

object HistoryRules {
    fun parseDate(value: String): Date? {
        var normalized = value.replace(' ', 'T')
        if (!Regex("(Z|[+-]\\d{2}:?\\d{2})$").containsMatchIn(normalized)) normalized += "Z"
        for (pattern in listOf("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mm:ssXXX")) {
            val parsed = runCatching { SimpleDateFormat(pattern, Locale.ROOT).apply { isLenient = false }.parse(normalized) }.getOrNull()
            if (parsed != null) return parsed
        }
        return null
    }
    fun isoDate(date: Date): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(date)

    fun merge(movements: List<HistoryTransaction>, onchain: List<HistoryTransaction>): List<HistoryTransaction> {
        val byTxid = onchain.filter { !it.txid.isNullOrEmpty() }.associateBy { it.txid }
        val mergedIDs = mutableSetOf<String>()
        val merged = movements.map { movement ->
            val chain = byTxid[movement.txid]
            if (movement.movementKind !in listOf("onboard", "offboard") || chain == null) movement
            else {
                mergedIDs.add(chain.id)
                movement.copy(txHex = chain.txHex, btcPrice = movement.btcPrice ?: chain.btcPrice,
                    balanceChangeSat = chain.balanceChangeSat, hasOnchainFee = chain.hasOnchainFee,
                    onchainFeeSat = movement.onchainFeeSat ?: chain.onchainFeeSat, hasConfirmation = chain.hasConfirmation,
                    confirmationHeight = chain.confirmationHeight, confirmationHash = chain.confirmationHash, sortHeight = chain.sortHeight)
            }
        }
        return merged + onchain.filter { it.id !in mergedIDs }
    }
    fun precedes(a: HistoryTransaction, b: HistoryTransaction): Boolean {
        val aChain = a.source == "onchain-wallet"; val bChain = b.source == "onchain-wallet"
        val aPending = aChain && a.hasConfirmation != true; val bPending = bChain && b.hasConfirmation != true
        if (aPending != bPending) return aPending
        val aTimed = (a.sortTimestamp ?: 0.0) > 0; val bTimed = (b.sortTimestamp ?: 0.0) > 0
        if (aTimed && bTimed) return a.sortTimestamp!! > b.sortTimestamp!!
        if (aChain && bChain) return (a.sortHeight ?: 0u) > (b.sortHeight ?: 0u)
        if (aTimed != bTimed) return aTimed
        return (a.sortTimestamp ?: a.timestamp?.time?.toDouble() ?: 0.0) > (b.sortTimestamp ?: b.timestamp?.time?.toDouble() ?: 0.0)
    }
    fun sorted(rows: List<HistoryTransaction>): List<HistoryTransaction> = rows.sortedWith { a, b ->
        when { precedes(a, b) -> -1; precedes(b, a) -> 1; else -> 0 }
    }
    fun unified(rows: List<HistoryTransaction>) = sorted(merge(rows.filter { it.source != "onchain-wallet" }, rows.filter { it.source == "onchain-wallet" }))
}
