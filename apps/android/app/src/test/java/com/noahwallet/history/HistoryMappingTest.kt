package com.noahwallet.history

import com.noahwallet.recurring.RecurringInterval
import com.noahwallet.send.PaymentEncoding
import com.noahwallet.ui.home.HomeRow
import io.github.niteshbalusu11.bark.Movement
import io.github.niteshbalusu11.bark.MovementDestination
import io.github.niteshbalusu11.bark.PaymentMethod
import org.junit.Assert.*
import org.junit.Test

/** Activity's data changes (docs/native-rewrite/design/activity.md, Data), shared with Home's rows. */
class HistoryMappingTest {
    @Suppress("LongParameterList") // Fixture builder: each parameter is a named, defaulted field of the model it builds.
    private fun movement(subsystem: String, status: String = "successful", intended: Long = 0, effective: Long = intended, fee: Long = 0,
        sentTo: List<Pair<String, String>> = emptyList(), receivedOn: List<Pair<String, String>> = emptyList(),
        inputs: List<String> = emptyList(), outputs: List<String> = emptyList(), exited: List<String> = emptyList(),
        metadata: String = "{}", id: UInt = 7u): Movement {
        val (name, kind) = subsystem.split(':')
        fun destinations(list: List<Pair<String, String>>) = list.map { (type, value) -> MovementDestination(PaymentMethod(type, value), 5_000uL) }
        return Movement(id, status, name, kind, metadata, intended, effective, fee.toULong(), destinations(sentTo), destinations(receivedOn),
            inputs, outputs, exited, "2026-10-23T09:12:00Z", "2026-10-23T09:12:00Z", null, null, null, null)
    }
    private fun row(m: Movement) = HistoryMapping.transaction(m)
    private fun home(m: Movement, domain: String? = "noahwallet.io") = HomeRow.of(row(m), domain)

    // BOLT11 spec vector: description "bolt11.org".
    private val invoice =
        "lnbc15u1p3xnhl2pp5jptserfk3zk4qy42tlucycrfwxhydvlemu9pqr93tuzlv9cc7g3sdqsvfhkcap3xyhx7un8cqzpgxqzjcsp5f8c52y2stc300gl6s4xswtjpc37hrnnr3c9wvtgjfu" +
            "vqmpm35evq9qyyssqy4lgd8tj637qcjp05rdpxxykjenthxftej7a2zzmwrmrl70fyj9hvj0rewhzj7jfyuwkwcg9g2jpwtk3wkjtwnkdks84hsnu8xps5vsq4gj5hs"
    private val invoiceHash = "90570c8d3688ad5012aa5ff982606971ae46b3f9df0a100cb15f05f61718f223"
    /** A BOLT12 offer: bech32 without a checksum over offer_description (TLV 10) and an issuer id (TLV 22). */
    private fun offer(description: String?): String {
        val tlv = mutableListOf<Byte>()
        description?.toByteArray()?.let { tlv += 10.toByte(); tlv += it.size.toByte(); tlv += it.toList() }
        tlv += 22.toByte(); tlv += 33.toByte(); tlv += 2.toByte(); tlv += List(32) { it.toByte() }
        return PaymentEncoding.encodeBech32("lno", tlv.toByteArray()).dropLast(6)
    }

    @Test fun failedAndRefundedLightningSendsAreShown() {
        // bark 0.8 fails a send before it leaves: zero balance change, the planned fee kept, no coins.
        val failed = movement("bark.lightning_send:send", "failed", intended = -5_000, effective = 0, fee = 12,
            sentTo = listOf("invoice" to invoice), inputs = listOf("in:0"))
        assertTrue(HistoryMapping.include(failed))
        assertFalse(row(failed).isRefunded)
        with(home(failed)) { assertTrue(this.failed); assertTrue("nothing left your wallet", unchanged); assertEquals("Failed", state) }
        // After its HTLCs, bark revokes the coins back: the movement's outputs are the refund.
        val refunded = movement("bark.lightning_send:send", "failed", intended = -5_000, effective = 0, sentTo = listOf("invoice" to invoice),
            inputs = listOf("in:0"), outputs = listOf("change:1", "revoked:0"))
        assertTrue(HistoryMapping.include(refunded))
        assertTrue(row(refunded).isRefunded)
        assertEquals("Refunded", home(refunded).state)
        // Pushed to an exit instead: the coins are on their way on-chain, not back in the wallet.
        val exited = movement("bark.lightning_send:send", "failed", intended = -5_000, effective = 0, sentTo = listOf("invoice" to invoice),
            outputs = listOf("change:1"), exited = listOf("htlc:0"))
        assertFalse(row(exited).isRefunded)
        assertEquals("Failed", home(exited).state)
        // A fee with coins produced may have been paid: no "nothing left" claim.
        assertFalse(home(movement("bark.lightning_send:send", "failed", intended = -5_000, effective = 0, fee = 12, outputs = listOf("c:0"))).unchanged)
        // Failures that never meant to move anything stay out, as before.
        assertFalse(HistoryMapping.include(movement("bark.arkoor:receive", "failed")))
        // Board and offboard keep today's rule.
        assertTrue(HistoryMapping.include(movement("bark.board:board", "failed")))
    }

