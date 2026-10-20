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
            ("schedules" to JsonObject(schedules + ("rent" to JsonObject(schedules.getValue("rent").jsonObject + ("futureSchedule" to JsonPrimitive("kept"))))))))).toString()
        val store = RecurringStore({ raw }, { raw = it })
        val original = store.load().schedules.getValue("rent")
        store.upsert(original.copy(status = "paused", inFlight = null))
        val reopened = RecurringStore({ raw }, { raw = it })
        assertEquals("paused", reopened.load().schedules.getValue("rent").status)
        assertTrue(raw.contains("futureSchedule")); assertTrue(raw.contains("futureState")); assertTrue(raw.contains("futureEnvelope"))
        store.upsert(original.copy(lastError = "failure")); store.upsert(original)
        assertNull(store.load().schedules.getValue("rent").lastError)
    }
    @Test fun invalidCorruptAndFutureStoresNeverBecomeEmptyOrWritable() {
        for (invalid in listOf("{", "{}", fixture().replace("\"version\":0", "\"version\":2"), fixture().replace("\"amountSat\":50000", "\"amountSat\":-1"), fixture().replace("\"id\":\"rent\"", "\"id\":\"other\""))) {
            var writes = 0; val store = RecurringStore({ invalid }, { writes++ })
            assertTrue(runCatching { store.load() }.isFailure); assertTrue(runCatching { store.remove("rent") }.isFailure); assertEquals(0, writes)
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
}
