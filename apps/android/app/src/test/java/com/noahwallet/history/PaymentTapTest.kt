package com.noahwallet.history

import com.noahwallet.fixtures.SharedFixtures
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** apps/fixtures/notification-tap.json: which payment a "Payment received" notification opens. */
class PaymentTapTest {
    @Test fun sharedCases() {
        val cases = SharedFixtures.load("notification-tap")
        assertTrue(cases.isNotEmpty())
        for (case in cases) {
            val input = case.input.jsonObject
            val tap = PaymentTap.of(input.getValue("data").jsonPrimitive.content)
            val expected = case.expected.jsonObject
            assertEquals(case.name, expected.getValue("tap").jsonPrimitive.boolean, tap != null)
            val opens = input.getValue("rows").jsonArray.map { it.jsonObject }.firstOrNull { row ->
                tap != null && tap.matches(row["paymentHash"]?.jsonPrimitive?.contentOrNull,
                    row["outputVtxos"]?.jsonArray?.map { it.jsonPrimitive.content })
            }?.getValue("id")?.jsonPrimitive?.content
            assertEquals(case.name, expected["opens"]?.jsonPrimitive?.contentOrNull, opens)
        }
    }

    @Test fun aLightningRowMatchesByTheHashItsMetadataRecords() {
        val row = HistoryTransaction("movement-4", "Bolt11", 1, "2026-11-06", "incoming", metadataJson = """{"payment_hash":"0f0f"}""")
        assertTrue(PaymentTap.of(PaymentTap.lightningData("0f0f"))!!.matches(row))
        assertFalse(PaymentTap.of(PaymentTap.lightningData("abab"))!!.matches(row))
    }
}
