package com.noahwallet.recurring

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class RecurringLeadingZeroPaymentTest {
    @Test fun leadingZeroAmountMustNotExecute() = runBlocking {
        val w = RecurringWorld(this)
        try {
            w.add()
            w.raw = w.raw!!.replace("\"amountSat\":1000", "\"amountSat\":01000")
            // Use a fresh ledger to classify the row before approving any digest.
            File("${w.journal.file.path}.policies").delete()
            File("${w.journal.file.path}.policies.initialized").delete()
            w.executor.execute()
            assertEquals("A leading-zero JSON number must not authorize payment", 0, w.wallet.calls)
        } finally { w.policyDirectory.parentFile!!.deleteRecursively() }
    }
}
