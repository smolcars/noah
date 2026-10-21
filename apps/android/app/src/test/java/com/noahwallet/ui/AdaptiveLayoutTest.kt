package com.noahwallet.ui

import org.junit.Assert.*
import org.junit.Test

class AdaptiveLayoutTest {
    @Test fun navigationUsesTheCurrentWindowRatherThanDeviceType() {
        assertFalse(AdaptiveLayout.useRail(599f, 900f))
        assertTrue(AdaptiveLayout.useRail(600f, 900f))
        assertTrue(AdaptiveLayout.useRail(915f, 340f))
        assertFalse(AdaptiveLayout.useRail(380f, 700f))
    }
    @Test fun detailsRequireTwoReadablePanesIncludingLargerText() {
        assertFalse(AdaptiveLayout.useDetailPane(599f, 900f))
        assertTrue(AdaptiveLayout.useDetailPane(600f, 900f))
        assertFalse(AdaptiveLayout.useDetailPane(700f, 900f, 1.5f))
        assertTrue(AdaptiveLayout.useDetailPane(864f, 900f, 1.5f))
    }
    @Test fun qrKeepsAReadableSquareWithoutUsingTheWholeShortWindow() {
        assertEquals(302f, AdaptiveLayout.qrSide(800f))
        assertEquals(172f, AdaptiveLayout.qrSide(300f))
        assertEquals(128f, AdaptiveLayout.qrSide(150f))
    }
    @Test fun separatingHingesLeaveControlsOnOneUnobscuredPane() {
        assertEquals(WindowRegion(0, 0, 490, 800), usableRegion(1000, 800, listOf(WindowRegion(490, 0, 510, 800))))
        assertEquals(WindowRegion(0, 410, 600, 1000), usableRegion(600, 1000, listOf(WindowRegion(0, 390, 600, 410))))
        assertEquals(WindowRegion(0, 0, 500, 800), usableRegion(1000, 800, listOf(WindowRegion(500, 0, 500, 800))))
    }
    @Test fun aHingeAtTheInsetBoundaryStillReservesItsOccludedBand() {
        assertEquals(WindowRegion(20, 0, 400, 800), usableRegion(400, 800, listOf(WindowRegion(-10, -24, 20, 900))))
        assertEquals(WindowRegion(0, 20, 400, 800), usableRegion(400, 800, listOf(WindowRegion(-10, -10, 500, 20))))
    }
    @Test fun unrelatedOrExternalFeaturesDoNotChangeTheWindow() {
        val whole = WindowRegion(0, 0, 400, 800)
        assertEquals(whole, usableRegion(400, 800, emptyList()))
        assertEquals(whole, usableRegion(400, 800, listOf(WindowRegion(500, 0, 520, 800))))
        assertEquals(whole, usableRegion(400, 800, listOf(WindowRegion(190, 300, 210, 400))))
    }
}
