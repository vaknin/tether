package com.kivan.tether.dibs

import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** What an intent opens: a conversation's notification on a cold start (no view loaded yet), and Recents. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DibsActivityTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before
    fun reset() {
        Dibs.host = FakeHost(null)
        Dibs.pages.clear()
        Dibs.resetAsks()
    }

    private fun launch(intent: Intent) {
        Robolectric.buildActivity(DibsActivity::class.java, intent).create()
    }

    @Test
    fun aConversationsNotificationOpensItsPageBeforeAnyView() {
        launch(DibsActivity.ask(context, 7, "note:41"))
        assertEquals(listOf<Page>(Page.Ask("note:41")), Dibs.pages.toList())
    }

    @Test
    fun withoutItsSubjectItOpensOnceAViewListsIt() {
        launch(DibsActivity.ask(context, 7, null))
        assertTrue(Dibs.pages.isEmpty())
        Dibs.resolveAsk(DibsView.parse(JSONObject().put("threads", JSONArray().put(JSONObject().put("about", "task:9").put("id", 7)))))
        assertEquals(listOf<Page>(Page.Ask("task:9")), Dibs.pages.toList())
    }

    @Test
    fun aSubjectThatIsntOneIsIgnored() {
        launch(DibsActivity.ask(context, 7, "../../etc"))
        assertTrue(Dibs.pages.isEmpty())
        Dibs.resolveAsk(DibsView.parse(JSONObject().put("threads", JSONArray().put(JSONObject().put("about", "task:9").put("id", 7)))))
        assertEquals(listOf<Page>(Page.Ask("task:9")), Dibs.pages.toList())
    }

    @Test
    fun recentsDoesntOpenItAgain() {
        launch(DibsActivity.ask(context, 7, "note:41").addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY))
        launch(DibsActivity.task(context, 3).addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY))
        assertTrue(Dibs.pages.isEmpty())
        launch(DibsActivity.task(context, 3))
        assertEquals(listOf<Page>(Page.Task(3)), Dibs.pages.toList())
    }

    @Test
    fun theMorningRecapsNotificationOpensTheRecapTab() {
        assertEquals("recap", DibsActivity.tabFor("recap"))
        assertEquals("chat", DibsActivity.tabFor(null))
        assertEquals("chat", DibsActivity.tabFor("ask:7"))
        launch(DibsActivity.intent(context, DibsActivity.tabFor("recap")))
        assertEquals("recap", Dibs.tab)
    }
}
