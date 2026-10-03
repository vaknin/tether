package com.kivan.tether

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.launch

/**
 * Keeps the process up through a long file transfer. Its notification is the transfer's own
 * progress notification ([Transfers]), so it adds nothing to the shade. Started once a transfer
 * has run for 2 s, stopped when the batch ends.
 */
class TransferService : Service() {
    private var holding = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForeground is owed even if the batch just ended; stop right after.
        try {
            startForeground(Notifier.TRANSFER_ID, Transfers.notification(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } catch (e: Exception) {
            Log.w("Tether", "transfer service not started", e)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!Transfers.active) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!holding) {
            holding = true
            Core.scope.launch { Core.acquire(HOLD) }
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    override fun onDestroy() {
        // While foreground, the system ignores a plain cancel of this notification; drop it here.
        stopForeground(STOP_FOREGROUND_REMOVE)
        Notifier.cancelTransfer(this)
        if (holding) Core.release(HOLD, close = true)
        super.onDestroy()
    }

    companion object {
        private const val HOLD = "transfer"

        /**
         * From the background a foreground service may start only with an exemption (a fresh FCM
         * wake, or "battery: Unrestricted"). Without one the transfer still runs, held by the sync.
         */
        fun tryStart(context: Context) {
            try {
                context.startForegroundService(Intent(context, TransferService::class.java))
            } catch (e: ForegroundServiceStartNotAllowedException) {
                Log.i("Tether", "no foreground service for the transfer: ${e.message}")
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TransferService::class.java))
        }
    }
}
