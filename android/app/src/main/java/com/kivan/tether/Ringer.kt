package com.kivan.tether

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * "Find my phone": a RING from the laptop loops the alarm sound at full alarm volume until it is
 * stopped here (notification, [RingActivity]), from the laptop, or after five minutes. Everything
 * runs on the main thread; the node calls in from a Rust thread.
 */
object Ringer {
    private val main = Handler(Looper.getMainLooper())
    private val _ringing = MutableStateFlow(false)
    val ringing: StateFlow<Boolean> = _ringing.asStateFlow()

    private var tone: Ringtone? = null
    private var savedVolume: Int? = null
    private var timeout: Runnable? = null

    fun start(context: Context) {
        val app = context.applicationContext
        main.post {
            timeout?.let(main::removeCallbacks)
            timeout = Runnable { stopNow(app, tellLaptop = true) }.also { main.postDelayed(it, MAX_MS) }
            if (_ringing.value) return@post
            _ringing.value = true
            val audio = app.getSystemService(AudioManager::class.java)
            runCatching {
                savedVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
                audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
            }.onFailure { Log.w("Tether", "alarm volume", it) }
            tone = sound(app)?.apply {
                audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                isLooping = true
                play()
            }
            Notifier.ring(app)
            // Keeps the process (and so the sound) alive; refused without a background-start exemption.
            SyncService.tryStart(app, "ring")
        }
    }

    /** [fromLaptop]: the laptop sent the stop, so it needn't be told. */
    fun stop(context: Context, fromLaptop: Boolean) {
        val app = context.applicationContext
        main.post { stopNow(app, tellLaptop = !fromLaptop) }
    }

    private fun stopNow(app: Context, tellLaptop: Boolean) {
        timeout?.let(main::removeCallbacks)
        timeout = null
        if (!_ringing.value) return
        tone?.stop()
        tone = null
        savedVolume?.let { v ->
            runCatching { app.getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_ALARM, v, 0) }
        }
        savedVolume = null
        Notifier.cancelRing(app)
        _ringing.value = false
        if (tellLaptop) Core.sendLive { it.stopRing() }
    }

    private fun sound(app: Context): Ringtone? {
        val uri = RingtoneManager.getActualDefaultRingtoneUri(app, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getActualDefaultRingtoneUri(app, RingtoneManager.TYPE_RINGTONE)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        return RingtoneManager.getRingtone(app, uri)
    }

    private const val MAX_MS = 5 * 60_000L
}

/** The notification's Stop action. */
class RingStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Ringer.stop(context, fromLaptop = false)
    }
}
