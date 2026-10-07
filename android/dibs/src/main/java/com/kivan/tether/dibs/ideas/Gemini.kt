package com.kivan.tether.dibs.ideas

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

// The pure half of the Gemini client, ported from Capture: request bodies and reading the answer.
// No Android beyond org.json, so it is covered by JVM tests; GeminiClient does the HTTP.

/**
 * The Interactions API request. [systemPrompt] is res/raw/idea_system_prompt.txt and [schema]
 * res/raw/idea_response_schema.json, both sent as they are.
 */
fun requestBody(systemPrompt: String, mimeType: String, audioBase64: String, schema: String): String =
    body(
        systemPrompt, schema,
        textPart(GeminiConfig.USER_TEXT),
        audioPart(mimeType, audioBase64),
    )

/**
 * The same request for a note the user typed: text in, no audio, with
 * res/raw/idea_system_prompt_text.txt and idea_response_schema_text.json, which ask for a title and a
 * summary only. The typed text is already the note's transcript, so Gemini never echoes it back.
 */
fun textRequestBody(systemPrompt: String, text: String, schema: String): String =
    body(
        systemPrompt, schema,
        textPart(GeminiConfig.USER_TEXT_TYPED),
        textPart(text),
    )

/**
 * A recording that adds to an existing note: the note's text so far, then the recording.
 * res/raw/idea_system_prompt_append.txt asks for the recording's transcript and a title and a summary
 * of the whole note, in idea_response_schema.json's shape.
 */
fun appendRequestBody(systemPrompt: String, noteText: String, mimeType: String, audioBase64: String, schema: String): String =
    body(
        systemPrompt, schema,
        textPart(noteText),
        textPart(GeminiConfig.USER_TEXT_APPEND),
        audioPart(mimeType, audioBase64),
    )

private fun textPart(text: String): JSONObject = JSONObject().put("type", "text").put("text", text)

private fun audioPart(mimeType: String, data: String): JSONObject =
    JSONObject().put("type", "audio").put("mime_type", mimeType).put("data", data)

/** Everything the requests share; only the `input` items differ. */
private fun body(systemPrompt: String, schema: String, vararg input: JSONObject): String {
    val items = JSONArray()
    input.forEach { items.put(it) }
    return JSONObject()
        .put("model", GeminiConfig.MODEL)
        .put("system_instruction", systemPrompt)
        .put("input", items)
        .put(
            "generation_config",
            JSONObject()
                .put("temperature", GeminiConfig.TEMPERATURE)
                .put("thinking_level", GeminiConfig.THINKING_LEVEL),
        )
        .put(
            "response_format",
            JSONObject()
                .put("type", "text")
                .put("mime_type", "application/json")
                .put("schema", JSONObject(schema)),
        )
        .put("store", false)
        .toString()
}

/** Gemini's answer, as res/raw/idea_response_schema.json describes it. All empty means nothing was heard. */
data class CaptureResult(val title: String, val summary: String, val transcript: String)

/**
 * Reads the answer to a recording. Every field is required: an answer missing one is a schema
 * mismatch that retries, never a note with a field silently emptied.
 */
val captureDecoder: (String) -> CaptureResult = { text ->
    val answer = answerObject(text)
    CaptureResult(answer.field("title"), answer.field("summary"), answer.field("transcript"))
}

/**
 * Reads the answer to a typed note, which is only a title and a summary, and keeps [typed] (the
 * text the user wrote) as the transcript. Everything downstream then sees an ordinary answer.
 */
fun typedDecoder(typed: String): (String) -> CaptureResult = { text ->
    val answer = answerObject(text)
    CaptureResult(answer.field("title"), answer.field("summary"), typed)
}

/** The answer's JSON object; anything else is a schema mismatch ([IllegalArgumentException]). */
private fun answerObject(text: String): JSONObject = try {
    JSONObject(text)
} catch (e: JSONException) {
    throw IllegalArgumentException("Gemini's answer is not a JSON object", e)
}

/** A required string field: missing, null or not a string is a schema mismatch. */
private fun JSONObject.field(key: String): String =
    string(key) ?: throw IllegalArgumentException("Gemini's answer has no string \"$key\"")

/** What one call came to. */
sealed interface GeminiResult {
    data class Parsed(val result: CaptureResult, val inputTokens: Long?, val outputTokens: Long?) : GeminiResult

    /** The interaction came back with a status other than `completed`. */
    data class NotCompleted(val status: String) : GeminiResult

    /**
     * [rawBody] is set when the answer could not be read, so it can be logged for prompt tuning.
     * [retryAfterMs] is set on a rate limit (HTTP 429): how long Gemini asked everyone to wait.
     */
    data class Failed(
        val message: String,
        val terminal: Boolean,
        val rawBody: String? = null,
        val retryAfterMs: Long? = null,
    ) : GeminiResult
}

/**
 * Reads an HTTP response. Bad request, bad key and other client errors will not get better by
 * waiting and are terminal; 408, 429 and 5xx are retryable, as is any answer that cannot be read.
 * Response shape verified 2026-09-12 (Capture's DECISIONS.md).
 */
