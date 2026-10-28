package com.noahwallet.send

import com.noahwallet.fixtures.FixtureCase
import com.noahwallet.fixtures.SharedFixtures
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class SendAmountTest(private val case: FixtureCase) {
    @Test fun untrustedURIAmountMatchesSharedSafetyVector() {
        val amount = case.input.jsonObject.getValue("amount").jsonPrimitive.content
        val encoded = java.net.URLEncoder.encode(amount, "UTF-8").replace("+", "%20")
        val parsed = SendDestination.parse("bitcoin:1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa?amount=$encoded", "mainnet")
        val expected = case.expected.jsonObject
        val valid = expected.getValue("valid").jsonPrimitive.boolean
        assertEquals(case.name, valid, parsed.destinationType != null)
        assertEquals(case.name, !valid, parsed.error != null)
        assertEquals(case.name, expected["sats"]?.jsonPrimitive?.contentOrNull?.toBigDecimal()?.stripTrailingZeros(), parsed.amount?.stripTrailingZeros())
        assertEquals(case.name, parsed.amount == null, parsed.isAmountEditable)
        // The reported recipient-preview crash must also be safe with no address/method present.
        assertNull(SendDestination.parse("bitcoin:?amount=$encoded", "mainnet").destinationType)
    }
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{index}")
        fun cases() = SharedFixtures.load("send-amount-safety").map { arrayOf(it) }
    }
}
