package com.kivan.tether.dibs

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import com.kivan.tether.dibs.ui.DibsApp
import com.kivan.tether.dibs.ui.theme.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * dibs's own app (docs/DIBS-APP.md): its own launcher icon and Recents card. While it is on screen
 * the host keeps the link up and dibs's notifications clear. An intent may name a tab
 * ([EXTRA_TAB]) and carry text and files shared from another app.
 */
class DibsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Always dark (the design language has no light theme), so the bar icons stay light.
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        if (savedInstanceState == null) take(intent)
        setContent { AppTheme { DibsApp() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        take(intent)
    }

    override fun onStart() {
        super.onStart()
        Dibs.host.visible(true)
    }

    override fun onStop() {
        Dibs.host.visible(false)
        super.onStop()
    }

    // A tab to show, and text or files shared to dibs (they wait in the box until sent).
    private fun take(intent: Intent) {
        intent.getStringExtra(EXTRA_TAB)?.let { Dibs.tab = it }
        intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }?.let { text ->
            Dibs.draft = listOf(Dibs.draft, text).filter { it.isNotBlank() }.joinToString("\n")
            Dibs.tab = TAB_CHAT
        }
        val uris = IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            ?: listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
        if (uris.isEmpty()) return
        Dibs.tab = TAB_CHAT
        lifecycleScope.launch {
            val picked = withContext(Dispatchers.IO) { uris.map { picked(this@DibsActivity, it) } }
            for (p in picked) if (Dibs.picked.none { it.uri == p.uri }) Dibs.picked += p
        }
    }

    companion object {
        /** "chat" | "waiting" | "work" | "recap". */
        const val EXTRA_TAB = "com.kivan.tether.dibs.TAB"
        const val TAB_CHAT = "chat"
        const val TAB_WAITING = "waiting"

        /** Opens dibs, on [tab] if given. */
        fun intent(context: Context, tab: String? = null): Intent =
            Intent(Intent.ACTION_VIEW, null, context, DibsActivity::class.java).apply {
                if (tab != null) putExtra(EXTRA_TAB, tab)
            }
    }
}

/** A file to send: its display name, and whether it's an image (by its type). Blocking. */
internal fun picked(context: Context, uri: Uri): Picked {
    val r = context.contentResolver
    val name = runCatching {
        r.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull() ?: uri.lastPathSegment ?: "file"
    val image = runCatching { r.getType(uri) }.getOrNull()?.startsWith("image/") == true
    return Picked(uri, name, image)
}
