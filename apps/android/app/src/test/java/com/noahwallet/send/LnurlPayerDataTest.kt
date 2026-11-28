package com.noahwallet.send

import com.noahwallet.fixtures.SharedFixtures
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import java.net.URLDecoder

/** LUD-18 payer data on Send: the shared fixtures, the callback request and To's stop and notice. */
class LnurlPayerDataTest {
    private val ada = LnurlPayerIdentity("Ada", "ada@noahwallet.io")

    /** The fixture's outcome for one case: the payer data sent, or which error stopped it. */
    private fun outcome(input: JsonObject): Pair<Map<String, String>, String?> {
        val requirements = LnurlPayerData.requirements(input["payerData"])
        val identity = input["identity"]?.jsonObject
        val saved = LnurlPayerIdentity(identity?.get("name")?.jsonPrimitive?.contentOrNull, identity?.get("identifier")?.jsonPrimitive?.contentOrNull)
        return try {
            LnurlPayerData.build(requirements, saved.fields) to null
        } catch (failure: SendFailure) {
            emptyMap<String, String>() to when (failure.reason) {
                SendFailure.Reason.payerDataUnsupported -> "unsupported"
                SendFailure.Reason.payerDataMissing -> "missing"
                else -> throw failure
            }
        }
    }
    private fun strings(value: JsonElement?): Map<String, String> =
        (value as? JsonObject)?.mapValues { it.value.jsonPrimitive.content }.orEmpty()

    @Test fun payerDataFixture() {
        val cases = SharedFixtures.load("lnurl-pay-payer-data")
        assertEquals(30, cases.size)
        for (case in cases) {
            val (payerData, error) = outcome(case.input.jsonObject)
            assertEquals(case.name, case.expected.jsonObject["error"]?.jsonPrimitive?.contentOrNull, error)
            assertEquals(case.name, strings(case.expected.jsonObject["payerData"]), payerData)
        }
    }

    /** The query as a form decoder reads it (`+` is a space), as LNURL services do. */
    private fun formDecoded(url: HttpUrl): MutableMap<String, String> = url.encodedQuery.orEmpty().split('&').filter { it.isNotEmpty() }
        .associate { pair -> pair.split('=', limit = 2).let { URLDecoder.decode(it[0], "UTF-8") to URLDecoder.decode(it.getOrElse(1) { "" }, "UTF-8") } }
        .toMutableMap()
    private fun payerData(params: MutableMap<String, String>): Map<String, String>? =
        params.remove("payerdata")?.let { strings(Json.parseToJsonElement(it)) }

    @Test fun callbackFixture() {
        val cases = SharedFixtures.load("lnurl-pay-callback")
        assertEquals(10, cases.size)
        for (case in cases) {
            val input = case.input.jsonObject
            val url = SendRecipientService.callbackUrl(input.getValue("callback").jsonPrimitive.content.toHttpUrl(),
                input.getValue("amountMsat").jsonPrimitive.long, input.getValue("comment").jsonPrimitive.content, strings(input["payerData"]),
                input["paymentOption"]?.jsonPrimitive?.contentOrNull)
            val params = formDecoded(url)
            val payerData = payerData(params)
            assertEquals(case.name, strings(case.expected.jsonObject["params"]), params)
            assertEquals(case.name, (case.expected.jsonObject["payerData"] as? JsonObject)?.let(::strings), payerData)
        }
    }

    @Test fun paymentOptionsFixture() {
        val cases = SharedFixtures.load("lnurl-pay-options")
        assertEquals(17, cases.size)
        for (case in cases) {
            val input = case.input.jsonObject
            val option = SendArkOption.first(input["paymentOptions"], input.getValue("minSendable").jsonPrimitive.long,
                input.getValue("maxSendable").jsonPrimitive.long)
            val expected = (case.expected as? JsonObject)?.let {
                SendArkOption(it.getValue("id").jsonPrimitive.content, it.getValue("minMsat").jsonPrimitive.long, it.getValue("maxMsat").jsonPrimitive.long)
            }
            assertEquals(case.name, expected, option)
        }
    }

    /** A Lightning address asking for [payerData] (field to mandatory), then answering its callback with an invoice. */
    private fun service(payerData: Map<String, Boolean>, requests: MutableList<HttpUrl>) = SendRecipientService("regtest", fetch = { url ->
        requests += url
        if (requests.size == 1) buildJsonObject {
            put("tag", "payRequest"); put("callback", "https://pay.example.com/callback"); put("minSendable", 1000); put("maxSendable", 10_000_000)
            put("payerData", buildJsonObject { payerData.forEach { (field, mandatory) -> put(field, buildJsonObject { put("mandatory", mandatory) }) } })
        } else buildJsonObject { put("pr", decoderInvoice()) }
    })
    private fun payment(sharing: Map<String, String>?) = SendPayment("bob@example.com", "lnurl", 1000, payerData = sharing)

    @Test fun callbackCarriesPayerDataAndNoEmptyComment() = runBlocking {
        val requests = mutableListOf<HttpUrl>()
        service(mapOf("name" to false, "identifier" to true), requests).invoice(payment(ada.fields))
        assertEquals(2, requests.size)
        val params = formDecoded(requests[1])
        assertEquals(mapOf("name" to "Ada", "identifier" to "ada@noahwallet.io"), payerData(params))
        assertEquals(mapOf("amount" to "1000000"), params)
    }

