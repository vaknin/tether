package com.kivan.tether.dibs.ideas

import org.robolectric.RuntimeEnvironment
import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.kivan.tether.dibs.Dibs
import com.kivan.tether.dibs.DibsView
import com.kivan.tether.dibs.FakeHost
import com.kivan.tether.dibs.Link
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** dibs's own sample of the Ideas tab (its tests/samples/ideas.json, copied here), and the phone's drafts against it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class IdeasTest {
    private val basil = "cccccccccccccccc0000000000000003"
    private val sample = JSONObject(javaClass.getResource("/ideas.json")!!.readText())
    private val ideas = Ideas.parse(sample.getJSONObject("ideas"))
    private val context: Context = RuntimeEnvironment.getApplication()
    private lateinit var host: FakeHost

    @Before
    fun fresh() {
        host = FakeHost(null)
        Dibs.host = host
        Drafts.reset()
        context.filesDir.resolve("dibs-ideas").deleteRecursively()
        Drafts.init(context)
        IdeaGemini.gate = RateGate(0)
    }

    @After
    fun noFake() {
        IdeaGemini.fake = null
    }

    /** The whole view as Tether holds it: the payload under `dibs`. */
    private fun view() = JSONObject().put("dibs", JSONObject().put("ideas", sample.getJSONObject("ideas")))

    private fun work(id: String, attempt: Int = 0): ListenableWorker.Result = runBlocking {
        TestListenableWorkerBuilder<IdeaWorker>(context).setInputData(workDataOf("draft" to id)).setRunAttemptCount(attempt).build().doWork()
    }

    private fun parsed(title: String, summary: String, transcript: String) = GeminiResult.Parsed(CaptureResult(title, summary, transcript), null, null)

    private fun audio(bytes: Int = 64): java.io.File = java.io.File(Drafts.audioDir(context), "${Draft.newId()}.ogg").apply { writeBytes(ByteArray(bytes) { 1 }) }

    private fun recorded(note: String?): Draft {
        val f = audio()
        Drafts.recorded(context, f, 1_000, 5_000, note)
        return Drafts.list.value.first { it.audio == f.name }
    }

    @Test
    fun theSampleReadsAsSent() {
        assertEquals(listOf("#45", "#44", "#43", "#42"), ideas.notes.map { it.label })
        val desk = ideas.notes[0]
        assertEquals("ask", desk.status!!.tone)
        assertEquals(listOf("Research", "Build", "Keep"), desk.actions.map { it.label })
        assertEquals("research", desk.actions[0].value.getString("choice"))
        val long = ideas.notes[1]
        assertNull(long.transcript)
        assertEquals("fetch", long.fetch!!.action)
        assertTrue(long.loadPrefix.matches(Regex("idea-${long.id}-[0-9a-f]{10}")))
        assertEquals(1L, ideas.notes[2].task)
        assertEquals(setOf("eeeeeeeeeeeeeeee0000000000000005"), ideas.notes[3].adds)
        assertEquals("Ticked off · 1", ideas.trashTitle)
        assertEquals("Restore", ideas.trash.single().actions.single().label)
        assertTrue("dddddddddddddddd0000000000000004" in ideas.known)
        // And through the whole payload.
        assertEquals(4, DibsView.parse(JSONObject().put("now", 1).put("yours", org.json.JSONArray()).put("ideas", sample.getJSONObject("ideas"))).ideas!!.notes.size)
    }

    @Test
    fun aShortTypedIdeaGoesAtOnceAndIsDroppedOnceListed() = runBlocking {
        Drafts.typed(context, "  Buy basil  ")
        val d = Drafts.list.value.single()
        assertEquals("Buy basil", d.title)
        val (action, value) = host.acts.single()
        assertEquals("idea-new", action)
        assertEquals(d.id, value!!.getString("id"))
        assertEquals("Buy basil", value.getString("transcript"))
        Drafts.seen(context, ideas)
        assertEquals(1, Drafts.list.value.size)
        Drafts.seen(context, Ideas.parse(JSONObject().put("notes", org.json.JSONArray().put(JSONObject().put("id", d.id)))))
        assertTrue(Drafts.list.value.isEmpty())
    }

    @Test
    fun anUnlistedDraftGoesAgainAfterTenMinutesWhileTheLaptopIsThere() = runBlocking {
        Drafts.typed(context, "Buy basil")
        val sent = Drafts.list.value.single().sentMs!!
        Drafts.seen(context, ideas, sent + Drafts.RESEND_MS - 1)
        assertEquals(1, host.acts.size)
        // A view kept from before, the laptop away: nothing to learn from it.
        host.link.value = Link.OFFLINE
        Drafts.seen(context, ideas, sent + Drafts.RESEND_MS)
        assertEquals(1, host.acts.size)
        host.link.value = Link.CONNECTED
        Drafts.seen(context, ideas, sent + Drafts.RESEND_MS)
        assertEquals(2, host.acts.size)
    }

    @Test
    fun anAdditionLandsWhenItsNoteListsIt() = runBlocking {
        Drafts.put(Draft("eeeeeeeeeeeeeeee0000000000000005", basil, 1, typed = true, text = "and the mint", sentMs = 1))
        Drafts.put(Draft("ffff0000000000000000000000000009", basil, 2, typed = true, text = "and thyme", sentMs = System.currentTimeMillis()))
        Drafts.seen(context, ideas)
        assertEquals(listOf("ffff0000000000000000000000000009"), Drafts.list.value.map { it.id })
    }

    @Test
    fun aLongOrMultiLineTypedIdeaWaitsForGemini() = runBlocking {
        Drafts.typed(context, "first line\nsecond line")
        val d = Drafts.list.value.single()
        assertTrue(d.gemini)
        assertTrue(host.acts.isEmpty())
        Drafts.heard(context, d.id, "Two lines", "A summary", null)
        assertEquals("idea-new", host.acts.single().first)
        assertEquals("first line\nsecond line", host.acts.single().second!!.getString("transcript"))
        assertEquals("Two lines", host.acts.single().second!!.getString("title"))
    }

    @Test
    fun aTypedAdditionGeminiCantTitleKeepsItsNotesTitle() = runBlocking {
        Drafts.typed(context, "and the mint", note = basil)
        val d = Drafts.list.value.single()
        Drafts.heard(context, d.id, "", "", null)
        val (action, value) = host.acts.single()
        assertEquals("idea-add", action)
        assertEquals("", value!!.getString("title"))
        assertEquals("and the mint", value.getString("text"))
    }

    @Test
    fun anAdditionToANoteInTrashFailsKeepingItsWords() = runBlocking {
        val pond = "dddddddddddddddd0000000000000004"
        Drafts.put(Draft("ffff0000000000000000000000000009", pond, 2, typed = true, text = "and frogs", sentMs = 1))
        Drafts.seen(context, ideas)
        val d = Drafts.list.value.single()
        assertTrue(d.failed)
        assertEquals("Its note is in Trash", d.why)
        assertEquals("and frogs", d.text)
    }

    @Test
    fun theLastViewIsReadFromWhereTetherNestsItAndKeptOnDisk() = runBlocking {
        host.view.value = view()
        assertEquals(4, Drafts.lastIdeas(context)!!.notes.size)
        Drafts.seen(context, ideas)
        // A cold start: Tether has no view yet; the copy on disk stands in.
        Drafts.reset()
        host.view.value = null
        assertEquals(4, Drafts.lastIdeas(context)!!.notes.size)
    }

    @Test
    fun aRecordingCutOffIsKeptAndAnEmptyOneGoes() {
        val f = audio()
        Drafts.recording(context, f, 1_000, null)
        val empty = audio(0)
        Drafts.recording(context, empty, 2_000, basil)
        assertTrue(Drafts.list.value.all { it.recording })
        // The app is killed; it starts again.
        Drafts.reset()
        Drafts.init(context)
        val d = Drafts.list.value.single()
        assertEquals(f.name, d.audio)
        assertFalse(d.recording)
        assertTrue(d.gemini)
        assertFalse(empty.exists())
    }

    @Test
    fun aRecordingNoDraftNamesBecomesOne() {
        val f = audio()
        val empty = audio(0)
        Drafts.reset()
        Drafts.init(context)
        assertEquals(f.name, Drafts.list.value.single().audio)
        assertFalse(empty.exists())
    }

    @Test
    fun aFinishedRecordingTakesOverItsDraftAndAnEmptyOneLeavesNone() {
        val f = audio()
        Drafts.recording(context, f, 1_000, null)
        Drafts.recorded(context, f, 1_000, 5_000, null)
        val d = Drafts.list.value.single()
        assertEquals(5_000L, d.durationMs)
        assertFalse(d.recording)
        val empty = audio(0)
        Drafts.recording(context, empty, 2_000, null)
        empty.delete()
        Drafts.recorded(context, empty, 2_000, 0, null)
        assertEquals(listOf(d.id), Drafts.list.value.map { it.id })
    }

    @Test
    fun aTypedAdditionGoesUpWithItsNotesWholeText() {
        host.view.value = view()
        var sent: String? = null
        IdeaGemini.fake = { _, text -> sent = text; parsed("Basil and mint", "Water both.", "") }
        val d = runBlocking { Drafts.typed(context, "and thyme", note = basil); Drafts.list.value.single() }
        assertEquals(ListenableWorker.Result.success(), work(d.id))
        assertTrue(sent!!.startsWith("water the basil every other day"))
        assertTrue(sent!!.endsWith("and thyme"))
        val (action, value) = host.acts.single()
        assertEquals("idea-add", action)
        assertEquals("Basil and mint", value!!.getString("title"))
        assertEquals("and thyme", value.getString("text"))
    }

    @Test
    fun anAdditionWhoseNoteTextIsntHereNeverRetitlesIt() {
        IdeaGemini.fake = { what, _ -> if (what == "voice") parsed("Wrong title", "Wrong summary", "more words") else error("not asked") }
        val typed = runBlocking { Drafts.typed(context, "and thyme", note = basil); Drafts.list.value.single() }
        assertEquals(ListenableWorker.Result.success(), work(typed.id))
        assertEquals("", host.acts.last().second!!.getString("title"))
        val spoken = recorded(basil)
        assertEquals(ListenableWorker.Result.success(), work(spoken.id))
        val v = host.acts.last().second!!
        assertEquals("" to "", v.getString("title") to v.getString("summary"))
        assertEquals("more words", v.getString("text"))
    }

    @Test
    fun aRecordingInWhichNothingWasHeardIsDropped() {
        IdeaGemini.fake = { _, _ -> parsed("", "", " ") }
        val d = recorded(null)
        assertEquals(ListenableWorker.Result.success(), work(d.id))
        assertTrue(Drafts.list.value.isEmpty())
        assertTrue(host.acts.isEmpty())
    }

    @Test
    fun aTypedNoteGeminiGivesUpOnGoesAsTyped() {
        IdeaGemini.fake = { _, _ -> GeminiResult.Failed("HTTP 400: bad", terminal = true) }
        val d = runBlocking { Drafts.typed(context, "first line\nsecond line"); Drafts.list.value.single() }
        assertEquals(ListenableWorker.Result.success(), work(d.id))
        val v = host.acts.single().second!!
        assertEquals("first line second line", v.getString("title"))
    }

    @Test
    fun aRecordingGeminiGivesUpOnFailsForGood() {
        IdeaGemini.fake = { _, _ -> GeminiResult.Failed("HTTP 400: bad", terminal = true) }
        val d = recorded(null)
        assertEquals(ListenableWorker.Result.failure(), work(d.id))
        assertTrue(Drafts.get(d.id)!!.failed)
        assertTrue(host.acts.isEmpty())
    }

    @Test
    fun aRateLimitHoldsWithoutUsingAnAttemptAndOtherFailuresDo() {
        val d = recorded(null)
        IdeaGemini.fake = { _, _ -> GeminiResult.Failed("HTTP 429", terminal = false, retryAfterMs = 1_000) }
        assertEquals(ListenableWorker.Result.retry(), work(d.id))
        assertEquals(0, Drafts.get(d.id)!!.attempts)
        IdeaGemini.gate = RateGate(0)
        IdeaGemini.fake = { _, _ -> GeminiResult.NotCompleted("failed") }
        assertEquals(ListenableWorker.Result.retry(), work(d.id))
        assertEquals(1, Drafts.get(d.id)!!.attempts)
        // The same status twice is for good.
        assertEquals(ListenableWorker.Result.failure(), work(d.id, attempt = 1))
        assertTrue(Drafts.get(d.id)!!.failed)
    }

    @Test
    fun wholeTextJoinsThePartsAsCaptureDid() {
        assertEquals("a\n\nb", IdeaWorker.wholeText(listOf(" a ", "", "b\n")))
    }
}
