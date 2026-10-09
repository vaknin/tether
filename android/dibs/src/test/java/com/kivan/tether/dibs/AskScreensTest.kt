package com.kivan.tether.dibs

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.core.view.WindowCompat
import com.github.takahirom.roborazzi.captureRoboImage
import com.kivan.tether.dibs.ui.COULD_NOT_OPEN
import com.kivan.tether.dibs.ui.DibsApp
import com.kivan.tether.dibs.ui.NOT_TAKEN
import com.kivan.tether.dibs.ui.theme.AppTheme
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Ask about's screens (task #102, mockups p1–p9) on the JVM, at 412 dp and 320 dp. PNGs with
 * `./gradlew :dibs:testDebugUnitTest -Pscreenshots --tests '*AskScreensTest*'` → dibs/build/outputs/roborazzi/.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AskScreensTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var host: FakeHost

    @Before
    fun reset() {
        Taps.reset()
        Dibs.pages.clear()
        Dibs.open.clear()
        Dibs.answered.clear()
        Dibs.chat.draft = ""
        Dibs.resetAsks()
    }

    private fun show(view: JSONObject) {
        host = FakeHost(view)
        Dibs.host = host
        WindowCompat.setDecorFitsSystemWindows(compose.activity.window, false)
        compose.setContent { AppTheme { DibsApp() } }
        compose.waitForIdle()
    }

    private fun shot(name: String) = compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")

    private fun scrollTo(text: String) = compose.onNode(hasScrollAction()).performScrollToNode(hasText(text))

    private fun noEllipsis() {
        compose.onAllNodes(hasText("…", substring = true), useUnmergedTree = true).assertCountEquals(0)
    }

    private fun threads(): JSONArray = JSONObject(javaClass.getResource("/threads.json")!!.readText()).getJSONArray("threads")

    private fun open41(): JSONObject = threads().getJSONObject(0)

    private fun view(threads: JSONArray = JSONArray(), talk: JSONArray = JSONArray(), yours: JSONArray = JSONArray(), ideas: JSONObject? = null) = JSONObject().put(
        "dibs",
        JSONObject().put("v", 1).put("now", NOW)
            .put("state", JSONObject().put("brain", "running").put("busy", false))
            .put("talk", talk).put("questions", JSONArray()).put("yours", yours)
            .put("threads", threads)
            .apply { if (ideas != null) put("ideas", ideas) },
    )

    // p2: the tap opens the page at once, before dibs lists the conversation.
    private fun readingRightAfterTheTap() {
        show(view())
        compose.runOnUiThread { Dibs.askAbout(null, NOTE, "Home server") }
        compose.waitForIdle()
        assertEquals("thread-open", host.acts.single().first)
        compose.onNodeWithText("OPENING").assertIsDisplayed()
        compose.onNodeWithText("Home server").assertIsDisplayed()
        compose.onNodeWithText("Reading the note").assertIsDisplayed()
        compose.onNodeWithText("You can type questions now", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Ask a question").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).performTextInput("how loud is it under a build?")
        compose.onNodeWithContentDescription("Send").performClick()
        compose.waitForIdle()
        val (action, value) = host.acts.last()
        assertEquals("thread-say", action)
        assertEquals(NOTE, value!!.getString("about"))
        compose.onNodeWithText("how loud is it under a build?").assertIsDisplayed()
        compose.onNodeWithText("Waiting for the overview").assertIsDisplayed()
        noEllipsis()
    }

    @Test
    fun theReadingStateOpensAtOnce() {
        readingRightAfterTheTap()
        shot("ask-reading")
    }

    @Test
    @Config(qualifiers = "w320dp-h568dp-280dpi")
    fun theReadingStateOnASmallScreen() {
        readingRightAfterTheTap()
        shot("ask-reading-small")
    }

    // p3: the overview card and its suggested questions; a tap asks one.
    private fun overviewWithChips() {
        val t = open41().put("state", "open").put("lines", JSONArray()).put("asked", JSONArray()).put("status", JSONObject.NULL)
            .put("row", JSONObject().put("title", "Asking about: Home server").put("words", "New answer").put("tone", "new"))
        show(view(JSONArray().put(t)))
        compose.runOnUiThread { Dibs.open(Page.Ask(NOTE)) }
        compose.waitForIdle()
        compose.onNodeWithText("ASKING ABOUT A NOTE").assertIsDisplayed()
        compose.onNodeWithText("Done").assertIsDisplayed()
        compose.onNodeWithText("OVERVIEW").assertIsDisplayed()
        compose.onNodeWithText("16 GB is too little", substring = true).assertExists()
        for (c in listOf("Why 32 GB and not 16?", "Used or new: what's the risk?", "What moves off the laptop first?")) {
            scrollTo(c)
            compose.onNodeWithText(c).assertIsDisplayed()
        }
        assertTrue("on screen: seen", host.acts.any { it.first == "thread-seen" && it.second!!.getInt("n") == 0 })
        noEllipsis()
        shot(if (compose.activity.resources.configuration.screenWidthDp < 400) "ask-overview-small" else "ask-overview")
        compose.onNodeWithText("Why 32 GB and not 16?").performClick()
        compose.waitForIdle()
        val (action, value) = host.acts.last()
        assertEquals("thread-say", action)
        assertEquals("Why 32 GB and not 16?", value!!.getString("text"))
        // Asked here: it shows checked at once, and as the user's line.
        compose.onNodeWithContentDescription("Asked: Why 32 GB and not 16?").assertExists()
        // The asked chip is read once, as asked (its words aren't read again); the line is the one left.
        compose.onAllNodesWithText("Why 32 GB and not 16?").assertCountEquals(1)
    }

    @Test
    fun theOverviewWithItsSuggestedQuestions() = overviewWithChips()

    @Test
    @Config(qualifiers = "w320dp-h568dp-280dpi")
    fun theOverviewOnASmallScreen() = overviewWithChips()

    // p4/p5: a waiting line, a passed-on note, the newest answer's chips, what it's doing; Done.
    @Test
    fun aConversationInFullAndDone() {
        show(view(threads()))
        compose.runOnUiThread { Dibs.open(Page.Ask(NOTE)) }
        compose.waitForIdle()
        compose.onNodeWithText("and is it quiet?").assertIsDisplayed()
        compose.onNodeWithText("Answered together with the line above").assertIsDisplayed()
        compose.onNodeWithText("why not the Dell at 900?").assertIsDisplayed()
        compose.onNodeWithText("Answering 2 questions · reading the note").assertIsDisplayed()
        compose.onNodeWithText("Passed to dibs as your words", substring = true).assertIsDisplayed()
        scrollTo("Can it build the phone apps too?")
        compose.onNodeWithText("Can it build the phone apps too?").assertIsDisplayed()
        // The overview's suggestions went once an answer had its own.
        compose.onAllNodesWithText("Used or new: what's the risk?").assertCountEquals(0)
        assertTrue(host.acts.any { it.first == "thread-seen" && it.second!!.getInt("n") == 6 })
        noEllipsis()
        shot("ask-asking")
        compose.onNodeWithText("Done").performClick()
        compose.waitForIdle()
        assertEquals("thread-done", host.acts.last().first)
        // No confirm: it says it's ending at once and the box greys.
        compose.onAllNodesWithText("Done").assertCountEquals(0)
        // The box can't be typed into.
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
    }

    // p7: ending.
    @Test
    fun endingGreysTheBox() {
        val t = open41().put("state", "ending").put("done", JSONObject.NULL).put("placeholder", "Ending")
            .put("status", JSONObject().put("busy", true).put("words", "Ending · writing down what you learned for dibs's notes"))
        show(view(JSONArray().put(t)))
        compose.runOnUiThread { Dibs.open(Page.Ask(NOTE)) }
        compose.waitForIdle()
        compose.onNodeWithText("Ending · writing down what you learned for dibs's notes").assertIsDisplayed()
        compose.onNodeWithText("Ending").assertIsDisplayed()
        // The box can't be typed into.
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.onAllNodesWithText("Done").assertCountEquals(0)
        shot("ask-ending")
    }

    // p8: the ended card and Ask more in place of the box.
    private fun theEndedCard() {
        show(view(threads()))
        compose.runOnUiThread { Dibs.open(Page.Ask("task:1")) }
        compose.waitForIdle()
        compose.onNodeWithText("ENDED · ASKED ABOUT A TASK").assertIsDisplayed()
        compose.onNodeWithText("ENDED 13:43 · 30 MINUTES WITHOUT A QUESTION").assertIsDisplayed()
        compose.onNodeWithText("Kept in dibs's notes on The Full story of a task, on your phone:").assertIsDisplayed()
        compose.onNodeWithText("The story is written only when you ask.").assertIsDisplayed()
        compose.onNodeWithText("It never enters the dibs chat.").assertIsDisplayed()
        compose.onNodeWithText("The task itself is not changed", substring = true).assertIsDisplayed()
        compose.onAllNodesWithText("Ask a question").assertCountEquals(0)
        noEllipsis()
        shot(if (compose.activity.resources.configuration.screenWidthDp < 400) "ask-ended-small" else "ask-ended")
        compose.onNodeWithText("Ask more").assertIsDisplayed().performClick()
        compose.waitForIdle()
        val (action, value) = host.acts.last()
        assertEquals("thread-open", action)
        assertEquals("task:1", value!!.getString("about"))
    }

    @Test
    fun theEndedCard412() = theEndedCard()

    @Test
    @Config(qualifiers = "w320dp-h568dp-280dpi")
    fun theEndedCardOnASmallScreen() = theEndedCard()

    @Test
    fun aConversationGoneFromTheViewClosesItsPage() {
        show(view(threads()))
        compose.runOnUiThread { Dibs.open(Page.Ask("task:1")) }
        compose.waitForIdle()
        host.view.value = view()
        compose.waitForIdle()
        assertTrue(Dibs.pages.isEmpty())
    }

    // p6/p9: one row per conversation in the main chat; a line passed on says where it came from.
    private fun theMainChatRow() {
        val talk = JSONArray()
            .put(JSONObject().put("id", "s1").put("n", 1).put("who", "dibs").put("text", "Started #102: the “Ask about” conversations, as you said.").put("ts", NOW - 600))
            .put(JSONObject().put("id", "s2").put("n", 2).put("who", "dibs").put("note", true).put("text", "Asking about: Home server").put("ts", NOW - 500).put("thread", NOTE))
            .put(JSONObject().put("id", "u-3").put("n", 3).put("who", "user").put("text", "ok add the price to the note").put("ts", NOW - 300)
                .put("under", "From your conversation about the home server"))
            .put(JSONObject().put("id", "s4").put("n", 4).put("who", "dibs").put("note", true).put("text", "Asking about: Old thing").put("ts", NOW - 200).put("thread", "note:9"))
            .put(JSONObject().put("id", "s5").put("n", 5).put("who", "dibs").put("note", true).put("text", "Asking about: #1").put("ts", NOW - 100).put("thread", "task:1"))
        val t = open41().put("row", JSONObject().put("title", "Asking about: Home server").put("words", "New answer · 4 questions").put("tone", "new"))
        show(view(JSONArray().put(t).put(threads().getJSONObject(1)), talk))
        compose.onNodeWithText("Asking about: Home server").assertIsDisplayed()
        compose.onNodeWithText("New answer · 4 questions").assertIsDisplayed()
        compose.onNodeWithText("Asking about: The Full story of a task, on your phone").assertIsDisplayed()
        compose.onNodeWithText("From your conversation about the home server").assertIsDisplayed()
        // Not listed any more: its line reads as a note.
        compose.onNodeWithText("Asking about: Old thing").assertIsDisplayed()
        noEllipsis()
        shot(if (compose.activity.resources.configuration.screenWidthDp < 400) "ask-mainchat-small" else "ask-mainchat")
        compose.onNodeWithText("Asking about: Home server").performClick()
        compose.waitForIdle()
        assertEquals(Page.Ask(NOTE), Dibs.pages.last())
    }

    @Test
    fun theMainChatRow412() = theMainChatRow()

    @Test
    @Config(qualifiers = "w320dp-h568dp-280dpi")
    fun theMainChatRowOnASmallScreen() = theMainChatRow()

    @Test
    @Config(qualifiers = "w320dp-h568dp-280dpi")
    fun longTitlesAndChipsWrap() {
        val t = open41().put("title", LONG_TITLE).put("state", "open").put("lines", JSONArray()).put("asked", JSONArray()).put("status", JSONObject.NULL)
        t.getJSONObject("overview").put("chips", JSONArray().put(LONG_CHIP).put("Short?"))
        show(view(JSONArray().put(t)))
        compose.runOnUiThread { Dibs.open(Page.Ask(NOTE)) }
        compose.waitForIdle()
        compose.onNodeWithText(LONG_TITLE).assertIsDisplayed()
        scrollTo(LONG_CHIP)
        compose.onNodeWithText(LONG_CHIP).assertIsDisplayed()
        noEllipsis()
        shot("ask-long-small")
    }

    // p1: the subjects' buttons.
    @Test
    fun aTaskPageAsksAboutIt() {
        val task = JSONObject().put("id", 85).put("title", "The Full story of a task, on your phone").put("name", "t85").put("project", "tether")
            .put("state", "done").put("ts", NOW - 60).put("started", NOW - 3600).put("asked", "on-demand Full story").put("line", "Added a Full story button")
            .put("ask", JSONObject().put("about", "task:85").put("label", "Ask about it"))
        show(view(yours = JSONArray().put(task)))
        compose.runOnUiThread { Dibs.open(Page.Task(85)) }
        compose.waitForIdle()
        shot("ask-task-page")
        compose.onNodeWithText("Ask about it").assertIsDisplayed().performClick()
        compose.waitForIdle()
        assertEquals(Page.Ask("task:85"), Dibs.pages.last())
        assertEquals("thread-open", host.acts.last { it.first != "seen" }.first)
        compose.onNodeWithText("The Full story of a task, on your phone").assertIsDisplayed()
        compose.onNodeWithText("Reading the task").assertIsDisplayed()
        // Back returns to the task.
        compose.runOnUiThread { Dibs.back() }
        compose.waitForIdle()
        assertEquals(Page.Task(85), Dibs.pages.last())
    }

    @Test
    fun anOlderDibsKeepsTodaysButton() {
        val task = JSONObject().put("id", 85).put("title", "Full story").put("name", "t85").put("project", "tether")
            .put("state", "done").put("ts", NOW - 60).put("started", NOW - 3600)
        show(view(yours = JSONArray().put(task)))
        compose.runOnUiThread { Dibs.open(Page.Task(85)) }
        compose.waitForIdle()
        compose.onNodeWithText("Ask dibs about it").assertIsDisplayed()
    }

    @Test
    fun aNotePageAsksAboutIt() {
        val ideas = JSONObject().put("notes", JSONArray().put(
            JSONObject().put("id", "41").put("label", "Note 41").put("title", "Home server").put("summary", "A small always-on box at home.")
                .put("meta", "Voice · 2 days ago").put("ask", JSONObject().put("about", "note:41").put("label", "Ask about this note")),
        ))
        show(view(ideas = ideas))
        compose.runOnUiThread { Dibs.open(Page.Idea("41")) }
        compose.waitForIdle()
        shot("ask-note-page")
        compose.onNodeWithText("Ask about this note").assertIsDisplayed().performClick()
        compose.waitForIdle()
        assertEquals(Page.Ask("note:41"), Dibs.pages.last())
        assertEquals("thread-open", host.acts.last().first)
    }

    // The page's waits on the test clock: it moves only when told.
    private fun stopTheClock() {
        compose.mainClock.autoAdvance = false
        Dibs.clock = { compose.mainClock.currentTime }
    }

    // What changed outside the screen (a tap, a page opened) is taken in first, then the time passes.
    private fun tick(ms: Long) {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(ms)
        compose.waitForIdle()
    }

    private fun link(l: Link) {
        host.link.value = l
        tick(100)
    }

    @Test
    fun anOpenDibsNeverListsSaysSoOnlyOnceTheLinkWasUpAWhile() {
        show(view())
        stopTheClock()
        link(Link.OFFLINE)
        compose.runOnUiThread { Dibs.askAbout(null, NOTE, "Home server") }
        tick(100)
        // Offline: it waits as long as it takes.
        tick(5 * Dibs.ASK_WAIT_MS)
        compose.onAllNodesWithText(COULD_NOT_OPEN).assertCountEquals(0)
        compose.onNodeWithText("OPENING").assertIsDisplayed()
        // Up: 20 s from then.
        link(Link.CONNECTED)
        tick(Dibs.ASK_WAIT_MS - 1_000)
        compose.onAllNodesWithText(COULD_NOT_OPEN).assertCountEquals(0)
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(1)
        tick(2_000)
        compose.onNodeWithText(COULD_NOT_OPEN).assertIsDisplayed()
        compose.onAllNodesWithText("OPENING").assertCountEquals(0)
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.onNodeWithText("Back").assertIsDisplayed()
        noEllipsis()
        // Try again asks anew, and waits again.
        val asked = host.acts.size
        compose.onNodeWithText("Try again").performClick()
        tick(100)
        assertEquals("thread-open", host.acts.drop(asked).single().first)
        compose.onNodeWithText("OPENING").assertIsDisplayed()
        tick(Dibs.ASK_WAIT_MS + 1_000)
        compose.onNodeWithText(COULD_NOT_OPEN).assertIsDisplayed()
        compose.onNodeWithText("Back").performClick()
        tick(100)
        assertTrue(Dibs.pages.isEmpty())
    }

    @Test
    @Config(qualifiers = "w320dp-h568dp-280dpi")
    fun theFailedCardOnASmallScreen() {
        show(view())
        stopTheClock()
        compose.runOnUiThread { Dibs.askAbout(null, NOTE, "Home server") }
        tick(Dibs.ASK_WAIT_MS + 1_000)
        compose.onNodeWithText(COULD_NOT_OPEN).assertIsDisplayed()
        compose.onNodeWithText("Try again").assertIsDisplayed()
        compose.onNodeWithText("Back").assertIsDisplayed()
        shot("ask-failed-small")
    }

    @Test
    fun aLineDibsDoesntListSaysSo() {
        show(view(threads()))
        stopTheClock()
        compose.runOnUiThread { Dibs.open(Page.Ask(NOTE)) }
        tick(100)
        compose.runOnUiThread { Dibs.askSay(NOTE, "is it loud?") }
        tick(Dibs.ASK_WAIT_MS - 1_000)
        compose.onAllNodesWithText(NOT_TAKEN).assertCountEquals(0)
        tick(2_000)
        scrollTo(NOT_TAKEN)
        compose.onNodeWithText(NOT_TAKEN).assertIsDisplayed()
        compose.onNodeWithText("is it loud?").assertIsDisplayed()
    }

    @Test
    fun doneComesBackOnlyOnceTheLinkWasUpAWhile() {
        show(view(threads()))
        stopTheClock()
        compose.runOnUiThread { Dibs.open(Page.Ask(NOTE)) }
        tick(100)
        link(Link.OFFLINE)
        compose.onNodeWithText("Done").performClick()
        tick(100)
        assertEquals("thread-done", host.acts.last().first)
        compose.onAllNodesWithText("Done").assertCountEquals(0)
        tick(5 * Dibs.TAP_MS)
        compose.onAllNodesWithText("Done").assertCountEquals(0)
        link(Link.CONNECTED)
        tick(Dibs.TAP_MS - 1_000)
        compose.onAllNodesWithText("Done").assertCountEquals(0)
        tick(2_000)
        compose.onNodeWithText("Done").assertIsDisplayed()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(1)
    }

    @Test
    fun aTabChangedBeforeTheViewListsItKeepsTheNotificationsPageAway() {
        show(view())
        compose.runOnUiThread { Dibs.openAsk(1, null, null) }
        compose.waitForIdle()
        compose.onNodeWithText("Tasks").performClick()
        compose.waitForIdle()
        host.view.value = view(threads())
        compose.waitForIdle()
        assertTrue(Dibs.pages.isEmpty())
        // Without one, it opens when listed.
        host.view.value = view()
        compose.runOnUiThread { Dibs.openAsk(1, null, null) }
        compose.waitForIdle()
        host.view.value = view(threads())
        compose.waitForIdle()
        assertEquals(listOf<Page>(Page.Ask(NOTE)), Dibs.pages.toList())
    }

    private companion object {
        val NOW = System.currentTimeMillis() / 1000
        /** The sample's open conversation (dibs's tests/samples/threads.json). */
        const val NOTE = "note:aaaaaaaaaaaaaaaa0000000000000041"
        const val LONG_TITLE = "The home server that would run dibs, the builds and every agent so the laptop stays light and can sleep at night"
        const val LONG_CHIP = "If I buy the used one now, what do I have to check before paying so it doesn't fail in a month?"
    }
}
