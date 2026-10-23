package com.noahwallet.send

import io.github.niteshbalusu11.bark.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** SDK 0.6.0 returns movement ids for Ark sends and offboards (FFI item 16). */
class SendMovementIdentityTest {
    private val address = "1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa"
    private fun tx(amount: Long): String {
        val script = PaymentEncoding.bitcoinScript(address)!!
        val value = ByteArray(8) { i -> (amount ushr (i * 8)).toByte() }
        return "0200000001" + "00".repeat(36) + "00ffffffff01" + PaymentEncoding.hex(value) + "%02x".format(script.size) + PaymentEncoding.hex(script) + "00000000"
    }
    private fun offboard(id: UInt = 2u, txid: String = "returned") = movement().copy(id = id, subsystemName = "bark.offboard", subsystemKind = "offboard",
        offchainFeeSats = 150u, metadataJson = buildJsonObject { put("offboard_txid", txid); put("offboard_tx", tx(850)) }.toString())
    private val maxOffboard = SendPayment(address, "onchain", 1000, SendSource.offchain, max = true)

    @Test fun returnedMovementIDSurvivesRestartWithoutHistoryMatching() = runBlocking {
        for (rail in listOf("ark", "offboard", "sendOnchain")) {
            val ark = rail == "ark"
            val payment = SendPayment("reviewed-recipient", if (ark) "ark" else "onchain", 1000, if (ark) null else SendSource.offchain, max = rail == "offboard")
            val receipt = if (ark) SendReceipt(1000, movementID = 7u, proof = SendReceipt.Proof.submitReturnedArkSuccess)
                else SendReceipt(1000, txid = "returned", movementID = 7u, proof = SendReceipt.Proof.submitReturnedTxid)
            var reconciled = 0
            val wallet = FakeSendWallet().apply { payBlock = { receipt }; reconciliation = { reconciled++; SendResolution.Uncertain } }
            val journal = TestJournal()
            val subject = controller(wallet, journal, this)
            subject.submit(payment, FakeSendWallet.quote)!!.await()
            assertEquals(rail, SendController.State.succeeded, subject.state)
            assertEquals(rail, receipt, journal.attempt!!.receipt)
            val restarted = controller(wallet, journal, this); restarted.reloadForWalletChange()
            assertEquals(rail, SendController.State.succeeded, restarted.state)
            assertEquals(rail, 7u, restarted.attempt!!.receipt!!.movementID)
            assertTrue(rail, restarted.attempt!!.receipt!!.isProven(payment))
            assertEquals(rail, 1, wallet.calls); assertEquals("A returned result needs no reconciliation", 0, reconciled)
        }
    }

    @Test fun metadataAttachesOnlyToTheExactNewMovement() {
        val snapshot = SendSnapshot(setOf(1u, 2u))
        assertEquals(7u, SendReconciliation.metadataMovementID(SendReceipt(1000, movementID = 7u, proof = SendReceipt.Proof.submitReturnedArkSuccess), snapshot))
        // An inherited checkpoint's movement predates the attempt and keeps its own metadata.
        assertNull(SendReconciliation.metadataMovementID(SendReceipt(1000, paymentHash = "hash", movementID = 2u, fee = 1, proof = SendReceipt.Proof.actualMovement), snapshot))
        // Without an exact id nothing is attached; history is never matched by destination or amount.
        assertNull(SendReconciliation.metadataMovementID(SendReceipt(1000, proof = SendReceipt.Proof.submitReturnedArkSuccess), snapshot))
    }

    @Test fun offboardReceiptNeedsTheReturnedMovementAndTxidToAgree() {
        val receipt = SendReceipt(0, txid = "returned", movementID = 2u, amountIsPending = true, proof = SendReceipt.Proof.submitReturnedTxid)
        // Two movements claim the txid: the returned id picks the payment's own.
        val resolved = SendReconciliation.offboardReceipt(receipt, maxOffboard, listOf(offboard(), offboard(id = 3u)))
        assertEquals(850L, resolved.amount); assertEquals(150L, resolved.fee); assertEquals(2u, resolved.movementID); assertFalse(resolved.amountIsPending)
        // Neither a movement with the txid but another id, nor the id with another txid, fills the receipt.
        for (history in listOf(listOf(offboard(id = 3u)), listOf(offboard(txid = "other"))))
            assertEquals(receipt, SendReconciliation.offboardReceipt(receipt, maxOffboard, history))
    }

    @Test fun journalsFromBeforeMovementIDsKeepTodaysMatching() = runBlocking {
        // SendJournal's format; older receipts have no movementID key at all.
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val wallet = FakeSendWallet()
        val current = json.encodeToJsonElement(SendAttempt(walletIdentity = wallet.owner, payment = maxOffboard,
            receipt = SendReceipt(0, txid = "returned", amountIsPending = true, proof = SendReceipt.Proof.submitReturnedTxid))).jsonObject
        val receipt = current.getValue("receipt").jsonObject
        assertEquals(JsonNull, receipt["movementID"])
        val journal = TestJournal().apply { attempt = json.decodeFromJsonElement<SendAttempt>(JsonObject(current + ("receipt" to JsonObject(receipt - "movementID")))) }
        assertNull(journal.attempt!!.receipt!!.movementID)
        wallet.reconciliation = { SendResolution.Paid(SendReconciliation.offboardReceipt(it.receipt!!, it.payment, listOf(offboard()))) }
        val restarted = controller(wallet, journal, this); restarted.reloadForWalletChange(); restarted.checkStatus()
        assertEquals(850L, restarted.attempt!!.receipt!!.amount); assertEquals(2u, restarted.attempt!!.receipt!!.movementID)

        // An Ark attempt that never saved a receipt has no id: it stays uncertain with candidates, never "not sent".
        journal.attempt = SendAttempt(walletIdentity = wallet.owner, payment = arkPayment, startedAt = 0)
        wallet.reconciliation = { SendReconciliation.ark(it, listOf(movement("ark", hash = null, status = "pending", id = 2u))) }
        val ark = controller(wallet, journal, this); ark.reloadForWalletChange(); ark.checkStatus()
        assertEquals(SendController.State.uncertain, ark.state); assertEquals(listOf(2u), ark.attempt!!.candidates)
        assertTrue(ark.locked); assertEquals(0, wallet.calls)
    }
}
