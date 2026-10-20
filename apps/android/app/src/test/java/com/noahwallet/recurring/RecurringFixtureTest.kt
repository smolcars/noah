package com.noahwallet.recurring

import com.noahwallet.fixtures.SharedFixtures
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.util.TimeZone

@RunWith(Parameterized::class)
class RecurringFixtureTest(private val name: String, private val domain: String, private val input: JsonElement, private val expected: JsonElement) {
    @Test fun sharedContract() {
        val i = input.jsonObject
        val json = RecurringStore.json
        when (domain) {
            "recurring-schedule", "recurring-zone-change" -> {
                val interval = json.decodeFromJsonElement<RecurringInterval>(i.getValue("interval"))
                val schedule = RecurringPayment("id", "Name", "address", "ark", 1000, interval = interval,
                    startAt = i.getValue("startAt").jsonPrimitive.long, createdAt = 0, updatedAt = 0)
                val zone = TimeZone.getTimeZone(i["zone"]?.jsonPrimitive?.content ?: i.getValue("toZone").jsonPrimitive.content)
                assertEquals(expected.jsonPrimitive.long, schedule.occurrence(i.getValue("index").jsonPrimitive.int, zone))
            }
            "recurring-policy" -> {
                val schedule = json.decodeFromJsonElement<RecurringPayment>(i.getValue("schedule"))
                val plan = schedule.plan(i.getValue("now").jsonPrimitive.long, TimeZone.getTimeZone(i.getValue("zone").jsonPrimitive.content))
                val actual = JsonObject(json.encodeToJsonElement(plan).jsonObject.filterValues { it != JsonNull })
                assertEquals(expected, actual)
            }
            "recurring-fee-cap" -> {
                val schedule = i["schedule"]?.takeUnless { it == JsonNull }?.jsonObject?.let { s ->
                    RecurringFeeSchedule(s.getValue("minFeeSats").jsonPrimitive.long, s.getValue("baseFeeSats").jsonPrimitive.long,
                        s.getValue("ppmExpiryTable").jsonArray.maxOfOrNull { it.jsonObject.getValue("ppm").jsonPrimitive.long } ?: 0)
                }
                val d = RecurringFeePolicy.evaluate(i.getValue("amountSat").jsonPrimitive.long, i.getValue("method").jsonPrimitive.content, i.getValue("quoteFeeSat").jsonPrimitive.long, schedule)
                assertEquals(expected, buildJsonObject { put("worstCaseFeeSat", d.worstCaseFeeSat?.let(::JsonPrimitive) ?: JsonNull); put("capSat", d.capSat?.let(::JsonPrimitive) ?: JsonNull); put("autoPay", d.autoPay); put("reason", d.reason) })
            }
        }
    }
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}") fun cases() = listOf("recurring-schedule", "recurring-policy", "recurring-zone-change", "recurring-fee-cap").flatMap { domain ->
            SharedFixtures.load(domain).map { arrayOf(it.name, domain, it.input, it.expected) }
        }
    }
}
