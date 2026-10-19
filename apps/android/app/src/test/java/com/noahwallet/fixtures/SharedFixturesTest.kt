package com.noahwallet.fixtures

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class SharedFixturesTest {
    @Test
    fun loadsSharedJsonWithoutLosingNestedValues() {
        val case = SharedFixtures.load("scaffold").single()
        assertEquals("nested JSON values survive loading", case.name)
        assertEquals("regtest", case.input.jsonObject.getValue("variant").jsonPrimitive.content)
        assertEquals(case.name, case.expected, case.input)
    }

    @Test(expected = IllegalArgumentException::class)
    fun missingDomainFailsClearly() {
        SharedFixtures.load("does-not-exist")
    }
}
