package com.noahwallet.recurring

import com.noahwallet.send.*
import org.junit.Assert.*
import org.junit.Test

/** Activity names a payment after a recurring schedule only when that occurrence made it. */
class RecurringHistoryLinkTest {
    private val binding = RecurringStore.Binding(SendIdentity("wallet", "regtest"), "instance")
    private val schedule = RecurringPayment("rent", "Rent", "rosa@noahwallet.io", "lnurl", 5_000, interval = RecurringInterval("month", 1),
        startAt = 0, createdAt = 0, updatedAt = 0)
    private val hash = "ab".repeat(32)
    private fun entry(outcome: RecurringJournal.Outcome, receipt: SendReceipt? = null, alreadyPaid: SendReceipt? = null, key: String? = "rent:1") =
        RecurringJournal.Entry(binding, "rent", RecurringPayment.Plan("pay", 1, 1000), 1000, terminal = RecurringJournal.TerminalEvidence("s",
            SendAttempt(recurringOccurrenceKey = key, payment = SendPayment("rosa@noahwallet.io", "lnurl", 5_000), paymentHash = hash,
                receipt = receipt, alreadyPaid = alreadyPaid), 1, 1, outcome, null, 2000))
    private val paid = SendReceipt(5_000, paymentHash = hash, movementID = 9u, fee = 12, proof = SendReceipt.Proof.actualMovement)

    @Test fun onlyTheOccurrencesOwnProvenPaymentJoins() {
        val link = RecurringJournal.historyLink(entry(RecurringJournal.Outcome.succeeded, receipt = paid), schedule)
        assertEquals("Rent", link?.recurring?.label); assertEquals(9u, link?.movementId); assertEquals(hash, link?.paymentHash)
        // Already paid: the receipt names a payment made before the occurrence, maybe by hand. Never relabel it.
        assertNull(RecurringJournal.historyLink(entry(RecurringJournal.Outcome.alreadyPaid, alreadyPaid = paid), schedule))
        assertNull(RecurringJournal.historyLink(entry(RecurringJournal.Outcome.succeeded, receipt = paid, alreadyPaid = paid), schedule))
        // Failed, uncertain, unproven or another occurrence's attempt: no join.
        assertNull(RecurringJournal.historyLink(entry(RecurringJournal.Outcome.failed, receipt = paid), schedule))
        assertNull(RecurringJournal.historyLink(entry(RecurringJournal.Outcome.uncertain, receipt = paid), schedule))
        assertNull(RecurringJournal.historyLink(entry(RecurringJournal.Outcome.succeeded, receipt = paid.copy(proof = null)), schedule))
        assertNull(RecurringJournal.historyLink(entry(RecurringJournal.Outcome.succeeded, receipt = paid, key = "rent:0"), schedule))
    }
}
