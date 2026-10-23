package com.noahwallet.ui.receive

import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.noahwallet.history.HistoryTransaction
import com.noahwallet.services.wallet.ReceiveRequest
import org.junit.Assert.*
import org.junit.Test

/** Each format shows, copies and shares exactly its own payload; arrivals come only from new records. */
class ReceiveFormatsTest {
    private val address = "maya@localhost.com"
    private val plain = ReceiveRequest("tark1qpayme", "bcrt1qpayme")
    private val withAmount = plain.copy(amountSat = 2_100, description = "coffee", lightningInvoice = "lnbcrt21u1pinvoice", paymentHash = "hash")

    @Test fun eachFormatEncodesExactlyItself() {
        assertEquals(address, ReceiveFormats.payload(ReceiveFormat.lightningAddress, plain, address))
        assertEquals("bitcoin:bcrt1qpayme?ark=tark1qpayme", ReceiveFormats.payload(ReceiveFormat.request, plain, address))
        assertEquals("bitcoin:bcrt1qpayme?amount=0.000021&lightning=lnbcrt21u1pinvoice&ark=tark1qpayme", ReceiveFormats.payload(ReceiveFormat.request, withAmount, address))
        assertEquals(withAmount.uri(), ReceiveFormats.payload(ReceiveFormat.request, withAmount, address))
        assertEquals("lnbcrt21u1pinvoice", ReceiveFormats.payload(ReceiveFormat.invoice, withAmount, address))
        assertEquals("tark1qpayme", ReceiveFormats.payload(ReceiveFormat.ark, withAmount, address))
        assertEquals("bcrt1qpayme", ReceiveFormats.payload(ReceiveFormat.onchain, withAmount, address))
        // No invoice without an amount; no address QR without an address; nothing before the request exists.
        assertNull(ReceiveFormats.payload(ReceiveFormat.invoice, plain, address))
        assertNull(ReceiveFormats.payload(ReceiveFormat.lightningAddress, plain, null))
        assertNull(ReceiveFormats.payload(ReceiveFormat.lightningAddress, plain, ""))
        assertNull(ReceiveFormats.payload(ReceiveFormat.ark, null, address))
        assertEquals(address, ReceiveFormats.payload(ReceiveFormat.lightningAddress, null, address))
    }

    @Test fun longPayloadsUseMediumCorrectionAndKeepTheLogo() {
        assertEquals(ErrorCorrectionLevel.H, ReceiveFormats.correction(address))
        assertEquals(ErrorCorrectionLevel.M, ReceiveFormats.correction("x".repeat(91)))
        for (format in ReceiveFormat.entries) {
            val payload = ReceiveFormats.payload(format, withAmount, address) ?: continue
            assertNotNull("$format encodes", com.noahwallet.ui.home.StyledQRModel.encode(payload, ReceiveFormats.correction(payload)))
        }
    }

    @Test fun amountsSwitchTheShownFormat() {
        assertEquals(ReceiveFormat.request, ReceiveFormats.afterAmount(ReceiveFormat.lightningAddress, true))
        assertEquals(ReceiveFormat.request, ReceiveFormats.afterAmount(ReceiveFormat.ark, true))
        assertEquals(ReceiveFormat.lightningAddress, ReceiveFormats.afterAmount(ReceiveFormat.request, false))
        assertEquals(ReceiveFormat.lightningAddress, ReceiveFormats.afterAmount(ReceiveFormat.invoice, false))
        assertEquals(ReceiveFormat.onchain, ReceiveFormats.afterAmount(ReceiveFormat.onchain, false))
        assertEquals("tark1qpayme", ReceiveFormats.preview("tark1qpayme"))
        val long = "bitcoin:bcrt1qpayme?amount=1&lightning=lnbc&ark=tark1q"
        assertEquals(long.take(16) + "…" + long.takeLast(10), ReceiveFormats.preview(long))
    }

    private fun chain(id: String, sats: Long, direction: String = "incoming", confirmed: Boolean = false) = HistoryTransaction("onchain-wallet-$id", "Onchain", sats,
        "2026-10-22T10:00:00Z", direction, source = "onchain-wallet", txid = id, txHex = "hex-$id", balanceChangeSat = if (direction == "incoming") sats else -sats,
        hasConfirmation = confirmed)

