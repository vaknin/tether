package com.kivan.tether.dibs.ideas

/**
 * What the recording screen shows ([IdeaRecording.state]). Only [IdeaRecordingService] and
 * [IdeaRecording] change it.
 */
sealed interface RecorderState {
    data object Idle : RecorderState

    /** The service has been asked to start and has not begun recording yet. */
    data object Starting : RecorderState

    /**
     * [level] is 0..1 on a log scale, from `getMaxAmplitude()` every 100 ms. [note]: the note this
     * recording adds to, or null for a new idea;
     * [drop]: the box that idea is dropped in (`ideas` or `backlog`).
     */
    data class Recording(val note: String?, val elapsedMs: Long, val level: Float, val drop: String? = null) : RecorderState

    /** Shown briefly once it is over: "Saved", "Nothing recorded", "Microphone unavailable", and so on. */
    data class Finished(val message: String) : RecorderState
}
