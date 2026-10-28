package com.noahwallet.services.wallet

import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.net.URLEncoder

/** BIP-321: the same address pair survives amount edits. */
@Serializable
data class ReceiveRequest(
    val arkAddress: String,
    val onchainAddress: String,
    val amountSat: Long? = null,
    val description: String = "",
    val lightningInvoice: String? = null,
    val paymentHash: String? = null,
) {
    fun uri(): String {
        val parameters = mutableListOf<String>()
        amountSat?.let {
            validate(it)
            requireNotNull(lightningInvoice) { "An amount requires a Lightning invoice" }
            parameters += "amount=${BigDecimal.valueOf(it, 8).stripTrailingZeros().toPlainString()}"
            parameters += "lightning=${encode(lightningInvoice)}"
        }
        parameters += "ark=${encode(arkAddress)}"
        return "bitcoin:$onchainAddress?${parameters.joinToString("&")}"
    }
    companion object {
        fun validate(amountSat: Long, description: String = "") {
            require(amountSat in 1..9_007_199_254_740_991) { """Receive amount must be a positive whole number of sats""" }
            require(description.trim().length <= 639) { """Note must be 639 characters or fewer""" }
        }
        private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
            .replace("+", "%20").replace("%21", "!").replace("%27", "'")
            .replace("%28", "(").replace("%29", ")").replace("%7E", "~")
    }
}
