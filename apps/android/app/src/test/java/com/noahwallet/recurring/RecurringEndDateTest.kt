package com.noahwallet.recurring

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.util.Calendar
import java.util.TimeZone

class RecurringEndDateTest {
    @Test fun newAndEditedEndDatesMatchRNsLastIncludedMillisecond() {
        for ((zoneID, selected, expected) in listOf(
            Triple("UTC", "2026-11-01T12:34:56.789Z", "2026-11-01T23:59:00Z"),
            Triple("America/New_York", "2026-11-01T05:30:56.789Z", "2026-11-02T04:59:00Z"),
            Triple("America/New_York", "2026-03-08T06:30:56.789Z", "2026-03-09T03:59:00Z"),
        )) {
            val zone = TimeZone.getTimeZone(zoneID)
            val end = recurringEndDate(Instant.parse(selected).toEpochMilli(), zone)
            assertEquals(Instant.parse(expected).toEpochMilli(), end)
            assertEquals(end, recurringEndDate(end + 59_999, zone))
            val start = Calendar.getInstance(zone).apply { timeInMillis = end; add(Calendar.DAY_OF_MONTH, -1) }.timeInMillis
            val schedule = RecurringPayment("rent", "Rent", "recipient", "ark", 1000,
                interval = RecurringInterval("day", 1), startAt = start, endAt = end, createdAt = 0, updatedAt = 0)
            assertEquals(end, schedule.next(1, zone))
            assertNull(schedule.copy(startAt = schedule.startAt + 1).next(1, zone))
            assertNull(schedule.next(2, zone))
        }
    }
    @Test fun persistedEndTimestampsAreNeverNormalizedOnReadOrUnrelatedWrites() {
        for (end in listOf(1793577540000L, 1793577599999L)) {
            val schedule = RecurringPayment("rent", "Rent", "recipient", "ark", 1000,
                interval = RecurringInterval("day", 1), startAt = end - 86_400_000, endAt = end, createdAt = 0, updatedAt = 0)
            var raw: String? = null
            val store = RecurringStore({ raw }, { raw = it })
            store.upsert(schedule)
            val saved = raw
            assertEquals(end, store.load().schedules.getValue("rent").endAt)
            assertEquals(saved, raw)
            store.upsert(store.load().schedules.getValue("rent").copy(status = "paused"))
            assertEquals(end, RecurringStore({ raw }, { raw = it }).load().schedules.getValue("rent").endAt)
        }
    }
}
