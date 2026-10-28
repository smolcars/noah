package com.noahwallet.widgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.core.graphics.toColorInt
import com.noahwallet.MainActivity
import com.noahwallet.R
import com.noahwallet.localization.L10n
import com.noahwallet.services.logging.AppLog
import com.noahwallet.services.logging.SafeErrorDescription
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.NumberFormat
import java.util.Locale

open class NoahWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_WIDGET_DATA_CHANGED = "com.noahwallet.action.WIDGET_DATA_CHANGED"

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val updates = Mutex()
        private val numberFormatter: NumberFormat by lazy {
            NumberFormat.getNumberInstance(Locale.US).apply {
                maximumFractionDigits = 0
                isGroupingUsed = true
            }
        }
    }

    protected open val appGroup: String = "com.noahwallet.regtest"
    protected open val variantName: String? = "REGTEST"
    protected open val layoutResId: Int = R.layout.widget_noah
    protected open val badgeBackgroundResId: Int = R.drawable.badge_background

    override fun onReceive(context: Context, intent: android.content.Intent) {
        super.onReceive(context, intent)

        // Handle widget update broadcast from within the app
        if (intent.action == ACTION_WIDGET_DATA_CHANGED) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val componentName = android.content.ComponentName(context, javaClass)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)

            if (appWidgetIds.isNotEmpty()) {
                onUpdate(context, appWidgetManager, appWidgetIds)
            }
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        val pending = goAsync()
        scope.launch {
            try {
                updates.withLock {
                    for (appWidgetId in appWidgetIds) updateWidget(context, appWidgetManager, appWidgetId)
                }
            } finally { pending.finish() }
        }
    }

    private fun updateWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int
    ) {
        val prefs = context.getSharedPreferences(appGroup, Context.MODE_PRIVATE)

        val snapshot = WidgetSnapshot.read(prefs)

        @Suppress("TooGenericExceptionCaught") // broadcast boundary: a failed update is logged, never crashes the receiver
        try {
            val views = RemoteViews(context.packageName, layoutResId)

            // Set click listener to open app
            val intent = Intent(context, MainActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)

            // Labels follow the app's language, which the layout's resources can't know
            views.setTextViewText(R.id.wallet_title, L10n.text(context, "Noah Wallet"))
            views.setTextViewText(R.id.onchain_label, L10n.text(context, "Onchain"))
            views.setTextViewText(R.id.offchain_label, L10n.text(context, "Offchain"))

            // Set balance text
            views.setTextViewText(R.id.balance_text, "₿\u00A0${formatSats(snapshot.total)}")

            // Set onchain/offchain balances with compact formatting
            views.setTextViewText(R.id.onchain_balance, formatSatsCompact(snapshot.onchain))
            views.setTextViewText(R.id.offchain_balance, formatSatsCompact(snapshot.offchain))

            // Show/hide pending balance
            if (snapshot.pending > 0) {
                views.setTextViewText(R.id.pending_balance, L10n.text(context, "Pending: {amount}", "₿\u00A0${formatSats(snapshot.pending)}"))
                views.setViewVisibility(R.id.pending_balance, android.view.View.VISIBLE)
            } else {
                views.setViewVisibility(R.id.pending_balance, android.view.View.GONE)
            }

            // Set variant badge if present
            if (variantName != null) {
                views.setTextViewText(R.id.variant_badge, variantName)
                views.setInt(R.id.variant_badge, "setBackgroundResource", badgeBackgroundResId)
                views.setViewVisibility(R.id.variant_badge, android.view.View.VISIBLE)
            } else {
                views.setViewVisibility(R.id.variant_badge, android.view.View.GONE)
            }

            // Set expiry status - only show if VTXOs exist (not -999 sentinel)
            if (snapshot.closestExpiryBlocks != -999L) {
                val expiryStatus = getExpiryStatus(snapshot.closestExpiryBlocks, snapshot.expiryThreshold)
                views.setTextViewText(R.id.expiry_text, L10n.plural(context, "VTXO expires: {n} blocks", snapshot.closestExpiryBlocks.toInt()))
                views.setTextColor(R.id.expiry_text, expiryStatus.color)
                views.setInt(R.id.expiry_icon, "setColorFilter", expiryStatus.color)
                views.setImageViewResource(R.id.expiry_icon, expiryStatus.icon)
                views.setViewVisibility(R.id.expiry_icon, android.view.View.VISIBLE)
                views.setViewVisibility(R.id.expiry_text, android.view.View.VISIBLE)
            } else {
                // Hide expiry section when no VTXOs
                views.setViewVisibility(R.id.expiry_icon, android.view.View.GONE)
                views.setViewVisibility(R.id.expiry_text, android.view.View.GONE)
            }

            appWidgetManager.updateAppWidget(appWidgetId, views)
        } catch (e: Exception) {
            AppLog(context.applicationContext, "NoahWidget").e("Error updating widget: ${SafeErrorDescription.of(e)}")
        }
    }

    private data class ExpiryStatus(val icon: Int, val color: Int)

    private fun getExpiryStatus(blocks: Long, threshold: Long): ExpiryStatus {
        val colorRed = "#EF4444".toColorInt()
        val colorOrange = "#F97316".toColorInt()
        val colorGreen = "#22C55E".toColorInt()

        // Critical: within 20% of threshold (e.g., < 58 blocks if threshold is 288)
        // Also includes expired VTXOs (negative blocks).
        val criticalThreshold = (threshold * 0.2).toLong()

        return when {
            blocks <= criticalThreshold -> ExpiryStatus(
                android.R.drawable.ic_dialog_alert,
                colorRed // Red
            )

            blocks <= threshold -> ExpiryStatus(
                android.R.drawable.ic_lock_idle_alarm,
                colorOrange // Orange
            )

            else -> ExpiryStatus(
                android.R.drawable.checkbox_on_background,
                colorGreen // Green
            )
        }
    }

    private fun formatSats(value: Long): String = numberFormatter.format(value)

    private fun formatSatsCompact(value: Long): String = when {
        value >= 1_000_000 -> {
            val millions = value / 1_000_000.0
            String.format(Locale.US, "%.1fM", millions)
        }

        value >= 100_000 -> {
            val thousands = value / 1_000.0
            String.format(Locale.US, "%.0fK", thousands)
        }

        value >= 10_000 -> {
            val thousands = value / 1_000.0
            String.format(Locale.US, "%.1fK", thousands)
        }

        else -> formatSats(value)
    }

    override fun onEnabled(context: Context) {
        // Called when the first widget is created
    }

    override fun onDisabled(context: Context) {
        // Called when the last widget is removed
    }
}
