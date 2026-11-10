package com.noahwallet.services.background

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import com.noahwallet.BuildConfig
import com.noahwallet.R
import com.noahwallet.localization.L10n
import com.noahwallet.notifications.NotificationChannels
import com.noahwallet.send.SendController
import com.noahwallet.services.push.PushNotifications
import com.noahwallet.ui.routeIntent

/** The pending-payment service's notifications. They never show an amount or a recipient. */
internal object SendPendingNotifications {
    const val ID = 7101
    private const val TAG = "send-pending"

    /** What a stopped service leaves behind: the title and line for how far the payment got, or null to say nothing. */
    enum class Outcome {
        sent, failed, stillPaying;

        companion object {
            /** Only a proven result is claimed; a payment bark is still paying says so; anything else stays silent. */
            fun of(state: SendController.State, pending: Boolean): Outcome? = when {
                state == SendController.State.succeeded -> sent
                state == SendController.State.failed -> failed
                state == SendController.State.uncertain && pending -> stillPaying
                else -> null
            }
        }
    }

    /** The ongoing notification while the service runs. */
    fun sending(context: Context): Notification {
        PushNotifications.ensureChannels(context)
        return builder(context).setContentTitle(L10n.text(context, "Sending")).setContentText(L10n.text(context, "Paying over Lightning"))
            .setOngoing(true).setSilent(true).build()
    }

    /** Replaces the ongoing notification once the service stops with Noah out of sight. */
    fun finished(context: Context, state: SendController.State, pending: Boolean) {
        val outcome = Outcome.of(state, pending) ?: return
        if (!PushNotifications.permissionGranted(context)) return
        val (title, line) = when (outcome) {
            Outcome.sent -> L10n.text(context, "Sent") to null
            Outcome.failed -> L10n.text(context, "Payment failed") to null
            Outcome.stillPaying -> L10n.text(context, "Sending") to L10n.text(context, "Still paying. This can take a few minutes.")
        }
        val notification = builder(context).setContentTitle(title).setAutoCancel(true)
        // Still paying is no news: it stays as quiet as the ongoing notification was.
        if (line != null) notification.setContentText(line).setSilent(true)
        context.getSystemService(NotificationManager::class.java).notify(TAG, ID, notification.build())
    }

    /** Noah is in front again: Send shows the payment itself. */
    fun clear(context: Context) = context.getSystemService(NotificationManager::class.java).cancel(TAG, ID)

    private fun builder(context: Context): NotificationCompat.Builder {
        val open = PendingIntent.getActivity(context, ID, routeIntent(context, "com.noahwallet.${BuildConfig.APP_VARIANT}://widget/send".toUri()),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(context, NotificationChannels.UNIFIED_PUSH).setSmallIcon(R.drawable.ic_notification).setContentIntent(open)
    }
}
