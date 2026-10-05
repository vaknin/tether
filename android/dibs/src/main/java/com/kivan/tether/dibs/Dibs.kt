package com.kivan.tether.dibs

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
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
     * Sends a message with files: copies each, sends it to the dibs channel, then a `say` with
     * [uid] naming the files. [onFile] tells each file's id as it is queued (for its thumbnail).
     */
    fun send(uid: String, text: String, files: List<Uri>, onFile: (Uri, String) -> Unit)

    /** The thumbnail of an image sent from here, by Tether's file id; null if there is none. Blocking. */
    fun thumb(fileId: String): ImageBitmap?

    /** A dibs screen is on screen (true) or gone: keep the link up and clear dibs's notifications. */
    fun visible(on: Boolean)

    /**
     * Tether's own screen (the Laptop chat and the other channels); [classic]: Tether's own dibs
     * channel screen, for a dibs that sends no payload yet.
     */
    fun openTether(classic: Boolean = false)
}

/** A message sent from here that no view lists yet (its echo), with the files picked for it. */
data class Pending(val uid: String, val text: String, val files: List<Picked>, val tsMs: Long = System.currentTimeMillis())

/** A file picked to send: its copy in [DibsHost.pickDir], its name, whether it's an image, and where it came from. */
data class Picked(val uri: Uri, val name: String, val image: Boolean, val source: Uri = uri)

/** The dibs screens' state that outlives a screen: the host, echoes, what's open. */
object Dibs {
    @Volatile lateinit var host: DibsHost

    private val _pending = MutableStateFlow<List<Pending>>(emptyList())
    val pending: StateFlow<List<Pending>> = _pending.asStateFlow()

    /** Sent files' ids by their picked uri, for the thumbnail of an echo once it's in the view. */
    val sentIds = mutableStateMapOf<Uri, String>()

    /** The text in the box, kept across screens. */
    var draft by mutableStateOf("")
    /** Typed answers, Tell it… texts and the like, by field, kept across tabs. */
    val fields = mutableStateMapOf<String, String>()
    /** Files picked for the next message. */
    val picked = mutableStateListOf<Picked>()
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

    /** Sends the box (text and picked files); it shows as pending until the view lists its uid. */
    fun send() {
        val text = draft.trim()
        val files = picked.toList()
        if (text.isEmpty() && files.isEmpty()) return
        val uid = UUID.randomUUID().toString()
        _pending.update { it + Pending(uid, text, files) }
        draft = ""
        picked.clear()
        if (files.isEmpty()) {
            host.act("say", JSONObject().put("text", text), uid)
        } else {
            host.send(uid, text, files.map { it.uri }) { uri, id -> sentIds[uri] = id }
        }
    }

    /** Drops an echo that will never be listed (nothing could be sent). */
    fun dropPending(uid: String) {
        _pending.update { list -> list.filter { it.uid != uid } }
    }

    /** Forgets the echoes a view now lists, and the answers to what it no longer asks. */
    fun seen(view: DibsView?) {
        val ids = view?.talk?.mapTo(HashSet()) { it.id } ?: return
        _pending.update { list -> list.filter { it.uid !in ids } }
        val asked = HashSet<String>()
        view.questions.forEach { asked += "q${it.id}" }
        view.talk.forEach { l -> l.ask?.takeIf { it.open }?.let { asked += "q${it.q}" } }
        view.decided.forEach { asked += it.ack }
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

    /** Got it to every decision in [decided] at once (`ack-decided`); questions are never among them. */
    fun ackAll(decided: List<Decision>) {
        if (decided.isEmpty()) return
        decided.forEach { answered[it.ack] = "Got it" }
        host.act("ack-decided", JSONObject().put("items", JSONArray(decided.map { it.id })))
    }

    fun toggle(key: String) {
        open[key] = open[key] != true
    }
}
