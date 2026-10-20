package com.noahwallet.send

import kotlinx.serialization.Serializable

@Serializable enum class SendRail(val label: String) { ark("Ark"), lightning("Lightning"), onchain("On-chain") }
@Serializable enum class SendSource(val label: String) { offchain("Ark balance"), onchain("On-chain wallet") }
@Serializable enum class SendStage { amount, recipient, method, source, review }
@Serializable data class SendProgress(
    val entry: String = "amount-first", val amountConfirmed: Boolean = false, val recipientConfirmed: Boolean = false,
    val railConfirmed: Boolean = false, val sourceConfirmed: Boolean = false, val rails: List<SendRail> = emptyList(),
    val selectedRail: SendRail? = null, val selectedRailAvailable: Boolean? = null, val sourceOptions: List<SendSource> = emptyList(),
) {
    val next: SendStage get() {
        if (entry == "max") return if (!sourceConfirmed) SendStage.source else if (recipientConfirmed) SendStage.review else SendStage.recipient
        for (stage in if (entry == "amount-first") listOf(SendStage.amount, SendStage.recipient) else listOf(SendStage.recipient, SendStage.amount)) {
            if (stage == SendStage.amount && !amountConfirmed) return stage
            if (stage == SendStage.recipient && !recipientConfirmed) return stage
        }
        if ((rails.size > 1 || selectedRailAvailable == false) && !railConfirmed) return SendStage.method
        if (selectedRail == SendRail.onchain && sourceOptions.size != 1 && !sourceConfirmed) return SendStage.source
        return SendStage.review
    }
}
object SendFlow {
    fun rails(type: String?, request: Map<String, String>?): List<SendRail> {
        if (type == "bip321" && request != null) return SendRail.entries.filter { method(it, request) != null }
        return when (type) { "ark" -> listOf(SendRail.ark); "lightning", "offer", "lnurl" -> listOf(SendRail.lightning); "onchain" -> listOf(SendRail.onchain); else -> emptyList() }
    }
    fun method(rail: SendRail, request: Map<String, String>) = when (rail) {
        SendRail.ark -> "ark".takeIf { "arkAddress" in request }
        SendRail.lightning -> if ("lightningInvoice" in request) "lightning" else "offer".takeIf { "offer" in request }
        SendRail.onchain -> "onchain".takeIf { "onchainAddress" in request }
    }
    fun label(type: String?) = mapOf("ark" to "Ark address", "lightning" to "Lightning invoice", "offer" to "Lightning offer", "lnurl" to "Lightning address", "onchain" to "On-chain address", "bip321" to "Bitcoin payment request")[type]
    fun recommend(rails: List<SendRail>, availability: Map<String, Boolean>) = rails.firstOrNull { availability[it.name] != false } ?: rails.firstOrNull()
    fun canNote(type: String?, allowed: Int) = type == "lnurl" && allowed > 0
    fun canMax(type: String?, request: Map<String, String>?) = type == "onchain" || type == "bip321" && request?.containsKey("onchainAddress") == true
    fun maxBalance(source: SendSource?, onchain: Long, offchain: Long) = when (source) { SendSource.onchain -> onchain; SendSource.offchain -> offchain; null -> maxOf(onchain, offchain) }
    fun sdkFeeRate(satsPerKwu: ULong): ULong = satsPerKwu / 250u + if (satsPerKwu % 250u == 0uL) 0u else 1u
}
