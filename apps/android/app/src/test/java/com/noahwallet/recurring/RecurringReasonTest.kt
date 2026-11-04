package com.noahwallet.recurring

import org.junit.Assert.assertEquals
import org.junit.Test

/** A schedule that needs you says why in its row; "Needs attention" is only for what has no specific reason. */
class RecurringReasonTest {
    private fun reason(code: RecurringIssue.Code) = attentionReason(RecurringPayment("a", "Rent", "a@b.c", "lnurl", 1_000,
        interval = RecurringInterval("month", 1), startAt = 0, status = "needs_attention", issue = RecurringIssue(code), createdAt = 0, updatedAt = 0))

    @Test fun eachKnownIssueHasItsOwnReason() {
        assertEquals("Approval needed", reason(RecurringIssue.Code.policyEvidence))
        assertEquals("Over fee limit", reason(RecurringIssue.Code.quoteExceedsCap))
        assertEquals("Over fee limit", reason(RecurringIssue.Code.scheduleExceedsCap))
        assertEquals("Over fee limit", reason(RecurringIssue.Code.arkFee))
        assertEquals("Couldn't prepare payment", reason(RecurringIssue.Code.retryableFailure))
        assertEquals("Check status", reason(RecurringIssue.Code.uncertain))
        assertEquals("Needs attention", reason(RecurringIssue.Code.unknown))
    }
}
