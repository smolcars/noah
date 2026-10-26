package com.noahwallet.recurring

import org.junit.Assert.*
import org.junit.Test

class RecurringEditorTest {
    @Test fun amountsStayAsTypedAndOnlyWholePositiveSatsSave() {
        val now = 1_700_000_000_000L
        fun draft(sats: Long) = RecurringPayment("id", "Rent", "a@b.c", "lnurl", sats, interval = RecurringInterval("month", 1),
            startAt = now + 86_400_000, createdAt = now, updatedAt = now)
        assertEquals(1_000L, recurringAmount(" 1000 "))
        draft(recurringAmount("1000")).validateInput(now)
        // Pasted or typed amounts aren't rewritten into other numbers ("1.5" was 15, "-1000" was 1000): they don't save.
        for (typed in listOf("1.5", "-1000", "1,000", "1e3", "0", "")) {
            assertEquals(typed, 0L, recurringAmount(typed))
            assertEquals(typed, "Enter a whole amount in sats, up to 21 million BTC.", runCatching { draft(recurringAmount(typed)).validateInput(now) }.exceptionOrNull()?.message)
        }
    }
}
