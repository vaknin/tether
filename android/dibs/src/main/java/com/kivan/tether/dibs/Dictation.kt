package com.kivan.tether.dibs

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Talking into a message box (task #377, the first step of "talk to dibs by voice"): the 🎤 next to
 * the box listens with the phone's own on-device recognizer, the one Gboard's voice typing uses, so
 * no audio leaves the phone; there is no fallback to a network service (the user's rule: local only).
 * Words show in the box as they're said, after what was already there; the user reads them and
 * taps Send. A box that got spoken words says so in its `say` (`spoken`), so dibs can allow for a
 * misheard word.
 *
 * One session stays open across pauses (a segmented session: one result per phrase) until the user
 * taps the square, sends, types, or stays quiet for [SILENCE_MS]. A recognizer that ignores
 * segmentation gives its one result and ends; the user taps 🎤 again.
 *
 * Main thread only: `SpeechRecognizer` is a Main-thread API.
 */
@Stable
class Dictation(private val context: Context) {
    /** Listening now: the 🎤 is a square. */
    var listening by mutableStateOf(false)
        private set
    /** The voice's loudness, 0..1, for the button's pulse. */
    var level by mutableFloatStateOf(0f)
        private set
    /** Why the last session ended badly, in a few words; null once it's read or a new one starts. */
    var note by mutableStateOf<String?>(null)

    private val main = Handler(Looper.getMainLooper())
    private var rec: SpeechRecognizer? = null
    private var box: Composer? = null
    /** The box's text when listening started, the phrases heard since, and the one being said. */
    private var base = ""
    private var heard = ""
    private var partial = ""
    /** The text this put in the box last: anything else there means the user typed. */
    private var wrote: String? = null

    /** Starts listening into [into], after its text. The caller has the microphone permission. */
    fun start(into: Composer) {
        cancel()
        note = null
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            note = "This phone can't recognise speech offline"
            return
        }
        val r = runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(context) }.getOrElse {
            note = "Couldn't start listening"
            return
        }
        box = into
        base = into.draft
        heard = ""
        partial = ""
        wrote = into.draft
        rec = r
        listening = true
        runCatching {
            r.setRecognitionListener(Listener(r))
            r.startListening(intent(biasWords(Dibs.index)))
        }.onFailure {
            note = "Couldn't start listening"
            end(r)
        }
    }

    /** Stops listening; the phrase being said still lands in the box. */
    fun stop() {
        val r = rec ?: return
        runCatching { r.stopListening() }
        // The last result comes within a moment; a recognizer that never sends it is let go.
        main.postDelayed({ if (rec === r) end(r) }, GRACE_MS)
    }

    /** Stops at once: what's in the box stays, nothing more comes (a send, the user typing, the screen leaving). */
    fun cancel() {
        rec?.let { end(it) }
    }

    private fun end(r: SpeechRecognizer) {
        if (rec !== r) return
        rec = null
        box = null
        wrote = null
        listening = false
        level = 0f
        main.removeCallbacksAndMessages(null)
        runCatching { r.destroy() }
    }

    /** Puts what was heard in the box, unless the box changed meanwhile (a pick, a reply's chip: then this stops). */
    private fun show(r: SpeechRecognizer) {
        val b = box ?: return
        if (b.draft != wrote) {
            note = "Stopped listening: the box changed"
            return end(r)
        }
        val text = joinSpoken(base, listOf(heard, partial).filter { it.isNotBlank() }.joinToString(" "))
        if (text == b.draft) return
        wrote = text
        b.spoken = true
        b.typed(text)
    }

    private inner class Listener(private val r: SpeechRecognizer) : RecognitionListener {
        private var segments = 0

        private fun first(b: Bundle?) = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()

        override fun onPartialResults(b: Bundle?) {
            if (rec !== r) return
            partial = first(b)
            show(r)
        }

        override fun onSegmentResults(b: Bundle) {
            if (rec !== r) return
            segments++
            heard = listOf(heard, first(b)).filter { it.isNotBlank() }.joinToString(" ")
            partial = ""
            show(r)
        }

        override fun onEndOfSegmentedSession() = end(r)

        override fun onResults(b: Bundle?) {
            if (rec !== r) return
            // A segmented session ends with onEndOfSegmentedSession; a recognizer that ignored the
            // segmentation extra sends its one phrase here.
            if (segments == 0) {
                first(b).takeIf { it.isNotEmpty() }?.let { heard = listOf(heard, it).filter { s -> s.isNotBlank() }.joinToString(" ") }
                partial = ""
                show(r)
            }
            end(r)
        }

        override fun onError(error: Int) {
            if (rec !== r) return
            // Hearing nothing (or nothing more) is a normal end.
            if (error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) note = errorNote(error)
            end(r)
        }

        override fun onRmsChanged(rmsdB: Float) {
            if (rec === r) level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
        }

        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun intent(bias: ArrayList<String>) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING, RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY)
        putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, SILENCE_MS)
        putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, bias)
    }

    private fun errorNote(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO, SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "The microphone is busy"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "dibs may not use the microphone"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
            "English speech isn't downloaded on this phone"
        else -> "Listening stopped"
    }

    companion object {
        /** Quiet this long ends a session. */
        const val SILENCE_MS = 6_000L
        /** How long a stopped session may take to send its last phrase. */
        private const val GRACE_MS = 2_000L
    }
}

/** Words the recognizer should expect (dibs's own, then the projects tasks ran in, busiest first), at most [MAX_BIAS]. */
fun biasWords(index: RefIndex?): ArrayList<String> {
    val projects = index?.tasks.orEmpty().map { it.p.trim() }.filter { it.isNotEmpty() && it != "Other" }
        .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }
    return ArrayList((BIAS + projects).distinctBy { it.lowercase() }.take(MAX_BIAS))
}

private val BIAS = listOf("dibs", "Tether", "worktree", "Recap", "Parakeet", "Voxtype", "Hyprland", "Omarchy", "Claude")
private const val MAX_BIAS = 40

/**
 * [said] after [before] in a box: a space between, and a capital where a sentence starts (an empty
 * box, or after . ! ? or a new line), since the recognizer often starts a phrase in lower case.
 */
fun joinSpoken(before: String, said: String): String {
    val s = said.trim()
    if (s.isEmpty()) return before
    val b = before.trimEnd(' ', '\t')
    val starts = b.isEmpty() || b.last() in ".!?\n"
    val words = if (starts) s.replaceFirstChar { it.uppercaseChar() } else s
    return if (b.isEmpty() || b.endsWith('\n')) b + words else "$b $words"
}
