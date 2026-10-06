package com.kivan.tether.dibs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Talking to dibs about a task's full story: the chip's state and what the `say` carries. */
class DibsTest {
    private lateinit var host: FakeHost
    private val task = YourTask(id = 31, title = "Recap", name = "recap", project = "tether", state = "done", ts = 9, started = 1)

    @Before
    fun reset() {
        host = FakeHost(null)
        Dibs.host = host
        Dibs.chat.draft = ""
        Dibs.chat.about = null
        Dibs.pages.clear()
    }

    @Test
    fun askingAboutAStoryRidesInTheSay() {
        Dibs.pages += Page.Story(31)
        Dibs.chatAboutStory(task, "ask", "It tried X first.")
        assertTrue("the chat shows", Dibs.pages.isEmpty())
        assertEquals("", Dibs.chat.draft)
        Dibs.chat.draft = "Why X?"
        Dibs.chat.send()
        val (action, value) = host.acts.single()
        assertEquals("say", action)
        assertEquals("Why X?", value!!.getString("text"))
        val about = value.getJSONObject("about")
        assertEquals(31L, about.getLong("story"))
        assertEquals("ask", about.getString("kind"))
        assertEquals("It tried X first.", about.getString("quote"))
        assertNull("it's for one message", Dibs.chat.about)
    }

    @Test
    fun aFollowUpIsSaidInTheDraftAndGoesWithTheChip() {
        Dibs.chatAboutStory(task, "follow")
        assertEquals("Follow-up: ", Dibs.chat.draft)
        Dibs.chatAboutStory(task, "follow")
        assertEquals("not twice", "Follow-up: ", Dibs.chat.draft)
        Dibs.dropAbout()
        assertNull(Dibs.chat.about)
        assertEquals("", Dibs.chat.draft)
        Dibs.chat.draft = "plain"
        Dibs.chat.send()
        assertFalse("a message about nothing", host.acts.single().second!!.has("about"))
    }

    @Test
    fun writingAgainAsksDibs() {
        Dibs.story(31, again = true)
        val (action, value) = host.acts.single()
        assertEquals("story", action)
        assertEquals(31L, value!!.getLong("task"))
        assertTrue(value.getBoolean("again"))
    }
}
