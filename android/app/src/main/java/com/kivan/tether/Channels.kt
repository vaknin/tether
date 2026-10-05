package com.kivan.tether

import android.content.Context
import android.util.Log
import androidx.compose.runtime.mutableStateMapOf
import androidx.core.content.edit
import com.kivan.tether.core.AppHistoryItem
import com.kivan.tether.core.Event
import com.kivan.tether.core.TetherNode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

private const val TAG = "Tether"

/** One entry of the daemon's `_channels` view (docs/PLAN.md, "App channels"). */
data class ChannelInfo(
    val name: String,
    val title: String,
    /** One emoji or letter, drawn when there is no [icon]. */
    val glyph: String,
    /** ARGB, or null for the theme's accent. */
    val accent: Int?,
    val dir: Dir,
    /** `kind = "thread"`; `app` and `list` are both view-driven. */
    val thread: Boolean,
    val share: Boolean,
    val notify: Boolean,
    /** The white mark on [tile] (a Lucide icon), from the manifest's `icon`. */
    val icon: ChannelMark? = null,
    /** ARGB of text and icons on [accent]; from `hue`. */
    val onAccent: Int? = null,
    /** ARGB of the icon's background; from `hue` (docs/DESIGN.md). */
    val tile: Int? = null,
)

/** A channel's `dir`: `auto` keeps the chrome LTR and gives each text its own direction. */
enum class Dir { LTR, RTL, AUTO }

/** A text's direction from its first strong character (none: LTR), as `dir = "auto"` uses it. */
fun textRtl(s: String): Boolean {
    var i = 0
    while (i < s.length) {
        val cp = s.codePointAt(i)
        when (Character.getDirectionality(cp)) {
            Character.DIRECTIONALITY_RIGHT_TO_LEFT,
            Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC,
            Character.DIRECTIONALITY_RIGHT_TO_LEFT_EMBEDDING,
            Character.DIRECTIONALITY_RIGHT_TO_LEFT_OVERRIDE -> return true
            Character.DIRECTIONALITY_LEFT_TO_RIGHT -> return false
        }
        i += Character.charCount(cp)
    }
    return false
}

/**
 * A compose block's text that was sent but isn't in a view yet (the ⏳ echo). A list item's reply
 * also has [item]: its echo also goes once a view no longer lists that item (it was answered).
 */
data class Pending(val uid: String, val text: String, val item: String? = null, val tsMs: Long = System.currentTimeMillis())

/** A list item swiped away (its `dismiss`): hidden at once, until a view no longer lists it. */
data class Dismissed(val channel: String, val block: String, val item: String)

/**
 * The swiped-away items still to hide: those whose channel's view still lists them. [ids] gives a
 * channel's item ids by list id, or null while it has no view.
 */
internal fun keepDismissed(gone: Set<Dismissed>, ids: (String) -> Map<String, Set<String>>?): Set<Dismissed> =
    gone.filterTo(mutableSetOf()) { d ->
        val lists = ids(d.channel) ?: return@filterTo true
        lists[d.block]?.contains(d.item) == true
    }

/**
 * App channels on the phone: the channel list (`_channels`), each channel's view (with live
 * patches applied) or thread, and which screen is open. Views come from the laptop; what the
 * phone sends back are queued actions. The screens draw from here ([ui.ChannelScreen]).
 */
object Channels {
    const val LIST = "_channels"
    /** The chat's entry in [open]; channel names are `[a-z0-9_-]+`, so it can't clash. */
    const val CHAT = "@chat"
    private const val HISTORY = 200u
    private const val PREFS = "channels"
    private const val LAST = "last"

    private lateinit var app: Context

