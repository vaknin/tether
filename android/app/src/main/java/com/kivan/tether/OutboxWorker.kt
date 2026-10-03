package com.kivan.tether

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/**
 * Retries the phone's own outbox when the laptop couldn't be reached (asleep, offline). Scheduled
 * only while something is queued, with exponential backoff, and only with a network.
 */
class OutboxWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val node = Core.acquire(HOLD)
        try {
            if (!Core.connect()) {
                Notifier.stuck(applicationContext, node.status())
                return Result.retry()
            }
            withTimeoutOrNull(120_000) {
                while (node.status().queued > 0uL && Core.connected.value) {
                    withTimeoutOrNull(2_000) { Core.connected.first { !it } }
                }
            }
            val status = node.status()
            Notifier.stuck(applicationContext, status)
            return if (status.queued > 0uL) Result.retry() else Result.success()
        } finally {
            Core.release(HOLD)
        }
    }

    companion object {
        private const val HOLD = "outbox"

        fun schedule(context: Context) {
            val req = OneTimeWorkRequestBuilder<OutboxWorker>()
                .setInitialDelay(30, TimeUnit.SECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("outbox", ExistingWorkPolicy.KEEP, req)
        }
    }
}
