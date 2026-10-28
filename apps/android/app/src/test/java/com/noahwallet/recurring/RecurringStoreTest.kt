package com.noahwallet.recurring

import com.noahwallet.send.SendIdentity
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class RecurringStoreTest {
    private fun fixture() = com.noahwallet.fixtures.SharedFixtures.document("recurring-storage").toString()
    @Test fun rnEnvelopeRoundTripsAndRetainsUnknownFields() {
        var raw = fixture()
        val root = Json.parseToJsonElement(raw).jsonObject
        val state = root.getValue("state").jsonObject
        val schedules = state.getValue("schedules").jsonObject
        raw = JsonObject(root + ("futureEnvelope" to JsonPrimitive(7)) + ("state" to JsonObject(state + ("futureState" to JsonPrimitive(true)) +
            ("schedules" to JsonObject(schedules + ("rent" to JsonObject(schedules.getValue("rent").jsonObject +
                ("futureSchedule" to JsonPrimitive("kept"))))))))).toString()
        val store = RecurringStore({ raw }, { raw = it })
        val original = store.load().schedules.getValue("rent")
        store.upsert(original.copy(status = "paused", inFlight = null))
        val reopened = RecurringStore({ raw }, { raw = it })
        assertEquals("paused", reopened.load().schedules.getValue("rent").status)
        assertTrue(raw.contains("futureSchedule")); assertTrue(raw.contains("futureState")); assertTrue(raw.contains("futureEnvelope"))
        store.upsert(original.copy(lastError = "failure")); store.upsert(original)
        assertNull(store.load().schedules.getValue("rent").lastError)
    }
    @Test fun oversizedRNPolicyStaysVisibleWithoutChangingBytesOrUnknownFields() {
        for (amount in listOf(RecurringPayment.maximumAmount + 1, Long.MAX_VALUE)) {
            val root = Json.parseToJsonElement(fixture()).jsonObject
            val state = root.getValue("state").jsonObject
            val rent = state.getValue("schedules").jsonObject.getValue("rent").jsonObject
            val oversized = JsonObject(rent + ("amountSat" to JsonPrimitive(amount)) + ("futureSchedule" to JsonPrimitive("kept")))
            var raw = JsonObject(root + ("futureEnvelope" to JsonPrimitive(7)) + ("state" to JsonObject(state +
                ("futureState" to JsonPrimitive(true)) + ("schedules" to JsonObject(mapOf("rent" to oversized)))))).toString()
            val original = raw
            val store = RecurringStore({ raw }, { raw = it })
            val schedule = store.load().schedules.getValue("rent")
            assertEquals(amount, schedule.amountSat)
            assertEquals("needs_attention", schedule.status)
            assertEquals(if (amount > 9_007_199_254_740_991L) RecurringPayment.unsupportedMessage else RecurringPayment.amountLimitMessage, schedule.lastError)
            assertEquals(original, raw)
            store.setPending(false)
            val saved = Json.parseToJsonElement(raw).jsonObject
            assertEquals(oversized, saved.getValue("state").jsonObject.getValue("schedules").jsonObject.getValue("rent"))
            assertEquals(JsonPrimitive(7), saved["futureEnvelope"])
            assertEquals(JsonPrimitive(true), saved.getValue("state").jsonObject["futureState"])
            store.upsert(schedule.copy(updatedAt = schedule.updatedAt + 1))
            assertEquals(amount, RecurringStore({ raw }, { raw = it }).load().schedules.getValue("rent").amountSat)
            assertTrue(raw.contains("futureSchedule"))
            store.remove("rent"); assertTrue(store.load().schedules.isEmpty())
        }
    }
    @Test fun invalidCorruptAndFutureStoresNeverBecomeEmptyOrWritable() {
        for (invalid in listOf("{", "{}", fixture().replace("\"version\":0", "\"version\":2"))) {
            var writes = 0; val store = RecurringStore({ invalid }, { writes++ })
            assertTrue(runCatching { store.load() }.isFailure); assertTrue(runCatching { store.remove("rent") }.isFailure); assertEquals(0, writes)
        }
    }
    @Test fun rnWriterCountsUseLongAndUnsupportedRowsRetainTheirOriginalJson() {
        val fixture = com.noahwallet.fixtures.SharedFixtures.document("recurring-rn-range").jsonObject
        var raw = fixture.toString()
        val original = raw
        val store = RecurringStore({ raw }, { raw = it })
        val schedules = store.load().schedules
        assertEquals(11, schedules.size)
        val expectations = fixture.getValue("expectedClassifications").jsonObject
        assertEquals(schedules.keys, expectations.keys)
        for ((id, expected) in expectations) {
            val row = schedules.getValue(id)
            val classification = if (row.unsupportedStorage) "stopped-unsupported" else row.status
            assertEquals("$id: ${expected.jsonObject.getValue("reason").jsonPrimitive.content}",
                expected.jsonObject.getValue("classification").jsonPrimitive.content, classification)
        }
        for ((id, count) in listOf("count-0" to 1L, "count-1" to 3_000_000_000L,
            "count-2" to 9_007_199_254_740_991L, "count-3" to 9_223_372_036_854_775_000L)) {
            val schedule = schedules.getValue(id)
            assertEquals(count, schedule.maxOccurrences)
            assertEquals("active", schedule.status)
            assertFalse(schedule.unsupportedStorage)
            assertEquals("pay", schedule.plan(1000).kind)
        }
        val unsupported = schedules.filterKeys { it !in setOf("count-0", "count-1", "count-2", "count-3") }
        assertEquals(7, unsupported.size)
        assertEquals(original, raw)
        for (schedule in unsupported.values) {
            assertEquals("needs_attention", schedule.status)
            assertEquals(RecurringPayment.unsupportedMessage, schedule.lastError)
            assertTrue(schedule.unsupportedStorage)
            assertEquals("idle", schedule.plan(1000).kind)
            assertTrue(runCatching { schedule.validateInput(1000) }.isFailure)
        }
        val before = Json.parseToJsonElement(raw).jsonObject.getValue("state").jsonObject.getValue("schedules")
        store.setPending(false)
        for (schedule in unsupported.values) store.upsert(schedule.copy(updatedAt = 123))
        val after = Json.parseToJsonElement(raw).jsonObject.getValue("state").jsonObject.getValue("schedules")
        assertEquals(before, after)
        assertEquals(11, RecurringStore({ raw }, { raw = it }).load().schedules.size)
        store.remove("count-4")
        assertEquals(10, store.load().schedules.size)
    }
    @Test fun unsupportedRowsNeverHideHealthySchedulesAndRetainUnknownFields() {
        for (replacement in listOf("\"amountSat\":-1", "\"maxOccurrences\":1e100", "\"nextOccurrenceIndex\":3000000000", "\"startAt\":1000.5")) {
            val root = Json.parseToJsonElement(fixture()).jsonObject
            val state = root.getValue("state").jsonObject
            val rent = state.getValue("schedules").jsonObject.getValue("rent").jsonObject
            val field = Json.parseToJsonElement("{$replacement}").jsonObject
            val unsupported = JsonObject(rent + field + ("unknown" to buildJsonObject { put("keep", true) }))
            var raw = JsonObject(root + ("futureEnvelope" to JsonPrimitive(7)) + ("state" to JsonObject(state +
                ("futureState" to JsonPrimitive(true)) + ("schedules" to JsonObject(mapOf("rent" to unsupported,
                    "healthy" to JsonObject(rent + ("id" to JsonPrimitive("healthy"))))))))).toString()
            val store = RecurringStore({ raw }, { raw = it })
            assertEquals("needs_attention", store.load().schedules.getValue("rent").status)
            store.upsert(store.load().schedules.getValue("rent").copy(status = "paused"))
            store.upsert(store.load().schedules.getValue("healthy").copy(status = "paused"))
            val saved = Json.parseToJsonElement(raw).jsonObject
            assertEquals(unsupported, saved.getValue("state").jsonObject.getValue("schedules").jsonObject.getValue("rent"))
            assertEquals(JsonPrimitive(7), saved["futureEnvelope"])
            assertEquals(JsonPrimitive(true), saved.getValue("state").jsonObject["futureState"])
            store.remove("rent")
            assertEquals(setOf("healthy"), store.load().schedules.keys)
        }
    }
    @Test fun stringAndOtherNonNumericVersionsPreserveBytesAndRejectEveryMutation() {
        val root = Json.parseToJsonElement(fixture()).jsonObject
        for (version in listOf(JsonPrimitive("0"), JsonPrimitive("0.0"), JsonPrimitive(false), JsonNull,
            JsonArray(emptyList()), buildJsonObject {}, JsonPrimitive(1))) {
            var raw = JsonObject(root + ("version" to version)).toString()
            val original = raw
            var writes = 0
            val store = RecurringStore({ raw }, { writes++; raw = it })
            assertTrue(runCatching { store.load() }.isFailure)
            assertTrue(runCatching { store.remove("rent") }.isFailure)
            assertTrue(runCatching { store.setPending(false) }.isFailure)
            assertEquals(0, writes); assertEquals(original, raw)
        }
        for (supported in listOf(root, JsonObject(root - "version"))) {
            val store = RecurringStore({ supported.toString() }, {})
            assertEquals(setOf("rent"), store.load().schedules.keys)
        }
    }
    @Test fun failedWriteLatchesSendingOff() {
        val store = RecurringStore({ fixture() }, { error("disk") })
        assertTrue(runCatching { store.setPending(false) }.isFailure)
        assertTrue(runCatching { store.load() }.isFailure)
    }
    @Test fun legacySchedulesAreSealedBeforeReplacement() {
        var raw: String? = fixture(); val store = RecurringStore({ raw }, { raw = it })
        store.protectLegacyBeforeReplacement()
        val previous = store.load().binding!!
        assertEquals("unverified-previous-wallet", previous.wallet.fingerprint)
        assertNotEquals(RecurringStore.Binding(SendIdentity("seed", "regtest"), UUID.randomUUID().toString()), previous)
        assertEquals(1, store.load().schedules.size)
    }
    @Test fun compareAndReplaceCannotResurrectCancelledOrPausedPolicy() {
        var raw: String? = fixture(); val store = RecurringStore({ raw }, { raw = it }); val schedule = store.load().schedules.getValue("rent")
        store.upsert(schedule.copy(status = "paused")); assertFalse(store.updateIf(schedule, schedule.copy(inFlight = RecurringPayment.InFlight(0, 0))))
        store.remove(schedule.id); assertFalse(store.updateIf(schedule, schedule)); assertTrue(store.load().schedules.isEmpty())
    }
    @Test fun longArithmeticSaturatesClosed() {
        assertFalse(RecurringFeePolicy.evaluate(Long.MAX_VALUE, "offer", 0, RecurringFeeSchedule(0, Long.MAX_VALUE, Long.MAX_VALUE)).autoPay)
        assertFalse(RecurringFeePolicy.evaluate(Long.MAX_VALUE, "offer", Long.MAX_VALUE, null).autoPay)
        assertEquals(10_500_000_000_000L, RecurringFeePolicy.evaluate(2_100_000_000_000_000, "offer", 0, null).worstCaseFeeSat)
    }
    @Test fun approvalCopyExplainsQuoteScheduleAndArkFeesLikeIos() {
        assertNull(RecurringFeePolicy.approvalReason(1000, "lightning", 20, null))
        assertEquals("The fee for this payment (41 sats) is above your automatic limit of 40 sats. Review it to pay manually.",
            RecurringFeePolicy.approvalReason(1000, "lightning", 41, null))
        val schedule = RecurringFeeSchedule(300, 0, 0)
        assertEquals("Ark's published Lightning fees for this amount can reach 300 sats, above your automatic limit of 200 sats. Review it to pay manually.",
            RecurringFeePolicy.approvalReason(1000, "lightning", 50, schedule))
        assertEquals("The fee for this payment (201 sats) is above your automatic limit of 200 sats. Review it to pay manually.",
            RecurringFeePolicy.approvalReason(1000, "lightning", 201, schedule))
        assertEquals("The fee for this Ark payment must be 0 sats to pay automatically. Review it to pay manually.",
            RecurringFeePolicy.approvalReason(1000, "ark", 1, null))
        assertNull(RecurringFeePolicy.approvalReason(1000, "ark", 0, null))
    }
}
