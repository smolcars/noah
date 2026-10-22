package com.noahwallet.ui.home

import org.junit.Assert.*
import org.junit.Test

/** Transactions map to the activity list's items (title, day headers, rows) and back, so a position can cross layouts. */
class ActivityItemsTest {
    // Title, then Today (a, b), Yesterday (c), Oct 12 (d, e, f).
    private val items = ActivityItems(listOf(listOf("a", "b"), listOf("c"), listOf("d", "e", "f")), empty = false)

    @Test fun transactionsLandOnTheirOwnItemsPastTheTitleAndHeaders() {
        assertEquals(2, items.indexOf("a"))
        assertEquals(3, items.indexOf("b"))
        assertEquals(5, items.indexOf("c"))
        assertEquals(7, items.indexOf("d"))
        assertEquals(9, items.indexOf("f"))
        assertNull(items.indexOf("gone"))
        assertEquals(listOf("a", "b", "c", "d", "e", "f"), items.ids)
    }

    @Test fun itemsReadBackAsTransactionsAndHeadersStandForTheirFirst() {
        assertNull("the title is above the rows", items.rowAt(0, 0))
        assertEquals("a" to 0, items.rowAt(1, 40))
        assertEquals("a" to 25, items.rowAt(2, 25))
        assertEquals("c" to 0, items.rowAt(4, 10))
        assertEquals("e" to 8, items.rowAt(8, 8))
        assertNull(items.rowAt(42, 0))
        for (id in items.ids) assertEquals(id to 7, items.rowAt(items.indexOf(id)!!, 7))
    }

    @Test fun anEmptyListHasOnlyItsTitleAndMessage() {
        val empty = ActivityItems(emptyList(), empty = true)
        assertNull(empty.indexOf("a"))
        assertNull(empty.rowAt(1, 0))
        assertTrue(empty.ids.isEmpty())
    }
}
