package com.noahwallet.send

import com.noahwallet.fixtures.SharedFixtures
import com.noahwallet.satora.SatoraQuote
import com.noahwallet.satora.SatoraSwap
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class UsdcSendTest {
    @Test fun addressFixture() {
        for (case in SharedFixtures.load("usdc-address")) {
            val input = case.input.jsonObject
            val parsed = SendDestination.parse(input.getValue("destination").jsonPrimitive.content, input.getValue("network").jsonPrimitive.content)
            assertEquals(case.name, case.expected.jsonObject["destinationType"]?.jsonPrimitive?.contentOrNull, parsed.destinationType)
        }
    }

    @Test fun keccakMatchesEthereum() {
        val hex = UsdcAddress.keccak256(ByteArray(0)).joinToString("") { "%02x".format(it) }
        assertEquals("c5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470", hex)
    }

    @Test fun amountFixture() {
        for (case in SharedFixtures.load("usdc-amount")) {
            val input = case.input.jsonObject
            fun long(key: String) = input.getValue(key).jsonPrimitive.long
            assertEquals(case.name, case.expected.jsonObject["error"]?.jsonPrimitive?.contentOrNull,
                UsdcSwaps.reviewError(long("sats"), long("total"), long("spendable"), long("min"), long("max")))
        }
    }

    @Test fun fineprintFloorsToCents() {
        assertEquals("5.83" to "73", UsdcSwaps.fineprint(quote("5839905")))
        assertEquals("0.00" to "73", UsdcSwaps.fineprint(quote("9999")))
        assertEquals("1234.50" to "73", UsdcSwaps.fineprint(quote("1234500000")))
    }

    @Test fun payoutFixture() {
        for (case in SharedFixtures.load("usdc-payout")) {
            val input = case.input.jsonObject
            assertEquals(case.name, case.expected.jsonObject.getValue("accepted").jsonPrimitive.boolean,
                UsdcSwaps.accepts(input.getValue("swapUsdc").jsonPrimitive.content, input.getValue("reviewedUsdc").jsonPrimitive.content))
        }
    }

    @Test fun nothingIsSubmittedForASwapThatPaysLessThanReviewed() = runBlocking {
        val swaps = FakeSatora(listOf(swap("a", invoice = invoice1500).copy(targetUsdc = "900000")))
        val failure = runCatching { UsdcSwaps.start(swaps, 1500, address, "1000000") { fail("submitted"); true } }.exceptionOrNull() as? UsdcFailure
        assertEquals(UsdcSwaps.rateChanged, failure?.message)
        assertEquals("0.90", failure?.argument)
        assertEquals("900000", failure?.usdc)
    }

    @Test fun swapIsCreatedBeforeItsInvoiceIsSubmitted() = runBlocking {
        val swaps = FakeSatora(listOf(swap("a", invoice = invoice1500)))
        var submitted: SendPayment? = null
        val swap = UsdcSwaps.start(swaps, 1500, address, "0") { payment ->
            assertEquals(listOf("create 1500 $address"), swaps.calls)
            submitted = payment; true
        }
        assertEquals("a", swap.id)
        assertEquals(SendPayment(invoice1500, "lightning", 1500), submitted)
    }

    @Test fun nothingIsSubmittedForAnInvoiceOfAnotherAmount() = runBlocking {
        val swaps = FakeSatora(listOf(swap("a", invoice = invoice1500)))
        val failure = runCatching { UsdcSwaps.start(swaps, 1501, address, "0") { fail("submitted"); true } }.exceptionOrNull()
        assertEquals(UsdcSwaps.notStarted, failure?.message)
    }

    @Test fun foregroundPassFinishesOpenSwaps() = runBlocking {
        val swaps = FakeSatora(listOf(swap("funded", "serverfunded"), swap("ended", "expired", terminal = true),
            swap("claimed", "serverfunded", claimTx = "0xabc"), swap("pending", "pending")))
        UsdcSwaps.finishOpen(swaps) { fail(it) }
        assertEquals(listOf("refresh funded", "claim funded", "refresh pending"), swaps.calls)
    }

    private class FakeSatora(private val swaps: List<SatoraSwap>) : SatoraSwaps {
        val calls = mutableListOf<String>()
        override fun mnemonic() = error("unused")
        override suspend fun quote(sats: ULong) = error("unused")
        override suspend fun createSwap(sats: ULong, destination: String) = swaps.first().also { calls += "create $sats $destination" }
        override suspend fun refreshSwap(id: String) = swaps.first { it.id == id }.also { calls += "refresh $id" }
        override fun listSwaps() = swaps
        override suspend fun claim(id: String): String? { calls += "claim $id"; return "0xtx" }
    }

    private companion object {
        const val address = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"
        // 15u: 1,500 sats (apps/fixtures/send-utils.json).
        const val invoice1500 = "lnbc15u1p3xnhl2pp5jptserfk3zk4qy42tlucycrfwxhydvlemu9pqr93tuzlv9cc7g3sdqsvfhkcap3xyhx7un8cqzpgxqzjcsp5" +
            "f8c52y2stc300gl6s4xswtjpc37hrnnr3c9wvtgjfuvqmpm35evq9qyyssqy4lgd8tj637qcjp05rdpxxykjenthxftej7a2zzmwrmrl70fyj9hvj0rewhzj7jfyuwk" +
            "wcg9g2jpwtk3wkjtwnkdks84hsnu8xps5vsq4gj5hs"
        fun quote(net: String) = SatoraQuote(50u, 0.0025, 20u, 3u, "100000", net, net, 356u, 20_000_000u)
        fun swap(id: String, status: String = "pending", terminal: Boolean = false, claimTx: String? = null, invoice: String = "") =
            SatoraSwap(id, status, terminal, invoice, "1500", "0", 0, address, "2026-11-20T00:00:00Z", null, 0, null, claimTx, null, false)
    }
}
