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
            for (row in balance.details) for (unit in BitcoinAmountUnit.entries) {
                assertEquals("••••", WalletBalance.display(row.sats, true, unit, Locale.US))
            }
        }
    }
}
