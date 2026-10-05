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

    /** Tether's own screen (the Laptop chat and the other channels). */
    fun openTether()
}

/** A message sent from here that no view lists yet (its echo), with the files picked for it. */
data class Pending(val uid: String, val text: String, val files: List<Picked>, val tsMs: Long = System.currentTimeMillis())

/** A file picked to send: where it is, its name, whether it's an image. */
data class Picked(val uri: Uri, val name: String, val image: Boolean)

/** The dibs screens' state that outlives a screen: the host, echoes, what's open. */
object Dibs {
    @Volatile lateinit var host: DibsHost

    private val _pending = MutableStateFlow<List<Pending>>(emptyList())
    val pending: StateFlow<List<Pending>> = _pending.asStateFlow()

    /** Sent files' ids by their picked uri, for the thumbnail of an echo once it's in the view. */
    val sentIds = mutableStateMapOf<Uri, String>()

    /** The text in the box, kept across screens. */
    var draft by mutableStateOf("")
    /** Files picked for the next message. */
    val picked = mutableStateListOf<Picked>()
    /** Lines (by id) opened to their full text, earlier days unfolded, cards opened. */
    val open = mutableStateMapOf<String, Boolean>()

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

    /** Forgets the echoes a view now lists. */
    fun seen(view: DibsView?) {
        val ids = view?.talk?.mapTo(HashSet()) { it.id } ?: return
        _pending.update { list -> list.filter { it.uid !in ids } }
    }

    fun toggle(key: String) {
        open[key] = open[key] != true
    }
}