    private val _list = MutableStateFlow<List<ChannelInfo>>(emptyList())
    val list: StateFlow<List<ChannelInfo>> = _list.asStateFlow()
    private val _views = MutableStateFlow<Map<String, JSONObject>>(emptyMap())
    val views: StateFlow<Map<String, JSONObject>> = _views.asStateFlow()
    private val _threads = MutableStateFlow<Map<String, List<AppHistoryItem>>>(emptyMap())
    val threads: StateFlow<Map<String, List<AppHistoryItem>>> = _threads.asStateFlow()
    /** "<channel>/<compose id>" (a reply: "<channel>/<list id>/<item id>") → sent texts no view lists yet. */
    private val _pending = MutableStateFlow<Map<String, List<Pending>>>(emptyMap())
    val pending: StateFlow<Map<String, List<Pending>>> = _pending.asStateFlow()

    /** Typed text by "<channel>/<block>/<field>", kept across view reloads and screen changes. */
    val drafts = mutableStateMapOf<String, String>()
    /** Chosen chips by "<channel>/<compose id>". */
    val chips = mutableStateMapOf<String, List<String>>()
    /** List items whose `details` are open, by "<channel>/<item id>"; kept across view reloads. */
    val expanded = mutableStateMapOf<String, Boolean>()
    /** List items swiped away that a view still lists (the app hasn't taken the swipe yet). */
    private val _dismissed = MutableStateFlow<Set<Dismissed>>(emptySet())
    val dismissed: StateFlow<Set<Dismissed>> = _dismissed.asStateFlow()

    /** The open screen: null is the list, [CHAT] the chat, else a channel name. */
    private val _open = MutableStateFlow<String?>(null)
    val open: StateFlow<String?> = _open.asStateFlow()
    /** MainActivity is started. */
    @Volatile private var foreground = false

    fun init(context: Context) {
        app = context.applicationContext
        _open.value = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(LAST, null)
    }

