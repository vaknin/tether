package com.kivan.tether

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.kivan.tether.core.TetherNode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A short sync after an FCM wake, a share, a reply from the notification or Retry: dial, let both
 * outboxes drain, then close. It runs as an expedited job, which needs no notification (the chat,
 * file or ring the sync brings is the visible part). Expedited time is a daily quota, so it ends as
 * soon as the link has gone quiet rather than waiting out the 60 s idle close. A long transfer
 * moves to [TransferService].
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        Log.i("Tether", "sync: ${inputData.getString(REASON)}")
        val node = Core.acquire(HOLD)
        try {
            if (!Core.connect()) {
                delay(5_000)
                // Still unreachable: anything of mine stays queued, and OutboxWorker retries it.
                if (!Core.connect()) return Result.success()
            }
            withTimeoutOrNull(MAX_MS) { untilDone(node) }
        } finally {
            Core.release(HOLD, close = true)
        }
        return Result.success()
    }

    /**
     * Returns once the link closed, or nothing is left to do: my outbox empty, no transfer, no
     * ring, the laptop not asking the link to stay (`tether notifications --fresh`), and no event
     * for [QUIET_MS], which covers the laptop's own resend.
     */
    private suspend fun untilDone(node: TetherNode) {
        while (Core.connected.value) {
            val quietFor = SystemClock.elapsedRealtime() - maxOf(Core.lastEventAt, kicked)
            val busy = node.status().queued > 0uL || Core.progress.value.isNotEmpty() ||
                node.peerStays() || Ringer.ringing.value
            if (!busy && quietFor >= QUIET_MS) return
            delay(1_000)
        }
    }

    companion object {
        private const val HOLD = "sync"
        private const val REASON = "reason"
        private const val QUIET_MS = 10_000L
        private const val MAX_MS = 5 * 60_000L

        /** A start while a sync runs: it dials now and waits for quiet again. */
        @Volatile private var kicked = 0L

        fun start(context: Context, reason: String) {
            kicked = SystemClock.elapsedRealtime()
            Core.scope.launch { Core.connect() }
            val req = OneTimeWorkRequestBuilder<SyncWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setInputData(workDataOf(REASON to reason))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("sync", ExistingWorkPolicy.KEEP, req)
        }
    }
}
