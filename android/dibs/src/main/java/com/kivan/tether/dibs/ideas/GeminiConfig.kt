package com.kivan.tether.dibs.ideas

/**
 * Everything about the Gemini call that may need changing, in one place. Ported from Capture's
 * GeminiConfig, whose tools/gemini_smoke.sh reads the same lines: keep each one a plain
 * `const val NAME = value`.
 */
object GeminiConfig {
    const val ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/interactions"
    const val MODEL = "gemini-3.5-flash-lite"
    /**
     * `minimal`, `low`, `medium` and `high` are all accepted by [MODEL] (2026-09-20; `minimal` is an
     * HTTP 400 on `gemini-3.8-flash`). Thinking costs latency, not quota (a recording is one
     * request at any level), so this is as high as it goes. Drop it if a long recording starts
     * hitting [CALL_TIMEOUT_MINUTES].
     */
    const val THINKING_LEVEL = "high"
    const val TEMPERATURE = 0.2
    const val USER_TEXT = "Process this recording."
    const val USER_TEXT_TYPED = "Process this typed note."
    const val USER_TEXT_APPEND = "Process this recording, which adds to the note above."
    const val CALL_TIMEOUT_MINUTES = 5L

    /** How long connecting to Gemini may take before the try counts as a network error. */
    const val CONNECT_TIMEOUT_SECONDS = 30L

    /**
     * A typed note this short, and on one line, is kept exactly as typed: it is its own title, and
     * nothing is sent to Gemini. Anything longer gets a title and a summary from Gemini, with the
     * typed text as the transcript. dibs's `notes.rs` keeps the same rule.
     */
    const val TEXT_NOTE_MAX_CHARS = 200

    /** A draft gives up after this many failed attempts. */
    const val MAX_ATTEMPTS = 8

    /**
     * Free-tier requests per minute for [MODEL] ("limit: 15 requests per minute on Free Tier" in the
     * 429 text, measured 2026-09-20). [IdeaGemini.gate] spaces requests to stay under it;
     * rate-limited tries do not use up an attempt.
     */
    const val REQUESTS_PER_MINUTE = 15

    /**
     * Free-tier requests per day (AI Studio, the Flash Lite line). When it is used up, requests wait
     * for the reset at midnight Pacific time.
     *
     * To go back to `gemini-3.8-flash`: these three constants become 5 and 20, and nothing else
     * changes: spacing and daily-quota detection are derived (Capture's DECISIONS.md, 2026-09-20).
     */
    const val REQUESTS_PER_DAY = 500
}
