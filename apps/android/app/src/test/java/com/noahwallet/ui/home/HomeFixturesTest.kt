package com.noahwallet.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/** Home's fixtures list each day once at any hour, as production's sorted history does, so the fold tests don't depend on the clock. */
class HomeFixturesTest {
    @Test fun everyDayAppearsOnceWhateverTheHour() {
        val previous = TimeZone.getDefault()
        try {
            for (zone in listOf("America/New_York", "UTC", "Asia/Tokyo")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone))
                for (hour in 0..23) for (state in listOf("everyday", "busy")) {
                    val now = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 23, hour, 30, 0) }.time
                    val ui = HomeFixtures.ui(state, now = now)
                    val days = HomeDays.grouped(ui.rows.map { HomeRow.of(it, ui.domain) }, { it.date }, now).map { it.first }
                    assertEquals("$state at $hour:30 in $zone", days.distinct(), days)
                }
            }
        } finally { TimeZone.setDefault(previous) }
    }
}
