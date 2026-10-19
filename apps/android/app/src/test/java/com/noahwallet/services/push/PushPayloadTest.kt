package com.noahwallet.services.push

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PushPayloadTest {
    @Test fun serverFixturesDecodeAsObjectsAndExpoBodyStrings() {
        val root = Json.parseToJsonElement(javaClass.classLoader!!.getResource("server-notification-data.json")!!.readText())
        root.jsonObject["cases"]!!.jsonArray.forEach { case ->
            val payload = case.jsonObject["input"]!!
            val objectResult = PushPayload.decode(payload)
            assertEquals(objectResult, PushPayload.decode(JsonPrimitive(payload.toString())))
            assertEquals(objectResult, PushPayload.decode(payload.toString()))
            assertEquals(objectResult, PushPayload.decode(PushPayload.encode(objectResult)))
        }
    }
    @Test fun malformedUnknownAndNegativeAmountsAreRejected() {
        listOf("null", "[]", "broken", "{}", """{"notification_type":"other"}""",
            """{"notification_type":"lightning_invoice_request","amount":-1,"transaction_id":"x"}""",
            """{"notification_type":"lightning_claim_request","amount_sat":-1,"payment_hash":"x"}""",
            """{"notification_type":"recurring_payment_due","due_count":4294967296}""").forEach {
            assertTrue(runCatching { PushPayload.decode(it) }.isFailure)
        }
    }
}
