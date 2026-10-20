package com.noahwallet.onchain

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** All 187 RN-derived cases, read directly from apps/fixtures (also used by iOS). */
@RunWith(Parameterized::class)
class OnchainFixtureTest(private val domain: String, private val name: String, private val input: JsonObject, private val expected: JsonElement) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        @JvmStatic @Parameterized.Parameters(name = "{0}: {1}") fun cases(): List<Array<Any>> =
            listOf("exit-fee-estimate", "exit-cancellation", "exit-timeline", "exit-overview-coordination", "auto-boarding").flatMap { domain ->
                val stream = checkNotNull(OnchainFixtureTest::class.java.classLoader!!.getResourceAsStream("$domain.json"))
                val root = stream.bufferedReader().use { json.parseToJsonElement(it.readText()).jsonObject }
                root.getValue("cases").jsonArray.map { case -> case.jsonObject.let {
                    arrayOf(domain, it.getValue("name").jsonPrimitive.content, it.getValue("input").jsonObject, it.getValue("expected"))
                } }
            }
    }
    private fun n(key: String) = input.getValue(key).jsonPrimitive.long
    private fun s(key: String) = input.getValue(key).jsonPrimitive.content
    private fun b(key: String) = input.getValue(key).jsonPrimitive.boolean
    private inline fun <reified T> decode(value: JsonElement): T = json.decodeFromJsonElement<T>(value)
    @Test fun matchesRN() {
        when (domain) {
            "exit-fee-estimate" -> when (s("operation")) {
                "eligible" -> assertEquals(expected.jsonPrimitive.boolean, ExitRules.eligible(s("state"), n("amount")))
                "receive" -> assertEquals(expected.jsonPrimitive.long, ExitRules.received(n("amount"), n("claimFee")))
                "warning" -> assertEquals(expected.jsonPrimitive.contentOrNull, ExitRules.warning(n("amount"), n("claimFee"), n("totalFee"), b("claimOnly")))
                "current" -> assertEquals(expected.jsonPrimitive.boolean, decode<ExitReviewInputs>(input.getValue("inputs")).matches(decode(input.getValue("current"))) && ExitRules.current(n("reviewedAt"), n("now")))
                else -> error("Unknown fixture")
            }
            "exit-cancellation" -> assertEquals(expected.jsonPrimitive.boolean, ExitRules.cancelable(s("state"), decode<ExitDetails>(input.getValue("details")).transactions?.lastOrNull()?.status?.kind))
            "auto-boarding" -> assertEquals(expected.jsonPrimitive.longOrNull, BoardingRules.autoAmount(n("balance"), n("minimum"), n("chainFee"), n("arkFee")))
            "exit-overview-coordination" -> {
                val ready = ExitRules.ready(b("isInitialized"), b("isWalletLoaded"), b("isWalletSuspended"), b("isBackgroundJobRunning"))
                assertEquals(expected.jsonObject.getValue("enabled").jsonPrimitive.boolean, ready)
                assertEquals(expected.jsonObject.getValue("succeeds").jsonPrimitive.boolean, ready)
                assertEquals(expected.jsonObject.getValue("reads").jsonArray.isNotEmpty(), ready)
            }
            "exit-timeline" -> {
                val actual = ExitTimeline.build(decode(input.getValue("history")), decode(input.getValue("historyDetails")), s("currentState"), decode(input.getValue("currentDetails")))
                assertEquals(expected.jsonArray.size, actual.size)
                actual.zip(expected.jsonArray).forEach { (item, e) ->
                    val obj = e.jsonObject
                    assertEquals(obj.getValue("state").jsonPrimitive.content, item.state)
                    assertEquals(obj.getValue("label").jsonPrimitive.content, item.label)
                    assertEquals(obj.getValue("count").jsonPrimitive.int, item.count)
                    assertEquals(obj["startHeight"]?.jsonPrimitive?.longOrNull, item.startHeight)
                    assertEquals(obj["endHeight"]?.jsonPrimitive?.longOrNull, item.endHeight)
                    assertEquals(obj.getValue("isCurrent").jsonPrimitive.boolean, item.isCurrent)
                    if (item.state == "Canceled") assertEquals("Exit processing was canceled before completion.", item.description)
                }
            }
        }
    }
}
