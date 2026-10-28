package com.noahwallet.services.server

import com.noahwallet.api.generated.models.*
import com.noahwallet.fixtures.SharedFixtures
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class NotificationDataTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun allServerFixturesDecodeAndRoundTrip() {
        val cases = SharedFixtures.load("server-notification-data")
        assertEquals(7, cases.size)
        val types = mutableSetOf<String>()
        for (case in cases) {
            val value = json.decodeFromJsonElement<NotificationData>(case.input)
            val type = when (value) {
                is NotificationDataMaintenance -> "maintenance"
                is NotificationDataLightningInvoiceRequest -> "lightning_invoice_request"
                is NotificationDataLightningClaimRequest -> "lightning_claim_request"
                is NotificationDataBackupTrigger -> "backup_trigger"
                is NotificationDataHeartbeat -> "heartbeat"
                is NotificationDataRecurringPaymentDue -> "recurring_payment_due"
            }
            assertEquals(case.name, case.expected.jsonPrimitive.content, type)
            assertEquals(case.name, case.input, json.encodeToJsonElement<NotificationData>(value))
            types += type
        }
        assertEquals(6, types.size)
    }

    @Test fun preservesIntegerBoundariesAndOptionalUsd() {
        val claim =
            json.decodeFromString<NotificationData>("""{"notification_type":"lightning_claim_request","amount_sat":9223372036854775807,""" +
                """"payment_hash":"test"}""") as NotificationDataLightningClaimRequest
        assertEquals(Long.MAX_VALUE, claim.amountSat)
        assertNull(claim.amountUsd)
        val due = json.decodeFromString<NotificationData>("""{"notification_type":"recurring_payment_due","due_count":4294967295}""")
            as NotificationDataRecurringPaymentDue
        assertEquals(4_294_967_295L, due.dueCount)
    }

    @Test fun rejectsUnknownTagsAndOverflow() {
        for (input in listOf("""{"notification_type":"future"}""",
            """{"notification_type":"lightning_claim_request","amount_sat":18446744073709551615,"payment_hash":"test"}""")) {
            assertThrows(kotlinx.serialization.SerializationException::class.java) {
                json.decodeFromString<NotificationData>(input)
            }
        }
    }
}
