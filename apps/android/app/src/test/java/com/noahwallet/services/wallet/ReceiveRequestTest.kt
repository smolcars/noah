package com.noahwallet.services.wallet

import com.noahwallet.fixtures.FixtureCase
import com.noahwallet.fixtures.SharedFixtures
import com.noahwallet.services.server.LightningIdentity
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class ReceiveRequestTest(private val case: FixtureCase) {
    @Test fun matchesSharedFixture() {
        val input = case.input.jsonObject
        val expected = case.expected.jsonObject
        val onchain = input["onchainAddress"]?.jsonPrimitive?.content
        if (onchain == null) {
            val result = runCatching { ReceiveRequest.validate(requireNotNull(input.getValue("amountSat").jsonPrimitive.longOrNull)) }
            assertEquals(case.name, "error" in expected, result.isFailure)
            return
        }
        val address = input["lightningAddress"]?.jsonPrimitive?.content
        val request = ReceiveRequest(input.getValue("arkAddress").jsonPrimitive.content, onchain, input["amountSat"]?.jsonPrimitive?.longOrNull,
            lightningInvoice = input["lightningInvoice"]?.jsonPrimitive?.content)
        assertEquals(case.name, expected.getValue("uri").jsonPrimitive.content, request.uri(address))
        assertEquals(case.name, expected["lightningAddressQR"]?.jsonPrimitive?.contentOrNull, address?.let(LightningIdentity::lnurlQR))
    }
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{index}")
        fun fixtures() = SharedFixtures.load("receive-request").map { arrayOf(it) }
    }
}
