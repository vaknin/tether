package com.kivan.tether.dibs

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Ask about (task #102): the sample payload as parsed, and what each tap sends. */
class AskTest {
    private lateinit var host: FakeHost

    private fun sample(): JSONObject = JSONObject(javaClass.getResource("/threads.json")!!.readText())

    /** The sample's threads in a dibs payload, with a main chat line for each and the subjects' buttons. */
    private fun payload(): JSONObject = JSONObject()
        .put("now", 1791230200)
        .put("state", JSONObject().put("brain", "running"))
        .put("threads", sample().getJSONArray("threads"))
        .put("talk", JSONArray()
            .put(JSONObject().put("id", "s90").put("n", 90).put("who", "dibs").put("note", true).put("text", "Asking about: Home server").put("ts", 1791229900).put("thread", "note:41"))
            .put(JSONObject().put("id", "s91").put("n", 91).put("who", "user").put("text", "ok add the price to the note").put("ts", 1791230100)
                .put("under", "From your conversation about the home server")))
        .put("yours", JSONArray().put(JSONObject().put("id", 85).put("title", "Full story").put("state", "done")
            .put("ask", JSONObject().put("about", "task:85").put("label", "Ask more"))))
        .put("board", JSONObject().put("columns", JSONArray().put(JSONObject().put("key", "now").put("title", "Working now").put("cards", JSONArray()
            .put(JSONObject().put("key", "task:90").put("n", 90).put("title", "X").put("ask", JSONObject().put("about", "task:90").put("label", "Ask about it")))))))
        .put("ideas", JSONObject().put("notes", JSONArray().put(JSONObject().put("id", "41").put("title", "Home server")
            .put("ask", JSONObject().put("about", "note:41").put("label", "Back to your questions")))))

    @Before
    fun reset() {
        host = FakeHost(null)
        Dibs.host = host
        Dibs.pages.clear()
        Dibs.resetAsks()
    }

    @Test
    fun parsesTheSample() {
        val v = DibsView.parse(payload())
        assertEquals(listOf("note:41", "task:85"), v.asks.map { it.about })
        val a = v.asking("note:41")!!
        assertEquals("Home server", a.title)
        assertEquals("Asking about a note", a.eyebrow)
        assertEquals("answering", a.state)
        assertEquals(AskRowWords("Asking about: Home server", "Answering now · 4 questions", "busy"), a.row)
        assertTrue(a.intro!!.startsWith("This conversation knows only this note"))
        assertNull(a.reading)
        assertEquals(3, a.overview!!.chips.size)
        assertTrue(a.overview!!.text.contains("- 16 GB is too little"))
        assertEquals(7, a.lines.size)
        assertEquals("u-41-1", a.lines[0].uid)
        assertNull(a.lines[1].uid)
        assertEquals(listOf("How much does 32 GB cost on its own?", "Can it build the phone apps too?"), a.lines[3].chips)
        assertEquals("note", a.lines[5].who)
        assertEquals("Answered together with the line above", a.lines[6].wait)
        assertEquals(listOf("Why 32 GB and not 16?"), a.asked)
        assertEquals(AskStatus(true, "Answering 2 questions · reading the research"), a.status)
        assertNull(a.ended)
        assertEquals("Ask a question", a.placeholder)
        assertEquals("Done", a.done)
        assertNull(a.more)
        assertEquals(1791229900, a.ts)
        assertEquals(1791230110, a.lastTs)

        val e = v.asking("task:85")!!
        assertTrue(e.isEnded)
        assertNull(e.done)
        assertEquals("Ask more", e.more)
        assertEquals("Ended 13:40 · you tapped Done", e.ended!!.words)
        assertEquals("Kept in dibs's notes on #85:", e.ended!!.keptTitle)
        assertEquals(2, e.ended!!.kept.size)
        assertNotNull(e.ended!!.foot)
    }

    @Test
    fun theChatLineAndTheSubjectsCarryIt() {
        val v = DibsView.parse(payload())
        assertEquals("note:41", v.talk[0].thread)
        assertNull(v.talk[0].under)
        assertEquals("From your conversation about the home server", v.talk[1].under)
        assertNull(v.talk[1].thread)
        assertEquals(AskEntry("task:85", "Ask more"), v.yours!![0].ask)
        assertEquals(AskEntry("task:90", "Ask about it"), v.board!!.columns[0].cards[0].ask)
        assertEquals(AskEntry("note:41", "Back to your questions"), v.ideas!!.notes[0].ask)
        // A task without its own button takes its board card's.
        val t90 = YourTask(id = 90, title = "X", name = "x", project = "p", state = "working", ts = 0, started = 0)
        assertEquals("task:90", v.askFor(t90)!!.about)
    }

    @Test
    fun anOlderDibsSendsNoneOfIt() {
        val v = DibsView.parse(JSONObject().put("talk", JSONArray().put(JSONObject().put("id", "s1").put("who", "dibs").put("text", "hi")))
            .put("yours", JSONArray().put(JSONObject().put("id", 1).put("title", "t")))
            .put("ideas", JSONObject().put("notes", JSONArray().put(JSONObject().put("id", "4").put("title", "n")))))
        assertTrue(v.asks.isEmpty())
        assertNull(v.talk[0].thread)
        assertNull(v.yours!![0].ask)
        assertNull(v.ideas!!.notes[0].ask)
    }

    @Test
    fun brokenThreadsStillDraw() {
        val v = DibsView.parse(JSONObject().put("threads", JSONArray()
            .put(JSONObject().put("title", "no about"))
            .put(JSONObject().put("about", "note:1").put("overview", JSONObject.NULL).put("status", JSONObject().put("busy", true))
                .put("lines", JSONArray().put(JSONObject().put("who", "user").put("text", "no id")).put(JSONObject().put("id", "a").put("text", "x"))))
            .put(JSONObject().put("about", "note:1").put("title", "again"))
            .put(JSONObject().put("about", "task:2").put("ask", JSONObject().put("about", JSONObject.NULL)))))
        assertEquals(listOf("note:1", "task:2"), v.asks.map { it.about })
        val a = v.asks[0]
        assertNull(a.overview)
        assertNull("a status without words isn't drawn", a.status)
        assertEquals(listOf("a"), a.lines.map { it.id })
        assertNull(a.done)
    }

    @Test
    fun askingOpensThePageAtOnceAndAsksDibsOnce() {
        val v = DibsView.parse(payload())
        // Not listed yet: dibs is asked to open it.
        Dibs.askAbout(null, "task:31", "Recap")
        assertEquals(Page.Ask("task:31"), Dibs.pages.last())
        assertEquals("Recap", Dibs.askTitles["task:31"])
        val (action, value) = host.acts.single()
        assertEquals("thread-open", action)
        assertEquals("task:31", value!!.getString("about"))
        // Open already: only the page.
        host.acts.clear()
        Dibs.askAbout(v, "note:41", "Home server")
        assertTrue(host.acts.isEmpty())
        assertEquals(Page.Ask("note:41"), Dibs.pages.last())
        // Ended: it comes back.
        Dibs.askAbout(v, "task:85", "Full story")
        assertEquals("thread-open", host.acts.single().first)
        assertEquals("task:85", host.acts.single().second!!.getString("about"))
    }

    @Test
    fun aParagraphGoesIntoTheBoxUnsent() {
        Dibs.askAbout(null, "task:31", "Recap", draft = askQuote("It first folded the lines by day, which hid the newest entry. Then it keyed them."))
        assertEquals("About “It first folded the lines by day, which hid the newest entry”: ", Dibs.askBox("task:31").draft)
        assertEquals("only the open", listOf("thread-open"), host.acts.map { it.first })
        assertEquals(
            "About “It first folded the lines by day in”: ",
            askQuote("It first folded the lines by day in a way that hid the newest entry of all and more besides."),
        )
    }

    @Test
    fun aLineGoesWithItsUidAndItsEchoClearsWhenListed() {
        val box = Dibs.askBox("note:41")
        assertFalse(box.canSend)
        box.typed("how loud is it under a build?")
        assertTrue(box.canSend)
        box.send()
        assertEquals("", box.draft)
        val (action, value) = host.acts.single()
        assertEquals("thread-say", action)
        assertEquals("note:41", value!!.getString("about"))
        assertEquals("how loud is it under a build?", value.getString("text"))
        val uid = host.uids.single()!!
        val echo = Dibs.pending.value.single()
        assertEquals(uid, echo.uid)
        assertEquals("note:41", echo.ask)
        assertNull(echo.task)
        // Typing here tells dibs's chat nothing (no typing pings).
        assertEquals(1, host.acts.size)

        val listed = payload()
        listed.getJSONArray("threads").getJSONObject(0).getJSONArray("lines")
            .put(JSONObject().put("id", "t7-9").put("uid", uid).put("who", "user").put("text", "how loud is it under a build?").put("ts", 1791230200))
        Dibs.seen(DibsView.parse(listed))
        assertTrue(Dibs.pending.value.isEmpty())
    }

    @Test
    fun aChipIsSentAsTheUsersLine() {
        Dibs.askSay("note:41", "Why 32 GB and not 16?")
        val (action, value) = host.acts.single()
        assertEquals("thread-say", action)
        assertEquals("Why 32 GB and not 16?", value!!.getString("text"))
        assertNotNull(host.uids.single())
        assertEquals("Why 32 GB and not 16?", Dibs.pending.value.single().text)
    }

    @Test
    fun doneAndAskMore() {
        Dibs.askDone("note:41")
        Dibs.askMore("note:41")
        assertEquals(listOf("thread-done", "thread-open"), host.acts.map { it.first })
        assertTrue(host.acts.all { it.second!!.getString("about") == "note:41" })
    }

    @Test
    fun seenIsSentOncePerLineCount() {
        Dibs.seenAsk("note:41", 0, overview = false)
        Dibs.seenAsk("note:41", 0, overview = false)
        Dibs.seenAsk("note:41", 0, overview = true)
        Dibs.seenAsk("note:41", 3, overview = true)
        Dibs.seenAsk("note:41", 3, overview = true)
        Dibs.seenAsk("task:85", 3, overview = true)
        assertEquals(listOf("thread-seen", "thread-seen", "thread-seen", "thread-seen"), host.acts.map { it.first })
        assertEquals(listOf(0, 0, 3, 3), host.acts.map { it.second!!.getInt("n") })
        assertEquals(listOf("note:41", "note:41", "note:41", "task:85"), host.acts.map { it.second!!.getString("about") })
    }

    @Test
    fun theChatsEchoesLeaveTheConversationsAlone() {
        Dibs.askSay("note:41", "a question")
        assertTrue(Dibs.pending.value.none { it.task == null && it.ask == null })
    }

    @Test
    fun aSubjectHasAName() {
        assertEquals("Task #85", askSubject("task:85"))
        assertEquals("A note", askSubject("note:41"))
        assertEquals("tether", askSubject("project:tether"))
        assertEquals("PLAN.md", askSubject("file:/home/k/PLAN.md"))
        assertEquals("Reading the note", askReading("note:41"))
        assertFalse(askReading("task:1").contains("…"))
    }
}