    @Test fun quoteSendsOnlyWhatToShowed() = runBlocking {
        // A lookup asking for more than To showed gets only what To showed; one with nothing shown gets nothing.
        val requests = mutableListOf<HttpUrl>()
        service(mapOf("name" to false, "identifier" to false), requests).invoice(payment(mapOf("name" to "Ada")))
        val params = formDecoded(requests.last())
        assertEquals(mapOf("name" to "Ada"), payerData(params))
        requests.clear()
        service(mapOf("name" to false, "identifier" to false), requests).invoice(payment(null))
        assertEquals(mapOf("amount" to "1000000"), formDecoded(requests.last()))
    }

    @Test fun requiredPayerDataNeverReachesTheCallback() = runBlocking {
        for ((asked, reason) in listOf(mapOf("email" to true) to SendFailure.Reason.payerDataUnsupported,
            // Mandatory since To's lookup: To never showed the address, so it isn't sent.
            mapOf("identifier" to true) to SendFailure.Reason.payerDataMissing)) {
            val requests = mutableListOf<HttpUrl>()
            val failure = runCatching { service(asked, requests).invoice(payment(mapOf("name" to "Ada"))) }.exceptionOrNull()
            assertEquals(reason, (failure as? SendFailure)?.reason)
            assertEquals(1, requests.size)
        }
    }

    private fun model(scope: CoroutineScope, payerData: Map<String, Boolean>, identity: LnurlPayerIdentity = ada,
        quote: suspend (SendPayment) -> SendQuote = { FakeSendWallet.quote }) =
        SendModel(controller(FakeSendWallet(), TestJournal(), scope), "regtest",
            { SendRecipientRoute(it, 1000, 10_000_000, 0, null, "https://example.com/callback".toHttpUrl(), "metadata", payerData) },
            quote, {}, scope, payerIdentity = { identity })

    @Test fun payerDataAddressStopsOnToWithItsNotice() = runBlocking {
        var reviewed: SendPayment? = null
        val m = model(this, mapOf("name" to false, "identifier" to false), quote = { reviewed = it; FakeSendWallet.quote })
        m.amount = "1000"; m.arkBalance = 10000
        // Scanned or pasted from Amount: To shows what Noah will share before the quote calls the address's callback.
        m.importRecipient("bob@example.com", stay = true)
        assertEquals(SendStage.recipient, m.stage); assertFalse(m.progress.recipientConfirmed); assertNull(reviewed)
        assertEquals(LnurlPayerData.Share.nameAndAddress, m.payerShare); assertNull(m.error)
        m.continueStage(); m.continueStage()
        assertEquals("bob@example.com", reviewed?.destination); assertTrue(m.showReview)
        // The payment carries what To showed, for the quote to send.
        assertEquals(ada.fields, reviewed?.payerData)
    }

    @Test fun payerShareNamesOnlyWhatIsSent() = runBlocking {
        val nameOnly = model(this, mapOf("name" to false, "email" to false))
        nameOnly.setRecipient("bob@example.com"); nameOnly.previewRecipient()
        assertEquals(LnurlPayerData.Share.nameOnly, nameOnly.payerShare)
        val nothingSaved = model(this, mapOf("name" to false), LnurlPayerIdentity(" ", null))
        nothingSaved.setRecipient("bob@example.com"); nothingSaved.previewRecipient()
        assertNotNull(nothingSaved.matchingRoute); assertNull(nothingSaved.payerShare)
    }

    @Test fun requiredPayerDataStopsOnToWithItsError() = runBlocking {
        var reviewed: SendPayment? = null
        val m = model(this, mapOf("identifier" to true), LnurlPayerIdentity("Ada", null), quote = { reviewed = it; FakeSendWallet.quote })
        m.amount = "1000"; m.arkBalance = 10000
        m.importRecipient("bob@example.com", stay = true)
        assertEquals(SendStage.recipient, m.stage); assertNull(reviewed); assertNull(m.payerShare)
        assertEquals(SendFailure.Reason.payerDataMissing.message, m.error)
        // Next again keeps To and its error.
        m.continueStage()
        assertEquals(SendStage.recipient, m.stage); assertNull(reviewed); assertEquals(SendFailure.Reason.payerDataMissing.message, m.error)
    }

    @Test fun requiredPayerDataKeepsATypedAddressOnTo() = runBlocking {
        var reviewed: SendPayment? = null
        val m = model(this, mapOf("identifier" to true), LnurlPayerIdentity("Ada", null), quote = { reviewed = it; FakeSendWallet.quote })
        m.amount = "1000"; m.arkBalance = 10000
        m.recipientFirst()
        // Typed: the preview looked it up while typing, so Next doesn't look it up again.
        m.setRecipient("bob@example.com"); m.previewRecipient()
        m.continueStage()
        assertEquals(SendStage.recipient, m.stage); assertNull(reviewed); assertEquals(SendFailure.Reason.payerDataMissing.message, m.error)
    }
}
