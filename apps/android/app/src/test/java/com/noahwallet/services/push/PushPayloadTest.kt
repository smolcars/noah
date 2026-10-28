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
    @Test fun visibleNotificationDecodesAndIsNoCommand() {
        val body = """{"notification_type":"visible_notification","title":"Payment received","body":"You received 10000 sats via Ark."}"""
        assertEquals(VisiblePush("Payment received", "You received 10000 sats via Ark."), PushPayload.visible(body))
        assertEquals(VisiblePush(body = "body"), PushPayload.visible("""{"notification_type":"visible_notification","title":null,"body":"body"}"""))
        assertTrue(runCatching { PushPayload.decode(body) }.isFailure)
        val root = Json.parseToJsonElement(javaClass.classLoader!!.getResource("server-notification-data.json")!!.readText())
        root.jsonObject["cases"]!!.jsonArray.forEach { assertNull(PushPayload.visible(it.jsonObject["input"]!!.toString())) }
        listOf("{}", "[]", "null", """{"notification_type":"other","title":"x"}""").forEach { assertNull(PushPayload.visible(it)) }
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
