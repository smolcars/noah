package com.noahwallet.onchain

import com.noahwallet.fixtures.SharedFixtures
import com.noahwallet.formatting.BitcoinAmountUnit
import com.noahwallet.recurring.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale

@org.robolectric.annotation.Config(sdk = [35])
@RunWith(RobolectricTestRunner::class)
class WalletScreenParityTest {
    @Test fun selectionTracksCurrentWalletRows() {
        for (item in SharedFixtures.load("wallet-selection")) {
            val input = item.input.jsonObject
            val rows = input.getValue("vtxos").jsonArray.map {
                val row = it.jsonObject
                VtxoModel(row.getValue("id").jsonPrimitive.content, row.getValue("amount").jsonPrimitive.long, 200,
                    "Spendable", "Pubkey", 1, 400, true, "public", 144, "anchor:0", "point:0", false, 100)
            }
            val ids = input.getValue("ids").jsonArray.map { it.jsonPrimitive.content }.toSet()
            val selected = OnchainDisplay.currentSelection(rows, ids)
            val expected = item.expected.jsonObject
            assertEquals(item.name, expected.getValue("ids").jsonArray.map { it.jsonPrimitive.content }, selected.map { it.id })
            assertEquals(item.name, expected.getValue("amount").jsonPrimitive.long, selected.sumOf { it.amount })
        }
    }

    @Test fun recurringInputLimitsPreserveUnicodeBoundaries() {
        assertEquals(64, limitedRecurringText("a".repeat(65), 64).length)
        assertEquals(139, limitedRecurringText("a".repeat(139) + "🌱", 140).length)
        assertEquals(64, limitedRecurringText("a".repeat(62) + "🌱", 64).length)
    }
    @Test fun anchorExplorerUsesTransactionPartAndNetwork() {
        val txid = "a".repeat(64)
        assertEquals("https://mempool.space/tx/$txid", OnchainDisplay.explorerURL("$txid:2", "mainnet"))
        assertEquals("https://mempool.space/signet/tx/$txid", OnchainDisplay.explorerURL("$txid:2", "signet"))
        assertNull(OnchainDisplay.explorerURL(txid, "regtest"))
        assertNull(OnchainDisplay.explorerURL("", "mainnet"))
    }
    @Test fun recurringAmountUsesSelectedUnitAndLocale() {
        val payment = RecurringPayment("id", "rent", "a@example.test", "lnurl", 1234,
            interval = RecurringInterval("month", 1), startAt = 0, createdAt = 0, updatedAt = 0)
        assertEquals("₿\u00a01,234", payment.formattedAmount(BitcoinAmountUnit.bip177, Locale.US))
        assertEquals("1,234 sats", payment.formattedAmount(BitcoinAmountUnit.sats, Locale.US))
        assertEquals(com.noahwallet.formatting.BitcoinAmount.format(1234, BitcoinAmountUnit.bip177, Locale.forLanguageTag("sv-SE")),
            payment.formattedAmount(BitcoinAmountUnit.bip177, Locale.forLanguageTag("sv-SE")))
        assertEquals(1234L, payment.amountSat)
    }
}
