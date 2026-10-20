package com.noahwallet.recurring

import org.junit.Assert.*
import org.junit.Test

class RecurringEditorTest {
    @Test fun customIntervalDefaultsToFourteenDaysLikeRnAndIos() {
        assertEquals("14", recurringDefaultEvery("day"))
        assertEquals("1", recurringDefaultEvery("week"))
        assertEquals("1", recurringDefaultEvery("month"))
        assertTrue(RecurringInterval("day", recurringDefaultEvery("day").toInt()).valid)
    }
}
