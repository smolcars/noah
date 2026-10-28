package com.noahwallet.ui.launch

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailabilityLight

// API 33 POST_NOTIFICATIONS is only used as a string or behind SDK checks; before 26 the notification settings action fails over to app settings.
@android.annotation.SuppressLint("InlinedApi")
object PlatformAccess {
    fun unifiedPush(context: Context) = GoogleApiAvailabilityLight.getInstance().isGooglePlayServicesAvailable(context) != ConnectionResult.SUCCESS
    fun physical() = !(Build.FINGERPRINT.startsWith("generic") || Build.FINGERPRINT.startsWith("unknown") ||
        Build.MODEL.contains("google_sdk") || Build.MODEL.contains("""Emulator""") || Build.MODEL.contains("""Android SDK built for""") ||
        Build.HARDWARE in listOf("goldfish", "ranchu") || Build.PRODUCT.contains("sdk"))
    fun permission(context: Context): PlatformGateRules.Permission {
        // Expo also reports DENIED when the notification manager is disabled, even before the first request.
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return PlatformGateRules.Permission.denied
        if (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
            return PlatformGateRules.Permission.granted
        return if (context.getSharedPreferences("expo.modules.permissions.asked", Context.MODE_PRIVATE)
                .getBoolean(Manifest.permission.POST_NOTIFICATIONS, false)) PlatformGateRules.Permission.denied
        else PlatformGateRules.Permission.undetermined
    }
    fun batteryEnabled(context: Context) = !(context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) ?: true)
    // Background payments need the exemption; the user asks for it from the launch gate.
    @android.annotation.SuppressLint("BatteryLife")
    fun openBattery(context: Context): Boolean = listOf(
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri()),
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()),
    ).any { intent -> runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false) }
    fun openSettings(context: Context) = context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        "package:${context.packageName}".toUri()))
    /** The app's own notification settings; after two denials Android no longer shows the prompt. */
    fun openNotificationSettings(context: Context) {
        val notifications = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        if (runCatching { context.startActivity(notifications) }.isFailure) openSettings(context)
    }
    /** Expo's flag, shared with RN: set before the first prompt so a later denial reads as denied. */
    fun markNotificationsAsked(context: Context) = context.getSharedPreferences("expo.modules.permissions.asked", Context.MODE_PRIVATE)
        .edit { putBoolean(Manifest.permission.POST_NOTIFICATIONS, true) }
    fun notificationsAsked(context: Context) = context.getSharedPreferences("expo.modules.permissions.asked", Context.MODE_PRIVATE)
        .getBoolean(Manifest.permission.POST_NOTIFICATIONS, false)
}
