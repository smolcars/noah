package com.noahwallet.onchain

import org.junit.Assert.*
import org.junit.Test

/**
 * Settings → VTXOs' expiry line: dots that meet stack upwards and the line grows to fit them, so none is cut off at the top.
 * iOS's VtxoTimelineStackTests checks the same numbers.
 */
class ExpiryLineStackTest {
    /** A 353 dp line across a three-week lifetime, as the line places a dot by blocks left. */
    private fun x(left: Double) = 6 + (353 - 12) * left / 3_024

    private fun place(amounts: List<Long>, lefts: List<Double>) = ExpiryLineStack.place(amounts) { index, _ -> x(lefts[index]) }

    /** Every ring, plus the margin, fits under the top edge. */
    private fun assertFits(placement: ExpiryLineStack.Placement) {
        val room = placement.height - ExpiryLineStack.BELOW - ExpiryLineStack.MARGIN
        placement.dots.forEach { assertTrue("dot ${it.index} reaches above the margin", it.lift + it.radius + ExpiryLineStack.RING <= room + 1e-9) }
    }

    @Test fun threeExpiringTogetherAllFitAndKeepTheirPlaces() {
        val placement = place(listOf(149_750, 41_030, 149_750), listOf(100.0, 140.0, 118.0))
        val small = 3 + 7 * kotlin.math.sqrt(41_030.0 / 149_750)
        assertEquals("largest first, ties in list order", listOf(0, 2, 1), placement.dots.map { it.index })
        assertEquals("never moved sideways", listOf(x(100.0), x(118.0), x(140.0)), placement.dots.map { it.x })
        listOf(10.0, 32.5, small + 45).zip(placement.dots.map { it.lift }).forEach { (expected, lift) -> assertEquals(expected, lift, 1e-9) }
        // At today's fixed 80 the third dot's centre sat above the top edge.
        assertTrue(placement.dots[2].lift > ExpiryLineStack.MINIMUM_HEIGHT - ExpiryLineStack.BELOW)
        assertEquals(30 + small + 45 + small + 3.5 + 6, placement.height, 1e-9)
        assertFits(placement)
    }

    @Test fun oneVtxoKeepsTodaysHeight() {
        val placement = place(listOf(149_750), listOf(100.0))
        assertEquals("resting on the baseline", listOf(ExpiryLineStack.Dot(0, x(100.0), 10.0, 10.0)), placement.dots)
        assertEquals(80.0, placement.height, 0.0)
        assertFits(placement)
    }

    @Test fun manyEqualAtOneExpiryStopRisingAtTheCap() {
        val placement = place(List(12) { 10_000L }, List(12) { 100.0 })
        assertEquals((0 until 12).toList(), placement.dots.map { it.index })
        assertTrue(placement.dots.all { it.x == x(100.0) && it.radius == 10.0 })
        // Each clears the one below by 1.5 until 60 steps (90 dp) is reached; the rest wait at the cap.
        assertEquals(listOf(10.0, 32.5, 55.0, 77.5) + List(8) { 100.0 }, placement.dots.map { it.lift })
        assertEquals(30 + 100 + 10 + 3.5 + 6, placement.height, 0.0)
        assertFits(placement)
    }
}
