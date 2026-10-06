package com.kivan.tether.dibs

import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.kivan.tether.dibs.ui.DibsApp
import com.kivan.tether.dibs.ui.theme.AppTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** What the screens asked of the host, and a view to show. */
class FakeHost(view: JSONObject?) : DibsHost {
    override val view = MutableStateFlow(view)
    override val link: StateFlow<Link> = MutableStateFlow(Link.CONNECTED)
    override val pickDir = File(System.getProperty("java.io.tmpdir"), "dibs-pick")
    override val uploads: StateFlow<Map<String, Float>> = MutableStateFlow(emptyMap())
    val acts = mutableListOf<Pair<String, JSONObject?>>()

    override fun act(action: String, value: JSONObject?, uid: String?): String {
        acts += action to value
        return uid ?: "uid"
    }

    override fun send(uid: String, text: String, files: List<Uri>, action: String, extra: JSONObject?, onFile: (Uri, String) -> Unit) {}
    override fun channelFile(prefix: String): Flow<File?> = flowOf(null)
    override fun thumb(fileId: String): ImageBitmap? = null
    override val thumbs: StateFlow<Int> = MutableStateFlow(0)
    override fun visible(on: Boolean) {}
    override fun openTether(classic: Boolean) {}
}

/**
 * dibs's screens with a made-up view, on the JVM. PNGs go to dibs/build/outputs/roborazzi/ with
 * `./gradlew :dibs:testDebugUnitTest -Pscreenshots --tests '*ScreensTest*'`; without -Pscreenshots
 * these only check what's on screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreensTest {
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
    }

    private fun show(view: JSONObject) {
        host = FakeHost(view)
        Dibs.host = host
        // Edge to edge, as DibsActivity draws: the screens pad for the bars and the keyboard themselves.
        WindowCompat.setDecorFitsSystemWindows(compose.activity.window, false)
        compose.setContent { AppTheme { DibsApp() } }
        insets(keyboard = 0)
    }

    /** The phone's bars, and the keyboard open [keyboard] px tall (0: closed). */
    private fun insets(keyboard: Int) {
        val bars = Insets.of(0, 63, 0, 63)
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.systemBars(), bars)
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, keyboard))
            .setVisible(WindowInsetsCompat.Type.ime(), keyboard > 0)
            .build()
        compose.runOnUiThread { ViewCompat.dispatchApplyWindowInsets(compose.activity.window.decorView, insets) }
        compose.waitForIdle()
    }

    private fun shot(name: String) = compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")

    @Test
    fun theChatHasItsBoxUnderALongConversation() {
        show(view(talk = longTalk() + ask(300, "Lend the phone for 30 min?", yesNo(300))))
        compose.onNodeWithText("Message dibs").assertIsDisplayed()
        shot("chat-long")
    }

    @Test
    @Config(qualifiers = "w320dp-h568dp-280dpi")
    fun theChatHasItsBoxOnASmallScreen() {
        show(view(talk = longTalk() + ask(300, "Lend the phone for 30 min?", yesNo(300))))
        compose.onNodeWithText("Message dibs").assertIsDisplayed()
        shot("chat-small")
    }

    @Test
    @Config(qualifiers = "w320dp-h568dp-280dpi")
    fun theChatHasItsBoxWithTheKeyboardOpenOnASmallScreen() {
        show(view(talk = longTalk() + ask(300, "Lend the phone for 30 min?", yesNo(300))))
        // A keyboard of 45% of the screen.
        insets(keyboard = (compose.activity.window.decorView.height * 0.45f).toInt())
        compose.onNodeWithText("Message dibs").assertIsDisplayed()
        shot("chat-small-keyboard")
    }

    @Test
    @Config(qualifiers = "w320dp-h568dp-280dpi")
    fun theBoxStaysWhateverTheKeyboardsHeight() {
        show(view(talk = longTalk() + ask(300, "Lend the phone for 30 min?", yesNo(300))))
        shot("chat-small-bars")
        for (k in listOf(0.3f, 0.45f, 0.55f)) {
            insets(keyboard = (compose.activity.window.decorView.height * k).toInt())
            compose.onNodeWithText("Message dibs").assertIsDisplayed()
        }
        shot("chat-small-keyboard-55")
    }

    @Test
    fun theTasksTabSaysWhatItsBadgeCounts() {
        val gb = 1_073_741_824L
        val v = view(talk = longTalk())
        val d = v.getJSONObject("dibs")
        d.getJSONObject("state").put("laptop", JSONObject().put("mem_used", 11 * gb).put("mem_total", 16 * gb).put("load", 6.2)
            .put("cores", 16).put("builds", 2).put("waiting", 11).put("agents", 14))
        d.put("yours", JSONArray()
            .put(yours(1, "Cut phone notification clutter (the user", "working"))
            .put(yours(2, "PLAN ONLY", "done", asked = "PLAN ONLY: plan how dibs spends less usage", unread = true))
            .put(yours(3, "Lend the phone?", "needs")))
        show(v)
        Dibs.tab = "tasks"
        compose.waitForIdle()
        compose.onNodeWithText("2 FOR YOU: 1 TO READ, 1 ASKING YOU").assertIsDisplayed()
        compose.onNodeWithText("Cut phone notification clutter").assertIsDisplayed()
        compose.onNodeWithText("Plan how dibs spends less usage").assertIsDisplayed()
        shot("tasks")
    }

    private fun yours(id: Long, title: String, state: String, asked: String = title, unread: Boolean = false) = JSONObject()
        .put("id", id).put("title", title).put("name", "t$id").put("project", "tether").put("state", state)
        .put("ts", NOW - id * 60).put("started", NOW - 3600).put("asked", asked).put("unread", unread).put("line", "What it did last")

    private fun yesNo(q: Long) = JSONArray()
        .put(JSONObject().put("id", "a$q").put("label", "Approve").put("style", "primary"))
        .put(JSONObject().put("id", "d$q").put("label", "Deny").put("style", ""))
        .put(JSONObject().put("id", "x$q").put("label", "Dismiss").put("style", "plain"))

    private fun ask(q: Long, text: String, actions: JSONArray, reply: Boolean = false) = listOf(
        JSONObject().put("id", "s$q").put("n", q).put("who", "dibs").put("text", text).put("ts", NOW - 60)
            .put("ask", JSONObject().put("q", q).put("actions", actions).apply { if (reply) put("reply", "r$q") }),
    )

    private fun longTalk() = (0 until 30).map { i ->
        val mine = i % 3 == 0
        JSONObject().put("id", if (mine) "u-$i" else "s$i").put("n", i).put("who", if (mine) "user" else "dibs")
            .put("text", if (mine) "Line $i from me" else "dibs's line $i, a little longer so it wraps onto a second line on the phone.")
            .put("ts", NOW - 3600 + i * 100)
    }

    private fun view(talk: List<JSONObject>, questions: List<JSONObject> = emptyList()) = JSONObject().put(
        "dibs",
        JSONObject()
            .put("v", 1).put("now", NOW)
            .put("state", JSONObject().put("brain", "running").put("busy", false).put("limits", JSONObject().put("ts", NOW).put("windows", JSONArray()
                .put(JSONObject().put("name", "five_hour").put("pct", 87.0).put("resets", NOW + 3 * 3600))
                .put(JSONObject().put("name", "seven_day").put("pct", 63.0).put("resets", NOW + 4 * 86400)))))
            .put("talk", JSONArray(talk))
            .put("questions", JSONArray(questions))
            .put("lends", JSONObject()
                .put("phone", JSONObject().put("lent", false).put("text", "").put("action", "phone-lend"))
                .put("laptop", JSONObject().put("lent", true).put("text", "Until 15:40").put("action", "laptop-back")))
            .put("yours", JSONArray())
            .put("badges", JSONObject().put("waiting", questions.size)),
    )

    private companion object {
        val NOW = System.currentTimeMillis() / 1000
    }
}
