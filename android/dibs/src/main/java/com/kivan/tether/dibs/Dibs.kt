package com.kivan.tether.dibs

import android.net.Uri
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.util.UUID

/** Whether the phone can reach the laptop. */
enum class Link { CONNECTED, OFFLINE, UNPAIRED }

/**
 * Everything the dibs screens need from Tether, which holds the link (docs/DIBS-APP.md). The app
 * implements it and sets [Dibs.host] before any dibs screen starts.
 */
interface DibsHost {
    /** The dibs channel's newest view (the whole JSON), or null before one arrived. */
    val view: StateFlow<JSONObject?>
    val link: StateFlow<Link>
    /**
     * Where a picked file is copied at once (a picker's or a share's read grant ends with the
     * screen); [send] takes it from there, and Tether deletes it once the laptop has it.
     */
    val pickDir: java.io.File

    /** Files being sent: Tether's file id → fraction done. */
    val uploads: StateFlow<Map<String, Float>>

    /** Queues an action on the dibs channel (`{"action", "value"}`), with [uid] if given; returns the uid. */
    fun act(action: String, value: JSONObject? = null, uid: String? = null): String

    /**
     * Sends a message with files: copies each, sends it to the dibs channel, then [action] (`say`,
     * or `task-say` with [extra] naming the task) with [uid], the text and the files' ids.
     * [onFile] tells each file's id as it is queued (for its thumbnail).
     */
    fun send(uid: String, text: String, files: List<Uri>, action: String = "say", extra: JSONObject? = null, onFile: (Uri, String) -> Unit)

    /**
     * The newest file dibs sent on its channel whose name starts with [prefix] (a fetched
     * transcript or report), or null; it emits again when a newer one arrives.
     */
    fun channelFile(prefix: String): Flow<java.io.File?>

    /** The thumbnail of an image sent from here, by Tether's file id; null if there is none. Blocking. */
    fun thumb(fileId: String): ImageBitmap?

    /** Bumped whenever a thumbnail is written, so a line that had none yet looks again. */
    val thumbs: StateFlow<Int>

    /**
     * The image itself for the full-screen view, at most [maxPx] on its longest side, when the
     * phone still has it (a file dibs sent); else its thumbnail.
     */
    suspend fun image(fileId: String, maxPx: Int): ImageBitmap? = thumb(fileId)

    /** A dibs screen is on screen (true) or gone: keep the link up and clear dibs's notifications. */
    fun visible(on: Boolean)

    /**
     * Tether's own screen (the Laptop chat and the other channels); [classic]: Tether's own dibs
     * channel screen, for a dibs that sends no payload yet.
     */
    fun openTether(classic: Boolean = false)
}

/**
 * A message sent from here that no view lists yet (its echo), with the files picked for it; [task]
 * when it went to one of the user's tasks instead of dibs.
 */
data class Pending(val uid: String, val text: String, val files: List<Picked>, val tsMs: Long = System.currentTimeMillis(), val task: Long? = null)

/** A file picked to send: its copy in [DibsHost.pickDir], its name, whether it's an image, and where it came from. */
data class Picked(val uri: Uri, val name: String, val image: Boolean, val source: Uri = uri)

/**
 * What a message to dibs is about, from a task's full story ([Page.Story]): [kind] `ask` (Ask dibs
 * about this) or `follow` (Start a follow-up), with the paragraph long-pressed ([quote]). It rides
 * in the `say` as `about`, so dibs gives its brain the story with the user's words; [title] is
 * only for the chip over the box.
 */
data class About(val story: Long, val kind: String, val title: String, val quote: String? = null) {
    fun json(): JSONObject = JSONObject().put("story", story).put("kind", kind).apply { quote?.let { put("quote", it) } }
}

/**
 * A message box: its draft and picked files, kept across screens. The dibs chat has one, and each
 * of the user's tasks has its own ([task]: its messages go to that task's agent, `task-say`).
 */
@Stable
class Composer(val task: Long?) {
    /** The text in the box. */
    var draft by mutableStateOf("")
    /** Files picked for the next message. */
    val picked = mutableStateListOf<Picked>()
    /** What the next message is about (a full story), shown as a chip over the box; the dibs chat's only. */
    var about by mutableStateOf<About?>(null)

