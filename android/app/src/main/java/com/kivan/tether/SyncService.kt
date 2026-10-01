package com.kivan.tether

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A short foreground sync, started by an FCM wake or a share: start the node, dial the laptop,
 * let both outboxes drain, and stop when the link closes (the core closes it after 60 s without
 * traffic). The visible notification is what Android requires for high-priority FCM.
 */
class SyncService : Service() {
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(Notifier.SYNC_ID, Notifier.sync(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)
        Log.i("Tether", "sync: ${intent?.getStringExtra(REASON)}")
        if (job?.isActive == true) {
            Core.scope.launch { Core.connect() }
            return START_NOT_STICKY
        }
        job = Core.scope.launch {
            try {
                Core.acquire(HOLD)
                var ok = Core.connect()
                if (!ok) {
                    delay(5_000)
                    ok = Core.connect()
                }
                if (ok) {
                    // Safety cap; a transfer in flight keeps the link (and so the service) alive.
                    while (withTimeoutOrNull(MAX_MS) { Core.connected.first { !it } } == null) {
                        if (Core.progress.value.isEmpty()) break
                    }
                }
            } catch (e: Exception) {
                Log.w("Tether", "sync failed", e)
            } finally {
                Core.release(HOLD)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    override fun onDestroy() {
        job?.cancel()
        Core.release(HOLD)
        super.onDestroy()
    }

    companion object {
        private const val HOLD = "sync"
        private const val REASON = "reason"
        private const val MAX_MS = 5 * 60_000L

        fun start(context: Context, reason: String) {
            context.startForegroundService(Intent(context, SyncService::class.java).putExtra(REASON, reason))
        }
    }
}
