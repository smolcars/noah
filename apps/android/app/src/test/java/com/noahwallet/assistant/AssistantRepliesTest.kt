package com.noahwallet.assistant

import android.app.Application
import android.content.Context
import com.noahwallet.fixtures.SharedFixtures
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

/** Siri's and Gemini's English replies, from the fixture iOS tests too (apps/fixtures/assistant-replies.json). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class AssistantRepliesTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Test fun repliesMatchTheSharedFixture() {
        val cases = SharedFixtures.load("assistant-replies")
        assertEquals(14, cases.size)
        for (case in cases) {
            val input = case.input.jsonObject
            fun text(key: String) = input[key]?.jsonPrimitive?.contentOrNull
            fun sats() = input.getValue("sats").jsonPrimitive.long
            val reply = when (text("kind")) {
                "balance" -> AssistantReplies.balance(context, sats(), text("fiat"))
                "request" -> AssistantReplies.request(context, sats())
                "arkAddress" -> AssistantReplies.arkAddress(context)
                "lightningAddress" -> AssistantReplies.lightningAddress(context, text("address"))
                "lastPayment" -> AssistantReplies.lastPayment(context, text("direction")?.let {
                    AssistantReplies.Payment(it == "incoming", sats(), text("counterparty"), checkNotNull(text("when")))
                })
                "pay" -> AssistantReplies.pay(context, text("recipient"))
                else -> error("Unknown kind in ${case.name}")
            }
            assertEquals(case.name, case.expected.jsonPrimitive.content, reply)
        }
    }

    @Test fun whenIsTheLargestWholeUnitMidSentence() {
        val now = 1_800_000_000_000L
        val second = 1_000L; val hour = 3_600 * second; val day = 24 * hour
        val expected = mapOf(0L to "now", 30 * second to "30 seconds ago", 2 * hour + 59 * 60 * second to "2 hours ago",
            day + 3 * hour to "yesterday", 3 * day to "3 days ago", 8 * day to "last week", 400 * day to "last year")
        for ((ago, text) in expected) assertEquals(text, AssistantReplies.relative(now - ago, now, Locale.ENGLISH))
        // A clock behind the payment never reads as the future.
        assertEquals("now", AssistantReplies.relative(now + hour, now, Locale.ENGLISH))
    }
}
