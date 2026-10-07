package com.kivan.tether.dibs.ideas

import android.content.Context
import com.kivan.tether.dibs.BuildConfig
import com.kivan.tether.dibs.R
import java.io.File

/**
 * The one way into Gemini for the Ideas tab: the prompts and schemas from res/raw, the key from
 * this build (`gemini.apiKey` in local.properties). Each call is one request; the caller takes its
 * turn through [gate] and, on a rate limit ([GeminiResult.Failed.retryAfterMs]), calls
 * [RateGate.holdFor] inside that turn, as Capture's worker did.
 */
object IdeaGemini {
    /** 60 s / [GeminiConfig.REQUESTS_PER_MINUTE], plus a second of margin. */
    var gate = RateGate(minIntervalMs = 60_000L / GeminiConfig.REQUESTS_PER_MINUTE + 1_000)
        internal set

    /** A test's stand-in for Gemini: (`voice`, `append` or `text`, the text sent) → its answer. */
    internal var fake: (suspend (String, String?) -> GeminiResult)? = null

    /** A new spoken idea: its transcript, a title and a summary. */
    suspend fun voice(context: Context, audio: File, mimeType: String): GeminiResult {
        fake?.let { return it("voice", null) }
        val key = key() ?: return NO_KEY
        if (!audio.isFile) return MISSING_AUDIO
        return GeminiClient.process(
            key, audio, mimeType,
            raw(context, R.raw.idea_system_prompt).trimEnd(),
            raw(context, R.raw.idea_response_schema),
        )
    }

    /**
     * A recording that adds to a note whose text so far is [noteText]: the recording's transcript,
     * and a title and a summary of the whole note.
     */
    suspend fun append(context: Context, noteText: String, audio: File, mimeType: String): GeminiResult {
        fake?.let { return it("append", noteText) }
        val key = key() ?: return NO_KEY
        if (!audio.isFile) return MISSING_AUDIO
        return GeminiClient.processAppend(
            key, noteText, audio, mimeType,
            raw(context, R.raw.idea_system_prompt_append).trimEnd(),
            raw(context, R.raw.idea_response_schema),
        )
    }

    /**
     * Typed text (a long note, or a whole note with a typed addition at its end): a title and a
     * summary, with [text] itself as the transcript.
     */
    suspend fun text(context: Context, text: String): GeminiResult {
        fake?.let { return it("text", text) }
        val key = key() ?: return NO_KEY
        return GeminiClient.processText(
            key, text,
            raw(context, R.raw.idea_system_prompt_text).trimEnd(),
            raw(context, R.raw.idea_response_schema_text),
        )
    }

    private val NO_KEY = GeminiResult.Failed("No Gemini key in this build", terminal = true)
    private val MISSING_AUDIO = GeminiResult.Failed("Audio file missing", terminal = true)

    private fun key(): String? = BuildConfig.GEMINI_API_KEY.trim().takeIf { it.isNotEmpty() }

    private fun raw(context: Context, resource: Int): String =
        context.resources.openRawResource(resource).bufferedReader().use { it.readText() }
}
