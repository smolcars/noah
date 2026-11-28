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
    val payerData: Map<String, Boolean> = emptyMap(),
    /** The `bark` payment option, when the service offers one (lnurl/luds#303). */
    val arkOption: SendArkOption? = null) {
    /** Whether Ark can pay this address at all, whatever the amount. */
    val offersArk get() = arkOption != null || arkAddress != null

    /** How Ark pays [sats] here (0: any amount): through the payment option while the amount fits it, else to the address
     *  discovery returned. Null when neither can. */
    fun arkRail(sats: Long): SendArkRail? {
        if (arkOption != null) {
            val msat = runCatching { Math.multiplyExact(sats, 1000) }.getOrNull()
            if (sats == 0L || (msat != null && msat in arkOption.minMsat..arkOption.maxMsat)) return SendArkRail.Option(arkOption.id)
        }
        return arkAddress?.let(SendArkRail::Address)
    }
}

/** How a Lightning address is paid over Ark. */
sealed interface SendArkRail {
    /** Through a `bark` payment option: its callback returns the address. */
    data class Option(val id: String) : SendArkRail
    /** To the address discovery returned for `ark=`, Noah's older contract. */
    data class Address(val address: String) : SendArkRail
}

/** The `bark` payment option Noah pays through (lnurl/luds#303, `apps/fixtures/lnurl-pay-options.json`), with the amount range
 *  it allows. */
data class SendArkOption(val id: String, val minMsat: Long, val maxMsat: Long) {
    companion object {
        /** The first usable `bark` entry of a pay request's `paymentOptions`, its own range narrowing the pay request's. Read
         *  leniently: a malformed entry or field counts as not offered, and anything but an array as no options. */
        fun first(paymentOptions: JsonElement?, minMsat: Long, maxMsat: Long): SendArkOption? =
            (paymentOptions as? JsonArray)?.firstNotNullOfOrNull { option(it, minMsat, maxMsat) }
        /** [entry] as a usable `bark` option, or null. */
        private fun option(entry: JsonElement, minMsat: Long, maxMsat: Long): SendArkOption? {
            val option = (entry as? JsonObject)?.takeIf { it.text("type") == "bark" && it.available() } ?: return null
            val id = option.text("id")?.takeIf { it.isNotEmpty() } ?: return null
            val low = maxOf(minMsat, option.msat("minSendable", minMsat) ?: return null)
            val high = minOf(maxMsat, option.msat("maxSendable", maxMsat) ?: return null)
            return SendArkOption(id, low, high).takeIf { low <= high }
        }
        /** `available` absent or true; anything else, malformed included, counts as unavailable. */
        private fun JsonObject.available() =
            get("available").let { it == null || (it as? JsonPrimitive)?.takeUnless { value -> value.isString }?.booleanOrNull == true }
        private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
        /** [key] in millisatoshis, [absent] when it's missing; null when it's malformed. */
        private fun JsonObject.msat(key: String, absent: Long): Long? {
            val value = get(key) ?: return absent
            return (value as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.takeIf { it >= 0 }
        }
    }
}

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

class SendRecipientService(private val network: String, private val fetch: suspend (HttpUrl) -> JsonObject = ::fetchJSON,
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
            LnurlPayerData.requirements(response["payerData"]), SendArkOption.first(response["paymentOptions"], min, max))
    }
    /** The Ark address to pay: discovery's own, or the one a payment option's callback returns, checked as discovery's is.
     *  Sends no comment or payer data: an Ark payment can't carry them to the recipient yet. */
    suspend fun arkAddress(rail: SendArkRail, route: SendRecipientRoute, sats: Long): String = when (rail) {
        is SendArkRail.Address -> rail.address
        is SendArkRail.Option -> {
            val response = fetch(callbackUrl(route.callback, Math.multiplyExact(sats, 1000), "", emptyMap(), rail.id))
            val address = (response["paymentDestination"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw SendFailure(SendFailure.Reason.invalid)
            requireSend(runCatching { validateArk(address) }.getOrDefault(false))
            address
        }
    }
    /** Sends only the payer data the payment carries (what To showed), as far as this lookup still asks for it. */
    suspend fun invoice(payment: SendPayment): String {
        val route = resolve(payment.destination)
        val msat = Math.multiplyExact(payment.amount, 1000)
        requireSend(msat in route.minMsat..route.maxMsat && payment.comment.length <= route.commentAllowed)
        val url = callbackUrl(route.callback, msat, payment.comment, LnurlPayerData.build(route.payerData, payment.payerData.orEmpty()))
        val invoice = fetch(url)["pr"]?.jsonPrimitive?.contentOrNull ?: throw SendFailure(SendFailure.Reason.invalid)
        // LNURL-pay no longer binds the invoice's description hash to the metadata (lnurl/luds#234), so it isn't checked.
        validateInvoice(invoice, payment.amount, network)
        return invoice
    }
    companion object {
        /** The callback with this payment's `amount`, its note as `comment`, LUD-18 `payerdata` and its `paymentOption`
         *  (lnurl/luds#303), each only when there is one (`apps/fixtures/lnurl-pay-callback.json`). The service's own query stays
         *  as it was. The provider enforces commentAllowed. */
        fun callbackUrl(callback: HttpUrl, msat: Long, comment: String, payerData: Map<String, String>, paymentOption: String? = null): HttpUrl =
            callback.newBuilder()
                .removeAllQueryParameters("amount").removeAllQueryParameters("comment").removeAllQueryParameters("payerdata")
                .removeAllQueryParameters("paymentOption")
                .addQueryParameter("amount", msat.toString())
                .apply { if (comment.isNotEmpty()) addQueryParameter("comment", comment) }
                .apply { if (payerData.isNotEmpty()) addQueryParameter("payerdata", LnurlPayerData.json(payerData)) }
                .apply { if (paymentOption != null) addQueryParameter("paymentOption", paymentOption) }
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
