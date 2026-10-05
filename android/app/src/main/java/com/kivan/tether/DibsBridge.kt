package com.kivan.tether

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import com.kivan.tether.dibs.DibsHost
import com.kivan.tether.dibs.Link
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * dibs's screens (the `:dibs` module, docs/DIBS-APP.md) over Tether's link: the dibs channel's
 * view, its actions, files sent to the channel, and the link held while the screen is up.
 */
class DibsBridge(context: Context) : DibsHost {
    private val app = context.applicationContext

    override val view: StateFlow<JSONObject?> = Channels.views.map { it[Channels.DIBS] }
        .stateIn(Core.scope, SharingStarted.Eagerly, Channels.views.value[Channels.DIBS])

    override val link: StateFlow<Link> = combine(Core.connected, Core.refused, Core.status) { up, refused, status ->
        when {
            refused || (status != null && status.peer == null) -> Link.UNPAIRED
            up -> Link.CONNECTED
            else -> Link.OFFLINE
        }
    }.stateIn(Core.scope, SharingStarted.Eagerly, Link.OFFLINE)

    override val uploads: StateFlow<Map<String, Float>> = Core.progress
        .map { p -> p.mapValues { (_, v) -> if (v.second > 0) v.first.toFloat() / v.second else 0f } }
        .stateIn(Core.scope, SharingStarted.Eagerly, emptyMap())

    override fun act(action: String, value: JSONObject?, uid: String?): String {
        val obj = JSONObject().put("action", action)
        if (value != null) obj.put("value", value)
        if (uid != null) obj.put("uid", uid)
        return Channels.act(Channels.DIBS, obj)
    }

    /**
     * Each file goes to the dibs channel as a file of its own (resume, checksum and progress as any
     * transfer), then one `say` names them with the text; dibs waits until they've all arrived.
     */
    override fun send(uid: String, text: String, files: List<Uri>, onFile: (Uri, String) -> Unit) {
        Core.scope.launch {
            val hold = "dibs-send:$uid"
            val ids = JSONArray()
            try {
                val node = Core.acquire(hold)
                for (uri in files) {
                    val f = withContext(Dispatchers.IO) { Outgoing.copyIn(app, uri) }
                    val m = node.sendChannelFile(Channels.DIBS, f.path)
                    Transfers.sentToChannel(m.id, f.name)
                    withContext(Dispatchers.IO) { Thumbs.save(app, m.id, f) }
                    withContext(Dispatchers.Main) { onFile(uri, m.id) }
                    ids.put(m.id)
                }
                // Keeps the link up past this screen until the files are through.
                SyncWorker.start(app, "dibs")
            } catch (e: Exception) {
                Log.w("Tether", "sending files to dibs failed", e)
            } finally {
                Core.release(hold)
            }
            // The words go even when a file couldn't (it was gone, say); the files that made it go along.
            if (text.isNotEmpty() || ids.length() > 0) {
                act("say", JSONObject().put("text", text).put("files", ids), uid)
            }
        }
    }

    override fun thumb(fileId: String): ImageBitmap? = Thumbs.byId(app, fileId)

    // Like MainActivity on screen: the link stays open, and what dibs posts is read, not notified.
    override fun visible(on: Boolean) {
        Channels.dibsShowing(on)
        if (on) {
            Core.scope.launch {
                val node = Core.acquire(Core.DIBS_UI)
                if (runCatching { node.status().peer }.getOrNull() != null) Core.connect()
            }
        } else {
            Core.release(Core.DIBS_UI)
        }
    }

    override fun openTether(classic: Boolean) {
        val intent = if (classic) {
            MainActivity.classic(app, Channels.DIBS)
        } else {
            // Tether's own screens: the channel list, not the dibs channel it may have been left on.
            if (Channels.open.value == Channels.DIBS) Channels.show(null)
            Intent(app, MainActivity::class.java)
        }
        app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
