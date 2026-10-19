package com.noahwallet.services.push

import com.noahwallet.services.wallet.WalletBalance
import org.junit.Assert.*
import org.junit.Test

class WidgetDataWriterTest {
    @Test fun providerKeysAreLongsWithExactBalanceBucketsAndExpirySentinel() {
        val balance = WalletBalance(WalletBalance.Ark(10u, 1u, 2u, 3u, 4u, 5u, 6u, 7u, 8u), WalletBalance.Onchain(20u, 9u, 29u))
        assertEquals(mapOf("totalBalance" to 75L, "onchainBalance" to 29L, "offchainBalance" to 46L,
            "pendingBalance" to 44L, "closestExpiryBlocks" to -999L, "expiryThreshold" to 24L),
            WidgetDataWriter.values(balance, null, "regtest"))
        assertEquals(-2L, WidgetDataWriter.values(balance, -2L, "mainnet")["closestExpiryBlocks"])
        assertEquals(144L, WidgetDataWriter.values(balance, 2L, "mainnet")["expiryThreshold"])
        assertEquals(12L, WidgetDataWriter.values(balance, 2L, "signet")["expiryThreshold"])
    }
}
