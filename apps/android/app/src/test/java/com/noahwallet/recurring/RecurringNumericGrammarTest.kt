package com.noahwallet.recurring
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
@RunWith(Parameterized::class)
class RecurringNumericGrammarTest(private val name: String, private val row: String, private val supported: Boolean) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases() =
            Json.parseToJsonElement(
                com.noahwallet.fixtures.SharedFixtures.document("recurring-rn-numeric-grammar").jsonObject.getValue("cases").toString(),
            ).jsonArray.map {
                val c = it.jsonObject
                arrayOf<Any>(c.getValue("name").jsonPrimitive.content, c.getValue("rowJSON").jsonPrimitive.content,
                    c.getValue("supported").jsonPrimitive.boolean)
            }
    }
    @Test fun exactTokens() {
        val raw = """{"version":0,"state":{"schedules":{"broken":$row},"serverSyncPending":false}}"""
        val store = RecurringStore({ raw }, {})
        assertEquals(name, supported, runCatching { !store.load().schedules.getValue("broken").unsupportedStorage }.getOrDefault(false))
    }
}
