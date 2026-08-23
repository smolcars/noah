package com.margelo.nitro.noahtools

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

internal object NoahBackgroundSyncScheduler {
    private const val UNIQUE_WORK_NAME = "NOAH_VTXO_REFRESH"
    private const val DEBUG_INTERVAL_MINUTES = 15L
    private const val RELEASE_INTERVAL_HOURS = 2L
    private const val RETRY_BACKOFF_MINUTES = 30L

    fun schedule(context: Context) {
        val requestBuilder = if (BuildConfig.DEBUG) {
            PeriodicWorkRequestBuilder<NoahVtxoRefreshWorker>(
                DEBUG_INTERVAL_MINUTES,
                TimeUnit.MINUTES
            )
        } else {
            PeriodicWorkRequestBuilder<NoahVtxoRefreshWorker>(
                RELEASE_INTERVAL_HOURS,
                TimeUnit.HOURS
            )
        }

        val request = requestBuilder
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                RETRY_BACKOFF_MINUTES,
                TimeUnit.MINUTES
            )
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
    }
}
