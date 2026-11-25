package com.noahwallet.recurring

import com.noahwallet.fixtures.FixtureCase
import com.noahwallet.fixtures.SharedFixtures
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** What a recurring schedule stores for a recipient, from the fixture iOS runs too. */
@RunWith(Parameterized::class)
class RecurringRecipientTest(private val case: FixtureCase) {
    @Test fun matchesSharedFixture() {
        val input = case.input.jsonObject
        val expected = case.expected.jsonObject
        val resolved = RecurringRecipient.resolve(input.getValue("recipient").jsonPrimitive.content, input.getValue("network").jsonPrimitive.content)
        assertEquals(case.name, expected.getValue("destination").jsonPrimitive.content to expected.getValue("type").jsonPrimitive.content, resolved)
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{index}")
        fun fixtures() = SharedFixtures.load("recurring-recipient").map { arrayOf(it) }
    }
}
