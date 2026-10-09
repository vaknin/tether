package com.kivan.tether.dibs.ideas

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Speaking an idea (Capture's recorder, PLAN.md "Capture moves into dibs"): [start] and [stop] drive
 * [IdeaRecordingService], and [state] is what the screen shows. The service owns the timer and the
 * level, so both survive the screen being closed while it records. A finished recording becomes a
 * draft through [Drafts.recorded].
 */
object IdeaRecording {
    private const val TAG = "dibs-ideas"

    private val _state = MutableStateFlow<RecorderState>(RecorderState.Idle)
    val state: StateFlow<RecorderState> = _state.asStateFlow()

    /**
     * Outlives the service, for handing a recording over when the system destroys the service mid
     * recording. Main thread, like the service's own work.
     */
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Whether a recording is starting or running: a second [start] does nothing then. */
    val busy: Boolean get() = _state.value.let { it is RecorderState.Recording || it is RecorderState.Starting }

    /**
     * Starts recording; [note]: the id of the note it adds to, or null for a new idea, which [drop] says
     * which box it was dropped in (`ideas` or `backlog`). Call it from a
     * screen that is on top, after RECORD_AUDIO is granted: Android lets a microphone service start
     * only while the app is in the foreground. Does nothing while [busy].
     */
    fun start(context: Context, note: String? = null, drop: String? = null) {
        if (busy) return
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            set(RecorderState.Finished("Microphone not allowed"))
            return
        }
        set(RecorderState.Starting)
        try {
            context.startForegroundService(
                Intent(context, IdeaRecordingService::class.java)
                    .setAction(IdeaRecordingService.ACTION_START)
                    .putExtra(IdeaRecordingService.EXTRA_NOTE, note)
                    .putExtra(IdeaRecordingService.EXTRA_DROP, drop)
            )
        } catch (e: Exception) {
            // Not allowed from the background, mostly.
            Log.e(TAG, "Could not start the recording service", e)
            set(RecorderState.Finished("Could not start recording"))
        }
    }

    /** Stops and keeps the recording; a stop while it is still starting stops it as soon as it has begun. */
    fun stop(context: Context) {
        try {
            context.startService(Intent(context, IdeaRecordingService::class.java).setAction(IdeaRecordingService.ACTION_STOP))
        } catch (e: Exception) {
            Log.e(TAG, "Could not reach the recording service", e)
        }
    }

    /** The screen has shown the [RecorderState.Finished] message: back to [RecorderState.Idle]. */
    fun clear() {
        if (_state.value is RecorderState.Finished) set(RecorderState.Idle)
    }

    /** The audio type of a recording this made, by its name: Ogg/Opus, or AAC where Opus would not prepare. */
    fun mimeType(audio: File): String = if (audio.extension == "aac") MIME_AAC else MIME_OGG

    internal const val MIME_OGG = "audio/ogg"
    internal const val MIME_AAC = "audio/aac"

    internal fun set(state: RecorderState) {
        _state.value = state
    }
}
