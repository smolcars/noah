package com.noahwallet.services.wallet

import com.noahwallet.fixtures.FixtureCase
import com.noahwallet.fixtures.SharedFixtures
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class ReceiveRequestTest(private val case: FixtureCase) {
    @Test fun matchesSharedFixture() {
        val input = case.input.jsonObject
        val amount = input["amountSat"]?.takeUnless { it == JsonNull }?.jsonPrimitive
        val result = runCatching {
            val sats = amount?.let { requireNotNull(it.longOrNull) }
            ReceiveRequest(input.getValue("arkAddress").jsonPrimitive.content,
                input.getValue("onchainAddress").jsonPrimitive.content, sats,
                lightningInvoice = input["lightningInvoice"]?.jsonPrimitive?.content).uri()
        }
        val expected = case.expected.jsonObject
        if ("error" in expected) assertTrue(case.name, result.isFailure)
        else assertEquals(case.name, expected.getValue("uri").jsonPrimitive.content, result.getOrThrow())
    }
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{index}")
        fun fixtures() = SharedFixtures.load("receive-request").map { arrayOf(it) }
    }
}
