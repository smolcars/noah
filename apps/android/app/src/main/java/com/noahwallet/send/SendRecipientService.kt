package com.noahwallet.send

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Recipient-bound, resolve-only LNURL: the creating SDK LNURL APIs are deliberately never used. */
data class SendRecipientRoute(val destination: String, val minMsat: Long, val maxMsat: Long,
    val commentAllowed: Int, val arkAddress: String?, val callback: HttpUrl, val metadata: String?)
class SendRecipientService(private val network: String, private val warn: (String) -> Unit = {},
    private val fetch: suspend (HttpUrl) -> JsonObject = ::fetchJSON,
    private val arkServerKey: suspend () -> String? = { null }, private val validateArk: suspend (String) -> Boolean = { false }) {
    suspend fun resolve(destination: String): SendRecipientRoute {
        val value = SendDestination.cleaned(destination).lowercase()
        val endpoint = if (SendDestination.isLightningAddress(value)) SendDestination.addressEndpoint(value)
            else PaymentEncoding.bech32(value)?.let { PaymentEncoding.bytes(it.words)?.toString(Charsets.UTF_8)?.toHttpUrlOrNull() }
        requireSend(SendDestination.allowsLnurl(endpoint))
        val request = endpoint!!.newBuilder().apply { runCatching { arkServerKey() }.getOrNull()?.let { addQueryParameter("ark", it) } }.build()
        val response = fetch(request)
        fun string(key: String) = response[key]?.jsonPrimitive?.contentOrNull
        val callback = string("callback")?.toHttpUrlOrNull()
        val min = response["minSendable"]?.jsonPrimitive?.longOrNull; val max = response["maxSendable"]?.jsonPrimitive?.longOrNull
        requireSend(string("tag") == "payRequest" && SendDestination.allowsLnurl(callback) && min != null && max != null && min >= 0 && min <= max)
        val ark = string("ark")?.takeIf { runCatching { validateArk(it) }.getOrDefault(false) }
        return SendRecipientRoute(value, min!!, max!!, maxOf(0, response["commentAllowed"]?.jsonPrimitive?.intOrNull ?: 0), ark, callback!!, string("metadata"))
    }
    suspend fun invoice(payment: SendPayment): String {
        val route = resolve(payment.destination)
        val msat = Math.multiplyExact(payment.amount, 1000)
        requireSend(msat in route.minMsat..route.maxMsat && payment.comment.length <= route.commentAllowed)
        val url = route.callback.newBuilder().removeAllQueryParameters("amount").removeAllQueryParameters("comment")
            .addQueryParameter("amount", msat.toString()).addQueryParameter("comment", payment.comment).build()
        val invoice = fetch(url)["pr"]?.jsonPrimitive?.contentOrNull ?: throw SendFailure(SendFailure.Reason.invalid)
        validateInvoice(invoice, payment.amount, network)
        val decoded = PaymentEncoding.invoice(invoice)!!
        if (route.metadata != null && decoded.descriptionHash != PaymentEncoding.hex(PaymentEncoding.sha256(route.metadata.toByteArray())))
            warn("LNURL invoice description hash does not commit to endpoint metadata")
        return invoice
    }
    companion object {
        private val http = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).followRedirects(false).build()
        private suspend fun fetchJSON(url: HttpUrl): JsonObject = withContext(Dispatchers.IO) {
            http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                requireSend(response.code == 200)
                val source = response.body?.source() ?: throw SendFailure(SendFailure.Reason.invalid)
                source.request(65537); requireSend(source.buffer.size <= 65536)
                Json.parseToJsonElement(source.readUtf8()).jsonObject
            }
        }
        fun validateInvoice(value: String, amount: Long, network: String) {
            val parsed = SendDestination.parse(value, network); val invoice = PaymentEncoding.invoice(value)
            requireSend(parsed.destinationType == "lightning" && parsed.error == null && invoice?.sats?.compareTo(amount.toBigDecimal()) == 0)
            requireSend(invoice!!.expires > System.currentTimeMillis() / 1000, SendFailure.Reason.expired)
        }
        fun suggestionQuery(destination: String, domain: String): String? {
            val value = SendDestination.cleaned(destination).lowercase(); val parts = value.split('@'); val username = parts.first()
            if (parts.size !in 1..2 || username.length < 2 || !Regex("^[a-z0-9_.-]+$").matches(username)) return null
            if (parts.size == 1) {
                if (username.length > 32 || !username.first().isLetter() || listOf("bc1", "tb1", "bcrt1", "lnbc", "lntb", "lnbcrt", "ark", "tark", "lno").any(username::startsWith)) return null
            } else if (!domain.startsWith(parts[1])) return null
            return value
        }
        fun withoutOwnAddress(suggestions: List<String>, own: String?) = suggestions.filterNot { it.equals(own, ignoreCase = true) }
    }
}
