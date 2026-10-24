package com.noahwallet.recurring

import org.junit.Assert.assertEquals
import org.junit.Test

/** Activity's Repeats › opens the payment's own schedule. */
class RecurringFocusTest {
    private fun schedule(id: String) = RecurringPayment(id, id, "rosa@noahwallet.io", "lnurl", 5_000, interval = RecurringInterval("month", 1),
        startAt = 0, createdAt = 0, updatedAt = 0)

    @Test fun repeatsShowsItsScheduleOrTheListIfItIsGone() {
        val all = listOf(schedule("rent"), schedule("savings"))
        assertEquals("savings", findSchedule(all, "savings")?.id)
        // The schedule page pops back to the list when its schedule is gone (canceled).
        assertEquals("a cancelled schedule falls back to the list", null, findSchedule(all, "gone"))
        assertEquals(listOf(com.noahwallet.ui.settings.SettingsDestination.Settings, com.noahwallet.ui.settings.SettingsDestination.Recurring,
            com.noahwallet.ui.settings.SchedulePage("savings")), com.noahwallet.ui.settings.SettingsRoutes.chain(com.noahwallet.ui.settings.SchedulePage("savings")))
    }
}
