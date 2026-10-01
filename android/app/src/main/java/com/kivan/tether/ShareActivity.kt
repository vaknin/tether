package com.kivan.tether

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.core.content.IntentCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Target of the "Laptop" Direct Share tile and the share-sheet entry. Files are copied in
 * ([Outgoing]), queued, and a sync is started. Text without a file becomes a chat message.
 */
class ShareActivity : Activity() {
    private val scope = MainScope()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uris = sharedUris(intent)
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }
        if (uris.isEmpty() && text == null) return finish()
        scope.launch {
            val msg = try {
                val files = withContext(Dispatchers.IO) { uris.map { Outgoing.copyIn(this@ShareActivity, it) } }
                val node = Core.acquire(HOLD)
                if (node.status().peer == null) {
                    "Pair with the laptop first"
                } else {
                    for (f in files) node.sendFile(f.path)
                    if (files.isEmpty()) node.sendText(text!!)
                    SyncService.start(this@ShareActivity, "share")
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
