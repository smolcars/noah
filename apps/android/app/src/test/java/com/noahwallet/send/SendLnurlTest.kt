package com.noahwallet.send

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.HttpUrl
import org.junit.Assert.*
import org.junit.Test

/** A decoder-only invoice for [amount] on [network]; deliberately never submitted to the SDK. */
internal fun decoderInvoice(amount: String = "10u", network: String = "lnbcrt", timestamp: Long = System.currentTimeMillis() / 1000): String {
    val hrp = network + amount
    val words = PaymentEncoding.bech32(com.noahwallet.send.invoice())!!.words.toMutableList()
    repeat(7) { i -> words[i] = ((timestamp ushr ((6 - i) * 5)) and 31).toInt() }
    val generators = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)
    var check = 1
    for (v in hrp.map { it.code shr 5 } + 0 + hrp.map { it.code and 31 } + words + List(6) { 0 }) {
        val top = check ushr 25; check = ((check and 0x1ffffff) shl 5) xor v
        repeat(5) { i -> if ((top shr i) and 1 != 0) check = check xor generators[i] }
    }
    check = check xor 1
    val alphabet = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    return hrp + "1" + (words + (0..5).map { (check ushr (5 * (5 - it))) and 31 }).map { alphabet[it] }.joinToString("")
}

/** Decoder-only invoices; deliberately never submitted to the SDK. */
class SendLnurlTest {
    private fun invoice(amount: String = "10u", network: String = "lnbcrt", timestamp: Long = System.currentTimeMillis() / 1000) =
        decoderInvoice(amount, network, timestamp)
    @Test fun validatesExactAmountNetworkAndExpiryBeforeBOLT11Submission() {
        SendRecipientService.validateInvoice(invoice(), 1000, "regtest")
        for (value in listOf(invoice("20u"), invoice(network = "lnbc"), invoice(timestamp = 1), invoice(amount = "")))
            assertTrue(runCatching { SendRecipientService.validateInvoice(value, 1000, "regtest") }.isFailure)
    }
    @Test fun resolvesInAppWithBoundsAndComment() = runBlocking {
        var requests = 0; val callbackInvoice = invoice()
        val service = SendRecipientService("regtest", fetch = { url ->
            requests++
            if (url.encodedPath == "/callback") {
                assertEquals("1000000", url.queryParameter("amount"))
                assertEquals("note", url.queryParameter("comment"))
                assertEquals("keep", url.queryParameter("other"))
                buildJsonObject { put("pr", callbackInvoice) }
            } else buildJsonObject {
                put("tag", "payRequest"); put("callback", "https://example.com/callback?other=keep&amount=old&comment=old")
                put("minSendable", 1000); put("maxSendable", 2000000); put("commentAllowed", 10); put("metadata", "metadata"); put("ark", "unvalidated")
            }
        })
        assertEquals(callbackInvoice, service.invoice(SendPayment("alice@example.com", "lnurl", 1000, comment = "note")))
        assertEquals(2, requests)
        requests = 0
        assertTrue(runCatching { service.invoice(SendPayment("alice@example.com", "lnurl", 3000)) }.isFailure); assertEquals(1, requests)
        requests = 0
        assertTrue(runCatching { service.invoice(SendPayment("alice@example.com", "lnurl", 1000, comment = "x".repeat(11))) }.isFailure)
        assertEquals(1, requests)
    }
    @Test fun onionOrUserinfoCallbacksAreNeverRequested() = runBlocking {
        for (callback in listOf("https://abc.onion/cb", "https://ABC.ONION./cb", "https://user:pass@example.com/cb", "https://user@example.com/cb")) {
            val requests = mutableListOf<String>()
            val service = SendRecipientService("regtest", fetch = { url -> requests += url.toString()
                buildJsonObject { put("tag", "payRequest"); put("callback", callback); put("minSendable", 1000); put("maxSendable", 2000) } })
            assertTrue(callback, runCatching { service.invoice(SendPayment("alice@example.com", "lnurl", 1)) }.isFailure)
            assertEquals(callback, listOf("https://example.com/.well-known/lnurlp/alice"), requests)
        }
    }
    @Test fun malformedOrInsecureCallbacksAndUnvalidatedArkRoutesAreRejected() = runBlocking {
        for (callback in listOf("http://example.com/callback", "javascript:bad")) {
            val service = SendRecipientService("regtest", fetch = { buildJsonObject {
                put("tag", "payRequest")
                put("callback", callback)
                put("minSendable", 1)
                put("maxSendable", 1000)
            } })
            assertTrue(runCatching { service.resolve("alice@example.com") }.isFailure)
        }
        val service = SendRecipientService("regtest", fetch = { buildJsonObject {
            put("tag", "payRequest")
            put("callback", "https://example.com/callback")
            put("minSendable", 1)
            put("maxSendable", 1000)
            put("ark", "foreign")
        } })
        assertNull(service.resolve("alice@example.com").arkAddress)
    }
    /** A `bark` payment option's callback is asked for the address, without the note or payer data, and the answer is checked. */
    @Test fun paymentOptionAddressComesFromTheCallbackAndIsChecked() = runBlocking {
        for (answer in listOf("tark-ours", "foreign")) {
            val requests = mutableListOf<HttpUrl>()
            val service = SendRecipientService("regtest", fetch = { url ->
                requests += url
                if (requests.size == 1) buildJsonObject {
                    put("tag", "payRequest"); put("callback", "https://example.com/callback?token=keep"); put("minSendable", 1000)
                    put("maxSendable", 10_000_000)
                    put("paymentOptions", buildJsonArray { add(buildJsonObject { put("id", "second"); put("type", "bark") }) })
                } else buildJsonObject { put("paymentOption", "second"); put("paymentDestination", answer) }
            }, validateArk = { it == "tark-ours" })
            val route = service.resolve("alice@example.com")
            assertEquals(SendArkOption("second", 1000, 10_000_000), route.arkOption)
            val rail = route.arkRail(330) ?: error("no Ark rail")
            val address = runCatching { service.arkAddress(rail, route, 330) }
            if (answer == "tark-ours") assertEquals(answer, address.getOrNull()) else assertTrue(address.isFailure)
            assertEquals("https://example.com/callback?token=keep&amount=330000&paymentOption=second", requests[1].toString())
        }
    }
}
