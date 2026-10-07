package com.kivan.tether.dibs.ideas

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Sends one recording, or one typed note, to the Interactions API. Plain HttpURLConnection, no
 * Google SDK. [IdeaGemini] is the entry point the rest of the app uses.
 */
object GeminiClient {
    private const val TAG = "dibs-ideas"

    suspend fun process(apiKey: String, audio: File, mimeType: String, systemPrompt: String, schema: String): GeminiResult =
        send(apiKey, captureDecoder) {
            requestBody(systemPrompt, mimeType, Base64.getEncoder().encodeToString(audio.readBytes()), schema)
        }

    /** A recording that adds to a note whose text so far is [noteText]. */
    suspend fun processAppend(apiKey: String, noteText: String, audio: File, mimeType: String, systemPrompt: String, schema: String): GeminiResult =
        send(apiKey, captureDecoder) {
            appendRequestBody(systemPrompt, noteText, mimeType, Base64.getEncoder().encodeToString(audio.readBytes()), schema)
        }

    /**
     * A note the user typed: the text goes up, only a title and a summary come back, and [text]
     * itself is put back as the transcript, so the rest of the app sees an ordinary answer.
     */
    suspend fun processText(apiKey: String, text: String, systemPrompt: String, schema: String): GeminiResult =
        send(apiKey, typedDecoder(text)) { textRequestBody(systemPrompt, text, schema) }

    /** [body] is built on the IO thread: reading and encoding audio does not belong on the caller's. */
    private suspend fun send(apiKey: String, decode: (String) -> CaptureResult, body: () -> String): GeminiResult {
        val result = try {
            runInterruptible(Dispatchers.IO) { post(apiKey, body().toByteArray(Charsets.UTF_8), decode) }
        } catch (e: IOException) {
            GeminiResult.Failed("Network error: ${e.message ?: e.javaClass.simpleName}", terminal = false)
        }
        when (result) {
            is GeminiResult.Parsed -> Log.i(TAG, "Gemini used ${result.inputTokens} input and ${result.outputTokens} output tokens")
            is GeminiResult.Failed -> result.rawBody?.let { raw ->
                // Logged in full so the prompt can be tuned; logcat cuts lines at ~4 KB.
                Log.w(TAG, "${result.message}; raw response follows (${raw.length} chars)")
                raw.chunked(3000).forEach { Log.w(TAG, it) }
            }
            is GeminiResult.NotCompleted -> Unit
        }
        return result
    }

    /**
     * One blocking POST. A long recording can take a minute or more before the first response byte,
     * hence the long read timeout. The body's length is fixed up front, so the connection streams it
     * instead of buffering a second copy.
     */
    private fun post(apiKey: String, body: ByteArray, decode: (String) -> CaptureResult): GeminiResult {
        val connection = URI(GeminiConfig.ENDPOINT).toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = TimeUnit.SECONDS.toMillis(GeminiConfig.CONNECT_TIMEOUT_SECONDS).toInt()
            connection.readTimeout = TimeUnit.MINUTES.toMillis(GeminiConfig.CALL_TIMEOUT_MINUTES).toInt()
            connection.doOutput = true
            connection.useCaches = false
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("x-goog-api-key", apiKey)
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            val code = connection.responseCode
            // An error's body comes on errorStream; it can be null when the server sent none.
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            return interpret(code, text, decode = decode)
        } finally {
            connection.disconnect()
        }
    }
}