    @Test fun onchainArrivalsAreNewIncomingTransactionsPreferringThisAddress() {
        val old = chain("old", 5_000)
        val other = chain("other", 7_000)
        val mine = chain("mine", 9_000)
        val amount = { target: String, hex: String -> if (target == "bcrt1qpayme" && hex == "hex-mine") 8_500L else null }
        assertNull(ReceiveArrivals.onchain(listOf(old), setOf(old.id), "bcrt1qpayme", amount))
        assertEquals(mine to 8_500L, ReceiveArrivals.onchain(listOf(other, mine, old), setOf(old.id), "bcrt1qpayme", amount))
        // Nothing tied to the address: any new incoming transaction, with its balance change.
        assertEquals(other to 7_000L, ReceiveArrivals.onchain(listOf(other, old), setOf(old.id), "bcrt1qpayme", amount))
        assertNull(ReceiveArrivals.onchain(listOf(chain("sent", 1_000, "outgoing")), emptySet(), "bcrt1qpayme", amount))
        // An older, already-mined deposit that a reconnect surfaces isn't an arrival, even paying this address.
        assertNull(ReceiveArrivals.onchain(listOf(chain("mined", 9_000, confirmed = true), old), setOf(old.id), "bcrt1qpayme", amount))
        assertEquals(mine to 8_500L, ReceiveArrivals.onchain(listOf(chain("mined", 9_000, confirmed = true), mine), emptySet(), "bcrt1qpayme", amount))
    }

    @Test fun addressPaymentsSkipThisScreensOwnRequests() {
        fun receive(id: String, kind: String, to: String? = null, metadata: String = "{}", status: String = "successful") =
            HistoryTransaction(id, if (kind == "lightning-receive") "Bolt11" else "Arkoor", 1_000, "2026-10-22T10:00:00Z", "incoming",
                movementStatus = status, movementKind = kind, metadataJson = metadata,
                receivedOn = to?.let { listOf(HistoryTransaction.Destination(it, 1_000, "ark")) })
        val viaAddress = receive("a", "lightning-receive", metadata = """{"payment_hash":"elsewhere"}""")
        assertEquals(viaAddress, ReceiveArrivals.viaAddress(listOf(viaAddress), emptySet(), setOf("tark1qpayme"), setOf("hash")))
        assertNull(ReceiveArrivals.viaAddress(listOf(viaAddress), setOf("a"), emptySet(), emptySet()))
        assertNull(ReceiveArrivals.viaAddress(listOf(receive("b", "arkoor-receive", "tark1qpayme")), emptySet(), setOf("tark1qpayme"), emptySet()))
        assertNull(ReceiveArrivals.viaAddress(listOf(receive("c", "lightning-receive", metadata = """{"payment_hash":"hash"}""")), emptySet(), emptySet(), setOf("hash")))
        assertNull(ReceiveArrivals.viaAddress(listOf(receive("d", "lightning-receive", status = "pending")), emptySet(), emptySet(), emptySet()))
        assertNotNull(ReceiveArrivals.viaAddress(listOf(receive("e", "arkoor-receive", "tark1qstatic")), emptySet(), setOf("tark1qpayme"), emptySet()))
        // A payment still pending when Receive appeared is announced once it succeeds; one already complete never is.
        val pendingAtOpen = receive("f", "lightning-receive", status = "pending")
        val completeAtOpen = receive("g", "lightning-receive")
        val settled = ReceiveArrivals.settled(listOf(pendingAtOpen, completeAtOpen)).toSet()
        assertEquals(setOf("g"), settled)
        assertNull(ReceiveArrivals.viaAddress(listOf(pendingAtOpen, completeAtOpen), settled, emptySet(), emptySet()))
        val succeeded = receive("f", "lightning-receive")
        assertEquals(succeeded, ReceiveArrivals.viaAddress(listOf(succeeded, completeAtOpen), settled, emptySet(), emptySet()))
        assertNull(ReceiveArrivals.viaAddress(listOf(succeeded, completeAtOpen), settled + "f", emptySet(), emptySet()))
    }

    @Test fun arrivalsInOneUpdateAreTakenOneAtATime() {
        val old = chain("old", 5_000, confirmed = true)
        val deposit = chain("new", 20_000)
        val payment = HistoryTransaction("ln", "Bolt11", 2_000, "2026-10-22T10:00:00Z", "incoming",
            movementStatus = "successful", movementKind = "lightning-receive", metadataJson = """{"payment_hash":"elsewhere"}""")
        val none = { _: String, _: String -> null as Long? }
        val baseline = ReceiveRecords.baseline(listOf(old))
        val rows = listOf(old, deposit, payment)
        // The deposit first...
        val (afterDeposit, first) = checkNotNull(ReceiveArrivals.next(rows, baseline, "bcrt1qpayme", emptySet(), emptySet(), none, 7))
        assertEquals(ReceiveArrival(ReceiveArrival.Rail.onchain, 20_000, 7, "new"), first)
        // ...then the address payment from the same update...
        val (afterPayment, second) = checkNotNull(ReceiveArrivals.next(rows, afterDeposit, "bcrt1qpayme", emptySet(), emptySet(), none, 9))
        assertEquals(ReceiveArrival(ReceiveArrival.Rail.lightning, 2_000, 9), second)
        // ...and then nothing is left waiting.
        assertNull(ReceiveArrivals.next(rows, afterPayment, "bcrt1qpayme", emptySet(), emptySet(), none, 11))
    }

