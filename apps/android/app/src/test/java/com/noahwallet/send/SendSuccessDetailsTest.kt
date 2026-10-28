package com.noahwallet.send

import com.noahwallet.history.HistoryRules
import com.noahwallet.history.HistoryTransaction
import org.junit.Assert.*
import org.junit.Test
import java.util.Date
import java.util.Locale

class SendSuccessDetailsTest {
    private val date = Date(1_700_000_000_000L)
    private fun history(id: UInt = 7u, status: String = "successful", preimage: String? = "proof") =
        HistoryTransaction("movement-$id", "Bolt11", 1000, HistoryRules.isoDate(date), "outgoing",
            movementId = id, movementStatus = status, preimage = preimage)

    @Test fun lightningProofFromExactSuccessfulMovement() {
        for (method in listOf("lightning", "lnurl", "offer")) {
            val details = SendSuccessDetails(SendReceipt(1000, movementID = 7u), SendPayment("invoice", method, 1000),
                listOf(history(8u, preimage = "unrelated"), history()), Date(Long.MAX_VALUE))
            assertEquals("Payment preimage", details.proof?.label)
            assertEquals("proof", details.proof?.value)
            assertEquals("Lightning", details.paidVia)
            assertEquals("invoice", details.destination)
            assertEquals(date, details.completedAt)
        }
    }
    @Test fun txidTakesPriority() {
        for (method in listOf("onchain", "ark", "lightning")) {
            val details = SendSuccessDetails(SendReceipt(1000, txid = "txid", movementID = 7u),
                SendPayment("destination", method, 1000), listOf(history()), date)
            assertEquals("Transaction ID", details.proof?.label)
            assertEquals("txid", details.proof?.value)
            assertEquals(when (method) { "ark" -> "Ark"; "onchain" -> "Onchain"; else -> "Lightning" }, details.paidVia)
        }
    }
    @Test fun missingOrUnrelatedOrUnsuccessfulProofIsOmitted() {
        val receipt = SendReceipt(1000, txid = "", movementID = 7u)
        val payment = SendPayment("invoice", "lightning", 1000)
        for (rows in listOf(emptyList(), listOf(history(8u)), listOf(history(status = "pending")),
            listOf(history(preimage = "")), listOf(history(preimage = null)))) {
            val details = SendSuccessDetails(receipt, payment, rows, date)
            assertNull(details.proof)
            assertEquals(date, details.completedAt)
        }
        assertNull(SendSuccessDetails(receipt, SendPayment("ark", "ark", 1000), listOf(history()), date).proof)
    }
    @Test fun historyCannotSupplyProofWithoutExactReceiptIdentity() {
        val payment = SendPayment("invoice", "lightning", 1000)
        assertNull(SendSuccessDetails(SendReceipt(1000), payment, listOf(history()), date).proof)
        assertNull(SendSuccessDetails(SendReceipt(1000, movementID = 7u), payment,
            listOf(history().copy(direction = "incoming")), date).proof)
        val details = SendSuccessDetails(SendReceipt(1000, txid = "txid"), payment,
            listOf(history().copy(txid = "txid")), Date(Long.MAX_VALUE))
        assertEquals(date, details.completedAt)
    }
    @Test fun truncatesOnlyOver32AndKeepsFullProof() {
        val value = "12345678901234567890123456789012345"
        assertEquals(value.take(32), SendSuccessDetails.truncate(value.take(32)))
        assertEquals("12345678901234…6789012345", SendSuccessDetails.truncate(value))
        assertEquals("", SendSuccessDetails.truncate(""))
        val details = SendSuccessDetails(SendReceipt(1000, txid = value), SendPayment(value, "onchain", 1000), emptyList(), date)
        assertEquals(value, details.proof?.value)
        assertEquals(value, details.destination)
        assertTrue(details.formattedCompletedAt(Locale.US).contains("2023"))
    }
}
