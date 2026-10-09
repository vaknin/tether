package com.kivan.tether.dibs

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.core.view.WindowCompat
import com.github.takahirom.roborazzi.captureRoboImage
import com.kivan.tether.dibs.ideas.Drafts
import com.kivan.tether.dibs.ui.IdeaTaps
import com.kivan.tether.dibs.ui.DibsApp
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

/** The Ideas tab and a note's page, drawn from dibs's sample (tests/samples/ideas.json). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h568dp-280dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IdeasScreensTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var host: FakeHost

    @Before
    fun reset() {
        Taps.reset()
        Dibs.pages.clear()
        Dibs.fields.clear()
        Dibs.open.clear()
        Drafts.reset()
        IdeaTaps.reset()
        compose.activity.filesDir.resolve("dibs-ideas").deleteRecursively()
        val ideas = JSONObject(javaClass.getResource("/ideas.json")!!.readText()).getJSONObject("ideas")
        val view = JSONObject().put(
            "dibs",
            JSONObject().put("v", 1).put("now", System.currentTimeMillis() / 1000)
                .put("state", JSONObject().put("brain", "running").put("busy", false))
                .put("talk", JSONArray()).put("questions", JSONArray()).put("yours", JSONArray())
                .put("ideas", ideas),
        )
        host = FakeHost(view)
        Dibs.host = host
        WindowCompat.setDecorFitsSystemWindows(compose.activity.window, false)
        compose.setContent { AppTheme { DibsApp() } }
        compose.onNodeWithText("Ideas").performClick()
        compose.waitForIdle()
    }

    /** Scrolls the tab's list to [text] (a lazy list composes only what's on screen). */
    private fun scrollTo(text: String) = compose.onNode(hasScrollAction()).performScrollToNode(hasText(text))

    private fun shot(name: String) = compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")

    @Test
    fun theTabDrawsDibssNotesAndAnAnswerShowsAtOnce() {
        compose.onNodeWithText("Say it").assertIsDisplayed()
        compose.onNodeWithText("Or type it here").assertIsDisplayed()
        shot("ideas-tab")
        scrollTo("Standing desk")
        compose.onNodeWithText("Standing desk").assertIsDisplayed()
        compose.onNodeWithText("Research").performClick()
        compose.waitForIdle()
        val (action, value) = host.acts.last()
        assertEquals("idea-answer", action)
        assertEquals("research", value!!.getString("choice"))
        assertTrue(value.getLong("at") > 0)
        compose.onNodeWithText("Research: sent").assertIsDisplayed()
        // Tether rebuilding its view (its node restarted) is no answer from dibs: the echo stays.
        val same = JSONObject(host.view.value.toString())
        host.view.value = same
        compose.waitForIdle()
        compose.onNodeWithText("Research: sent").assertIsDisplayed()
        // dibs's view with the question answered ends it.
        val desk = same.getJSONObject("dibs").getJSONObject("ideas").getJSONArray("notes").getJSONObject(0)
        desk.put("actions", JSONArray()).put("status", JSONObject().put("text", "Research running").put("tone", "work"))
        host.view.value = JSONObject(same.toString())
        compose.waitForIdle()
        scrollTo("Research running")
        compose.onNodeWithText("Research running").assertIsDisplayed()
        compose.onAllNodesWithText("Research: sent").assertCountEquals(0)
    }

    @Test
    fun aTypedIdeaShowsAsADraftUntilDibsListsIt() {
        compose.onAllNodes(hasSetTextAction()).onFirst().performTextInput("Buy basil")
        compose.onAllNodesWithContentDescription("Save").onFirst().performClick()
        compose.waitForIdle()
        assertEquals("idea-new", host.acts.last().first)
        compose.onNodeWithText("#–").assertIsDisplayed()
        compose.onNodeWithText("Sent, waiting for the laptop").assertIsDisplayed()
        shot("ideas-draft")
    }

    @Test
    fun aQuestionDibsAsksAboutADropHasItsAnswerBoxAndButtons() {
        compose.onNodeWithText("Drop an idea, a problem or a task").assertIsDisplayed()
        compose.onNodeWithText("dibs files it in the Backlog and asks if it needs more").assertIsDisplayed()
        compose.onNodeWithText("dibs asks: Which app: the phone or the laptop?").assertIsDisplayed()
        compose.onNodeWithText("File it as it is").assertIsDisplayed()
        compose.onNodeWithText("Drop it").assertIsDisplayed()
        shot("ideas-tab-asks")
        compose.onNodeWithText("File it as it is").performClick()
        compose.waitForIdle()
        val (action, value) = host.acts.last()
        assertEquals("drop-answer", action)
        assertEquals("file", value!!.getString("choice"))
        scrollTo("FILED · 1")
        shot("ideas-tab-filed")
    }

    @Test
    fun trashFoldsAndRestores() {
        scrollTo("TICKED OFF · 2")
        compose.onNodeWithText("TICKED OFF · 2").performClick()
        compose.waitForIdle()
        scrollTo("Pond in the garden")
        compose.onNodeWithText("Pond in the garden").assertIsDisplayed()
        compose.onAllNodesWithText("Restore").onFirst().performClick()
        compose.waitForIdle()
        assertEquals("idea-restore", host.acts.last().first)
    }

    @Test
    fun aLongNotesPageLoadsItsTranscriptOnATap() {
        scrollTo("Notes from the call with the builder")
        compose.onNodeWithText("Notes from the call with the builder").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Transcript").performClick()
        compose.waitForIdle()
        shot("idea-page-long")
        compose.onNodeWithText("Load the whole transcript").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals("fetch", host.acts.last().first)
        assertEquals("idea", host.acts.last().second!!.getString("what"))
    }

    @Test
    fun aNotesPageShowsItsSummaryAndDoneGoesBack() {
        scrollTo("Water plan for the balcony")
        compose.onNodeWithText("Water plan for the balcony").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Drip line on a timer").assertIsDisplayed()
        shot("idea-page")
        compose.onNodeWithText("Done").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals("idea-done", host.acts.last().first)
        // Back on the tab, the note is gone at once (until dibs's next view says otherwise).
        scrollTo("NOTES · 4")
        compose.onAllNodesWithText("Water plan for the balcony").assertCountEquals(0)
    }
}
