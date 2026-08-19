package com.margelo.nitro.noahtools

import android.content.Context
import androidx.annotation.Keep
import androidx.work.Worker
import androidx.work.WorkerParameters

@Keep
class NoahPushMessageWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {
    override fun doWork(): Result {
        val message = inputData.getString(MESSAGE_KEY) ?: return Result.failure()
        val instance = inputData.getString(INSTANCE_KEY) ?: return Result.failure()

        return try {
            NoahNativeWalletRuntime.initializeNativeRuntime()
            when (NoahPushMessageProcessor(applicationContext).process(message, instance)) {
                NoahPushMessageProcessor.Result.COMPLETED,
                NoahPushMessageProcessor.Result.DISCARD -> Result.success()

                NoahPushMessageProcessor.Result.RETRY -> Result.retry()
            }
        } catch (error: Exception) {
            NoahToolsLogging.performNativeLog(
                "warn",
                "NoahPushMessageWorker",
                "Deferred UnifiedPush handling failed: ${error.message}"
            )
            Result.retry()
        }
    }

    companion object {
        internal const val MESSAGE_KEY = "message"
        internal const val INSTANCE_KEY = "instance"
    }
}
