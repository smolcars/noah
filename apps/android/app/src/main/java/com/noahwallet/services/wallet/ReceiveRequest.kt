package com.noahwallet.services.wallet

import com.noahwallet.services.server.LightningIdentity
import kotlinx.serialization.Serializable
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
    /**
     * The Payment request: the on-chain address, your Lightning address as an LNURL-pay code in `lightning` (LUD-01's
     * fallback key; Noah is Lightning address first) and the Ark address. It never carries an amount or an invoice:
     * those belong to the Lightning invoice format.
     */
    fun uri(lightningAddress: String?): String {
        val parameters = mutableListOf<String>()
        lightningAddress?.let(LightningIdentity::lnurl)?.let { parameters += "lightning=${encode(it)}" }
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
