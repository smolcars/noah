package com.noahwallet.services.background

import android.app.Notification
import android.app.NotificationManager
import com.noahwallet.notifications.NotificationChannels
import com.noahwallet.send.SendController.State
import com.noahwallet.services.background.SendPendingNotifications.Outcome
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** What the pending-payment service shows: quiet while it runs, and afterwards only what the send journal proves. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SendPendingNotificationsTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val manager get() = context.getSystemService(NotificationManager::class.java)

    @org.junit.Before fun allowNotifications() {
        org.robolectric.Shadows.shadowOf(context).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    @Test fun onlyProvenResultsAreClaimed() {
        assertEquals(Outcome.sent, Outcome.of(State.succeeded, pending = false))
        assertEquals(Outcome.failed, Outcome.of(State.failed, pending = false))
        assertEquals(Outcome.stillPaying, Outcome.of(State.uncertain, pending = true))
        // "May have been sent", an invoice paid before, unreadable storage, or nothing in flight: no claim.
        for (state in listOf(State.uncertain, State.alreadyPaid, State.storageBlocked, State.ready, State.submitting))
            assertNull(Outcome.of(state, pending = false))
    }

    @Test fun theOngoingNotificationIsQuietAndOnItsOwnChannel() {
        val sending = SendPendingNotifications.sending(context)
        assertEquals(NotificationChannels.PAYMENTS, sending.channelId)
        assertTrue(sending.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals("Sending", sending.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("Paying over Lightning", sending.extras.getString(Notification.EXTRA_TEXT))
        assertEquals("Still paying. This can take a few minutes.",
            SendPendingNotifications.sending(context, slow = true).extras.getString(Notification.EXTRA_TEXT))
        val channel = manager.getNotificationChannel(NotificationChannels.PAYMENTS)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel.importance)
        assertNull(channel.sound); assertFalse(channel.shouldVibrate())
    }

    @Test
    @Config(sdk = [36])
    fun android16DrawsItAsAProgressLiveUpdate() {
        val template = SendPendingNotifications.sending(context).extras.getString(Notification.EXTRA_TEMPLATE)
        assertTrue(template, template?.contains("ProgressStyle") == true)
    }

    @Test fun aStoppedServiceLeavesOnlyTheResult() {
        SendPendingNotifications.finished(context, State.succeeded, pending = false)
        var posted = manager.activeNotifications.single().notification
        assertEquals("Sent", posted.extras.getString(Notification.EXTRA_TITLE))
        assertEquals(NotificationChannels.UNIFIED_PUSH, posted.channelId)
        assertTrue(posted.flags and Notification.FLAG_AUTO_CANCEL != 0 && posted.flags and Notification.FLAG_ONGOING_EVENT == 0)
        // At the cap nothing is checking the payment any more.
        SendPendingNotifications.finished(context, State.uncertain, pending = true)
        posted = manager.activeNotifications.single().notification
        assertEquals("Sending", posted.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("Open Noah to check", posted.extras.getString(Notification.EXTRA_TEXT))
        SendPendingNotifications.clear(context)
        assertTrue(manager.activeNotifications.isEmpty())
        // An outcome nothing proves posts nothing.
        SendPendingNotifications.finished(context, State.uncertain, pending = false)
        assertTrue(manager.activeNotifications.isEmpty())
    }
}
