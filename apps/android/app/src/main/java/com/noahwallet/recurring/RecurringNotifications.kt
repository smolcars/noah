package com.noahwallet.recurring

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.noahwallet.BuildConfig
import com.noahwallet.MainActivity
import com.noahwallet.R
import com.noahwallet.notifications.NotificationChannels
import com.noahwallet.services.push.PushNotifications
import com.noahwallet.services.storage.*
import java.util.concurrent.TimeUnit

/** Advisory only. WorkManager preserves reminders across process death and reboot. */
object RecurringNotifications {
    private const val tag = "noah-recurring-reminder"
    @Synchronized fun refresh(context: Context, schedules: List<RecurringPayment>, result: RecurringPayment?) {
        runCatching {
            if (result != null) {
                val (title, body) = when {
                    result.status == "needs_approval" -> "Recurring payment needs approval" to "Automatic payment to ${result.label} was skipped. Open the schedule to review its fee and pay manually."
                    result.status == "needs_attention" -> "Recurring payment needs your attention" to "A payment to ${result.label} was interrupted. Check your history, then resume it."
                    result.runs.firstOrNull()?.status == "failed" -> "Recurring payment will retry" to "${result.amountSat} sats to ${result.label} could not be sent yet."
                    else -> "Recurring payment sent" to "${result.amountSat} sats sent to ${result.label}."
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
            val settings = SettingsStorage(applicationContext)
            val store = RecurringStore(settings)
            val location = WalletDataLocation(applicationContext.filesDir, BuildConfig.APP_VARIANT)
            val s = store.reminderSchedule(java.io.File(location.directory, "noah-recurring-journal.json.policies"),
                { RecurringStore.binding(location) }, inputData.getString("id"), inputData.getLong("due", -1)) ?: return Result.success()
            val wallet = settings.persistedState<PersistedWalletState>(PersistedWalletState.key)
            if (wallet?.isInitialized == false || wallet?.isWalletSuspended == true) return Result.success()
            val reminder = inputData.getString("kind") == "reminder"
            RecurringNotifications.show(applicationContext, "recurring-${s.id}-${inputData.getString("kind")}",
                if (reminder) "Upcoming recurring payment" else "Recurring payment waiting",
                if (reminder) "${s.amountSat} sats to ${s.label} will be sent tomorrow." else "Open Noah to send ${s.amountSat} sats to ${s.label}.")
            Result.success()
        } catch (_: Exception) { Result.failure() }
    }
}

/** The worker uses the same policy/automation authority as Send before displaying a reminder. */
internal fun RecurringStore.reminderSchedule(ledger: java.io.File, binding: () -> RecurringStore.Binding,
    id: String?, due: Long, sync: (java.io.File) -> Unit = ::syncDirectory,
    syncFile: (java.io.FileDescriptor) -> Unit = { it.sync() }): RecurringPayment? {
    protectPolicies(ledger, sync, syncFile, binding)
    val state = load()
    return state.schedules[id]?.takeIf { it.status == "active" && it.inFlight == null && it.nextRunAt == due && state.binding == binding() }
}
