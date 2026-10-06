package com.kivan.tether

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.launch

/**
 * Tells the laptop when the user unlocks or uses the phone, so dibs knows they're on it even away
 * from home: live frames on the reserved channel `_presence` (the daemon's `presence.rs`), never
 * queued. Battery (CLAUDE.md "Battery"): an unlock dials at most once a minute ([DEBOUNCE_MS]),
 * unless the screen went off in between (the laptop heard "off", and the link from the last unlock
 * is usually still up then); a `use` renewal dials every [RENEW_MS] only while the screen is on and
 * unlocked; and `off` goes only over a link that is already up. There's no service of its own:
 * [PhoneListener], which the system keeps bound, registers the receiver ([start]/[stop]).
 *
 * Data: `{"op":"present","why":"unlock"|"use","ts":<epoch ms>}` and `{"op":"off","ts":<ms>}`.
 */
object Presence {
    const val CHANNEL = "_presence"
    /** An unlock less than this after the last sent one isn't sent again. */
    const val DEBOUNCE_MS = 60_000L
    /** While the screen stays on and unlocked, a `use` renewal this often. */
    const val RENEW_MS = 5 * 60_000L
    private const val TAG = "Tether"
    private const val HOLD = "presence:"

    fun present(why: String, ts: Long) = """{"op":"present","why":"$why","ts":$ts}"""

    fun off(ts: Long) = """{"op":"off","ts":$ts}"""

    /** When to send, apart from Android (unit-tested). Times are [SystemClock.elapsedRealtime]. */
    class Gate(private val debounceMs: Long = DEBOUNCE_MS, private val renewMs: Long = RENEW_MS) {
        private var lastUnlock: Long? = null
        private var lastPresent: Long? = null

        /** The user unlocked: true when it's to be sent, false within [debounceMs] of the last one sent. */
        fun unlock(now: Long): Boolean {
            if (lastUnlock?.let { now - it < debounceMs } == true) return false
            lastUnlock = now
            lastPresent = now
            return true
        }

        /** A `use` renewal went out. */
        fun renewed(now: Long) {
            lastPresent = now
        }

        /**
         * The screen went off (and the laptop was told, if a link was up): the next unlock is sent
         * whenever it comes, or the laptop would think the user gone while they're on the phone.
         */
        fun screenOff() {
            lastUnlock = null
        }

        /** How long until the next renewal: [renewMs] after the last `present` sent, at once if overdue. */
        fun renewIn(now: Long): Long = lastPresent?.let { (it + renewMs - now).coerceIn(0, renewMs) } ?: renewMs
    }

    private val main by lazy { Handler(Looper.getMainLooper()) }
    /** Main thread only, as are the fields below. */
    private val gate = Gate()
    private var app: Context? = null
    private var receiver: BroadcastReceiver? = null
    private val renew = Runnable { tick() }

    /** Main thread ([PhoneListener.onListenerConnected]). */
    fun start(context: Context) {
        if (receiver != null) return
        val ctx = context.applicationContext
        app = ctx
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) = on(intent.action)
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        // System broadcasts reach a not-exported receiver.
        ctx.registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED)
        receiver = r
        // Already in use (the listener rebound after an update): renew, no unlock to tell.
        if (inUse()) schedule()
    }

    /** Main thread ([PhoneListener.onListenerDisconnected]). */
    fun stop() {
        main.removeCallbacks(renew)
        val r = receiver ?: return
        receiver = null
        runCatching { app?.unregisterReceiver(r) }
    }

    private fun on(action: String?) {
        when (action) {
            Intent.ACTION_USER_PRESENT -> unlocked()
            // No keyguard (none set, or Smart Lock): screen on is the unlock.
            Intent.ACTION_SCREEN_ON -> if (!keyguardLocked()) unlocked()
            Intent.ACTION_SCREEN_OFF -> {
                main.removeCallbacks(renew)
                gate.screenOff()
                val data = off(System.currentTimeMillis())
                if (Core.sendLive { it.sendAppLive(CHANNEL, data) }) Log.i(TAG, "presence: off")
            }
        }
    }

    private fun unlocked() {
        if (gate.unlock(SystemClock.elapsedRealtime())) send("unlock")
        schedule()
    }

    private fun schedule() {
        main.removeCallbacks(renew)
        main.postDelayed(renew, gate.renewIn(SystemClock.elapsedRealtime()))
    }

    private fun tick() {
        // Screen off or locked again: the next unlock starts renewing.
        if (!inUse()) return
        gate.renewed(SystemClock.elapsedRealtime())
        send("use")
        schedule()
    }

    /** Dials for it (live state: dropped when the laptop can't be reached, no retry). */
    private fun send(why: String) {
        if (Core.refused.value) return
        // A node that ran in this process knows whether it's paired; don't start one to find out.
        if (Core.status.value?.let { it.peer == null } == true) return
        val data = present(why, System.currentTimeMillis())
        // Each send its own hold, so one ending doesn't drop another's still dialing.
        val hold = HOLD + System.nanoTime()
        Core.scope.launch {
            try {
                val node = Core.acquire(hold)
                if (node.status().peer == null || !Core.connect()) return@launch
                node.sendAppLive(CHANNEL, data)
                Log.i(TAG, "presence: $why")
                // The screen went off while dialing, and that `off` had no link to go over.
                if (!interactive()) node.sendAppLive(CHANNEL, off(System.currentTimeMillis()))
            } catch (e: Exception) {
                Log.i(TAG, "presence: $why not sent: ${e.message}")
            } finally {
                Core.release(hold)
            }
        }
    }

    private fun interactive() = app?.getSystemService(PowerManager::class.java)?.isInteractive == true

    private fun keyguardLocked() = app?.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked != false

    private fun inUse() = interactive() && !keyguardLocked()
}
