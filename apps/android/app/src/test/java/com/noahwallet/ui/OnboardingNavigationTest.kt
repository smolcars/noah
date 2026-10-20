package com.noahwallet.ui

import org.junit.Assert.*
import org.junit.Test

class OnboardingNavigationTest {
    @Test fun emailAndLightningReturnThroughVisitedScreens() {
        for (middle in listOf(emptyList(), listOf("battery"), listOf("unified-push", "battery"))) {
            var history = listOf("welcome", "beta", "mnemonic", "email")
            assertEquals("mnemonic", OnboardingNavigation.back(history, false, true).last())
            for (step in middle + "lightning") history = OnboardingNavigation.visit(history, step)
            assertEquals(middle.lastOrNull() ?: "email", OnboardingNavigation.back(history, false, true).last())
        }
    }

    @Test fun unifiedPushBackReturnsToEmailAndBatteryBackDoesNotAdvance() {
        val history = listOf("mnemonic", "email", "unified-push", "battery")
        val fromBattery = OnboardingNavigation.back(history, false, true)
        assertEquals(listOf("mnemonic", "email", "unified-push"), fromBattery)
        assertEquals(listOf("mnemonic", "email"), OnboardingNavigation.back(fromBattery, false, true))
        assertEquals(listOf("mnemonic", "email"), OnboardingNavigation.back(listOf("mnemonic", "email", "battery"), false, true))
    }

    @Test fun busyRestoreAndRecoveryPhraseBoundariesStillHold() {
        val history = listOf("welcome", "beta", "mnemonic", "email")
        assertEquals(history, OnboardingNavigation.back(history, true, true))
        assertEquals(history, OnboardingNavigation.back(history, false, false))
        val mnemonic = history.dropLast(1)
        assertEquals(mnemonic, OnboardingNavigation.back(mnemonic, false, true))
        assertEquals(listOf("welcome"), OnboardingNavigation.back(listOf("welcome", "restore"), false, true))
        assertEquals(listOf("welcome"), OnboardingNavigation.back(listOf("welcome", "beta"), false, true))
        assertEquals(history, OnboardingNavigation.visit(history, "email"))
        assertEquals(listOf("mnemonic"), OnboardingNavigation.back(listOf("mnemonic"), false, true))
    }
}
