package com.kivan.tether

import android.content.ComponentName
import android.content.Context
import android.database.ContentObserver
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.kivan.tether.core.MediaCmd
import com.kivan.tether.core.MediaState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Mirrors the phone's media session to the laptop (MPRIS there) and applies its commands. Session
 * access comes with the notification listener, so [PhoneListener] owns this. State is sent only
 * on changes: the laptop extrapolates the position itself. While something plays the phone holds
 * the link open ("media"), so the bar's controls answer at once; after a pause the hold lingers
 * for [LINGER_MS] in case playback resumes. Main thread only.
 */
class MediaMirror(
    private val context: Context,
    private val component: ComponentName,
    private val label: (String) -> String,
) {
    private val main = Handler(Looper.getMainLooper())
    private val sessions = context.getSystemService(MediaSessionManager::class.java)
    private var controller: MediaController? = null
    /** What was last sent, minus the clock; a resend of the same state is skipped. */
    private var lastSent: Any? = Unsent
    private var keeper: Job? = null
    private var release: Runnable? = null

    private val changed = MediaSessionManager.OnActiveSessionsChangedListener { choose(it.orEmpty()) }

    private val callback = object : MediaController.Callback() {
        // Another session may now be the playing one.
        override fun onPlaybackStateChanged(state: PlaybackState?) = rechoose()
        override fun onSessionDestroyed() = rechoose()
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onAudioInfoChanged(info: MediaController.PlaybackInfo) = publish()
    }

    // Local stream volume changes don't reach the controller callback, but the system persists
    // them to Settings.System. Other settings land here too; the dedupe in publish() drops those.
    private val volume = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean, uri: Uri?) = publish()
    }
    private var observing = false

    fun start() {
        try {
            sessions.addOnActiveSessionsChangedListener(changed, component, main)
            rechoose()
        } catch (e: SecurityException) {
            Log.w("Tether", "no media session access", e)
        }
    }

    fun stop() {
        runCatching { sessions.removeOnActiveSessionsChangedListener(changed) }
        select(null)
        Core.sendLive { it.sendMedia(null) }
        release?.let(main::removeCallbacks)
        release = null
        keeper?.cancel()
        keeper = null
    }

    /** A new link: it knows nothing yet. */
    fun linkUp() {
        lastSent = Unsent
        publish()
    }

    fun command(cmd: MediaCmd) {
        val c = controller ?: return
        val t = c.transportControls
        when (cmd) {
            MediaCmd.Play -> t.play()
            MediaCmd.Pause -> t.pause()
            MediaCmd.PlayPause -> if (c.playbackState?.state == PlaybackState.STATE_PLAYING) t.pause() else t.play()
            MediaCmd.Next -> t.skipToNext()
            MediaCmd.Previous -> t.skipToPrevious()
            is MediaCmd.Seek -> t.seekTo(cmd.positionMs)
            is MediaCmd.Volume -> c.setVolumeTo(Mirror.volumeIndex(cmd.percent.toInt(), c.playbackInfo.maxVolume), 0)
        }
    }

    private fun rechoose() {
        val list = try {
            sessions.getActiveSessions(component)
        } catch (e: SecurityException) {
            emptyList()
        }
        // KDE Connect mirrors the laptop's players (ours included) as phone sessions; sending
        // those back would loop laptop → phone → laptop.
        choose(list.filter { it.packageName !in MIRRORS })
    }

    private fun choose(list: List<MediaController>) {
        select(list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list.firstOrNull())
        publish()
    }

    private fun select(c: MediaController?) {
        if (c?.sessionToken == controller?.sessionToken) return
        controller?.unregisterCallback(callback)
        controller = c
        c?.registerCallback(callback, main)
        if (c != null && !observing) {
            context.contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, volume)
            observing = true
        } else if (c == null && observing) {
            context.contentResolver.unregisterContentObserver(volume)
            observing = false
        }
    }

    private fun publish() {
        val c = controller
        val ps = c?.playbackState
        val playing = ps?.state == PlaybackState.STATE_PLAYING
        hold(playing)
        if (!Core.connected.value) return
        val state = c?.let { state(it, ps, playing) }
        // The clock fields, not the extrapolated position, tell a seek from time passing.
        val key = state?.copy(positionMs = 0) to ps?.let { Triple(it.position, it.lastPositionUpdateTime, it.playbackSpeed) }
        if (key == lastSent) return
        if (Core.sendLive { it.sendMedia(state) }) lastSent = key
    }

    private fun state(c: MediaController, ps: PlaybackState?, playing: Boolean): MediaState {
        val md = c.metadata
        val duration = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val info = c.playbackInfo
        val vol = if (info.volumeControl == android.media.VolumeProvider.VOLUME_CONTROL_FIXED) null
        else Mirror.volumePercent(info.currentVolume, info.maxVolume)
        val actions = ps?.actions ?: 0L
        return MediaState(
            player = label(c.packageName),
            title = md?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
            artist = (md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)).orEmpty(),
            album = md?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty(),
            playing = playing,
            positionMs = ps?.let {
                Mirror.positionNow(it.position, it.lastPositionUpdateTime, SystemClock.elapsedRealtime(), it.playbackSpeed, playing, duration)
            } ?: 0L,
            durationMs = duration.coerceAtLeast(0),
            volume = vol?.toUByte(),
            canSeek = actions and PlaybackState.ACTION_SEEK_TO != 0L,
            canNext = actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L,
            canPrevious = actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L,
        )
    }

    /** Playing takes the "media" hold at once; anything else lets it go after [LINGER_MS]. */
    private fun hold(playing: Boolean) {
        if (playing) {
            release?.let(main::removeCallbacks)
            release = null
            if (keeper == null) keeper = Core.scope.launch { keepLinked() }
        } else if (keeper != null && release == null) {
            release = Runnable {
                release = null
                keeper?.cancel()
                keeper = null
            }.also { main.postDelayed(it, LINGER_MS) }
        }
    }

    private suspend fun keepLinked() {
        try {
            Core.acquire(Core.MEDIA)
            SyncService.tryStart(context, "media")
            var backoff = RETRY_MIN_MS
            while (true) {
                if (Core.connect()) {
                    backoff = RETRY_MIN_MS
                    Core.connected.first { !it }
                    // The laptop just went away (sleep, restart); one quick redial, then back off.
                    delay(1_000)
                } else {
                    delay(backoff)
                    backoff = (backoff * 2).coerceAtMost(RETRY_MAX_MS)
                }
            }
        } finally {
            Core.release(Core.MEDIA)
        }
    }

    private object Unsent

    private companion object {
        const val LINGER_MS = 5 * 60_000L
        const val RETRY_MIN_MS = 30_000L
        const val RETRY_MAX_MS = 5 * 60_000L
        val MIRRORS = setOf("org.kde.kdeconnect_tp")
    }
}
