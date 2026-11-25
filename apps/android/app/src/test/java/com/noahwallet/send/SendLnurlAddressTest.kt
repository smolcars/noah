package com.noahwallet.send

import com.noahwallet.fixtures.FixtureCase
import com.noahwallet.fixtures.SharedFixtures
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** An LNURL to LUD-16's well-known path reads back as the Lightning address it stands for (Noah's own QR codes). */
@RunWith(Parameterized::class)
class SendLnurlAddressTest(private val case: FixtureCase) {
    @Test fun matchesSharedFixture() {
        val value = case.input.jsonObject.getValue("value").jsonPrimitive.content
        assertEquals(case.name, case.expected.jsonObject.getValue("cleaned").jsonPrimitive.content, SendDestination.cleaned(value))
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{index}")
        fun fixtures() = SharedFixtures.load("send-lnurl-address").map { arrayOf(it) }
    }
}
