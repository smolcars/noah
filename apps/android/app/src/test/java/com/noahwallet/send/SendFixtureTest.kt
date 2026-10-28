package com.noahwallet.send

import com.noahwallet.fixtures.FixtureCase
import com.noahwallet.fixtures.SharedFixtures
import com.noahwallet.services.wallet.ReceiveRequest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class SendFixtureTest(private val domain: String, private val case: FixtureCase) {
    private val json = Json { ignoreUnknownKeys = true }
    @Test fun matchesRN() {
        val input = case.input.jsonObject
        when (domain) {
            "send-utils" -> sendUtils(input)
            "send-flow" -> assertEquals(case.name, case.expected, sendFlow(input.getValue("args").jsonArray, input.getValue("operation").jsonPrimitive.content))
            "onchain-send" -> assertEquals(case.name, case.expected.jsonPrimitive.long,
                SendFlow.maxBalance(input["source"]?.jsonPrimitive?.contentOrNull?.let(SendSource::valueOf), input.getValue("onchain").jsonPrimitive.long,
                    input.getValue("offchain").jsonPrimitive.long))
            "lightning-invoice" -> lightningInvoice(input)
        }
    }

    private fun sendUtils(input: JsonObject) {
        val expected = case.expected.jsonObject
        val actual = SendDestination.parse(input.getValue("destination").jsonPrimitive.content, input.getValue("network").jsonPrimitive.content)
        fun JsonObject.string(key: String) = get(key)?.jsonPrimitive?.contentOrNull
        assertEquals(case.name, expected["valid"]!!.jsonPrimitive.boolean, actual.destinationType != null)
        assertEquals(case.name, expected.string("destinationType"), actual.destinationType)
        assertEquals(case.name, expected.string("amount")?.toBigDecimal()?.stripTrailingZeros(), actual.amount?.stripTrailingZeros())
        assertEquals(case.name, expected["isAmountEditable"]?.jsonPrimitive?.boolean ?: true, actual.isAmountEditable)
        assertEquals(case.name, expected.string("error") != null, actual.error != null)
        assertEquals(case.name, expected["bip321"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content }, actual.bip321)
    }

    private fun sendFlow(args: JsonArray, operation: String): JsonElement {
        fun str(i: Int) = args[i].jsonPrimitive.contentOrNull
        fun map(i: Int) = args[i].takeUnless { it == JsonNull }?.jsonObject?.mapValues { it.value.jsonPrimitive.content }
        return when (operation) {
            "stage" -> JsonPrimitive(json.decodeFromJsonElement<SendProgress>(args[0]).next.name)
            "rails" -> JsonArray(SendFlow.rails(str(0), map(1)).map { JsonPrimitive(it.name) })
            "label" -> SendFlow.label(str(0))?.let(::JsonPrimitive) ?: JsonNull
            "note" -> JsonPrimitive(SendFlow.canNote(str(0), args[1].jsonPrimitive.int))
            "max" -> JsonPrimitive(SendFlow.canMax(str(0), map(1)))
            "method" -> SendFlow.method(SendRail.valueOf(str(0)!!), map(1)!!)?.let(::JsonPrimitive) ?: JsonNull
            "recommend" -> SendFlow.recommend(args[0].jsonArray.map { SendRail.valueOf(it.jsonPrimitive.content) },
                args[1].jsonObject.mapValues { it.value.jsonPrimitive.boolean })?.name?.let(::JsonPrimitive) ?: JsonNull
            else -> error("Unknown fixture")
        }
    }

    private fun lightningInvoice(input: JsonObject) {
        val expected = case.expected.jsonObject
        val trimmed = input["description"]?.jsonPrimitive?.contentOrNull?.trim() ?: ""
        assertEquals(expected["normalized"]?.jsonPrimitive?.contentOrNull, trimmed.ifEmpty { null })
        assertEquals(expected.getValue("length").jsonPrimitive.int, trimmed.length)
        assertEquals(expected.getValue("valid").jsonPrimitive.boolean, runCatching { ReceiveRequest.validate(1, trimmed) }.isSuccess)
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0} {index}")
        fun cases() =
            listOf("send-utils", "send-flow", "onchain-send", "lightning-invoice").flatMap { domain -> SharedFixtures.load(domain).map { arrayOf(domain, it) } }
    }
}
