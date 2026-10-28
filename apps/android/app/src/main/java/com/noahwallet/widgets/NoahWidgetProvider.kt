package com.noahwallet.widgets

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.widget.RemoteViews
import com.noahwallet.services.logging.AppLog
import com.noahwallet.services.logging.SafeErrorDescription
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The balance wave widget (docs/native-rewrite/design/widgets.md). It never opens the wallet: it reads the app's
 * snapshot, and old keys or none at all still render. One resizable widget, its layout chosen by size: a size map on
 * Android 12+, the options bundle's portrait and landscape sizes below that.
 */
open class NoahWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_WIDGET_DATA_CHANGED = "com.noahwallet.action.WIDGET_DATA_CHANGED"

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val updates = Mutex()
        /** The launcher's default before it reports a size: the 2×2. */
        private val fallback = SizeF(180f, 180f)
        /**
         * The launcher's report of every size it may show, in dp (Android 12+), all of them up to the 16 a size map takes:
         * the launcher shows the one that fits its slot, and without it Android falls back to the smallest (a Fold's 4×4
         * reports five, its own the fifth, and drew the 2×2 design).
         */
        @Suppress("DEPRECATION")
        internal fun sizes(options: Bundle): List<SizeF> {
            val reported = if (Build.VERSION.SDK_INT >= 31) options.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES).orEmpty()
            else emptyList()
            return reported.filter { it.width > 0 && it.height > 0 }.distinct().take(16).ifEmpty { orientations(options).toList().distinct() }
        }
        /**
         * Publishes [build]'s views. A launcher that still refuses them (its bitmap limit is lower than we took it to be)
         * gets them again with a quarter of the bitmap bytes, so a deleted wallet's balance never stays on screen.
         */
        internal fun publish(budget: Long, build: (Long) -> RemoteViews, update: (RemoteViews) -> Unit) {
            try { update(build(budget)) } catch (_: IllegalArgumentException) { update(build(budget / 4)) }
        }

        /** Portrait (min width, max height) and landscape (max width, min height), as Android 7–11 report them. */
        internal fun orientations(options: Bundle): Pair<SizeF, SizeF> {
            fun value(key: String) = options.getInt(key, 0).toFloat()
            val portrait = SizeF(value(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH), value(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT))
            val landscape = SizeF(value(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH), value(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT))
            fun known(size: SizeF) = if (size.width > 0 && size.height > 0) size else null
            val p = known(portrait) ?: known(landscape) ?: fallback
            return p to (known(landscape) ?: p)
        }
    }

    protected open val appGroup: String = "com.noahwallet.regtest"
    protected open val variant: String = "regtest"

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)

        // Handle widget update broadcast from within the app
        if (intent.action == ACTION_WIDGET_DATA_CHANGED) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(ComponentName(context, javaClass))
            if (appWidgetIds.isNotEmpty()) onUpdate(context, appWidgetManager, appWidgetIds)
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) = render(context, appWidgetManager, appWidgetIds)

    /** A resize, fold or rotation reports new sizes: each slot's wave is drawn for its own pixels. */
    override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) =
        render(context, appWidgetManager, intArrayOf(appWidgetId))

    private fun render(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val pending = goAsync()
        scope.launch {
            try {
                updates.withLock {
                    for (appWidgetId in appWidgetIds) updateWidget(context, appWidgetManager, appWidgetId)
                }
            } finally { pending.finish() }
        }
    }

    private fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
        @Suppress("TooGenericExceptionCaught") // broadcast boundary: a failed update is logged, never crashes the receiver
        try {
            val prefs = context.getSharedPreferences(appGroup, Context.MODE_PRIVATE)
            val snapshot = WidgetSnapshot.read(prefs)
            val content = snapshot?.let { WidgetContent.of(context, it, variant) }
            val density = context.resources.displayMetrics.density
            val options = appWidgetManager.getAppWidgetOptions(appWidgetId)
            // Every size travels in one update, within the launcher's bitmap budget.
            fun views(budget: Long) =
                if (Build.VERSION.SDK_INT >= 31) RemoteViews(WidgetViews.buildAll(context, variant, content, sizes(options), density, budget).views)
                else orientations(options).let { (portrait, landscape) ->
                    val built = WidgetViews.buildAll(context, variant, content, listOf(landscape, portrait).distinct(), density, budget).views
                    RemoteViews(built.getValue(landscape), built.getValue(portrait))
                }
            publish(WidgetViews.bitmapBudget(context), ::views) { appWidgetManager.updateAppWidget(appWidgetId, it) }
        } catch (e: Exception) {
            AppLog(context.applicationContext, "NoahWidget").e("Error updating widget: ${SafeErrorDescription.of(e)}")
        }
    }
}
