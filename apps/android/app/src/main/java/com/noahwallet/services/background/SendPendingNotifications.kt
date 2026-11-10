package com.noahwallet.services.background

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
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

    /**
     * The ongoing notification while the service runs, on its own quiet channel. [slow]: the payment has run past
     * "Paying". Android 16 draws it as a Live Update: three steps (locked, paying, done) with the first filled.
     */
    fun sending(context: Context, slow: Boolean = false): Notification {
        ensureChannel(context)
        val line = if (slow) L10n.text(context, "Still paying. This can take a few minutes.") else L10n.text(context, "Paying over Lightning")
        val notification = builder(context, NotificationChannels.PAYMENTS).setContentTitle(L10n.text(context, "Sending")).setContentText(line)
            .setOngoing(true).setSilent(true).setOnlyAlertOnce(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            val step = NotificationCompat.ProgressStyle.Segment(1)
            notification.setStyle(NotificationCompat.ProgressStyle().setProgressSegments(listOf(step, step, step)).setProgress(1))
                .setShortCriticalText(L10n.text(context, "Sending")).setRequestPromotedOngoing(true)
        } else notification.setProgress(0, 0, true)
        return notification.build()
    }

    /** Importance DEFAULT, without sound: lower would keep Android 16 from promoting it. Creating it again only
     *  renames it, so its name follows the app's language. */
    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(NotificationChannels.PAYMENTS, L10n.text(context, "Payments in progress"),
            NotificationManager.IMPORTANCE_DEFAULT).apply { setSound(null, null); enableVibration(false) })
    }

    /** Replaces the ongoing notification once the service stops with Noah out of sight. */
    fun finished(context: Context, state: SendController.State, pending: Boolean) {
        val outcome = Outcome.of(state, pending) ?: return
        if (!PushNotifications.permissionGranted(context)) return
        val (title, line) = when (outcome) {
            Outcome.sent -> L10n.text(context, "Sent") to null
            Outcome.failed -> L10n.text(context, "Payment failed") to null
            // Nothing checks it any more: the service has stopped.
            Outcome.stillPaying -> L10n.text(context, "Sending") to L10n.text(context, "Open Noah to check")
        }
        PushNotifications.ensureChannels(context)
        val notification = builder(context, NotificationChannels.UNIFIED_PUSH).setContentTitle(title).setAutoCancel(true)
        // Still paying is no news: it stays as quiet as the ongoing notification was.
        if (line != null) notification.setContentText(line).setSilent(true)
        context.getSystemService(NotificationManager::class.java).notify(TAG, ID, notification.build())
    }

    /** The service's notification after ten seconds of paying, if it is still running. */
    fun slow(context: Context) = context.getSystemService(NotificationManager::class.java).notify(ID, sending(context, slow = true))

    /** Noah is in front again: Send shows the payment itself. */
    fun clear(context: Context) = context.getSystemService(NotificationManager::class.java).cancel(TAG, ID)

    private fun builder(context: Context, channel: String): NotificationCompat.Builder {
        val open = PendingIntent.getActivity(context, ID, routeIntent(context, "com.noahwallet.${BuildConfig.APP_VARIANT}://widget/send".toUri()),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(context, channel).setSmallIcon(R.drawable.ic_notification).setContentIntent(open)
    }
}
