package com.kivan.tether.dibs.ideas

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Ported from Capture's GeminiTest, on org.json; every case kept. */
class GeminiTest {
    // The shipped files, read by path (unit tests run in the module directory).
    private val prompt = File("src/main/res/raw/idea_system_prompt.txt").readText()
    private val schema = File("src/main/res/raw/idea_response_schema.json").readText()
    private val textPrompt = File("src/main/res/raw/idea_system_prompt_text.txt").readText()
    private val textSchema = File("src/main/res/raw/idea_response_schema_text.json").readText()
    private val appendPrompt = File("src/main/res/raw/idea_system_prompt_append.txt").readText()

    @Test
    fun shippedPromptHasNoPlaceholders() {
        assertFalse(prompt.contains("{{"))
        assertFalse(textPrompt.contains("{{"))
        assertFalse(appendPrompt.contains("{{"))
    }

    /** The text schema and [typedDecoder]'s answer type have to stay in step. */
    @Test
    fun textSchemaAsksForATitleAndASummaryOnly() {
        val fields = JSONObject(textSchema)
        assertEquals(listOf("title", "summary"), fields.getJSONArray("required").strings())
        assertEquals(setOf("title", "summary"), fields.getJSONObject("properties").keys().asSequence().toSet())
        assertFalse(fields.getBoolean("additionalProperties"))
    }

    @Test
    fun textRequestBodyShape() {
        val body = JSONObject(textRequestBody("PROMPT", "Water the basil", textSchema))
        assertEquals(GeminiConfig.MODEL, body.getString("model"))
        assertEquals("PROMPT", body.getString("system_instruction"))
        val input = body.getJSONArray("input").objects()
        // Two text parts and no audio: nothing is recorded for a typed note.
        assertEquals(listOf("text", "text"), input.map { it.getString("type") })
        assertEquals(GeminiConfig.USER_TEXT_TYPED, input[0].getString("text"))
        assertEquals("Water the basil", input[1].getString("text"))
        assertEquals(GeminiConfig.THINKING_LEVEL, body.getJSONObject("generation_config").getString("thinking_level"))
        assertEquals(plain(JSONObject(textSchema)), plain(body.getJSONObject("response_format").getJSONObject("schema")))
        assertFalse(body.getBoolean("store"))
    }

    @Test
    fun typedAnswerKeepsTheTypedTextAsTheTranscript() {
        val answer = """{"title":"Water the basil","summary":"Water the basil before Friday."}"""
        val typed = "water the basil\nsometime before Friday"
        val result = interpret(200, interaction(answer), decode = typedDecoder(typed)) as GeminiResult.Parsed
        assertEquals(CaptureResult("Water the basil", "Water the basil before Friday.", typed), result.result)
    }

    /**
     * The other way round: a recording's answer must keep needing all three fields. A transcript
     * silently defaulted to "" would become a "Nothing heard" note whose audio is then deleted.
     */
    @Test
    fun aRecordingAnswerWithoutATranscriptRetries() {
        val answer = """{"title":"Skimmer","summary":"Buy a skimmer."}"""
        val result = interpret(200, interaction(answer)) as GeminiResult.Failed
        assertFalse(result.terminal)
        assertNotNull(result.rawBody)
    }

    @Test
    fun requestBodyShape() {
        val body = JSONObject(requestBody("PROMPT", "audio/ogg", "QUJD", schema))
        assertEquals(GeminiConfig.MODEL, body.getString("model"))
        assertEquals("PROMPT", body.getString("system_instruction"))
        val input = body.getJSONArray("input").objects()
        assertEquals(listOf("text", "audio"), input.map { it.getString("type") })
        assertEquals(GeminiConfig.USER_TEXT, input[0].getString("text"))
        assertEquals("audio/ogg", input[1].getString("mime_type"))
        assertEquals("QUJD", input[1].getString("data"))
        assertEquals(GeminiConfig.THINKING_LEVEL, body.getJSONObject("generation_config").getString("thinking_level"))
        val format = body.getJSONObject("response_format")
        assertEquals("application/json", format.getString("mime_type"))
        assertEquals(plain(JSONObject(schema)), plain(format.getJSONObject("schema")))
        assertFalse(body.getBoolean("store"))
    }

    /** An addition: the note so far, then what to do, then the recording. */
    @Test
    fun appendRequestBodyShape() {
        val body = JSONObject(appendRequestBody("PROMPT", "The note so far.", "audio/ogg", "QUJD", schema))
        assertEquals("PROMPT", body.getString("system_instruction"))
        val input = body.getJSONArray("input").objects()
        assertEquals(listOf("text", "text", "audio"), input.map { it.getString("type") })
        assertEquals("The note so far.", input[0].getString("text"))
        assertEquals(GeminiConfig.USER_TEXT_APPEND, input[1].getString("text"))
        assertEquals("QUJD", input[2].getString("data"))
        assertEquals(plain(JSONObject(schema)), plain(body.getJSONObject("response_format").getJSONObject("schema")))
    }

