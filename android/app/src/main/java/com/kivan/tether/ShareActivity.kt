package com.kivan.tether

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.core.content.IntentCompat
import androidx.core.content.pm.ShortcutManagerCompat
import com.kivan.tether.dibs.DibsActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Target of the "Laptop" Direct Share tile and the share-sheet entry. Files are copied in
 * ([Outgoing]), queued, and a sync is started. Text without a file becomes a chat message.
 * Text shared to an app channel's tile opens that channel with the text in its compose.
 */
class ShareActivity : Activity() {
    private val scope = MainScope()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uris = sharedUris(intent)
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }
        if (uris.isEmpty() && text == null) return finish()
        val channel = Shortcuts.channelOf(intent.getStringExtra(ShortcutManagerCompat.EXTRA_SHORTCUT_ID))
        // dibs's tile: text and files wait in dibs's box, to be sent from there. The read grant
        // goes along with the URIs (in the ClipData, which carries it).
        if (channel == Channels.DIBS) {
            val open = DibsActivity.intent(this, DibsActivity.TAB_CHAT)
            text?.let { open.putExtra(Intent.EXTRA_TEXT, it) }
            if (uris.isNotEmpty()) {
                open.putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                open.clipData = ClipData.newRawUri(null, uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
                open.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(open)
            return finish()
        }
        // A channel's tile: the text goes into that channel's compose, to be sent from there.
        channel?.let { name ->
            if (text != null) startActivity(MainActivity.open(this, name).putExtra(Intent.EXTRA_TEXT, text))
            return finish()
        }
        scope.launch {
            val msg = try {
                val files = withContext(Dispatchers.IO) { uris.map { Outgoing.copyIn(this@ShareActivity, it) } }
                val node = Core.acquire(HOLD)
                if (node.status().peer == null) {
                    "Pair with the laptop first"
                } else {
                    for (f in files) {
                        val m = node.sendFile(f.path)
                        withContext(Dispatchers.IO) { Thumbs.save(this@ShareActivity, m.id, f) }
                    }
                    if (files.isEmpty()) node.sendText(text!!)
                    SyncWorker.start(this@ShareActivity, "share")
                    Shortcuts.used(this@ShareActivity)
                    if (files.isEmpty()) "Sent to Laptop" else "Sending ${files.size} to Laptop"
                }
            } catch (e: Exception) {
                Log.w("Tether", "share failed", e)
                "Couldn't share: ${e.message}"
            } finally {
                Core.release(HOLD)
            }
            Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun sharedUris(intent: Intent): List<Uri> = when (intent.action) {
        Intent.ACTION_SEND ->
            listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
        Intent.ACTION_SEND_MULTIPLE ->
            IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        else -> emptyList()
    }

    private companion object {
        const val HOLD = "share"
    }
}
