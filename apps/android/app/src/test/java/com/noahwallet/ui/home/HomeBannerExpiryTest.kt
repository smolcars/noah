package com.noahwallet.ui.home

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class HomeBannerExpiryTest {
    @Test fun idleAndExpiredStatusesNeverStartATimer() = runBlocking {
        for (created in listOf(emptyList(), listOf(1000L))) {
            val updates = mutableListOf<Long>()
            homeBannerExpiries(created, { 6000L }, { error("idle timer") }, updates::add)
            assertEquals(listOf(6000L), updates)
        }
    }
    @Test fun backupAndBoardingExpireOnceAtTheirFiveSecondDeadlines() = runBlocking {
        var now = 2000L
        val delays = mutableListOf<Long>(); val updates = mutableListOf<Long>()
        homeBannerExpiries(listOf(1000, 2000, 1000), { now }, { delays += it; now += it }, updates::add)
        assertEquals(listOf(4000L, 1000L), delays)
        assertEquals(listOf(2000L, 6000L, 7000L), updates)
    }
    @Test fun returningAfterExpiryImmediatelyHidesSuccessWithoutATimer() = runBlocking {
        var visible = true
        homeBannerExpiries(listOf(1000), { 8000L }, { error("expired timer") }) {
            visible = HomeRules.boarding(1000, 1000, it, java.util.Locale.US) != null
        }
        assertFalse(visible)
    }
}
