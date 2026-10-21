package com.noahwallet.services.push

import android.annotation.SuppressLint
import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import com.noahwallet.localization.L10n
import com.noahwallet.localization.NotificationCopy
import com.noahwallet.MainActivity
import com.noahwallet.R
import com.noahwallet.notifications.NotificationChannels

object PushNotifications {
    fun permissionGranted(context: Context): Boolean = Build.VERSION.SDK_INT < 33 ||
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    // Expo/RN created this channel with MAX (5); retain that contract even though
    // Android's channel @IntDef lists HIGH as its public maximum. Existing channels are untouched.
    @SuppressLint("WrongConstant")
    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(NotificationChannels.DEFAULT) == null) {
            manager.createNotificationChannel(NotificationChannel(NotificationChannels.DEFAULT, L10n.text(context, "Default"), NotificationManager.IMPORTANCE_MAX).apply {
                vibrationPattern = longArrayOf(0, 250, 250, 250)
                lightColor = Color.parseColor("#FF231F7C")
            })
        }
        if (manager.getNotificationChannel(NotificationChannels.UNIFIED_PUSH) == null) {
            manager.createNotificationChannel(NotificationChannel(NotificationChannels.UNIFIED_PUSH, L10n.text(context, "Noah notifications"), NotificationManager.IMPORTANCE_HIGH))
        }
    }

    fun received(context: Context, sats: Long, usd: String?, paymentHash: String) {
        ensureChannels(context)
        if (!permissionGranted(context)) return
        val unit = com.noahwallet.services.storage.SettingsPreferences.amountUnit(com.noahwallet.services.storage.SettingsStorage(context).string("profile-storage"))
        val copy = NotificationCopy.received(context, sats, usd, unit)
        val intent = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, NotificationChannels.DEFAULT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(copy.title)
            .setContentText(copy.body)
            .setDefaults(android.app.Notification.DEFAULT_SOUND)
            .setContentIntent(intent).setAutoCancel(true).setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_MAX).build()
        context.getSystemService(NotificationManager::class.java).notify("lightning-$paymentHash", 1, notification)
    }

    /** Expo sends visible receipts as data title/message; FCM also supports a notification envelope. */
    fun remote(context: Context, message: com.google.firebase.messaging.RemoteMessage) {
        val title = message.notification?.title ?: message.data["title"]
        val body = message.notification?.body ?: message.data["message"]
        if (title.isNullOrEmpty() && body.isNullOrEmpty()) return
        ensureChannels(context)
        if (!permissionGranted(context)) return
        val intent = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, NotificationChannels.DEFAULT)
            .setSmallIcon(R.drawable.ic_notification).setContentTitle(title).setContentText(body)
            .setContentIntent(intent).setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_HIGH)
        if ((message.notification?.sound ?: message.data["sound"]) in listOf(null, "default"))
            notification.setDefaults(android.app.Notification.DEFAULT_SOUND)
        context.getSystemService(NotificationManager::class.java).notify(message.messageId ?: java.util.UUID.randomUUID().toString(), 1, notification.build())
    }

    fun foreground(context: Context): ForegroundInfo {
        ensureChannels(context)
        val notification = NotificationCompat.Builder(context, NotificationChannels.UNIFIED_PUSH)
            .setSmallIcon(R.drawable.ic_notification).setContentTitle(L10n.text(context, "Updating Noah wallet"))
            .setOngoing(true).setSilent(true).build()
        return ForegroundInfo(1001, notification)
    }
}
