package com.margelo.nitro.noahtools

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

internal object NoahPushMessageRetryScheduler {
    private const val RETRY_BACKOFF_SECONDS = 10L

    fun enqueue(context: Context, message: String, instance: String) {
        val inputData = Data.Builder()
            .putString(NoahPushMessageWorker.MESSAGE_KEY, message)
            .putString(NoahPushMessageWorker.INSTANCE_KEY, instance)
            .build()
        val request = OneTimeWorkRequestBuilder<NoahPushMessageWorker>()
            .setInputData(inputData)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                RETRY_BACKOFF_SECONDS,
                TimeUnit.SECONDS
            )
            .build()

        WorkManager.getInstance(context).enqueue(request)
        NoahToolsLogging.performNativeLog(
            "info",
            "NoahPushService",
            "Queued UnifiedPush message for durable retry"
        )
    }
}