    @Test fun theBlockRowFollowsAReplacementOrStopsWaiting() {
        val amount = { target: String, hex: String -> if (target == "bcrt1qpayme" && hex in setOf("hex-a", "hex-b")) 9_000L else null }
        val a = chain("a", 9_500)
        val tracked = ReceiveArrival(ReceiveArrival.Rail.onchain, 9_000, 1, "a", "bcrt1qpayme")
        val known = setOf(a.id)
        // While it's in the records, it's followed as it is.
        assertEquals(tracked to null, ReceiveArrivals.followed(tracked, listOf(a), known, amount))
        // Replaced (RBF), and the replacement confirmed before the next refresh: the block row follows the replacement.
        val b = chain("b", 9_400, confirmed = true)
        assertEquals(tracked.copy(txid = "b") to b, ReceiveArrivals.followed(tracked, listOf(b), known, amount))
        // A new transaction paying somewhere else isn't its replacement: the original is gone, so the wait ends.
        assertEquals(null to null, ReceiveArrivals.followed(tracked, listOf(chain("other", 7_000)), known, amount))
        // Not tied to an address: any new incoming transaction takes its place, and with none the wait ends.
        val untied = tracked.copy(address = null, sats = 9_500)
        assertEquals("b", ReceiveArrivals.followed(untied, listOf(b), known, amount).first?.txid)
        assertEquals(null to null, ReceiveArrivals.followed(untied, emptyList(), known, amount))
        // A deposit paying this request's address is tied to it.
        val (_, arrival) = checkNotNull(ReceiveArrivals.next(listOf(a), ReceiveRecords(emptySet(), emptySet()), "bcrt1qpayme", emptySet(), emptySet(), amount, 1))
        assertEquals("bcrt1qpayme", arrival.address)
    }

    @Test fun anArrivalTakesAFreeCardOrOneShowingADeposit() {
        // The deposit's block row carries on separately, so a later payment can take its card; an Ark or Lightning one plays out first.
        assertTrue(ReceiveArrivals.takesCard(null))
        assertTrue(ReceiveArrivals.takesCard(ReceiveArrival(ReceiveArrival.Rail.onchain, 5_000, 0, "tx")))
        assertFalse(ReceiveArrivals.takesCard(ReceiveArrival(ReceiveArrival.Rail.ark, 5_000, 0)))
        assertFalse(ReceiveArrivals.takesCard(ReceiveArrival(ReceiveArrival.Rail.lightning, 5_000, 0)))
    }

    @Test fun arrivalsFlipThenReturn() {
        for (rail in ReceiveArrival.Rail.entries) for (reduce in listOf(false, true)) {
            val flip = ReceiveTimeline.flipAt(rail, reduce)
            assertEquals(0f, ReceiveTimeline.turn(rail, flip - 1, reduce))
            assertTrue(ReceiveTimeline.turn(rail, ReceiveTimeline.backAt(rail, reduce) - 1, reduce) > 90f)
            assertTrue(ReceiveTimeline.end(rail, reduce) > ReceiveTimeline.backAt(rail, reduce))
        }
        // Each haptic once: light on the delivery and the unlock, success on the flip; none after a resume.
        assertEquals(listOf(false, false, true), ReceiveTimeline.haptics(ReceiveArrival.Rail.lightning, -1, 10_000, false))
        assertEquals(listOf(false, true), ReceiveTimeline.haptics(ReceiveArrival.Rail.ark, -1, 2_000, false))
        assertEquals(listOf(true), ReceiveTimeline.haptics(ReceiveArrival.Rail.onchain, -1, 1_000, false))
        assertTrue(ReceiveTimeline.haptics(ReceiveArrival.Rail.ark, 2_000, 9_000, false).isEmpty())
        assertEquals("Incoming over Lightning", ReceiveTimeline.phase(ReceiveArrival.Rail.lightning, 0, false))
        assertEquals("Claiming with your key", ReceiveTimeline.phase(ReceiveArrival.Rail.lightning, 1_700, false))
        assertEquals("Seen on the network", ReceiveTimeline.phase(ReceiveArrival.Rail.onchain, 100, false))
        assertNull(ReceiveTimeline.phase(ReceiveArrival.Rail.onchain, 1_000, false))
    }
}
