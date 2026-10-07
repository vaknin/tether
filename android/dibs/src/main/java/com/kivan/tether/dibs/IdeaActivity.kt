package com.kivan.tether.dibs

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.kivan.tether.dibs.ideas.Drafts
import com.kivan.tether.dibs.ideas.IdeaRecording
import com.kivan.tether.dibs.ideas.RecorderState
import com.kivan.tether.dibs.ui.IdeaTextBox
import com.kivan.tether.dibs.ui.RecordPanel
import com.kivan.tether.dibs.ui.later
import com.kivan.tether.dibs.ui.rememberRecord
import com.kivan.tether.dibs.ui.theme.AppTheme
import com.kivan.tether.dibs.ui.theme.AppType
import com.kivan.tether.dibs.ui.theme.Eyebrow
import com.kivan.tether.dibs.ui.theme.Palette
import com.kivan.tether.dibs.ui.theme.Space
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * An idea from anywhere (PLAN.md "Capture moves into dibs"): the Quick Settings tile opens a dark
 * sheet asking Voice or Text; the dibs icon's shortcuts go straight to one. Voice is a calm screen
 * (the time, the level, one Stop); Text is a box and Save. Both work over the lock screen, and
 * finishing returns to whatever was showing. With a recording running, any way in shows it.
 */
class IdeaActivity : ComponentActivity() {
    private var mode by mutableStateOf(CHOOSE)

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(MODE, mode)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        Drafts.init(this)
        if (savedInstanceState == null) take(intent) else mode = savedInstanceState.getInt(MODE, CHOOSE)
        setContent {
            AppTheme {
                when (mode) {
                    VOICE -> VoiceScreen(::finish)
                    TEXT -> TextScreen { text ->
                        // Saved on disk at once; the screen stays a moment while it goes to Tether's queue.
                        val saving = later { Drafts.typed(applicationContext, text) }
                        lifecycleScope.launch {
                            withTimeoutOrNull(3_000) { saving.join() }
                            finish()
                        }
                    }
                    else -> ChooseSheet(onVoice = { mode = VOICE }, onText = { mode = TEXT }, onDismiss = ::finish)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        take(intent)
    }

    private fun take(intent: Intent) {
        mode = when {
            IdeaRecording.busy -> VOICE
            intent.action == ACTION_RECORD -> VOICE
            intent.action == ACTION_TYPE -> TEXT
            else -> CHOOSE
        }
    }

    companion object {
        const val ACTION_RECORD = "com.kivan.tether.dibs.IDEA_RECORD"
        const val ACTION_TYPE = "com.kivan.tether.dibs.IDEA_TYPE"
        private const val MODE = "mode"
        private const val CHOOSE = 0
        private const val VOICE = 1
        private const val TEXT = 2

        /** The tile's way in: Voice or Text. */
        fun choose(context: Context): Intent = Intent(context, IdeaActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

/** A dark sheet from the bottom over a dimmed screen: Voice | Text. A tap above it leaves. */
@Composable
private fun ChooseSheet(onVoice: () -> Unit, onText: () -> Unit, onDismiss: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Palette.SurfaceLowest.copy(alpha = 0.6f))
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier.fillMaxWidth()
                .background(Palette.Surface, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .clickable(remember { MutableInteractionSource() }, indication = null) {}
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(start = Space.L, end = Space.L, top = Space.L, bottom = Space.XL),
            verticalArrangement = Arrangement.spacedBy(Space.M),
        ) {
            Eyebrow("New idea")
            Row(horizontalArrangement = Arrangement.spacedBy(Space.M)) {
                ChoiceButton(R.drawable.lucide_mic, "Voice", accent = true, Modifier.weight(1f), onVoice)
                ChoiceButton(R.drawable.lucide_keyboard, "Text", accent = false, Modifier.weight(1f), onText)
            }
        }
    }
}

@Composable
private fun ChoiceButton(icon: Int, label: String, accent: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.height(132.dp).clip(MaterialTheme.shapes.large)
            .background(if (accent) Palette.Accent else Palette.SurfaceHigh)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.S, Alignment.CenterVertically),
    ) {
        val fg = if (accent) Palette.OnAccent else Palette.Text
        Icon(painterResource(icon), null, Modifier.size(36.dp), tint = fg)
        Text(label, style = AppType.heading, color = fg)
    }
}

/**
 * Recording at once (asking for the microphone first if need be): the time, the level, one Stop.
 * Once it's over, its outcome shows for a moment and the screen goes.
 */
@Composable
private fun VoiceScreen(onDone: () -> Unit) {
    // A recording that ended before this screen came is no outcome of this one.
    remember { if (IdeaRecording.state.value is RecorderState.Finished) IdeaRecording.clear() }
    val rec by IdeaRecording.state.collectAsStateWithLifecycle()
    var denied by remember { mutableStateOf(false) }
    val record = rememberRecord(onDenied = { denied = true })
    // Saved: a screen made again (rotated) during "Saved" mustn't start another recording.
    var started by rememberSaveable { mutableStateOf(IdeaRecording.busy) }
    val context = LocalContext.current
    // Back from the app's settings with the microphone allowed: record.
    LifecycleResumeEffect(denied) {
        if (denied && context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            denied = false
            record(null)
        }
        onPauseOrDispose {}
    }
    LaunchedEffect(Unit) {
        if (!started) {
            started = true
            record(null)
        }
    }
    LaunchedEffect(rec) {
        if (rec is RecorderState.Finished) {
            delay(1_500)
            IdeaRecording.clear()
            onDone()
        }
    }
    Column(
        Modifier.fillMaxSize().background(Palette.Bg).windowInsetsPadding(WindowInsets.systemBars).padding(Space.L),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (val r = rec) {
            is RecorderState.Finished -> Text(r.message, style = AppType.heading, color = Palette.Text)
            is RecorderState.Idle -> if (denied) {
                Text("dibs needs the microphone to record an idea.", style = AppType.body, color = Palette.Muted)
                com.kivan.tether.dibs.ui.ActButton("Allow it", "primary", Modifier.padding(top = Space.L)) {
                    denied = false
                    record(null)
                }
            }
            else -> RecordPanel(r, "Recording an idea", big = true)
        }
    }
}

/** A box for the idea, the keyboard up, and Save. Back leaves without one. */
@Composable
private fun TextScreen(onSave: (String) -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Palette.Bg)
            .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.ime))
            .padding(Space.L),
        verticalArrangement = Arrangement.spacedBy(Space.M),
    ) {
        Eyebrow("Type an idea", Modifier.padding(top = Space.S))
        IdeaTextBox("idea:tile", "What's the idea?", minLines = 6, focus = true, saveLabel = "Save", onSave = onSave)
    }
}
