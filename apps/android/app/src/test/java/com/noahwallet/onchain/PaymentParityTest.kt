package com.noahwallet.onchain

import com.noahwallet.fixtures.SharedFixtures
import com.noahwallet.send.SendReceipt
import com.noahwallet.services.wallet.ChainStatus
import io.github.niteshbalusu11.bark.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PaymentParityTest {
    @Test fun sharedCopyAndBoardReceiptTotals() {
        val table = SharedFixtures.document("payment-status-copy").jsonObject
        val actual = mapOf("notFound" to ChainStatus(ChainStatus.Kind.notFound).text,
            "inMempool" to ChainStatus(ChainStatus.Kind.inMempool).text,
            "confirmed" to ChainStatus(ChainStatus.Kind.confirmed, 123).text,
            "lookupError" to ChainStatus.errorText,
            "insufficientFunds" to OnchainFailure.insufficientFunds.text,
            "belowMinimum" to OnchainFailure.belowMinimum.text,
            "reservedInputs" to OnchainFailure.reserved.text,
            "tooManyInputs" to OnchainFailure.tooManyInputs.text,
            "notSynced" to OnchainFailure.notSynced.text,
            "boardNotAttempted" to OnchainFailure.boardNotAttempted.text,
            "broadcastFailed" to OnchainReceipt.broadcastFailedMessage,
            "broadcastPending" to SendReceipt.broadcastPendingMessage,
            "submitted" to SendReceipt.submittedMessage, "uncertain" to SendReceipt.uncertainMessage)
        assertEquals(table.getValue("copy").jsonObject.mapValues { it.value.jsonPrimitive.content }, actual)
        for (value in table.getValue("receipts").jsonArray) {
            val row = value.jsonObject
            val quote = OnchainQuote(OnchainAction.board, "wallet", amount = row.getValue("gross").jsonPrimitive.long,
                fee = row.getValue("arkFee").jsonPrimitive.long, chainFee = row.getValue("chainFee").jsonPrimitive.long)
            val receipt = OnchainReceipt("Submitted", onchainFee = row.getValue("actualChainFee").jsonPrimitive.longOrNull)
            assertEquals(Json.decodeFromJsonElement(OnchainReceipt.BoardAmounts.serializer(), row.getValue("expected")), receipt.boardAmounts(quote))
        }
        assertEquals(OnchainFailure.insufficientFunds, boardFailure(BarkError("not for UI", BarkErrorCode.INSUFFICIENT_FUNDS)))
    }
    @Test fun lookupErrorsAreDisplayStateAndNeverSerializedAsEvidence() {
        val json = Json { ignoreUnknownKeys = true }
        val send = SendReceipt(1000, txid = "exact", status = SendReceipt.Status.submitted, broadcastRetryPending = true,
            chainStatus = ChainStatus(ChainStatus.Kind.inMempool), statusError = ChainStatus.errorText)
        val restored = json.decodeFromString(SendReceipt.serializer(), json.encodeToString(SendReceipt.serializer(), send))
        assertNull(restored.statusError); assertEquals("exact", restored.txid)
        assertEquals(send.chainStatus, restored.chainStatus); assertTrue(restored.broadcastRetryPending)
        val board = OnchainReceipt("Submitted", txid = "exact", broadcastRetryPending = true, statusError = ChainStatus.errorText)
        val restoredBoard = json.decodeFromString(OnchainReceipt.serializer(), json.encodeToString(OnchainReceipt.serializer(), board))
        assertNull(restoredBoard.statusError); assertEquals("exact", restoredBoard.txid); assertTrue(restoredBoard.broadcastRetryPending)
    }
}