    @Test
    fun readsCompletedInteraction() {
        val answer = """{"transcript":"Buy a skimmer. Maybe a pond.","title":"Skimmer and pond","summary":"- Buy a skimmer\n- Maybe a pond"}"""
        val result = interpret(200, interaction(answer)) as GeminiResult.Parsed
        assertEquals(CaptureResult("Skimmer and pond", "- Buy a skimmer\n- Maybe a pond", "Buy a skimmer. Maybe a pond."), result.result)
        assertEquals(900L, result.inputTokens)
        assertEquals(40L, result.outputTokens)
    }

    @Test
    fun joinsTextPartsOfModelOutput() {
        val body = JSONObject()
            .put("status", "completed")
            .put(
                "steps",
                JSONArray()
                    .put(JSONObject().put("type", "thought"))
                    .put(
                        JSONObject()
                            .put("type", "model_output")
                            .put(
                                "content",
                                JSONArray()
                                    .put(JSONObject().put("type", "text").put("text", """{"transcript":"","""))
                                    .put(JSONObject().put("type", "text").put("text", """"title":"","summary":""}""")),
                            ),
                    ),
            )
            .toString()
        val result = interpret(200, body) as GeminiResult.Parsed
        assertEquals(CaptureResult("", "", ""), result.result)
        assertNull(result.inputTokens)
    }

    @Test
    fun nonCompletedStatusIsReported() {
        assertEquals(GeminiResult.NotCompleted("in_progress"), interpret(200, interaction("{}", status = "in_progress")))
    }

    @Test
    fun unreadableAnswersRetryAndKeepTheBody() {
        val noOutput = """{"status":"completed","steps":[{"type":"thought"}]}"""
        val badType = interaction("""{"transcript":"x","title":"t","summary":["s"]}""")
        val missingField = interaction("""{"transcript":"x","title":"t"}""")
        for (body in listOf("<html>", noOutput, badType, missingField, """{"steps":[]}""")) {
            val result = interpret(200, body) as GeminiResult.Failed
            assertFalse(body, result.terminal)
            assertEquals(body, result.rawBody)
        }
    }

    @Test
    fun httpErrors() {
        val bad = interpret(400, """{"error":{"code":400,"message":"Invalid audio","status":"INVALID_ARGUMENT"}}""") as GeminiResult.Failed
        assertEquals("HTTP 400: Invalid audio", bad.message)
        assertTrue(bad.terminal)
        assertNull(bad.rawBody)
        for (code in listOf(401, 403, 404)) assertTrue("$code", (interpret(code, "") as GeminiResult.Failed).terminal)
        for (code in listOf(408, 429, 500, 503)) assertFalse("$code", (interpret(code, "busy") as GeminiResult.Failed).terminal)
        assertEquals("HTTP 503: busy", (interpret(503, "busy") as GeminiResult.Failed).message)
        val quota = """{"error":{"message":"You exceeded your current quota, please check your plan.\n* Quota exceeded","code":"too_many_requests"}}"""
        assertEquals("HTTP 429: rate limit reached (free tier), will retry", (interpret(429, quota) as GeminiResult.Failed).message)
        assertEquals(60_000L, (interpret(429, quota) as GeminiResult.Failed).retryAfterMs)
        val hinted = """{"error":{"message":"Quota exceeded. Please retry in 14.898357626s.","code":"too_many_requests"}}"""
        assertEquals(14_898L, (interpret(429, hinted) as GeminiResult.Failed).retryAfterMs)
        val retryInfo = """{"error":{"message":"x","details":[{"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay": "7s"}]}}"""
        assertEquals(7_000L, (interpret(429, retryInfo) as GeminiResult.Failed).retryAfterMs)
        assertNull((interpret(503, "busy") as GeminiResult.Failed).retryAfterMs)
        val multiLine = """{"error":{"message":"Invalid audio\nmore detail"}}"""
        assertEquals("HTTP 400: Invalid audio", (interpret(400, multiLine) as GeminiResult.Failed).message)
    }

    @Test
    fun badStatusIsTerminalOnlyWhenRepeated() {
        assertFalse(isTerminalStatus("failed", null))
        assertTrue(isTerminalStatus("failed", statusError("failed")))
        assertFalse(isTerminalStatus("failed", statusError("incomplete")))
        assertTrue(isTerminalStatus("budget_exceeded", statusError("budget_exceeded")))
        assertFalse(isTerminalStatus("in_progress", statusError("in_progress")))
    }

