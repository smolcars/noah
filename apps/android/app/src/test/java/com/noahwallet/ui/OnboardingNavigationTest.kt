package com.noahwallet.ui

import org.junit.Assert.*
import org.junit.Test

class OnboardingNavigationTest {
    @Test fun laterStepsReturnThroughVisitedScreens() {
        val history = listOf("welcome", "creating", "name", "email", "notifications")
        assertEquals(listOf("welcome", "creating", "name", "email"), OnboardingNavigation.back(history, false, true))
        assertEquals(listOf("welcome", "creating", "name"), OnboardingNavigation.back(history.dropLast(1), false, true))
        assertEquals(history, OnboardingNavigation.back(OnboardingNavigation.visit(history, "battery"), false, true))
    }

    @Test fun backNeverUndoesCreationRestoreOrDone() {
        // Name is the first step after the wallet exists; back there must not reach Welcome or Creating.
        for (boundary in listOf("creating", "name", "restoring", "done")) {
            val history = listOf("welcome", "restore", "creating", "name", "email", "notifications", "done").takeWhile { it != boundary } + boundary
            assertEquals(boundary, history, OnboardingNavigation.back(history, false, true))
        }
        assertEquals(listOf("welcome"), OnboardingNavigation.back(listOf("welcome", "restore"), false, true))
        assertEquals(listOf("notifications"), OnboardingNavigation.back(listOf("notifications", "battery"), false, true))
    }

    @Test fun busyAndRestoreInFlightBlockBack() {
        val history = listOf("name", "email")
        assertEquals(history, OnboardingNavigation.back(history, true, true))
        assertEquals(history, OnboardingNavigation.back(history, false, false))
        assertEquals(history, OnboardingNavigation.visit(history, "email"))
        assertEquals(listOf("welcome"), OnboardingNavigation.back(listOf("welcome"), false, true))
    }

    @Test fun interruptedOnboardingResumesAtName() {
        assertEquals("name", OnboardingNavigation.resumeStep)
    }
}
