package com.kivan.tether

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.lifecycleScope
import com.kivan.tether.ui.TetherScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        setContent {
            val ctx = LocalContext.current
            val scheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
            MaterialTheme(colorScheme = scheme) { TetherScreen() }
        }
    }

    // On screen: keep the link open (the core's StayConnected) and check in with the laptop.
    override fun onStart() {
        super.onStart()
        Core.chatVisible = true
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
