package com.noahwallet.ui.launch

import com.noahwallet.fixtures.SharedFixtures
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PlatformGateRulesTest {
    @Test fun seedSafetyPrecedesPlatformGatesEvenWithStaleOpenState() {
        val states = com.noahwallet.services.launch.LaunchState.entries
        states.forEach { state ->
            assertEquals(state.name, state in setOf(com.noahwallet.services.launch.LaunchState.existingWallet,
                com.noahwallet.services.launch.LaunchState.onboarding), PlatformGateRules.launchResolved(state))
        }
        assertFalse(PlatformGateRules.launchResolved(null))
    }
    @Test fun matchesRNTruthTable() {
        SharedFixtures.load("platform-gates").forEach { case ->
            val input = case.input.jsonObject
            fun flag(key: String) = input.getValue(key).jsonPrimitive.boolean
            assertEquals(case.name, case.expected.jsonPrimitive.contentOrNull, PlatformGateRules.gate(
                flag("initialized"), flag("physical"), PlatformGateRules.Permission.valueOf(input.getValue("permission").jsonPrimitive.content),
                flag("batteryEnabled"), flag("batterySeen"))?.name)
        }
    }
}
