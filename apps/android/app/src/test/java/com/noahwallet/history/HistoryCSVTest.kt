package com.noahwallet.history

import com.noahwallet.ui.activity.ActivityFixtures
import org.junit.Assert.assertEquals
import org.junit.Test

/** The CSV's amount column is the amount the row proves, as Activity shows it. */
class HistoryCSVTest {
    @Test fun anAmountlessRetryExportsWhatLeftTheWalletOrNothing() {
        // Failed at 1,000, then paid at 2,000 + 10: bark's reused movement keeps the first attempt's destination amount.
        val invoice = ActivityFixtures.invoice("Tea", 1)
        val retried = HistoryTransaction("movement-9", "Bolt11", 1_000, "2026-10-20T12:00:00.000Z", "outgoing", source = "ark",
            txid = "movement-9", destination = invoice, movementId = 9u, movementStatus = "successful", subsystemName = "bark.lightning_send",
            subsystemKind = "send", effectiveBalanceSat = -2_010, sentTo = listOf(HistoryTransaction.Destination(invoice, 1_000, "invoice")))
        val unproven = retried.copy(id = "movement-10", effectiveBalanceSat = 0)
        val lines = HistoryCSV.content(listOf(retried, unproven), "USD").lines()
        assertEquals("movement-9,2026-10-20,Lightning,Successful,Outgoing,-2010,,movement-9,$invoice", lines[1])
        assertEquals("movement-10,2026-10-20,Lightning,Successful,Outgoing,,,movement-9,$invoice", lines[2])
    }
}
