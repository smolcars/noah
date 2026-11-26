package com.noahwallet.send

import com.noahwallet.services.storage.ServerStateStorage
import com.noahwallet.services.storage.SettingsPreferences
import com.noahwallet.services.storage.SettingsStorage
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
    val commentAllowed: Int, val arkAddress: String?, val callback: HttpUrl, val metadata: String?,
    /** LUD-18 fields the address asks for, each to whether it's mandatory. Empty when it asks for none. */
    val payerData: Map<String, Boolean> = emptyMap())

/** The identity Noah can send as LUD-18 payer data: the profile's display name and the wallet's own Lightning address. */
data class LnurlPayerIdentity(val name: String? = null, val identifier: String? = null) {
    /** The saved values Noah can send, trimmed; an empty one counts as unset. */
    val fields: Map<String, String>
        get() = mapOf("name" to name, "identifier" to identifier).mapValues { it.value?.trim().orEmpty() }.filterValues { it.isNotEmpty() }
    companion object {
        fun stored(settings: SettingsStorage) = LnurlPayerIdentity(
            SettingsPreferences.state(settings.string("profile-storage"))["displayName"]?.jsonPrimitive?.contentOrNull,
            ServerStateStorage(settings).state.lightningAddress)
    }
}

/** LUD-18 payer data (`apps/fixtures/lnurl-pay-payer-data.json`). Noah sends only the fields a pay request asks for. */
object LnurlPayerData {
    /** What To's notice says Noah shares. */
    enum class Share { nameOnly, addressOnly, nameAndAddress }
    private val supported = setOf("name", "identifier")

    /** A pay request's `payerData`: each field to whether it's mandatory, read leniently: a malformed object or field counts
     *  as not asked for. */
    fun requirements(value: JsonElement?): Map<String, Boolean> = (value as? JsonObject).orEmpty().mapNotNull { (field, entry) ->
        ((entry as? JsonObject)?.get("mandatory") as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull?.let { field to it }
    }.toMap()

    /** The fields to send: those asked for among [available] (the saved identity on To, what To showed at the quote). Fails
     *  when a field Noah doesn't send is mandatory, then when a mandatory field Noah sends isn't available. */
    fun build(requirements: Map<String, Boolean>, available: Map<String, String>): Map<String, String> {
        requireSend(requirements.none { (field, mandatory) -> mandatory && field !in supported }, SendFailure.Reason.payerDataUnsupported)
        requireSend(requirements.none { (field, mandatory) -> mandatory && field in supported && field !in available },
            SendFailure.Reason.payerDataMissing)
        return available.filterKeys { it in supported && it in requirements }
    }

    fun share(shared: Map<String, String>): Share? = when {
        "name" in shared && "identifier" in shared -> Share.nameAndAddress
        "name" in shared -> Share.nameOnly
        "identifier" in shared -> Share.addressOnly
        else -> null
    }

    fun json(shared: Map<String, String>) = buildJsonObject { shared.toSortedMap().forEach { (field, value) -> put(field, value) } }.toString()
}

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
        return SendRecipientRoute(value, min!!, max!!, maxOf(0, response["commentAllowed"]?.jsonPrimitive?.intOrNull ?: 0), ark, callback!!, string("metadata"),
            LnurlPayerData.requirements(response["payerData"]))
    }
    /** Sends only the payer data the payment carries (what To showed), as far as this lookup still asks for it. */
    suspend fun invoice(payment: SendPayment): String {
        val route = resolve(payment.destination)
        val msat = Math.multiplyExact(payment.amount, 1000)
        requireSend(msat in route.minMsat..route.maxMsat && payment.comment.length <= route.commentAllowed)
        val url = callbackUrl(route.callback, msat, payment.comment, LnurlPayerData.build(route.payerData, payment.payerData.orEmpty()))
        val invoice = fetch(url)["pr"]?.jsonPrimitive?.contentOrNull ?: throw SendFailure(SendFailure.Reason.invalid)
        validateInvoice(invoice, payment.amount, network)
        val decoded = PaymentEncoding.invoice(invoice)!!
        if (route.metadata != null && decoded.descriptionHash != PaymentEncoding.hex(PaymentEncoding.sha256(route.metadata.toByteArray())))
            warn("LNURL invoice description hash does not commit to endpoint metadata")
        return invoice
    }
    companion object {
        /** The callback with this payment's `amount`, its note as `comment` and LUD-18 `payerdata`, each only when there is one
         *  (`apps/fixtures/lnurl-pay-callback.json`). The service's own query stays as it was. The provider enforces commentAllowed. */
        fun callbackUrl(callback: HttpUrl, msat: Long, comment: String, payerData: Map<String, String>): HttpUrl = callback.newBuilder()
            .removeAllQueryParameters("amount").removeAllQueryParameters("comment").removeAllQueryParameters("payerdata")
            .addQueryParameter("amount", msat.toString())
            .apply { if (comment.isNotEmpty()) addQueryParameter("comment", comment) }
            .apply { if (payerData.isNotEmpty()) addQueryParameter("payerdata", LnurlPayerData.json(payerData)) }
            .build()
        private val http = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).followRedirects(false).build()
        private suspend fun fetchJSON(url: HttpUrl): JsonObject = withContext(Dispatchers.IO) {
            http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                requireSend(response.code == 200)
                val source = response.body.source()
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
                if (username.length > 32 || !username.first().isLetter() || listOf("bc1", "tb1", "bcrt1", "lnbc", "lntb", "lnbcrt", "ark", "tark",
                        "lno").any(username::startsWith)) return null
            } else if (!domain.startsWith(parts[1])) return null
            return value
        }
        fun withoutOwnAddress(suggestions: List<String>, own: String?) = suggestions.filterNot { it.equals(own, ignoreCase = true) }
    }
}
