package com.kivan.tether

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.kivan.tether.ui.theme.AppTheme
import com.kivan.tether.ui.theme.AppType
import com.kivan.tether.ui.theme.Eyebrow
import com.kivan.tether.ui.theme.Palette
import com.kivan.tether.ui.theme.Pill
import com.kivan.tether.ui.theme.Space
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** The ring's full-screen intent: shown over the lock screen, one big Stop button. */
class RingActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        // Stopped elsewhere (the laptop, the notification, the timeout): nothing left to show.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                Ringer.ringing.first { !it }
                finish()
            }
        }
        setContent {
            AppTheme {
                Surface(Modifier.fillMaxSize(), color = Palette.Bg) {
                    Column(
                        Modifier.fillMaxSize().padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(32.dp, Alignment.CenterVertically),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.S)) {
                            Eyebrow("Ring", dot = true)
                            Text("Laptop is ringing your phone", style = AppType.title, color = Palette.Text)
                        }
                        Button(
                            onClick = { Ringer.stop(this@RingActivity, fromLaptop = false) },
                            shape = Pill,
                            colors = ButtonDefaults.buttonColors(containerColor = Palette.Danger, contentColor = Palette.OnDanger),
                            modifier = Modifier.size(200.dp),
                        ) { Text("Stop", style = AppType.heroSmall) }
                    }
                }
            }
        }
    }
}