    /** Sends the box (text and picked files); it shows as pending until the view lists its uid. */
    fun send() {
        val text = draft.trim()
        val files = picked.toList()
        if (text.isEmpty() && files.isEmpty()) return
        val uid = UUID.randomUUID().toString()
        Dibs.addPending(Pending(uid, text, files, task = task))
        draft = ""
        picked.clear()
        val on = about?.takeIf { task == null }?.json()
        about = null
        val action = if (task == null) "say" else "task-say"
        val extra = {
            if (task == null) JSONObject().apply { on?.let { put("about", it) } } else JSONObject().put("task", task)
        }
        if (files.isEmpty()) {
            Dibs.host.act(action, extra().put("text", text), uid)
        } else {
            Dibs.host.send(uid, text, files.map { it.uri }, action, extra()) { uri, id -> Dibs.sentIds[uri] = id }
        }
    }

    /** Drops a picked file and its copy (✕ in the strip). */
    fun unpick(p: Picked) {
        picked.remove(p)
        p.uri.path?.takeIf { p.uri.scheme == "file" }?.let { java.io.File(it).parentFile?.deleteRecursively() }
    }
}

/** A screen over the tabs (docs/DIBS-APP.md, "Your tasks"): a task's page, its transcript, its report, its full story. */
sealed interface Page {
    data class Task(val id: Long) : Page
    data class Transcript(val id: Long) : Page
    data class Report(val id: Long) : Page
    data class Story(val id: Long) : Page
}

/** The dibs screens' state that outlives a screen: the host, echoes, what's open. */
object Dibs {
    @Volatile lateinit var host: DibsHost

    private val _pending = MutableStateFlow<List<Pending>>(emptyList())
    val pending: StateFlow<List<Pending>> = _pending.asStateFlow()

    /** Sent files' ids by their picked uri, for the thumbnail of an echo once it's in the view. */
    val sentIds = mutableStateMapOf<Uri, String>()

    /** The dibs chat's box. */
    val chat = Composer(null)

    /** Typed answers and the like, by field, kept across tabs. */
    val fields = mutableStateMapOf<String, String>()
    /** Lines (by id) opened to their full text, earlier days unfolded, cards opened. */
    val open = mutableStateMapOf<String, Boolean>()
    /**
     * Questions and lines answered here (`q<id>`, `k<id>`, `w<id>`) that a view may still list: they
     * show as answered at once, until the next view drops them.
     */
    val answered = mutableStateMapOf<String, String>()
    /** Lines hidden here (long-press, Hide) that a view may still list, by line id. */
    val hidden = mutableStateMapOf<String, Boolean>()
    /** A tab asked for by an intent (a notification, a shortcut), taken by the screen. */
    var tab by mutableStateOf<String?>(null)

    /** Each task's newest chat line when its page was last open: a reply since opens the page at the chat. */
    val chatSeen = HashMap<Long, String>()

    /** The pages over the tabs, the top one last; system back pops it. */
    val pages = mutableStateListOf<Page>()

    fun open(page: Page) {
        if (pages.lastOrNull() != page) pages += page
    }

    /** Pops the top page; false when the tabs were showing already. */
    fun back(): Boolean = pages.removeLastOrNull() != null

    /** It's ticked off: by the view, or by a tap here the view hasn't caught up with. */
    fun ticked(t: YourTask): Boolean = if (t.ticked == null) "tick:${t.id}" in answered else "untick:${t.id}" !in answered

    /** Done and not opened yet (an open here counts at once). */
    fun unread(t: YourTask): Boolean = t.unread && "seen:${t.id}" !in answered

    /** Ticks a finished task off (it moves to "Ticked"); only the user does this. */
    fun tick(t: YourTask) {
        answered.remove("untick:${t.id}")
        answered["tick:${t.id}"] = "ticked"
        host.act("tick", JSONObject().put("task", t.id))
    }

    fun untick(t: YourTask) {
        answered.remove("tick:${t.id}")
        answered["untick:${t.id}"] = "unticked"
        host.act("untick", JSONObject().put("task", t.id))
    }

