package com.noahwallet.recurring

import org.junit.Assert.assertEquals
import org.junit.Test

/** Activity's Repeats › opens the payment's own schedule. */
class RecurringFocusTest {
    private fun schedule(id: String) = RecurringPayment(id, id, "rosa@noahwallet.io", "lnurl", 5_000, interval = RecurringInterval("month", 1),
        startAt = 0, createdAt = 0, updatedAt = 0)

    @Test fun repeatsShowsItsScheduleOrTheListIfItIsGone() {
        val all = listOf(schedule("rent"), schedule("savings"))
        assertEquals(listOf("savings"), focusedSchedules(all, "savings").map { it.id })
        assertEquals("a cancelled schedule falls back to the list", listOf("rent", "savings"), focusedSchedules(all, "gone").map { it.id })
        assertEquals(listOf("rent", "savings"), focusedSchedules(all, null).map { it.id })
    }
}
