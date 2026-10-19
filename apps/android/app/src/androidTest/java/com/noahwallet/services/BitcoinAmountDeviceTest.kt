package com.noahwallet.services

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.noahwallet.formatting.BitcoinAmount
import com.noahwallet.formatting.BitcoinAmountUnit
import java.util.Locale
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** java.text on the host JVM is not evidence for Android ICU grouping. */
@RunWith(AndroidJUnit4::class)
class BitcoinAmountDeviceTest {
    @Test fun allSeventySharedCasesMatchAndroidIcu() {
        val cases = InstrumentationRegistry.getInstrumentation().context.assets.open("bitcoin-amount.json")
            .bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject.getValue("cases").jsonArray }
        assertEquals(70, cases.size)
        val failures = cases.mapNotNull { element ->
            val case = element.jsonObject
            val input = case.getValue("input").jsonObject
            val sats = input.getValue("sats").jsonPrimitive.long
            val unit = BitcoinAmountUnit.valueOf(input.getValue("unit").jsonPrimitive.content)
            val locale = Locale.forLanguageTag(input.getValue("locale").jsonPrimitive.content)
            val actual = BitcoinAmount.format(sats, unit, locale)
            val legacy = BitcoinAmount.legacyNumber(sats, locale)
            val legacyAmount = if (unit == BitcoinAmountUnit.bip177) "₿\u00a0$legacy" else "$legacy sats"
            val expected = case.getValue("expected").jsonPrimitive.content
            if (actual == expected && legacyAmount == expected) null
            else "${case.getValue("name")}: expected=$expected actual=$actual legacy=$legacyAmount"
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
