package com.noahwallet.history

import com.noahwallet.ui.home.HomeRow
import io.github.niteshbalusu11.bark.*
import org.junit.Assert.*
import org.junit.Test

/** The fee children an exit's on-chain wallet pays read as "Exit fee" rows, found by txid in bark's exit records. */
class ExitFeeRowTest {
    private val block = BlockRef(101u, "h")
    private fun chain(txid: String, change: Long) = WalletTransaction(txid, "", null, change, block, false)

    @Test fun childTxidsFromCurrentAndPastExitRecordsMapToTheirExit() {
        val replaced = ExitState.Processing(100u, listOf(ExitTx("root", ExitTxStatus.AwaitingConfirmation("child-0", ExitTxOrigin.Mempool))))
        val current = ExitState.Processing(101u, listOf(ExitTx("root", ExitTxStatus.Confirmed("child-1", block, ExitTxOrigin.Wallet(block))),
            ExitTx("leaf", ExitTxStatus.AwaitingCpfpBroadcast)))
        val packages = listOf(ExitTransactionPackage("root", "", "child-1", "", ExitTxOrigin.Wallet(block), current.transactions[0].status),
            ExitTransactionPackage("leaf", "", null, null, null, ExitTxStatus.AwaitingCpfpBroadcast))
        val exits = listOf(ExitTransactionStatus("vtxo-a", 30_000u, current, listOf(ExitState.Start(100u), replaced), 2u, packages),
            ExitTransactionStatus("vtxo-b", 20_000u, ExitState.Start(100u), emptyList(), 0u, emptyList()))
        assertEquals(mapOf("child-1" to "vtxo-a", "child-0" to "vtxo-a"), HistoryMapping.exitFees(exits))
    }

    @Test fun anExitFeeRowNamesItselfAndShowsTheFeeGoingOut() {
        val fees = mapOf("child-1" to "vtxo-a")
        val fee = HistoryMapping.transaction(chain("child-1", -1_234), exitFeeFor = fees["child-1"])
        val home = HomeRow.of(fee, null)
        assertEquals("Exit fee", home.title)
        assertEquals(HomeRow.Avatar.Exit, home.avatar)
        assertEquals("On-chain", home.kind)
        assertEquals(1_234L, home.amount); assertEquals("−", home.sign); assertFalse(home.incoming)
        assertEquals("vtxo-a", fee.exitFeeFor)
        assertTrue(HistoryCSV.content(listOf(fee), "USD").lines()[1].contains(",Exit fee,"))
        // Same amount, another txid: an ordinary on-chain send.
        val send = HistoryMapping.transaction(chain("other", -1_234), exitFeeFor = fees["other"])
        assertEquals("Onchain", HomeRow.of(send, null).title)
        assertEquals(HomeRow.Avatar.Chain, HomeRow.of(send, null).avatar)
    }
}
