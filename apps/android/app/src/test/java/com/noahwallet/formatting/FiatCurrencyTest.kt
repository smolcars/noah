package com.noahwallet.formatting

import com.noahwallet.fixtures.FixtureCase
import com.noahwallet.fixtures.SharedFixtures
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.util.Locale

@RunWith(Parameterized::class)
class FiatCurrencyTest(private val case: FixtureCase) {
    @Test fun matchesSharedFixture() {
        val input = case.input.jsonObject
        val currency = input.getValue("currency").jsonPrimitive.content
        val locale = Locale.forLanguageTag(input.getValue("locale").jsonPrimitive.content)
        val price = input["btcPrice"]?.jsonPrimitive?.double ?: 0.0
        val amount = input["amount"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
        val actual = when (input.getValue("operation").jsonPrimitive.content) {
            "fiatToSats" -> FiatCurrency.fiatToSats(amount, price).toString()
            "satsToFiat" -> FiatCurrency.display(input.getValue("sats").jsonPrimitive.long, price, currency, locale)
            "format" -> FiatCurrency.format(amount, currency, locale)
            else -> error("Unknown fixture operation")
        }
        assertEquals(case.name, case.expected.jsonPrimitive.content, actual)
    }
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{index}")
        fun fixtures() = SharedFixtures.load("fiat-currency").map { arrayOf(it) }
    }
}
