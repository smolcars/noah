package com.noahwallet.ui.home

import org.junit.Assert.*
import org.junit.Test

/** Rows map to the activity list's items (title, day headers, rows) and back, so a position can cross layouts. */
class ActivityItemsTest {
    // Title, then Today (rows 0–1), Yesterday (row 2), Oct 12 (rows 3–5).
    private val items = ActivityItems(listOf(2, 1, 3), empty = false)

    @Test fun rowsLandOnTheirOwnItemsPastTheTitleAndHeaders() {
        assertEquals(2, items.indexOfRow(0))
        assertEquals(3, items.indexOfRow(1))
        assertEquals(5, items.indexOfRow(2))
        assertEquals(7, items.indexOfRow(3))
        assertEquals(9, items.indexOfRow(5))
        assertNull(items.indexOfRow(6))
    }

    @Test fun itemsReadBackAsRowsAndHeadersStandForTheirFirstRow() {
        assertNull("the title is above the rows", items.rowAt(0, 0))
        assertEquals(0 to 0, items.rowAt(1, 40))
        assertEquals(0 to 25, items.rowAt(2, 25))
        assertEquals(2 to 0, items.rowAt(4, 10))
        assertEquals(4 to 8, items.rowAt(8, 8))
        assertNull(items.rowAt(42, 0))
        for (row in 0..5) assertEquals(row to 7, items.rowAt(items.indexOfRow(row)!!, 7))
    }

    @Test fun anEmptyListHasOnlyItsTitleAndMessage() {
        val empty = ActivityItems(emptyList(), empty = true)
        assertNull(empty.indexOfRow(0))
        assertNull(empty.rowAt(1, 0))
    }
}
