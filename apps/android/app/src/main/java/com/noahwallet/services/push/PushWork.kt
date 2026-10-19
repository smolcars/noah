package com.noahwallet.services.push

import android.content.Context
import androidx.work.*
import com.noahwallet.api.generated.models.NotificationData
import com.noahwallet.services.AppServices
import com.noahwallet.services.logging.AppLog
import kotlinx.coroutines.CancellationException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

object PushWork {
    internal const val PAYLOAD = "payload"
    fun enqueue(context: Context, notification: NotificationData, expedited: Boolean) {
        val payload = PushPayload.encode(notification)
        val digest = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray()).joinToString("") { "%02x".format(it) }
        val work = OneTimeWorkRequestBuilder<PushWorker>().addTag("noah-push").setInputData(workDataOf(PAYLOAD to payload))
        if (expedited) work.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        // Coalesce duplicate deliveries while the same job is pending/running.
        WorkManager.getInstance(context).enqueueUniqueWork("push-$digest", ExistingWorkPolicy.KEEP, work.build())
    }

    fun register(context: Context) {
        val work = OneTimeWorkRequestBuilder<PushRegistrationWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        // An endpoint/token callback arriving during registration must get its own turn.
        WorkManager.getInstance(context).enqueueUniqueWork("push-registration", ExistingWorkPolicy.APPEND_OR_REPLACE, work)
    }
}

class PushWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val log = AppLog(applicationContext, "push")
        return try {
            val payload = inputData.getString(PushWork.PAYLOAD) ?: return Result.failure()
            PushRuntime(applicationContext, AppServices.shared(applicationContext)).jobs.handle(PushPayload.decode(payload))
            log.i("Push job finished")
            Result.success()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            // Don't replay an ambiguously completed wallet operation. The server coordinates later work.
            log.w("Push job failed")
            Result.failure()
        }
    }

    // WorkManager uses a foreground service for expedited work on Android < 12.
    override suspend fun getForegroundInfo(): ForegroundInfo = PushNotifications.foreground(applicationContext)
}

class PushRegistrationWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        PushRuntime(applicationContext, AppServices.shared(applicationContext)).register()
        Result.success()
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) {
        AppLog(applicationContext, "push").w("Push registration failed; queued for retry")
        Result.retry()
    }
}
