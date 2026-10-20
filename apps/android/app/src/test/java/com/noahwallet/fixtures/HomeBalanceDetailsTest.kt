package com.noahwallet.fixtures

import com.noahwallet.services.wallet.WalletBalance
import com.noahwallet.formatting.BitcoinAmountUnit
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class HomeBalanceDetailsTest {
    @Test fun bucketsAndHiddenAmounts() {
        for (fixture in SharedFixtures.load("home-balance-details")) {
            val balance = Json.decodeFromJsonElement(WalletBalance.serializer(), fixture.input)
            val expected = Json.decodeFromJsonElement(kotlinx.serialization.builtins.ListSerializer(WalletBalance.Detail.serializer()), fixture.expected)
            assertEquals(fixture.name, expected, balance.details)
            assertEquals("200 sats pending in a round. These funds become available after the round confirms.", balance.pendingRoundMessage("200 sats"))
            assertTrue(balance.pendingRoundMessage("••••")!!.startsWith("•••• pending"))
            assertNull(balance.copy(ark = balance.ark.copy(pendingInRound = 0uL)).pendingRoundMessage("0 sats"))
            for (row in balance.details) for (unit in BitcoinAmountUnit.entries) {
                assertEquals("••••", WalletBalance.display(row.sats, true, unit, Locale.US))
            }
        }
    }
}