    /** Opens a screen (null: the list); the app reopens it next time. */
    fun show(name: String?) {
        _open.value = name
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(LAST, name) }
        changed()
    }

    fun foreground(on: Boolean) {
        foreground = on
        changed()
    }

    fun showing(name: String) = foreground && _open.value == name

    // What is on screen is read: the chat's messages, a channel's notification.
    private fun changed() {
        Core.chatVisible = showing(CHAT)
        val name = _open.value ?: return
        if (!foreground) return
        if (name == CHAT) {
            Notifier.clearChat(app)
            Core.scope.launch { Core.withNode { runCatching { it.markRead() } } }
        } else {
            Notifier.clearApp(app, name)
        }
    }

    fun info(name: String): ChannelInfo? = _list.value.firstOrNull { it.name == name }

    /** Re-reads everything from a node that just started. Called on a Rust runtime thread. */
    fun load(n: TetherNode) {
        runCatching {
            val list = parseList(n.appView(LIST))
            setList(list)
            val views = mutableMapOf<String, JSONObject>()
            val threads = mutableMapOf<String, List<AppHistoryItem>>()
            for (c in list) {
                if (c.thread) threads[c.name] = n.appHistory(c.name, HISTORY)
                else n.appView(c.name)?.let(::parse)?.let { views[c.name] = it }
                // Posts that arrived while nothing showed them.
                for (item in n.appPending(c.name)) {
                    post(c, item.data)
                    n.appDone(item.id)
                }
            }
            _views.value = views
            _threads.value = threads
            prunePending()
            pruneDismissed()
        }.onFailure { Log.w(TAG, "loading channels failed", it) }
    }

    /** Called on a Rust runtime thread, in order. */
    fun onEvent(n: TetherNode, e: Event.App) {
        runCatching {
            when {
                e.channel == LIST -> if (e.view) load(n)
                e.view -> {
                    val v = n.appView(e.channel)?.let(::parse) ?: return
                    _views.value = _views.value + (e.channel to v)
                    prunePending()
                    pruneDismissed()
                    val c = info(e.channel) ?: return
                    // `open_tags` lists the tagged posts still current; the others were answered (maybe
                    // on the laptop). It wins over the badge: a channel with nothing waiting can still
                    // have news showing (a reply, a card). Without it: nothing waiting, all go.
                    val open = v.optJSONArray("open_tags")
                    val nothingWaiting = v.optInt("badge", -1) == 0
                    when {
                        open != null -> {
                            Notifier.keepApp(app, e.channel, (0 until open.length()).map { open.optString(it) }.toSet())
                            if (nothingWaiting) Notifier.cancelUntagged(app, e.channel)
                        }
                        nothingWaiting -> Notifier.clearApp(app, e.channel)
                    }
                    val note = v.optJSONObject("notify")
                    if (c.notify && note != null && !showing(c.name)) {
                        Notifier.app(app, c, note.optString("title", c.title), note.optString("text"))
                    }
                }
                e.id == null -> patch(e.channel, e.data)
                else -> {
                    val c = info(e.channel) ?: ChannelInfo(e.channel, e.channel, e.channel.take(1), null, Dir.AUTO, true, false, true)
                    if (c.thread) _threads.value = _threads.value + (c.name to n.appHistory(c.name, HISTORY))
                    post(c, e.data)
                    n.appDone(e.id)
                }
            }
        }.onFailure { Log.w(TAG, "channel event failed", it) }
    }

    /**
     * A thread post (or any queued item from the laptop) is a notification unless it is on screen:
     * titled by its `title` (else the channel's), and its own notification when it has a `tag`.
     */
    private fun post(c: ChannelInfo, data: String) {
        if (!c.notify || showing(c.name)) return
        val d = parse(data) ?: return
        val p = d.optJSONObject("post")
        val text = p?.optString("text") ?: d.optString("text")
        val title = p?.optString("title").orEmpty().ifEmpty { c.title }
        val tag = p?.optString("tag")?.takeIf { it.isNotEmpty() }
        val actions = p?.optJSONArray("actions")?.let { a ->
            (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { it.optString("id") to it.optString("label") }
        }.orEmpty()
        if (text.isNotBlank()) Notifier.app(app, c, title, text, actions, tag)
    }

    // A live `{"patch":{"<block id>":{…}}}` replaces those fields until the next view.
    private fun patch(channel: String, data: String) {
        val p = parse(data)?.optJSONObject("patch") ?: return
        val v = _views.value[channel] ?: return
        val copy = JSONObject(v.toString())
        val blocks = copy.optJSONArray("blocks") ?: return
        for (i in 0 until blocks.length()) {
            val b = blocks.optJSONObject(i) ?: continue
            val fields = p.optJSONObject(b.optString("id")) ?: continue
            for (k in fields.keys()) b.put(k, fields.get(k))
        }
        _views.value = _views.value + (channel to copy)
    }

    private fun setList(list: List<ChannelInfo>) {
        if (list == _list.value) return
        _list.value = list
        Notifier.appChannels(app, list)
        Shortcuts.channels(app, list)
    }

    /**
     * Queues an action for the channel's app, with `from`, `uid` and `ts` filled in; returns the uid.
     * A thread's reply is the same item without `action`.
     */
    fun act(name: String, obj: JSONObject): String {
        val uid = obj.optString("uid").ifEmpty { UUID.randomUUID().toString() }
        obj.put("from", "phone").put("uid", uid).put("ts", System.currentTimeMillis())
        val data = obj.toString()
        Core.scope.launch {
            val hold = "act:$uid"
            try {
                val n = Core.acquire(hold)
                n.sendApp(name, data, false)
                if (info(name)?.thread != false) _threads.value = _threads.value + (name to n.appHistory(name, HISTORY))
            } catch (e: Exception) {
                Log.w(TAG, "action on $name failed", e)
            } finally {
                Core.release(hold)
            }
        }
        Shortcuts.usedChannel(app, name)
        return uid
    }

    /** Sends a compose block's text and shows it as pending until a view lists its uid. */
    fun compose(name: String, block: String, text: String) {
        // A thread's reply is plain text; it is in the thread (from me) as soon as it is queued.
        if (info(name)?.thread == true) {
            act(name, JSONObject().put("text", text))
            return
        }
        val k = "$name/$block"
        val uid = act(name, JSONObject().put("action", block).put("value", JSONObject().put("text", text).put("chips", JSONArray(chips[k].orEmpty()))))
        chips.remove(k)
        _pending.value = _pending.value + (k to (_pending.value[k].orEmpty() + Pending(uid, text)))
    }

    /**
     * Answers a list item's `reply` box with `{"action":<reply id>,"value":{"item","text"}}`; the
     * text shows as pending under the item until a view lists the uid or drops the item.
     */
    fun reply(name: String, block: String, item: String, action: String, text: String) {
        val k = "$name/$block/$item"
        val uid = act(name, JSONObject().put("action", action).put("value", JSONObject().put("item", item).put("text", text)))
        _pending.value = _pending.value + (k to (_pending.value[k].orEmpty() + Pending(uid, text, item)))
    }

    /**
     * A list item's `dismiss` (swiped away): hidden at once, and `{"action":<dismiss id>,"value":{"item"}}`
     * goes to the app, as an item action's tap does.
     */
    fun dismiss(name: String, block: String, item: String, action: String) {
        _dismissed.update { it + Dismissed(name, block, item) }
        act(name, JSONObject().put("action", action).put("value", JSONObject().put("item", item)))
    }

    /** Forgets the swiped-away items their channel's view no longer lists (the app took the swipe). */
    private fun pruneDismissed() {
        _dismissed.update { gone -> if (gone.isEmpty()) gone else keepDismissed(gone) { listIds(_views.value[it]) } }
    }

    /** A view's item ids by list id; null without a view. */
    private fun listIds(view: JSONObject?): Map<String, Set<String>>? {
        val blocks = view?.optJSONArray("blocks") ?: return null
        val out = mutableMapOf<String, Set<String>>()
        for (i in 0 until blocks.length()) {
            val b = blocks.optJSONObject(i) ?: continue
            val items = b.optJSONArray("items") ?: continue
            out[b.optString("id")] = (0 until items.length()).mapNotNull { items.optJSONObject(it)?.optString("id") }.toSet()
        }
        return out
    }

    /** Drops each echo whose uid a view now lists, and each reply whose item the view dropped. */
    private fun prunePending() {
        val now = _pending.value
        if (now.isEmpty()) return
        val left = now.mapValues { (k, list) ->
            val view = _views.value[k.substringBefore('/')]
            val ids = itemIds(view)
            list.filter { it.uid !in ids && (it.item == null || view == null || it.item in ids) }
        }.filterValues { it.isNotEmpty() }
        if (left != now) _pending.value = left
    }

    private fun itemIds(view: JSONObject?): Set<String> {
        val ids = mutableSetOf<String>()
        val blocks = view?.optJSONArray("blocks") ?: return ids
        for (i in 0 until blocks.length()) {
            val items = blocks.optJSONObject(i)?.optJSONArray("items") ?: continue
            for (j in 0 until items.length()) items.optJSONObject(j)?.optString("id")?.let { ids += it }
        }
        return ids
    }

    /** Text shared into a channel (Direct Share), for its first compose block to take. */
    val shared = mutableStateMapOf<String, String>()

    private fun parse(s: String): JSONObject? = runCatching { JSONObject(s) }.getOrNull()

    private fun parseList(s: String?): List<ChannelInfo> {
        val arr = s?.let(::parse)?.optJSONArray("channels") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val name = o.optString("name").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            ChannelInfo(
                name = name,
                title = o.optString("title", name),
                glyph = o.optString("glyph").ifEmpty { name.take(1) },
                accent = argb(o.optString("accent")),
                dir = when (o.optString("dir")) {
                    "ltr" -> Dir.LTR
                    "rtl" -> Dir.RTL
                    else -> Dir.AUTO
                },
                thread = o.optString("kind") == "thread",
                share = o.optBoolean("share"),
                notify = o.optBoolean("notify", true),
                icon = ChannelMark.parse(o.optJSONObject("icon")),
                onAccent = argb(o.optString("on_accent")),
                tile = argb(o.optString("tile")),
            )
        }
    }

    private fun argb(hex: String): Int? =
        hex.takeIf { it.matches(Regex("#[0-9a-fA-F]{6}")) }?.let { (0xFF000000 or it.substring(1).toLong(16)).toInt() }
}