    /** Its page is open: it's read, and its ping goes. */
    fun seenTask(t: YourTask) {
        if (t.unread) answered["seen:${t.id}"] = "seen"
        host.act("seen", JSONObject().put("task", t.id))
    }

    fun stop(task: Long) {
        host.act("stop", JSONObject().put("task", task))
    }

    /** Resumes its chat in a tab on the laptop: by its Recap entry's saved chat, else by the task's number. */
    fun openOnLaptop(view: DibsView, task: Long) {
        val key = view.feed.firstOrNull { it.task == task && it.reopen != null }?.reopen ?: task.toString()
        host.act("reopen", JSONObject().put("reopen", key))
    }

    /**
     * Asks dibs for a task's transcript, report or full story ([what]); it comes as a file on the
     * channel. A story none was written of yet is written first (a few minutes).
     */
    fun fetch(task: Long, what: String) {
        host.act("fetch", JSONObject().put("task", task).put("what", what))
    }

    /** Asks dibs to write a task's full story ([again]: anew, though one is kept); the file follows when done. */
    fun story(task: Long, again: Boolean) {
        host.act("story", JSONObject().put("task", task).put("again", again))
    }

    /**
     * Opens the dibs chat about a task's full story: the chip over the box says so, and the next
     * message carries it ([About]). A follow-up starts its draft with "Follow-up: ", so the line
     * reads right in the chat later.
     */
    fun chatAboutStory(t: YourTask, kind: String, quote: String? = null) {
        chat.draft = chat.draft.removePrefix(FOLLOW_UP)
        if (kind == "follow") chat.draft = FOLLOW_UP + chat.draft
        chat.about = About(t.id, kind, t.label, quote)
        pages.clear()
        tab = DibsActivity.TAB_CHAT
    }

    /** ✕ on the chip: the next message is about nothing in particular (and loses "Follow-up: "). */
    fun dropAbout() {
        if (chat.about?.kind == "follow") chat.draft = chat.draft.removePrefix(FOLLOW_UP)
        chat.about = null
    }

    private const val FOLLOW_UP = "Follow-up: "

    internal fun addPending(p: Pending) {
        _pending.update { it + p }
    }

    /** Drops an echo that will never be listed (nothing could be sent). */
    fun dropPending(uid: String) {
        _pending.update { list -> list.filter { it.uid != uid } }
    }

    /**
     * Forgets the echoes a view now lists (in the dibs chat or a task's), and the answers to what
     * it no longer asks.
     */
    fun seen(view: DibsView?) {
        val ids = view?.talk?.mapTo(HashSet()) { it.id } ?: return
        val listed = HashSet(ids)
        view.yours?.forEach { t -> t.talk.forEach { listed += it.id } }
        _pending.update { list -> list.filter { it.uid !in listed } }
        val asked = HashSet<String>()
        // A tick, untick or open here shows at once, until the view agrees.
        view.yours?.forEach { t ->
            asked += if (t.ticked == null) "tick:${t.id}" else "untick:${t.id}"
            if (t.unread) asked += "seen:${t.id}"
        }
        view.questions.forEach { asked += "q${it.id}" }
        view.talk.forEach { l -> l.ask?.takeIf { it.open }?.let { asked += "q${it.q}" } }
        view.decided.forEach { asked += it.ack }
        // Recap's Undo shows "Undo asked" until dibs lists it without Undo.
        view.recapDecided.forEach { if (it.undo) asked += it.ack }
        view.away?.let { asked += "w${it.id}" }
        answered.keys.retainAll(asked)
        hidden.keys.retainAll(ids)
    }

    /** Hides a line at once; dibs drops it from the next view (`h<n>`). */
    fun hide(line: TalkLine) {
        hidden[line.id] = true
        host.act("h${line.n}")
    }

    /** Answers [key] (`q<id>`, an ack) with [action], shown as [label] until the view drops it. */
    fun answer(key: String, label: String, action: String, value: JSONObject? = null) {
        answered[key] = label
        host.act(action, value)
    }

    fun toggle(key: String) {
        open[key] = open[key] != true
    }
}
