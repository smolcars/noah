package com.noahwallet.recurring

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.noahwallet.localization.L10n
import com.noahwallet.localization.NotificationCopy
import com.noahwallet.MainActivity
import com.noahwallet.R
import com.noahwallet.notifications.NotificationChannels
import com.noahwallet.services.push.PushNotifications
import java.util.concurrent.TimeUnit

/** Advisory only. WorkManager preserves reminders across process death and reboot. */
object RecurringNotifications {
    private const val tag = "noah-recurring-reminder"
    @Synchronized fun refresh(context: Context, schedules: List<RecurringPayment>, result: RecurringPayment?) {
        runCatching {
            if (result != null) {
                val amount = amount(context, result.amountSat)
                val (title, body) = when {
                    result.status == "needs_approval" -> L10n.text(context, "Recurring payment needs approval") to L10n.text(context, "Automatic payment to {recipient} was skipped. Open the schedule to review its fee and pay manually.", result.label)
                    result.status == "needs_attention" -> L10n.text(context, "Recurring payment needs your attention") to L10n.text(context, "A payment to {recipient} was interrupted. Check your history, then resume it.", result.label)
                    result.runs.firstOrNull()?.status == "failed" -> L10n.text(context, "Recurring payment will retry") to L10n.text(context, "{amount} to {recipient} could not be sent yet: {error}", amount, result.label, result.issue?.message(context) ?: L10n.text(context, result.lastError ?: "unknown error"))
                    else -> L10n.text(context, "Recurring payment sent") to L10n.text(context, "{amount} sent to {recipient}.", amount, result.label)
                }
                show(context, "recurring-result-${result.id}-${result.updatedAt}", title, body)
                return
            }
            val work = WorkManager.getInstance(context)
            work.cancelAllWorkByTag(tag)
            for (s in schedules.filter { it.status == "active" && it.inFlight == null && it.nextRunAt != null }) {
                for ((kind, at) in listOf("reminder" to s.nextRunAt!! - 86_400_000, "overdue" to s.nextRunAt + 7_200_000)) {
                    val delay = at - System.currentTimeMillis()
                    if (delay <= 0) continue
                    work.enqueueUniqueWork("recurring-${s.id}-$kind", ExistingWorkPolicy.REPLACE,
                        OneTimeWorkRequestBuilder<RecurringReminderWorker>().addTag(tag).setInitialDelay(delay, TimeUnit.MILLISECONDS)
                            .setInputData(workDataOf("id" to s.id, "due" to s.nextRunAt, "kind" to kind)).build())
                }
            }
        }
    }
    fun amount(context: Context, sats: Long): String {
        val unit = com.noahwallet.services.storage.SettingsPreferences.amountUnit(com.noahwallet.services.storage.SettingsStorage(context).string("profile-storage"))
        return com.noahwallet.formatting.BitcoinAmount.format(sats, unit, java.util.Locale.forLanguageTag(com.noahwallet.services.storage.LegacyLocale.resolved(context)))
    }
    fun show(context: Context, id: String, title: String, body: String) {
        PushNotifications.ensureChannels(context)
        if (!PushNotifications.permissionGranted(context)) return
        val intent = PendingIntent.getActivity(context, 701, Intent(context, MainActivity::class.java).putExtra("recurring", true), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, NotificationChannels.DEFAULT).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title).setContentText(body).setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(intent).setAutoCancel(true).build()
        context.getSystemService(NotificationManager::class.java).notify(id, 7, notification)
    }
}
class RecurringReminderWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        return try {
            if (com.noahwallet.services.launch.LaunchState.current(applicationContext) != com.noahwallet.services.launch.LaunchState.existingWallet) return Result.success()
            val s = RecurringRuntime.shared(applicationContext).executor.reminderSchedule(
                inputData.getString("id"), inputData.getLong("due", -1)) ?: return Result.success()
            val reminder = inputData.getString("kind") == "reminder"
            val copy = NotificationCopy.reminder(applicationContext, RecurringNotifications.amount(applicationContext, s.amountSat), s.label, reminder)
            RecurringNotifications.show(applicationContext, "recurring-${s.id}-${inputData.getString("kind")}", copy.title, copy.body)
            Result.success()
        } catch (_: Exception) { Result.failure() }
    }
}
