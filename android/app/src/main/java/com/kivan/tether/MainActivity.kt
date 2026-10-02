package com.kivan.tether

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
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
        setContent { TetherTheme { TetherScreen() } }
    }

    // On screen: keep the link open (the core's StayConnected) and check in with the laptop.
    override fun onStart() {
        super.onStart()
        Core.chatVisible = true
        Notifier.clearChat(this)
        lifecycleScope.launch {
            val node = Core.acquire(Core.UI)
            if (node.status().peer != null) {
                Core.connect()
                runCatching { node.markRead() }
            }
        }
    }

    override fun onStop() {
        Core.chatVisible = false
        Core.release(Core.UI)
        super.onStop()
    }
}
