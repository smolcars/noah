package com.noahwallet.send

import io.github.niteshbalusu11.bark.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SendOffboardTest {
    private val address = "1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa"
    private fun tx(amount: Long): String {
        val script = PaymentEncoding.bitcoinScript(address)!!
        val value = ByteArray(8) { i -> (amount ushr (i * 8)).toByte() }
        return "0200000001" + "00".repeat(36) + "00ffffffff01" + PaymentEncoding.hex(value) + "%02x".format(script.size) + PaymentEncoding.hex(script) + "00000000"
    }
    @Test fun maxReceiptWaitsForExactTxidAndUsesActualOutputAndFee() {
        val payment = SendPayment(address, "onchain", 10000, SendSource.offchain, max = true)
        val receipt = SendReceipt(0, txid = "exact", amountIsPending = true, proof = SendReceipt.Proof.submitReturnedTxid)
        fun candidate(id: String) = movement().copy(subsystemName = "bark.offboard", subsystemKind = "offboard", offchainFeeSats = 217u,
            metadataJson = buildJsonObject { put("offboard_txid", id); put("offboard_tx", tx(9783)) }.toString())
        assertEquals(receipt, SendReconciliation.offboardReceipt(receipt, payment, listOf(candidate("lookalike"))))
        val actual = SendReconciliation.offboardReceipt(receipt, payment, listOf(candidate("exact")))
        assertEquals(9783L, actual.amount); assertEquals(217L, actual.fee); assertFalse(actual.amountIsPending)
        assertEquals(receipt, SendReconciliation.offboardReceipt(receipt, payment, listOf(candidate("exact"), candidate("exact"))))
    }
    @Test fun strictOutputReaderRejectsTruncatedAndWrongAddressEvidence() {
        assertEquals(1234L, SendTransaction.amount(address, tx(1234)))
        assertTrue(runCatching { SendTransaction.amount(address, tx(1234).dropLast(2)) }.isFailure)
        assertTrue(runCatching { SendTransaction.amount("invalid", tx(1234)) }.isFailure)
    }
}
