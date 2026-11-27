package com.noahwallet.services.push

import android.app.Application
import android.util.Base64
import com.noahwallet.fixtures.SharedFixtures
import com.noahwallet.formatting.BitcoinAmountUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The payer data an LNURL-pay push carries: the server's sealed fixtures, and the notification they make. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class LnurlPayPushTest {
    private val fixture = SharedFixtures.document("lnurl-pay-receive-notification").jsonObject
    private val key = Base64.decode(fixture.getValue("key").jsonPrimitive.content, Base64.DEFAULT)

    @Test fun opensTheServersSealedFixtures() {
        for (case in fixture.getValue("cases").jsonArray.map { it.jsonObject }) {
            val name = case.getValue("name").jsonPrimitive.content
            val expected = case.getValue("expected").takeUnless { it is JsonNull }
                ?.let { Json.decodeFromJsonElement<LnurlPayPush.Content>(it) }
            val opened = LnurlPayPush.open(case.getValue("id").jsonPrimitive.content, case.getValue("sealed").jsonPrimitive.content, key)
            assertEquals(name, expected, opened)
        }
    }

    @Test fun readsTheSealedFieldFromThePushData() {
        val case = fixture.getValue("cases").jsonArray.first().jsonObject
        val data = buildJsonObject {
            put("notification_type", "ark_payment_received")
            putJsonObject("lnurl_pay_receive") {
                put("v", 1)
                put("id", case.getValue("id").jsonPrimitive.content)
                put("sealed", case.getValue("sealed").jsonPrimitive.content)
            }
        }.toString()
        assertEquals("Alice Payer", LnurlPayPush.open(data, key)?.payerData?.name)
        // No key registered, another key, an unknown version, or no field: the server's own text shows.
        assertNull(LnurlPayPush.open(data, null))
        assertNull(LnurlPayPush.open(data, ByteArray(32) { 1 }))
        assertNull(LnurlPayPush.open(data.replace("\"v\":1", "\"v\":2"), key))
        assertNull(LnurlPayPush.open("""{"notification_type":"ark_payment_received"}""", key))
        assertNull(LnurlPayPush.open("not json", key))
    }

    @Test fun namesThePayerAsTheTitleWithTheirComment() {
        val context = RuntimeEnvironment.getApplication()
        fun copy(name: String?, identifier: String?, comment: String?) = LnurlPayPush.copy(context,
            LnurlPayPush.Content(1_000, LnurlPayPush.PayerData(name, identifier).takeIf { name != null || identifier != null },
                comment), BitcoinAmountUnit.sats)

        val full = copy("Alice", "alice@example.com", "Thanks")
        assertEquals("Alice (⚡ alice@example.com)", full.title)
        assertEquals(listOf("Thanks"), full.body.lines().drop(1))
        assertEquals("alice@example.com", copy(null, "alice@example.com", null).title)
        assertEquals(1, copy("Alice", null, null).body.lines().size)
        // Nobody named: the usual title, and the comment still shows.
        val anonymous = copy(null, null, "From the meetup")
        assertEquals("From the meetup", anonymous.body.lines().last())
    }
}
