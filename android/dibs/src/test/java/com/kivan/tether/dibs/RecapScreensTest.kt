package com.kivan.tether.dibs

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.core.view.WindowCompat
import com.kivan.tether.dibs.ui.DibsApp
import com.kivan.tether.dibs.ui.theme.AppTheme
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The morning recap (design C): the unread list, the merged story screen, a Tasks card opening it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RecapScreensTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var host: FakeHost

    @Before
    fun reset() {
        Dibs.pages.clear()
        Dibs.answered.clear()
        Dibs.fields.clear()
        Dibs.open.clear()
        Dibs.chat.draft = ""
        Dibs.chat.about = null
        Dibs.holdTap = null
        Dibs.tab = null
    }

    private fun show(view: JSONObject, tab: String? = "recap") {
        host = FakeHost(view)
        Dibs.host = host
        WindowCompat.setDecorFitsSystemWindows(compose.activity.window, false)
        compose.setContent { AppTheme { DibsApp() } }
        Dibs.tab = tab
        compose.waitForIdle()
    }

    private fun brief(task: Long, tier: String, title: String, unread: Boolean = true, extra: JSONObject.() -> Unit = {}) = JSONObject()
        .put("key", "t$task").put("task", task).put("tier", tier).put("title", title).put("lede", "The line under $title.")
        .put("what", "What happened in $title.").put("why", "Why for $title.").put("means", "").put("next", "Next for $title.")
        .put("project", "dibs").put("kind", "plan").put("state", "done").put("ts", NOW - 600).put("unread", unread).apply(extra)

    private fun recapView(items: List<JSONObject>, extra: JSONObject.() -> Unit = {}): JSONObject {
        val unread = items.count { it.getBoolean("unread") }
        val d = JSONObject()
            .put("v", 1).put("now", NOW)
            .put("state", JSONObject().put("brain", "running").put("busy", false))
            .put("talk", JSONArray()).put("questions", JSONArray()).put("yours", JSONArray())
            .put("recap", JSONObject().put("items", JSONArray(items)).put("unread", unread).put("needs", 1)
                .put("small", JSONObject().put("n", 47).put("groups", JSONArray()
                    .put(JSONObject().put("project", "dibs").put("n", 21).put("lines", JSONArray().put("Chat no longer repeats its last message").put("Stats examples fixed")))
                    .put(JSONObject().put("project", "tether").put("n", 9).put("lines", JSONArray().put("Reconnects faster after the phone wakes")))))
                .put("decided", JSONArray().put(JSONObject().put("id", 9).put("text", "LocalSend opens only at home").put("why", "Safer").put("from", "a").put("ts", NOW - 100).put("undo", true).put("ack", "k9"))))
            .put("badges", JSONObject().put("waiting", 0).put("recap", unread))
        d.extra()
        return JSONObject().put("dibs", d)
    }

    private val three get() = listOf(
        brief(210, "needs", "The plan is ready"),
        brief(189, "asked", "Terminal choice", extra = { put("answer", "tmux on the server") }),
        brief(137, "talked", "More room for the chat", unread = false),
    )

    @Test
    fun theUnreadListDraws() {
        show(recapView(three))
        compose.onNodeWithText("UNREAD").assertIsDisplayed()
        compose.onNodeWithText("1 need you · 47 small fixes folded").assertIsDisplayed()
        compose.onNodeWithText("Mark all read").assertIsDisplayed()
        for (t in listOf("The plan is ready", "Terminal choice", "More room for the chat")) compose.onNodeWithText(t).assertIsDisplayed()
        compose.onNodeWithText("The line under The plan is ready.").assertIsDisplayed()
        compose.onNodeWithText("Needs you").assertIsDisplayed()
        compose.onNodeWithText("Answers your question").assertIsDisplayed()
        compose.onNodeWithText("You talked it over").assertIsDisplayed()
        compose.onNodeWithText("THE REST").assertIsDisplayed()
        compose.onNodeWithText("SMALL FIXES · 47").assertIsDisplayed()
        compose.onNodeWithText("LocalSend opens only at home").assertIsDisplayed()
        // The tab's badge is the unread count.
        compose.onAllNodes(hasText("2"), useUnmergedTree = true).assertCountEquals(2)
        // The fold opens to each project's lines, and says how many more there are.
        compose.onNodeWithText("SMALL FIXES · 47").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Stats examples fixed").assertIsDisplayed()
        compose.onNodeWithText("19 more").assertIsDisplayed()
        compose.onNodeWithText("8 more").assertIsDisplayed()
    }

    @Test
    fun markAllReadSendsItAndClearsTheDots() {
        show(recapView(three))
        compose.onNodeWithText("Mark all read").performClick()
        compose.waitForIdle()
        val (_, v) = host.acts.single { it.first == "recap-seen" }
        assertTrue(v!!.getBoolean("all"))
        compose.onAllNodes(hasText("Mark all read")).assertCountEquals(0)
    }

    @Test
    fun aTapOpensTheBriefAndSaysItWasRead() {
        show(recapView(three))
        compose.onNodeWithText("The plan is ready").performClick()
        compose.waitForIdle()
        assertEquals(Page.Brief(210, true), Dibs.pages.last())
        val (_, v) = host.acts.single { it.first == "recap-seen" }
        assertEquals(210L, v!!.getLong("task"))
        compose.onNodeWithText("1 of 3").assertIsDisplayed()
        for (p in listOf("WHAT HAPPENED", "WHY", "WHAT'S NEXT")) compose.onNodeWithText(p).assertIsDisplayed()
        // An empty part isn't drawn.
        compose.onAllNodes(hasText("WHAT IT MEANS FOR YOU")).assertCountEquals(0)
        compose.onNodeWithText("What happened in The plan is ready.").assertIsDisplayed()
        compose.onNodeWithText("Ask dibs about this").assertIsDisplayed()
        compose.onNodeWithText("FULL STORY").assertIsDisplayed()
        // Nothing paid starts by itself: the full story waits for a tap.
        assertTrue(host.acts.none { it.first == "fetch" || it.first == "story" })
        compose.onNodeWithText("Read the full story").performClick()
        compose.waitForIdle()
        val (_, f) = host.acts.single { it.first == "fetch" }
        assertEquals("story", f!!.getString("what"))
        assertEquals(210L, f.getLong("task"))
        // Back to the list: that row has no dot now, so Mark all read counts one fewer.
        Dibs.back()
        compose.waitForIdle()
        compose.onNodeWithText("1 need you · 47 small fixes folded").assertIsDisplayed()
    }

    @Test
    fun theNextArrowMovesOnInPlace() {
        show(recapView(three))
        compose.onNodeWithText("The plan is ready").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Next").performClick()
        compose.waitForIdle()
        assertEquals(listOf<Page>(Page.Brief(189, true)), Dibs.pages.toList())
        compose.onNodeWithText("2 of 3").assertIsDisplayed()
        compose.onNodeWithText("THE ANSWER").assertIsDisplayed()
        compose.onNodeWithText("tmux on the server").assertIsDisplayed()
        assertEquals(listOf(210L, 189L), host.acts.filter { it.first == "recap-seen" }.map { it.second!!.getLong("task") })
        // The last one read already: no second recap-seen for it, and no next.
        compose.onNodeWithContentDescription("Next").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("3 of 3").assertIsDisplayed()
        assertEquals(2, host.acts.count { it.first == "recap-seen" })
        compose.onNodeWithContentDescription("Previous").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("2 of 3").assertIsDisplayed()
        assertEquals(1, Dibs.pages.size)
    }

    @Test
    fun aSwipeMovesOnToo() {
        show(recapView(three))
        compose.onNodeWithText("The plan is ready").performClick()
        compose.waitForIdle()
        compose.onRoot().performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals(Page.Brief(189, true), Dibs.pages.last())
    }

    @Test
    fun anOpenQuestionsButtonsActFromTheBrief() {
        val v = recapView(listOf(brief(210, "needs", "The plan is ready", extra = { put("card", 1093) })))
        v.getJSONObject("dibs").put("questions", JSONArray().put(JSONObject().put("id", 1093).put("title", "Build the plan?").put("why", "It is cheap")
            .put("from", "agent").put("ts", NOW - 60).put("kind", "question").put("actions", JSONArray()
                .put(JSONObject().put("id", "y1093").put("label", "Build it").put("style", "primary"))
                .put(JSONObject().put("id", "n1093").put("label", "Backlog it").put("style", "")))))
        show(v)
        compose.onNodeWithText("The plan is ready").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Build the plan?").assertIsDisplayed()
        compose.onNodeWithText("Build it").performClick()
        compose.waitForIdle()
        assertTrue(host.acts.any { it.first == "y1093" })
        compose.onAllNodes(hasText("Build it")).assertCountEquals(0)
    }

    @Test
    @Config(qualifiers = "w320dp-h568dp-280dpi")
    fun theBriefsButtonsFitASmallScreen() {
        val item = brief(210, "needs", "Plan", extra = { put("what", "A plan."); put("why", ""); put("next", ""); put("card", 1093) })
        val v = recapView(listOf(item))
        v.getJSONObject("dibs").put("yours", JSONArray().put(JSONObject().put("id", 210).put("title", "Plan").put("name", "t210").put("project", "dibs")
            .put("state", "needs").put("ts", NOW).put("started", NOW - 3600).put("asked", "Plan")))
        v.getJSONObject("dibs").put("questions", JSONArray().put(JSONObject().put("id", 1093).put("title", "Build it?").put("why", "")
            .put("from", "agent").put("ts", NOW - 60).put("kind", "question").put("actions", JSONArray()
                .put(JSONObject().put("id", "y1093").put("label", "Build it").put("style", "primary"))
                .put(JSONObject().put("id", "n1093").put("label", "Backlog it").put("style", "")))))
        show(v)
        compose.onNodeWithText("Plan").performClick()
        compose.waitForIdle()
        for (b in listOf("Build it", "Backlog it", "Ask dibs about this", "Open the task")) compose.onNodeWithText(b).assertIsDisplayed()
        compose.onAllNodes(hasText("…", substring = true), useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun aReadyStoryReadsUnderTheParts() {
        val item = brief(210, "needs", "The plan is ready", extra = { put("story", JSONObject().put("state", "ready").put("ts", NOW - 600).put("have", true).put("by", "agent")) })
        show(recapView(listOf(item)))
        val f = File.createTempFile("story-210-", ".md").apply { deleteOnExit(); writeText("# The long account\n\nIt went like this.\n") }
        host.files["story-210-"] = f
        compose.onNodeWithText("The plan is ready").performClick()
        compose.waitForIdle()
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("It went like this.")).fetchSemanticsNodes().isNotEmpty() }
        assertTrue("a kept one isn't asked for again", host.acts.none { it.first == "fetch" })
    }

    @Test
    fun aStoryBeingWrittenIsNeverFetchedFromTheBrief() {
        val item = brief(210, "needs", "Plan", extra = { put("writing", true); put("what", ""); put("story", JSONObject().put("state", "writing").put("since", NOW - 60)) })
        show(recapView(listOf(item)))
        compose.onNodeWithText("Plan").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("The full write-up is being written").assertIsDisplayed()
        // With no `what` yet, the line under the title stands in for it.
        compose.onNodeWithText("The line under Plan.").assertIsDisplayed()
        assertTrue(host.acts.none { it.first == "fetch" || it.first == "story" })
    }

    @Test
    fun aBoardCardOfATaskNotYoursOpensTheBrief() {
        val v = recapView(emptyList())
        fun card(key: String, n: Long, title: String, b: JSONObject?) = JSONObject().put("key", key).put("n", n).put("title", title).put("state_words", "Waiting its turn")
            .put("now", "").put("tags", JSONArray()).put("actions", JSONArray()).apply { if (b != null) put("brief", b) }
        v.getJSONObject("dibs").put("board", JSONObject().put("columns", JSONArray().put(JSONObject().put("key", "now").put("title", "Working now").put("cards", JSONArray()
            .put(card("task:50", 50, "A task dibs started", brief(50, "asked", "The card's own account", false)))
            .put(card("task:51", 51, "A bare card", null))))))
        show(v, tab = "tasks")
        compose.onNodeWithText("A task dibs started").performClick()
        compose.waitForIdle()
        assertEquals(Page.Brief(50, false), Dibs.pages.last())
        compose.onNodeWithText("Story").assertIsDisplayed()
        compose.onNodeWithText("The card's own account").assertIsDisplayed()
        assertFalse("no arrows outside Recap", compose.onAllNodes(hasText("Next")).fetchSemanticsNodes().isNotEmpty())
        Dibs.back()
        compose.waitForIdle()
        compose.onNodeWithText("A bare card").performClick()
        compose.waitForIdle()
        assertEquals(Page.Brief(51, false), Dibs.pages.last())
        compose.onNodeWithText("Nothing written up yet").assertIsDisplayed()
        compose.onNodeWithText("Waiting its turn").assertIsDisplayed()
        compose.onNodeWithText("Ask dibs about this").assertIsDisplayed()
        compose.onNodeWithText("Read the full story").assertIsDisplayed()
    }

    @Test
    fun anEmptyListIsCaughtUp() {
        show(recapView(emptyList()))
        compose.onNodeWithText("You're caught up").assertIsDisplayed()
        compose.onAllNodes(hasText("Mark all read")).assertCountEquals(0)
    }

    @Test
    fun anOlderDibsStillGetsTheOldTab() {
        val d = JSONObject().put("v", 1).put("now", NOW).put("state", JSONObject().put("brain", "running"))
            .put("talk", JSONArray()).put("questions", JSONArray()).put("yours", JSONArray())
            .put("recap", JSONObject().put("feed", JSONArray().put(JSONObject().put("ts", NOW - 60).put("kind", "did").put("who", "a").put("text", "Shipped the fix"))))
            .put("badges", JSONObject().put("recap", 1))
        show(JSONObject().put("dibs", d))
        compose.onNodeWithText("DONE TODAY").assertIsDisplayed()
        compose.onNodeWithText("Shipped the fix").assertIsDisplayed()
        compose.onAllNodes(hasText("UNREAD")).assertCountEquals(0)
    }

    private companion object {
        val NOW = System.currentTimeMillis() / 1000
    }
}
