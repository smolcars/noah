package com.noahwallet.ui.home

import org.junit.Assert.*
import org.junit.Test

/** The first payment's timeline: the prototype's springs, or under Reduce Motion fades only. */
class ReceiptTest {
    @Test fun reduceMotionFadesInTheCompletedRingAndCheck() {
        val early = Receipt.at(60, reduceMotion = true)
        assertEquals("the ring is complete, never drawn", 1f, early.ring)
        assertEquals("the check is complete, never drawn", 1f, early.check)
        assertTrue("revealed by a fade", early.gotIn in 0.2f..0.5f)
        val shown = Receipt.at(180, reduceMotion = true)
        assertEquals(1f, shown.gotIn); assertEquals(1f, shown.qrOut); assertEquals(1f, shown.amountIn)
        assertEquals("no fold while the receipt shows", 0f, Receipt.at(2_399, reduceMotion = true).folding)
    }

    @Test fun reduceMotionThenFadesTheCardOutOnItsOwn() {
        assertEquals(0f, Receipt.at(2_400, reduceMotion = true).folding)
        val half = Receipt.at(2_490, reduceMotion = true)
        assertEquals(.5f, half.folding, .01f)
        assertFalse("it fades without collapsing or shrinking", half.collapse)
        assertEquals(1f, Receipt.at(2_580, reduceMotion = true).folding)
        assertEquals(2_580L, Receipt.end(reduceMotion = true))
    }

    @Test fun fullMotionDrawsTheRingAndCheckAndFoldsTheCard() {
        assertTrue("the ring is still drawing", Receipt.at(200, reduceMotion = false).ring < .5f)
        assertEquals(0f, Receipt.at(600, reduceMotion = false).check)
        assertEquals(1f, Receipt.at(1_200, reduceMotion = false).ring)
        assertEquals(1f, Receipt.at(1_200, reduceMotion = false).check)
        val folding = Receipt.at(2_600, reduceMotion = false)
        assertTrue(folding.collapse); assertTrue(folding.folding in 0.01f..0.99f)
        assertEquals(1f, Receipt.at(2_920, reduceMotion = false).folding)
        assertEquals(2_920L, Receipt.end(reduceMotion = false))
    }
}
