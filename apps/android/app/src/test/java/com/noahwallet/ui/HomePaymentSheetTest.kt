package com.noahwallet.ui

import com.noahwallet.history.HistoryRules
import com.noahwallet.history.HistoryTransaction
import com.noahwallet.ui.activity.ActivityRules
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Date

/** Home's payment sheet reads a record with Activity's status and fee rules. */
class HomePaymentSheetTest {
    private fun send(status: String, effective: Long, fee: Long, outputs: List<String> = emptyList()) = HistoryTransaction(
        "movement-7", "Lnurl", 5_000, HistoryRules.isoDate(Date()), "outgoing", source = "ark", destination = "bob@getalby.com",
        movementId = 7u, movementStatus = status, subsystemName = "bark.lightning_send", subsystemKind = "send",
        intendedBalanceSat = -5_000, effectiveBalanceSat = effective, offchainFeeSat = fee,
        sentTo = listOf(HistoryTransaction.Destination("bob@getalby.com", 5_000, "lightning-address")), outputVtxos = outputs)

    @Test fun aFailedSendNeverReadsSentOrShowsItsPlannedFee() {
        // bark 0.8 keeps the planned fee on a send that never left.
        val failed = paymentSummary(send("failed", 0, 15), "noahwallet.io")
        assertEquals("Failed · nothing left your wallet", failed.status)
        assertEquals(ActivityRules.Tone.failed, failed.tone)
        assertEquals(listOf(ActivityRules.FeeLine("Fee", ActivityRules.FeeValue.None)), failed.fees)
        val refunded = paymentSummary(send("failed", 0, 0, outputs = listOf("revoked:0")), "noahwallet.io")
        assertEquals("Refunded · back in your wallet", refunded.status)
        assertEquals(listOf(ActivityRules.FeeLine("Fee", ActivityRules.FeeValue.None)), refunded.fees)
        val sent = paymentSummary(send("successful", -5_015, 15), "noahwallet.io")
        assertEquals("Sent", sent.status)
        assertEquals(listOf(ActivityRules.FeeLine("Fee", ActivityRules.FeeValue.Sats(15))), sent.fees)
    }
}