    @Test fun feePayingRenewalsAreArkRenewedAndFreeOnesStayHidden() {
        val paid = movement("bark.round:refresh", intended = 0, effective = -45, fee = 45, inputs = listOf("a:0", "b:0", "c:0"), outputs = listOf("d:0"))
        assertTrue(HistoryMapping.include(paid))
        with(row(paid)) {
            assertEquals("Arkoor", type); assertEquals("outgoing", direction); assertEquals(45L, amount); assertTrue(isRenewal)
            assertEquals(listOf("a:0", "b:0", "c:0"), inputVtxos)
        }
        with(home(paid)) { assertEquals("Renewed", title); assertEquals("Ark", kind); assertEquals(HomeRow.Avatar.Renewal, avatar); assertEquals("−", sign) }
        assertTrue(HistoryFilter.Ark.includes(row(paid)))
        assertFalse(HistoryMapping.include(movement("bark.round:refresh", intended = 0, effective = 0, inputs = listOf("a:0"), outputs = listOf("b:0"))))
    }

    @Test fun recurringRowsJoinTheJournalByMovementOrPaymentHash() {
        val monthly = HistoryTransaction.Recurring("s-1", "Savings", RecurringInterval("month", 1))
        val ark = row(movement("bark.arkoor:send", intended = -10_000, sentTo = listOf("lightning-address" to "rosa@noahwallet.io"), id = 7u))
        val lightning = row(movement("bark.lightning_send:send", intended = -2_500, sentTo = listOf("invoice" to invoice), id = 8u))
        val other = row(movement("bark.arkoor:send", intended = -500, sentTo = listOf("ark" to "tark1other"), id = 9u))
        val joined = HistoryMapping.joinRecurring(listOf(ark, lightning, other), listOf(
            HistoryMapping.RecurringLink(monthly, 7u, null),
            HistoryMapping.RecurringLink(monthly.copy(scheduleID = "s-2", label = "Coffee"), null, invoiceHash),
        ))
        assertEquals(monthly, joined[0].recurring)
        assertEquals("Coffee", joined[1].recurring?.label)
        assertNull("no link: an ordinary send", joined[2].recurring)
        with(HomeRow.of(joined[0], "noahwallet.io")) {
            assertEquals("Savings", title)
            assertEquals("Recurring", kind)
            assertEquals(HomeRow.Avatar.Recurring, avatar)
        }
        // A miss leaves Home's own reading.
        val missed = HistoryMapping.joinRecurring(listOf(ark), listOf(HistoryMapping.RecurringLink(monthly, 99u, "ff")))
        assertNull(missed[0].recurring)
        assertEquals("rosa", HomeRow.of(missed[0], "noahwallet.io").title)
        // Only payments out run a schedule.
        val incoming = row(movement("bark.arkoor:receive", intended = 2_100, receivedOn = listOf("ark" to "tark1me"), id = 7u))
        assertNull(HistoryMapping.joinRecurring(listOf(incoming), listOf(HistoryMapping.RecurringLink(monthly, 7u, null)))[0].recurring)
    }

    @Test fun invoiceAndOfferRowsAreTitledByTheirDescription() {
        val received = movement("bark.lightning_receive:receive", intended = 1_500, receivedOn = listOf("invoice" to invoice))
        assertEquals("bolt11.org", row(received).description)
        with(home(received)) { assertEquals("bolt11.org", title); assertEquals("Lightning", kind); assertEquals(HomeRow.Avatar.Lightning, avatar) }
        val coffee = movement("bark.lightning_send:send", intended = -3_300, sentTo = listOf("offer" to offer("Coffee Collective")))
        assertEquals("Coffee Collective", home(coffee).title)
        // Without a description: what happened, never empty.
        assertEquals("Sent", home(movement("bark.lightning_send:send", intended = -3_300, sentTo = listOf("offer" to offer(null)))).title)
        assertEquals("Received", home(movement("bark.lightning_receive:receive", intended = 1_500,
            receivedOn = listOf("invoice" to "lnbcrt1unreadable"))).title)
        // A Lightning address keeps its counterparty title.
        assertEquals("bob@getalby.com", home(movement("bark.lightning_send:send", intended = -5_000,
            sentTo = listOf("lightning-address" to "bob@getalby.com"))).title)
    }

