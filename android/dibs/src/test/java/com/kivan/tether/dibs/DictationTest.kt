package com.kivan.tether.dibs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Talking into the box: where spoken words go, the words the recognizer expects, and the `spoken` mark. */
class DictationTest {
    private lateinit var host: FakeHost

    @Before
    fun reset() {
        host = FakeHost(null)
        Dibs.host = host
        Dibs.chat.draft = ""
        Dibs.chat.spoken = false
        Dibs.chat.about = null
        Dibs.chat.replyTo = null
    }

    @Test
    fun spokenWordsFollowTheBox() {
        assertEquals("Start the build", joinSpoken("", "start the build"))
        assertEquals("Fix it and ship", joinSpoken("Fix it", "and ship"))
        assertEquals("Fix it. Then ship", joinSpoken("Fix it.", " then ship"))
        assertEquals("Fix it? Yes", joinSpoken("Fix it?  ", "yes"))
        assertEquals("one\nTwo", joinSpoken("one\n", "two"))
        assertEquals("nothing said", "Fix it", joinSpoken("Fix it", "  "))
    }

    @Test
    fun theRecognizerExpectsDibssWordsAndTheBusiestProjects() {
        fun t(n: Int, p: String) = IndexTask(n, "t", "", "done", p, "", null)
        val index = RefIndex(listOf(t(1, "rami"), t(2, "tether"), t(3, "rami"), t(4, "Other"), t(5, ""), t(6, "motoparty"), t(7, "rami")), emptyList())
        val words = biasWords(index)
        assertEquals("dibs", words.first())
        assertEquals("once, whatever its case", 1, words.count { it.equals("tether", ignoreCase = true) })
        assertTrue(words.indexOf("rami") < words.indexOf("motoparty"))
        assertFalse(words.contains("Other"))
        assertFalse(words.contains(""))
        assertTrue(biasWords(null).contains("Tether"))
        val many = RefIndex((1..100).map { t(it, "p$it") }, emptyList())
        assertEquals(40, biasWords(many).size)
    }

    @Test
    fun aSpokenBoxSaysSoOnce() {
        Dibs.chat.spoken = true
        Dibs.chat.typed("Start the build")
        Dibs.chat.send()
        assertTrue(host.acts.last().second!!.getBoolean("spoken"))
        assertFalse("it's for one message", Dibs.chat.spoken)
        Dibs.chat.typed("typed this time")
        Dibs.chat.send()
        assertFalse(host.acts.last().second!!.has("spoken"))
    }

    @Test
    fun anEmptiedBoxIsNoLongerSpoken() {
        Dibs.chat.spoken = true
        Dibs.chat.typed("misheard")
        Dibs.chat.typed("")
        Dibs.chat.typed("typed instead")
        Dibs.chat.send()
        assertFalse(host.acts.last().second!!.has("spoken"))
    }

    @Test
    fun aTasksBoxCarriesItToo() {
        val box = Composer(42)
        box.spoken = true
        box.draft = "Use the cache"
        box.send()
        val (action, value) = host.acts.last()
        assertEquals("task-say", action)
        assertEquals(42L, value!!.getLong("task"))
        assertTrue(value.getBoolean("spoken"))
    }
}