fun interpret(
    code: Int,
    body: String,
    nowMs: Long = System.currentTimeMillis(),
    decode: (String) -> CaptureResult = captureDecoder,
): GeminiResult {
    if (code !in 200..299) {
        if (code == 429) {
            // The free tier's 429 text is a long paragraph about billing; a row needs one line.
            if (isDailyQuota(body)) {
                return GeminiResult.Failed(
                    "HTTP 429: daily free-tier limit reached, will retry after it resets",
                    terminal = false,
                    retryAfterMs = untilQuotaResetMs(nowMs),
                )
            }
            val wait = retryDelayMs(body) ?: DEFAULT_RATE_LIMIT_WAIT_MS
            return GeminiResult.Failed("HTTP 429: rate limit reached (free tier), will retry", terminal = false, retryAfterMs = wait)
        }
        val terminal = code in 400..499 && code != 408
        return GeminiResult.Failed("HTTP $code: ${errorMessage(body)}", terminal)
    }
    val root = try {
        JSONObject(body)
    } catch (e: JSONException) {
        return GeminiResult.Failed("Unreadable response from Gemini", terminal = false, rawBody = body)
    }
    val status = root.string("status") ?: return GeminiResult.Failed("Response has no status", false, body)
    if (status != "completed") return GeminiResult.NotCompleted(status)
    val text = outputText(root) ?: return GeminiResult.Failed("Gemini returned no text", false, body)
    val result = try {
        decode(text)
    } catch (e: IllegalArgumentException) {
        return GeminiResult.Failed("Gemini's answer did not match the schema", false, body)
    } catch (e: JSONException) {
        return GeminiResult.Failed("Gemini's answer did not match the schema", false, body)
    }
    val usage = root.optJSONObject("usage")
    return GeminiResult.Parsed(result, usage?.long("total_input_tokens"), usage?.long("total_output_tokens"))
}

/** A 429 that names no wait gets a full rate-limit window. */
private const val DEFAULT_RATE_LIMIT_WAIT_MS = 60_000L

private val retryDelayField = Regex("\"retryDelay\"\\s*:\\s*\"(\\d+(?:\\.\\d+)?)s\"")
private val retryInText = Regex("retry in (\\d+(?:\\.\\d+)?)s", RegexOption.IGNORE_CASE)

private val quotaLimit = Regex("limit: (\\d+)")
private val perDayText = Regex("per day", RegexOption.IGNORE_CASE)
private val perMinuteText = Regex("per minute", RegexOption.IGNORE_CASE)

/**
 * A daily 429 has to be told from a per-minute one, because the "retry in 47s" that comes with a
 * daily one is wrong: it waits for the reset instead. The 429 now names the window
 * ("limit: 15 requests per minute on Free Tier", 2026-09-20), which decides it; the older form
 * (2026-09-13) only had "limit: 5" / "limit: 20" under one metric name, so a limit bigger than a
 * minute's is the fallback. Google's standard `PerDay` quota ids also count.
 */
fun isDailyQuota(body: String): Boolean {
    if (perMinuteText.containsMatchIn(body)) return false
    if (body.contains("PerDay") || perDayText.containsMatchIn(body)) return true
    val limit = quotaLimit.find(body)?.groupValues?.get(1)?.toIntOrNull() ?: return false
    return limit > GeminiConfig.REQUESTS_PER_MINUTE
}

/** Daily quotas reset at midnight Pacific time (ai.google.dev rate-limits page); plus a minute's margin. */
fun untilQuotaResetMs(nowMs: Long): Long {
    val pacific = ZoneId.of("America/Los_Angeles")
    val now = Instant.ofEpochMilli(nowMs).atZone(pacific)
    val reset = now.toLocalDate().plusDays(1).atStartOfDay(pacific)
    return Duration.between(now, reset).toMillis() + 60_000
}

/**
 * How long a 429 asks to wait: RetryInfo's `retryDelay` ("7s"), or "Please retry in 14.9s." in the
 * message (the free tier's form, seen 2026-09-13). Null if neither is there.
 */
fun retryDelayMs(body: String): Long? {
    val match = retryDelayField.find(body) ?: retryInText.find(body) ?: return null
    return match.groupValues[1].toDoubleOrNull()?.let { (it * 1000).toLong() }
}

/** The error text for a non-`completed` status; also how the next attempt recognises a repeat. */
fun statusError(status: String) = "Gemini returned status $status"

/**
 * A `failed`, `cancelled`, `incomplete` or `budget_exceeded` interaction is terminal only when the
 * attempt before gave the same status; other statuses always retry.
 */
fun isTerminalStatus(status: String, previousError: String?): Boolean =
    status in setOf("failed", "cancelled", "incomplete", "budget_exceeded") && previousError == statusError(status)

/** The text of every `model_output` step, joined; null if there is none. */
private fun outputText(root: JSONObject): String? = root.optJSONArray("steps").objects()
    .filter { it.string("type") == "model_output" }
    .flatMap { it.optJSONArray("content").objects() }
    .filter { it.string("type") == "text" }
    .mapNotNull { it.string("text") }
    .takeIf { it.isNotEmpty() }
    ?.joinToString("")

/** The first line of Google's `{"error": {"message": ...}}`, or of the body; at most 200 characters. */
private fun errorMessage(body: String): String {
    val message = runCatching { JSONObject(body).optJSONObject("error")?.string("message") }.getOrNull()
    return (message ?: body).trim().lineSequence().first().trim().take(200).ifEmpty { "no details" }
}

/** The array's objects, skipping anything else; none for a missing array. */
private fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

/** A string value only: a number, a null or an object is not one (org.json's optString would coerce them). */
private fun JSONObject.string(key: String): String? = opt(key) as? String

/** A whole number, written as a number or a string, as kotlinx's `longOrNull` read it in Capture. */
private fun JSONObject.long(key: String): Long? = when (val v = opt(key)) {
    is Number, is String -> v.toString().toLongOrNull()
    else -> null
}
