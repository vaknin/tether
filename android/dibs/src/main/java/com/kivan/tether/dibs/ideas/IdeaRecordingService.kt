package com.kivan.tether.dibs.ideas

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.kivan.tether.dibs.DibsActivity
import com.kivan.tether.dibs.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import kotlin.math.log10

/**
 * The microphone foreground service (Capture's RecordingService): Ogg/Opus into
 * `files/dibs-ideas/audio/<uuid>.ogg`, at most 15 minutes, other media paused while it records.
 * Started only through [IdeaRecording.start]. Its draft is written when it starts
 * ([Drafts.recording]); when it ends, one with any audio in it waits for Gemini
 * ([Drafts.recorded]) and an empty one is deleted.
 */
class IdeaRecordingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val audioManager: AudioManager by lazy { getSystemService(AudioManager::class.java) }

    private var recorder: MediaRecorder? = null
    private var audio: File? = null
    private var note: String? = null
    private var startWallMs = 0L
    private var startElapsedMs = 0L
    private var ticker: Job? = null
    private var starting = false
    private var stopRequested = false
    private var finishing = false
    private var focus: AudioFocusRequest? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stop(StopCause.USER)
            else -> start(intent?.getStringExtra(EXTRA_NOTE))
        }
        return START_NOT_STICKY
    }

    /** [note]: the recording adds to that note instead of being a new idea. */
    private fun start(note: String?) {
        val idle = recorder == null && !starting && !finishing
        if (idle) startWallMs = System.currentTimeMillis()
        try {
            startForeground(ID_RECORDING, notification(startWallMs), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (e: Exception) {
            // SecurityException without RECORD_AUDIO; not-allowed exceptions from the background.
            Log.e(TAG, "Could not start the recording service", e)
            if (idle) IdeaRecording.set(RecorderState.Finished("Could not start recording"))
            stopSelf()
            return
        }
        if (!idle) return
        this.note = note
        starting = true
        stopRequested = false
        requestFocus()
        scope.launch { begin(note) }
    }

    private fun begin(note: String?) {
        val dir = File(filesDir, AUDIO_DIR).apply { mkdirs() }
        val name = UUID.randomUUID().toString()
        var file = File(dir, "$name.ogg")
        val mr = try {
            prepare(file, ogg = true)
        } catch (e: Exception) {
            Log.w(TAG, "Opus/OGG recorder failed to prepare; falling back to AAC", e)
            file.delete()
            file = File(dir, "$name.aac")
            try {
                prepare(file, ogg = false)
            } catch (e2: Exception) {
                Log.e(TAG, "AAC recorder failed to prepare too", e2)
                null
            }
        }
        val started = mr != null && try {
            mr.start()
            true
        } catch (e: Exception) {
            Log.e(TAG, "MediaRecorder.start failed", e)
            mr.release()
            false
        }
        starting = false
        if (!started) {
            file.delete()
            finishService("Microphone unavailable")
            return
        }
        recorder = mr
        audio = file
        startElapsedMs = SystemClock.elapsedRealtime()
        // Its draft now: a recording cut off by a crash or a restart is kept with what it got.
        runCatching { Drafts.recording(applicationContext, file, startWallMs, note) }.onFailure { Log.e(TAG, "Could not write the draft for ${file.name}", it) }
        Log.i(TAG, "Recording ${file.name} started (${IdeaRecording.mimeType(file)})")
        ticker = scope.launch {
            while (isActive) {
                val amplitude = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)
                IdeaRecording.set(RecorderState.Recording(note, SystemClock.elapsedRealtime() - startElapsedMs, level(amplitude)))
                delay(100)
            }
        }
        if (stopRequested) stop(StopCause.USER)
    }

    /**
     * Pauses whatever else is playing for as long as the recording lasts: transient *exclusive*
     * focus asks players to pause instead of ducking, and releasing it lets them resume.
     */
    private fun requestFocus() {
        if (focus != null) return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    // ASSISTANT, not VOICE_COMMUNICATION: the latter reads as a call to the audio
                    // policy and can pull in call routing, for nothing: this service never plays.
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            // Nothing acts on a change: whatever takes the microphone from us (a call) already
            // reaches MediaRecorder's error path, which stops the recording and keeps the audio.
            .setOnAudioFocusChangeListener { change -> Log.i(TAG, "Audio focus changed: $change") }
            .build()
        focus = request
        val granted = audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        Log.i(TAG, if (granted) "Audio focus taken; other media pauses" else "Audio focus refused; other media keeps playing")
    }

    /** Gives the focus back, so what was playing can resume. Safe to call more than once. */
    private fun abandonFocus() {
        val request = focus ?: return
        focus = null
        audioManager.abandonAudioFocusRequest(request)
        Log.i(TAG, "Audio focus released")
    }

    private fun prepare(file: File, ogg: Boolean): MediaRecorder {
        val mr = MediaRecorder(this)
        try {
            mr.setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            if (ogg) {
                mr.setOutputFormat(MediaRecorder.OutputFormat.OGG)
                mr.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
            } else {
                mr.setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS)
                mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            }
            mr.setAudioChannels(1)
            mr.setAudioSamplingRate(16_000)
            mr.setAudioEncodingBitRate(32_000)
            mr.setMaxDuration(MAX_DURATION_MS)
            mr.setOutputFile(file)
            mr.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) stop(StopCause.CAP)
            }
            mr.setOnErrorListener { _, what, extra ->
                Log.e(TAG, "MediaRecorder error $what/$extra; keeping what was recorded")
                stop(StopCause.ERROR)
            }
            mr.prepare()
            return mr
        } catch (e: Exception) {
            mr.release()
            throw e
        }
    }

    private fun stop(cause: StopCause) {
        val mr = recorder
        val file = audio
        if (mr == null || file == null) {
            if (starting) stopRequested = true else if (!finishing) stopSelf()
            return
        }
        recorder = null
        audio = null
        finishing = true
        ticker?.cancel()
        val durationMs = SystemClock.elapsedRealtime() - startElapsedMs
        try {
            mr.stop()
        } catch (e: RuntimeException) {
            Log.w(TAG, "MediaRecorder.stop failed ($cause)", e)
        }
        mr.release()
        Log.i(TAG, "Recording ${file.name} stopped after $durationMs ms ($cause)")
        val kept = handOver(file, durationMs)
        finishService(
            when {
                !kept -> "Nothing recorded"
                cause == StopCause.CAP -> "Saved · stopped at the 15:00 limit"
                else -> "Saved"
            }
        )
    }

    /**
     * The recorder stopped: audio with anything in it becomes a draft, an empty file goes (Capture's
     * rule). Returns whether it was kept.
     */
    private fun handOver(file: File, durationMs: Long): Boolean {
        val empty = file.length() <= 0L
        if (empty) {
            file.delete()
            Log.i(TAG, "Recording ${file.name} was empty; discarded")
        }
        val context = applicationContext
        val createdMs = startWallMs
        val adds = this.note
        // On the process-wide scope: it has to happen even when the service is being destroyed.
        IdeaRecording.scope.launch {
            try {
                Drafts.recorded(context, file, createdMs, durationMs, adds)
            } catch (e: Exception) {
                Log.e(TAG, "Could not keep recording ${file.name} as a draft", e)
            }
        }
        return !empty
    }

    private fun finishService(message: String) {
        abandonFocus()
        IdeaRecording.set(RecorderState.Finished(message))
        finishing = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        // Only reached with a live recorder if the system stops the service: keep the audio.
        val mr = recorder
        val file = audio
        if (mr != null && file != null) {
            recorder = null
            audio = null
            ticker?.cancel()
            runCatching { mr.stop() }
            mr.release()
            handOver(file, SystemClock.elapsedRealtime() - startElapsedMs)
            IdeaRecording.set(RecorderState.Idle)
        }
        abandonFocus()
        scope.cancel()
        super.onDestroy()
    }

    /** Ongoing, silent, with the system chronometer instead of per-second updates; Stop keeps the recording. */
    private fun notification(startedAt: Long): Notification {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_RECORDING, "Recording an idea", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, REQUEST_OPEN, DibsActivity.intent(this), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, REQUEST_STOP,
            Intent(this, IdeaRecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_RECORDING)
            .setSmallIcon(R.drawable.ic_idea_mic)
            .setContentTitle("Recording an idea")
            .setWhen(startedAt)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()
    }

    private enum class StopCause { USER, CAP, ERROR }

    companion object {
        private const val TAG = "dibs-ideas"
        const val ACTION_START = "com.kivan.tether.dibs.ideas.action.START"
        const val ACTION_STOP = "com.kivan.tether.dibs.ideas.action.STOP"
        const val EXTRA_NOTE = "com.kivan.tether.dibs.ideas.extra.NOTE"
        const val MAX_DURATION_MS = 15 * 60 * 1000

        /** Under `filesDir`; [Drafts.audioDir] is the same folder. */
        private const val AUDIO_DIR = "dibs-ideas/audio"

        private const val CHANNEL_RECORDING = "dibs-idea-recording"

        /** Clear of Tether's own notification ids (Notifier.kt uses 1 to 4 and tagged ones). */
        private const val ID_RECORDING = 7301
        private const val REQUEST_OPEN = 7301
        private const val REQUEST_STOP = 7302

        /** 0..1: -60 dBFS and below is empty, full scale is full. */
        private fun level(amplitude: Int): Float {
            if (amplitude <= 0) return 0f
            val dbfs = 20 * log10(amplitude / 32767f)
            return ((dbfs + 60f) / 60f).coerceIn(0f, 1f)
        }
    }
}
