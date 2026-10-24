package com.noahwallet.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** Activity's block row: a late chain read never overwrites the open payment's newer one. */
class ActivityChainReadsTest {
    private val reads = ChainReads()
    private var tip: Long? = null
    private fun land(request: ChainReads.Request, value: Long, selected: String?, wallet: String? = "wallet") {
        if (reads.current(request, selected, wallet)) tip = value
    }

    @Test fun aLateReadForAnEarlierPaymentNeverLowersTheOpenOnesTip() {
        val a = reads.begin("movement-1", "wallet")
        val b = reads.begin("movement-2", "wallet")
        land(b, 105, selected = "movement-2")
        land(a, 104, selected = "movement-2")
        assertEquals("A's delayed, older tip lands after B's", 105L, tip)
        // Back on A: only the read that reopening it started writes.
        val again = reads.begin("movement-1", "wallet")
        land(b, 106, selected = "movement-1")
        land(a, 103, selected = "movement-1")
        assertEquals(105L, tip)
        land(again, 107, selected = "movement-1")
        assertEquals(107L, tip)
    }

    @Test fun aReadLandsOnlyWhileItsPaymentIsOpenInTheWalletThatAsked() {
        val read = reads.begin("movement-1", "wallet")
        land(read, 100, selected = null)
        land(read, 101, selected = "movement-1", wallet = "another wallet")
        assertEquals(null, tip)
        land(read, 102, selected = "movement-1")
        assertEquals(102L, tip)
    }
}
