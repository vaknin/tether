package com.kivan.tether

import android.app.ForegroundServiceStartNotAllowedException
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The foreground service that keeps the process up while the phone is linked. Started by an FCM
 * wake or a share (a short sync: dial, let both outboxes drain, stop when the link closes, which
 * the core does after 60 s without traffic), by media playing (stays while "media" is held), or
 * by a ring (stays while it rings). The visible notification is what Android requires for
 * high-priority FCM.
 */
class SyncService : Service() {
    private var job: Job? = null
    private var linked = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val reason = intent?.getStringExtra(REASON)
        linked = Core.MEDIA in Core.held.value
        startForeground(Notifier.SYNC_ID, Notifier.sync(this, linked), ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)
        Log.i("Tether", "sync: $reason")
        if (job?.isActive == true) {
            if (reason in DIALS) Core.scope.launch { Core.connect() }
            return START_NOT_STICKY
        }
        job = Core.scope.launch {
            try {
                if (reason in DIALS) sync()
                stayWhileNeeded()
            } catch (e: Exception) {
                Log.w("Tether", "sync failed", e)
            } finally {
                Core.release(HOLD)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun sync() {
        Core.acquire(HOLD)
        if (!Core.connect()) {
            delay(5_000)
            Core.connect()
        }
    }

    /**
     * Returns once nothing needs the process: no media hold, no ring, and the link closed. A plain
     * sync also gives up after [MAX_MS] (a transfer in flight extends it); once media or a ring has
     * kept the service, the link's own idle close is what ends it.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun stayWhileNeeded() {
        val deadline = SystemClock.elapsedRealtime() + MAX_MS
        var extended = false
        combine(Core.held, Core.connected, Ringer.ringing) { h, c, r -> Triple(Core.MEDIA in h, c, r) }
            .distinctUntilChanged()
            .transformLatest { (media, connected, ringing) ->
                showLinked(media)
                if (media || ringing) {
                    extended = true
                    return@transformLatest
                }
                if (!connected) return@transformLatest emit(Unit)
                if (extended) return@transformLatest
                delay(deadline - SystemClock.elapsedRealtime())
                while (Core.progress.value.isNotEmpty()) {
                    withTimeoutOrNull(MAX_MS) { Core.progress.first { it.isEmpty() } }
                }
                emit(Unit)
            }
            .first()
    }

    private fun showLinked(media: Boolean) {
        if (media == linked) return
        linked = media
        getSystemService(NotificationManager::class.java).notify(Notifier.SYNC_ID, Notifier.sync(this, media))
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
        /** Reasons that dial and hold the node themselves; media and ring arrive with their own hold or link. */
        private val DIALS = setOf("wake", "share")

        fun start(context: Context, reason: String) {
            context.startForegroundService(Intent(context, SyncService::class.java).putExtra(REASON, reason))
        }

        /**
         * From the background (the notification listener, a ring) a foreground service may start
         * only with an exemption, in practice "battery: Unrestricted". Without one the caller still
         * holds and connects, just with no service keeping the process up.
         */
        fun tryStart(context: Context, reason: String): Boolean = try {
            start(context, reason)
            true
        } catch (e: ForegroundServiceStartNotAllowedException) {
            Log.i("Tether", "no foreground service for $reason: ${e.message}")
            false
        }
    }
}