    @Test fun paymentHashIsDecodedFromTheStoredInvoice() {
        assertEquals(invoiceHash, PaymentEncoding.invoice(invoice)?.hash)
        assertEquals(invoiceHash, row(movement("bark.lightning_receive:receive", intended = 1_500, receivedOn = listOf("invoice" to invoice))).paymentHash)
        // A Lightning-address send stores no invoice; bark's recorded hash is the fallback.
        val address = movement("bark.lightning_send:send", intended = -5_000, sentTo = listOf("lightning-address" to "bob@getalby.com"),
            metadata = """{"payment_hash":"abcd"}""")
        assertEquals("abcd", row(address).paymentHash)
        assertNull(row(movement("bark.arkoor:send", intended = -500, sentTo = listOf("ark" to "tark1x"))).paymentHash)
    }

    @Test fun recordsWithoutMetadataAndRNMigratedRowsReadAsBefore() {
        // No metadata at all: no hash, description, recurring or refund; today's label and rail.
        val bare = row(movement("bark.arkoor:send", intended = -500, sentTo = listOf("ark" to "tark1x"), metadata = ""))
        assertNull(bare.paymentHash); assertNull(bare.description); assertNull(bare.recurring); assertFalse(bare.isRefunded)
        assertEquals("Ark", HomeRow.of(bare, null).title)
        // An RN-era repeat payment keeps its Lightning-address reading and Pay again.
        val repeat = row(movement("bark.arkoor:send", intended = -1_000, sentTo = listOf("ark" to "tark1maya"),
            metadata = """{"noah":{"repeat_payment":{"version":1,"destination":"maya@noahwallet.io","comment":"","amount_mode":"sats",""" +
                """"amount_value":"1000"}}}"""))
        assertEquals("Lnurl", repeat.type)
        assertEquals("maya", HomeRow.of(repeat, "noahwallet.io").title)
        // On-chain wallet rows carry no movement facts.
        val chain = HistoryMapping.transaction(io.github.niteshbalusu11.bark.WalletTransaction("ab".repeat(32), "", null, 10_000, null, false))
        assertNull(chain.paymentHash); assertFalse(chain.isRenewal); assertTrue(chain.exported)
    }

    @Test fun pendingRowsSayWhatTheyAreWaitingFor() {
        assertEquals("Exiting", home(movement("bark.exit:start", "pending", intended = -20_000, sentTo = listOf("bitcoin" to "bcrt1qexit"))).state)
        val offboard = movement("bark.offboard:send_onchain", "pending", intended = -40_000, effective = -40_420, sentTo = listOf("bitcoin" to "bcrt1qx"))
        assertEquals("In a round", home(offboard).state)
        val signed = movement("bark.offboard:send_onchain", "pending", intended = -40_000, effective = -40_420, sentTo = listOf("bitcoin" to "bcrt1qx"),
            metadata = """{"offboard_txid":"${"ab".repeat(32)}"}""")
        assertEquals("Confirming", home(signed).state)
        assertEquals("Sending", home(movement("bark.lightning_send:send", "pending", intended = -5_000, sentTo = listOf("invoice" to invoice))).state)
    }

    @Test fun exportsLeaveOutOnlyTheNewlyShownFailures() {
        val failedSend = row(movement("bark.lightning_send:send", "failed", intended = -5_000, effective = 0))
        val failedBoard = row(movement("bark.board:board", "failed", intended = 50_000, effective = 0))
        val canceled = row(movement("bark.arkoor:send", "canceled", intended = -500, effective = 0))
        val sent = row(movement("bark.arkoor:send", intended = -500))
        assertEquals(listOf(failedBoard, canceled, sent), listOf(failedSend, failedBoard, canceled, sent).filter { it.exported })
    }

    @Test fun offerDescriptionsSurviveSplittingAndIgnoreOtherText() {
        val split = offer("A longer description to split").chunked(20).joinToString("+\n ")
        assertEquals("A longer description to split", PaymentEncoding.offerDescription(split))
        assertNull(PaymentEncoding.offerDescription("lnbc1notanoffer"))
        assertNull(PaymentEncoding.offerDescription("lno1qqqq!!"))
    }
}