    @Test
    fun dailyQuotaWaitsForTheReset() {
        // The two 429s seen on 2026-09-13, trimmed.
        val perMinute = """{"error":{"message":"You exceeded your current quota.\n* Quota exceeded for metric: generativelanguage.googleapis.com/generate_content_free_tier_requests, limit: 5, model: gemini-3.8-flash\nPlease retry in 14.898357626s.","code":"too_many_requests"}}"""
        val perDay = """{"error":{"message":"You exceeded your current quota.\n* Quota exceeded for metric: generativelanguage.googleapis.com/generate_content_free_tier_requests, limit: 20, model: gemini-3.8-flash\nPlease retry in 47.324331527s.","code":"too_many_requests"}}"""
        val now = java.time.Instant.parse("2026-09-13T04:51:00Z").toEpochMilli() // 21:51 Pacific, 12 Sep
        val minute = interpret(429, perMinute, now) as GeminiResult.Failed
        assertEquals(14_898L, minute.retryAfterMs)
        assertEquals("HTTP 429: rate limit reached (free tier), will retry", minute.message)
        val day = interpret(429, perDay, now) as GeminiResult.Failed
        assertEquals("HTTP 429: daily free-tier limit reached, will retry after it resets", day.message)
        assertFalse(day.terminal)
        // Midnight Pacific is 07:00Z; plus the minute's margin.
        assertEquals((2 * 3600 + 9 * 60 + 60) * 1000L, day.retryAfterMs)
        assertTrue(isDailyQuota("""{"details":[{"quotaId":"GenerateRequestsPerDayPerProjectPerModel-FreeTier"}]}"""))
    }

    @Test
    fun newerRateLimitWordingNamesTheWindow() {
        // The 429 gemini-3.5-flash-lite gave on 2026-09-20, verbatim: it says which window it is,
        // so the limit no longer has to be compared with REQUESTS_PER_MINUTE.
        val perMinute = """{"error":{"message":"Rate limit exceeded for model gemini-3.5-flash-lite (limit: 15 requests per minute on Free Tier). Please retry in 52s or upgrade your tier at https://ai.dev/rate-limit.","code":"too_many_requests"}}"""
        // The same form for a daily limit (not seen yet; the per-minute one is what the burst hit).
        val perDay = """{"error":{"message":"Rate limit exceeded for model gemini-3.5-flash-lite (limit: 500 requests per day on Free Tier). Please retry in 52s or upgrade your tier at https://ai.dev/rate-limit.","code":"too_many_requests"}}"""
        val now = java.time.Instant.parse("2026-09-20T04:51:00Z").toEpochMilli() // 21:51 Pacific, 19 Sep
        assertFalse(isDailyQuota(perMinute))
        val minute = interpret(429, perMinute, now) as GeminiResult.Failed
        assertEquals(52_000L, minute.retryAfterMs)
        assertEquals("HTTP 429: rate limit reached (free tier), will retry", minute.message)
        assertTrue(isDailyQuota(perDay))
        val day = interpret(429, perDay, now) as GeminiResult.Failed
        assertEquals("HTTP 429: daily free-tier limit reached, will retry after it resets", day.message)
        assertEquals((2 * 3600 + 9 * 60 + 60) * 1000L, day.retryAfterMs) // the reset, not the 52 s it names
    }

    @Test
    fun quotaResetIsMidnightPacificAcrossDaylightSaving() {
        // 30 s before midnight Pacific: the reset is 30 s away, plus the minute's margin.
        val beforeMidnight = java.time.ZonedDateTime.of(2026, 10, 31, 23, 59, 30, 0, java.time.ZoneId.of("America/Los_Angeles"))
        assertEquals(90_000L, untilQuotaResetMs(beforeMidnight.toInstant().toEpochMilli()))
        // The night the clocks go back (1 Nov 2026) is 25 hours long.
        val dstStart = java.time.ZonedDateTime.of(2026, 11, 1, 0, 0, 0, 0, java.time.ZoneId.of("America/Los_Angeles"))
        assertEquals((25 * 3600 + 60) * 1000L, untilQuotaResetMs(dstStart.toInstant().toEpochMilli()))
    }

    private fun interaction(text: String, status: String = "completed"): String = JSONObject()
        .put("status", status)
        .put(
            "steps",
            JSONArray()
                .put(JSONObject().put("type", "user_input"))
                .put(
                    JSONObject()
                        .put("type", "model_output")
                        .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text))),
                ),
        )
        .put("usage", JSONObject().put("total_input_tokens", 900).put("total_output_tokens", 40))
        .toString()

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

    private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }

    /** Plain Kotlin maps and lists, so two JSON values compare by content (org.json's don't). */
    private fun plain(v: Any?): Any? = when (v) {
        is JSONObject -> v.keys().asSequence().associateWith { plain(v.get(it)) }
        is JSONArray -> (0 until v.length()).map { plain(v.get(it)) }
        else -> v
    }
}
