package com.noahwallet.ui.receive

import com.noahwallet.fixtures.FixtureCase
import com.noahwallet.fixtures.SharedFixtures
import com.noahwallet.history.HistoryTransaction
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Payments through your Lightning address that Receive shows arriving, from the shared fixture both apps run. */
@RunWith(Parameterized::class)
class ReceiveAddressPaymentsTest(private val case: FixtureCase) {
    @Test fun matchesSharedFixture() {
        val input = case.input.jsonObject
        val expected = case.expected.jsonObject
        val rows = input.getValue("rows").jsonArray.map { row(it.jsonObject) }
        expected["settled"]?.let { settled ->
            assertEquals(case.name, settled.jsonArray.map { it.jsonPrimitive.content }.toSet(), ReceiveArrivals.settled(rows).toSet())
            return
        }
        val next = ReceiveArrivals.viaAddress(rows, strings(input, "settled"), strings(input, "ownArk"), strings(input, "ownHashes"))
        assertEquals(case.name, expected.getValue("next").jsonPrimitive.contentOrNull, next?.id)
    }

    private fun strings(input: JsonObject, key: String) = input.getValue(key).jsonArray.map { it.jsonPrimitive.content }.toSet()

    private fun row(json: JsonObject) = HistoryTransaction(json.getValue("id").jsonPrimitive.content, "Bolt11",
        json.getValue("amount").jsonPrimitive.long, "2026-11-25T10:00:00Z", json.getValue("direction").jsonPrimitive.content,
        movementStatus = json.getValue("status").jsonPrimitive.content, movementKind = json.getValue("kind").jsonPrimitive.content,
        metadataJson = json["paymentHash"]?.let { """{"payment_hash":"${it.jsonPrimitive.content}"}""" },
        receivedOn = json["receivedOn"]?.jsonArray?.map { HistoryTransaction.Destination(it.jsonPrimitive.content, 1_000, "ark") })

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{index}")
        fun fixtures() = SharedFixtures.load("receive-address-payments").map { arrayOf(it) }
    }
}
