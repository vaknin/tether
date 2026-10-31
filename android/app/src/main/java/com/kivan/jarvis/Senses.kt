package com.kivan.jarvis

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import com.kivan.jarvis.core.Sensed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * What the phone knows for the two rules of thread #167 (`crates/voice/src/rules.rs`, which decides):
 * the Lark, a headset with a mic, the screen, music, mute. [changed] runs on the main thread whenever
 * one of them does.
 *
 * The conversation screen counts as the screen off: it speaks and takes the call route, as it always
 * has, and its mute stays the crate's, which drops the frames. So does music: the call route pauses it.
 */
class Senses(private val context: Context, private val changed: (Sensed) -> Unit) {
    private val am = context.getSystemService(AudioManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private var last: Sensed? = null
    private var playing = false
    private var music = false
    private var heldUntil = 0L

    /** Our own media track is playing: not his music. */
    @Volatile var ownTrack = false
        set(on) {
            field = on
            main.post(::lookAtMusic)
        }

    fun sensed(): Sensed {
        val open = Voice.conversation.value
        val fake = fake
        return Sensed(
            lark = fake?.lark ?: (lark() != null),
            airpods = fake?.airpods ?: am.availableCommunicationDevices.any(::headset),
            screen = power.isInteractive && !open,
            music = (fake?.music ?: (music || heldUntil > clock())) && !open,
            muted = Voice.ui.value.muted && !open,
        )
    }

    fun start() {
        am.registerAudioDeviceCallback(devices, main)
        am.registerAudioPlaybackCallback(playback, main)
        context.registerReceiver(screen, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }, Context.RECEIVER_NOT_EXPORTED)
        Voice.ui.combine(Voice.conversation) { _, _ -> }.onEach { look() }.launchIn(scope)
        lookAtMusic()
        one = this
    }

    fun stop() {
        one = null
        main.removeCallbacksAndMessages(null)
        scope.cancel()
        am.unregisterAudioDeviceCallback(devices)
        am.unregisterAudioPlaybackCallback(playback)
        runCatching { context.unregisterReceiver(screen) }
    }

    /** The Lark's receiver, as motoparty proved it: a two-channel USB input (`TalkMic.choose` there). */
    fun lark(): AudioDeviceInfo? = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
        .filter { it.type in USB && (it.channelCounts.isEmpty() || 2 in it.channelCounts) }
        .minWithOrNull(compareBy({ USB.indexOf(it.type) }, { it.id }))

    /** He pressed for the music: it counts as playing for [ms], until the app has it going again. */
    fun holdMusic(ms: Long) {
        heldUntil = clock() + ms
        look()
        main.postDelayed(::look, ms + 50)
    }

    private fun clock() = android.os.SystemClock.elapsedRealtime()

    private fun look() {
        val s = sensed()
        if (s == last) return
        last = s
        changed(s)
    }

    private fun headset(d: AudioDeviceInfo) = d.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || d.type == AudioDeviceInfo.TYPE_BLE_HEADSET

    /** On at once, off only after [MUSIC_GAP], so the gap between songs doesn't open the mic. */
    private fun lookAtMusic() {
        val others = am.activePlaybackConfigurations.count { it.audioAttributes.usage == AudioAttributes.USAGE_MEDIA } - if (ownTrack) 1 else 0
        val on = others > 0
        if (on == playing) return
        playing = on
        main.removeCallbacks(musicEnded)
        if (on) {
            music = true
            look()
        } else main.postDelayed(musicEnded, MUSIC_GAP)
    }

    private val musicEnded = Runnable {
        music = false
        look()
    }

    private val playback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) = lookAtMusic()
    }

    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = again()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = again()

        // A headset's communication device can show up a moment after its other profiles.
        private fun again() {
            look()
            main.postDelayed(::look, DEVICE_SETTLE)
        }
    }

    private val screen = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = look()
    }

    /** Tests: stands in for what the emulator hasn't got. Null fields stay real. */
    data class Fake(val lark: Boolean? = null, val airpods: Boolean? = null, val music: Boolean? = null)

    companion object {
        private const val MUSIC_GAP = 1000L
        private const val DEVICE_SETTLE = 500L
        private val USB = listOf(AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY)

        @Volatile var fake: Fake? = null
            set(f) {
                field = f
                one?.let { s -> s.main.post(s::look) }
            }

        @Volatile private var one: Senses? = null
    }
}
