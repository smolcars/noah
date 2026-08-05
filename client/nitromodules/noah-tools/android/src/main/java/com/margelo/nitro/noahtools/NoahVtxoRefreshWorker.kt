package com.margelo.nitro.noahtools

import android.app.ActivityManager
import android.content.Context
import androidx.annotation.Keep
import androidx.work.Worker
import androidx.work.WorkerParameters

@Keep
class NoahVtxoRefreshWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {
    override fun doWork(): Result {
        if (isMainActivityForeground()) {
            log("debug", "Skipping periodic refresh while Noah is foregrounded")
            return Result.success()
        }

        val hasMnemonic = runCatching {
            !NoahNativeWalletRuntime.readMnemonic(applicationContext).isNullOrEmpty()
        }.getOrElse { error ->
            log("warn", "Unable to read native wallet state: ${error.message}")
            false
        }
        if (!hasMnemonic) {
            log("debug", "Skipping periodic refresh because no native wallet is available")
            return Result.success()
        }

        val owner = "periodic-sync:$id"
        if (!NoahBackgroundWalletLease.tryAcquire(owner)) {
            log("debug", "Retrying periodic refresh because another wallet job is active")
            return Result.retry()
        }

        val startedAt = System.currentTimeMillis()
        return try {
            if (isMainActivityForeground()) {
                log("debug", "Skipping periodic refresh because Noah became foregrounded")
                Result.success()
            } else {
                log("info", "Starting periodic VTXO refresh")
                NoahNativeWalletRuntime.runMaintenanceDelegated(applicationContext)
                log(
                    "info",
                    "Periodic VTXO refresh completed in ${System.currentTimeMillis() - startedAt}ms"
                )
                Result.success()
            }
        } catch (error: Exception) {
            log("error", "Periodic VTXO refresh failed: ${rootMessage(error)}")
            if (runAttemptCount < MAX_RETRY_ATTEMPTS) {
                Result.retry()
            } else {
                // Keep the periodic request alive; a future interval can recover.
                Result.success()
            }
        } finally {
            NoahBackgroundWalletLease.release(owner)
        }
    }

    private fun isMainActivityForeground(): Boolean {
        return try {
            val activityManager =
                applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            activityManager.appTasks.any { task ->
                val topActivity = task.taskInfo?.topActivity
                topActivity?.packageName == applicationContext.packageName &&
                    topActivity.className.endsWith(".MainActivity")
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun rootMessage(error: Throwable): String {
        var current = error
        while (current.cause != null) {
            current = current.cause!!
        }
        return current.message ?: current.javaClass.simpleName
    }

    private fun log(level: String, message: String) {
        NoahToolsLogging.performNativeLog(level, TAG, message)
    }

    companion object {
        private const val TAG = "NoahVtxoRefreshWorker"
        private const val MAX_RETRY_ATTEMPTS = 2
    }
}
