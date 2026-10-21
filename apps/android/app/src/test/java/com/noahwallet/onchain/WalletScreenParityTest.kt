package com.noahwallet.onchain

import com.noahwallet.formatting.BitcoinAmountUnit
import com.noahwallet.recurring.*
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@org.robolectric.annotation.Config(sdk = [35])
@RunWith(RobolectricTestRunner::class)
class WalletScreenParityTest {
    @Test fun recurringInputLimitsPreserveUnicodeBoundaries() {
        assertEquals(64, limitedRecurringText("a".repeat(65), 64).length)
        assertEquals(139, limitedRecurringText("a".repeat(139) + "🌱", 140).length)
        assertEquals(64, limitedRecurringText("a".repeat(62) + "🌱", 64).length)
    }
    @Test fun remainingHandlesUnknownReachedAndPluralTargets() {
        assertEquals("Unknown", OnchainDisplay.remaining(null, 101))
        assertEquals("Unknown", OnchainDisplay.remaining(100, null))
        assertEquals("{count} block", OnchainDisplay.remaining(100, 101))
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val resources = com.noahwallet.localization.L10n.context(context, "en").resources
        val id = resources.getIdentifier(com.noahwallet.localization.L10n.resourceName(OnchainDisplay.remaining(100, 101)), "string", context.packageName)
        assertTrue(id != 0)
        assertEquals("1 block", resources.getString(id, 1))
        assertEquals("{count} blocks", OnchainDisplay.remaining(100, 102))
        assertEquals("Now", OnchainDisplay.remaining(100, 100))
        assertEquals("Now", OnchainDisplay.remaining(101, 100))
    }
    @Test fun claimableFallbackAndBlockIdentityAreVisible() {
        val block = ExitDetails.Block(100, "a".repeat(64))
        val details = ExitDetails(claimable_since = block)
        assertEquals(100L, details.displayClaimableHeight)
        assertEquals(101L, details.copy(claimable_height = 101).displayClaimableHeight)
        assertEquals("100 (${block.hash})", OnchainDisplay.block(block))
        assertNull(ExitDetails().displayClaimableHeight)
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
