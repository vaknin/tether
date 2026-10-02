package com.kivan.tether

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.kivan.tether.ui.TetherScreen
import com.kivan.tether.ui.TetherTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Always dark (gruvbox), so the bar icons stay light.
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        if (savedInstanceState == null) route(intent)
        setContent { TetherTheme { TetherScreen() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        route(intent)
    }

    // A notification, shortcut or share names the screen; a plain launch reopens the last one.
    private fun route(intent: Intent) {
        val uri = intent.data?.takeIf { it.scheme == SCHEME } ?: return
        val name = if (uri.host == CHAT) Channels.CHAT else uri.lastPathSegment ?: return
        intent.getStringExtra(Intent.EXTRA_TEXT)?.let { Channels.shared[name] = it }
        Channels.show(name)
    }

    // On screen: keep the link open (the core's StayConnected) and check in with the laptop.
    override fun onStart() {
        super.onStart()
        Channels.foreground(true)
        lifecycleScope.launch {
            val node = Core.acquire(Core.UI)
            if (node.status().peer != null) {
                Core.connect()
                if (Channels.showing(Channels.CHAT)) runCatching { node.markRead() }
            }
        }
    }

    override fun onStop() {
        Channels.foreground(false)
        Core.release(Core.UI)
        super.onStop()
    }

    companion object {
        private const val SCHEME = "tether"
        private const val CHAT = "chat"

        /** Opens the app on a channel, or the chat for [Channels.CHAT]. */
        fun open(context: Context, name: String): Intent {
            val uri = if (name == Channels.CHAT) Uri.parse("$SCHEME://$CHAT") else Uri.parse("$SCHEME://channel/$name")
            return Intent(Intent.ACTION_VIEW, uri, context, MainActivity::class.java)
        }
    }
}
