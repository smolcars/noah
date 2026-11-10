package com.noahwallet.services.background

import com.noahwallet.send.SendController.State
import com.noahwallet.services.background.SendPendingNotifications.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What the pending-payment service may say when it stops out of sight: only what the send journal proves. */
class SendPendingNotificationsTest {
    @Test fun onlyProvenResultsAreClaimed() {
        assertEquals(Outcome.sent, Outcome.of(State.succeeded, pending = false))
        assertEquals(Outcome.failed, Outcome.of(State.failed, pending = false))
        assertEquals(Outcome.stillPaying, Outcome.of(State.uncertain, pending = true))
        // "May have been sent", an invoice paid before, unreadable storage, or nothing in flight: no claim.
        for (state in listOf(State.uncertain, State.alreadyPaid, State.storageBlocked, State.ready, State.submitting))
            assertNull(Outcome.of(state, pending = false))
    }
}
