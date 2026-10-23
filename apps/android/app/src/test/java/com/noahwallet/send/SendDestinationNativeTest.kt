package com.noahwallet.send

import com.noahwallet.fixtures.FixtureCase
import com.noahwallet.fixtures.SharedFixtures
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.HttpUrl
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Native-only vectors for what bark accepts and RN refused; the RN-generated send-utils vectors still run in SendFixtureTest. */
@RunWith(Parameterized::class)
class SendDestinationNativeTest(private val case: FixtureCase) {
    @Test fun matchesNativeVector() {
        val input = case.input.jsonObject; val expected = case.expected.jsonObject
        val destination = input.getValue("destination").jsonPrimitive.content; val network = input.getValue("network").jsonPrimitive.content
        val actual = SendDestination.parse(destination, network)
        fun JsonObject.string(key: String) = get(key)?.jsonPrimitive?.contentOrNull
        assertEquals(case.name, expected.getValue("valid").jsonPrimitive.boolean, actual.destinationType != null)
        assertEquals(case.name, expected.string("destinationType"), actual.destinationType)
        assertEquals(case.name, expected.string("amount")?.toBigDecimal()?.stripTrailingZeros(), actual.amount?.stripTrailingZeros())
        assertEquals(case.name, expected.getValue("isAmountEditable").jsonPrimitive.boolean, actual.isAmountEditable)
        assertEquals(case.name, expected.string("error"), actual.error?.message)
        assertEquals(case.name, expected["bip321"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content }, actual.bip321)
        if ("url" !in expected) return
        // Resolution keeps today's client: HTTPS only, and the first request is the endpoint itself.
        val requests = mutableListOf<HttpUrl>()
        val service = SendRecipientService(network, fetch = { url -> requests += url; buildJsonObject {
            put("tag", "payRequest"); put("callback", "https://example.com/callback"); put("minSendable", 1000); put("maxSendable", 2000)
        } })
        val route = runCatching { runBlocking { service.resolve(SendDestination.cleaned(destination).lowercase()) } }
        assertEquals(case.name, expected.string("url"), requests.firstOrNull()?.toString())
        assertEquals(case.name, expected.string("url") != null, route.isSuccess)
    }
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{index}") fun cases() = SharedFixtures.load("send-destination-native").map { arrayOf(it) }
    }
}

class SendBech32Test {
    @Test fun encodesAnLnurlAsLnurlRsDoes() {
        // lnurl-rs 0.10's own encode_test vector.
        val url = "https://service.com/api?q=3fc3645b439ce8e7f2553a69e5267081d96dcd340693afabe04be7b0ccd178df"
        val encoded = PaymentEncoding.encodeBech32("lnurl", url.toByteArray())
        assertEquals("LNURL1DP68GURN8GHJ7UM9WFMXJCM99E3K7MF0V9CXJ0M385EKVCENXC6R2C35XVUKXEFCV5MKVV34X5EKZD3EV56NYD3HXQURZEPEXEJXXEPNXSCRVWFNV9NXZCN9XQ6XYEFHVGCXXCMYXYMNSERXFQ5FNS", encoded.uppercase())
        assertEquals(url, PaymentEncoding.bech32(encoded)!!.let { PaymentEncoding.bytes(it.words)!!.toString(Charsets.UTF_8) })
    }
}
