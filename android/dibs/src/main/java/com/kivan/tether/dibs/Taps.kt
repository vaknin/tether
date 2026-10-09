package com.kivan.tether.dibs

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** What a tap on a button is doing between the press and dibs's answer. */
enum class TapState {
    /** No tap, or dibs has answered. */
    IDLE,

    /** Pressed and waiting: the button shows a spinner (at least [Taps.MIN_BUSY_MS], so the tap is seen). */
    BUSY,

    /** Pressed, but there is no link to the laptop: the action is queued and goes when the link is back. */
    QUEUED,

    /** The link has been up for [Taps.FAIL_MS] and dibs said nothing. */
    FAILED,
}

/**
 * Taps that show at once (docs/DIBS-APP.md, "Instant feedback"). A button that changes nothing on
 * screen by itself (Stop, Start dibs, a card's Start or Park) presses a key here: it shows a spinner
 * until dibs's next view arrives, and a plain note when dibs stays silent. A tap whose result
 * is shown anyway ([Dibs.answered]: a question answered, a task ticked) also presses here, but only
 * time ends it: if the view still lists the question [FAIL_MS] after the link was up, the app
 * takes the answer back ([Dibs.sweep]) and the note says so.
 */
object Taps {
    /** A spinner shows at least this long, so a quick answer still looks like a tap that registered. */
    const val MIN_BUSY_MS = 600L

    /** The link has been up this long since the tap and dibs didn't answer (the Ask page's wait). */
    const val FAIL_MS = Dibs.ASK_WAIT_MS

    /** A queued tap is told so after this long without a link. */
    const val QUEUED_MS = 2_500L

    /**
     * One pressed button: when, how many views the app had seen then, what it said ([what]), whether
     * only time ends it, and [progress]: the words a note shows while it waits, for a tap with no
     * place of its own to show it (a menu item).
     */
    class Tap(val at: Long, val views: Int, val what: String, val timeOnly: Boolean, val progress: String? = null, val quiet: Boolean = false)

    private val taps = mutableStateMapOf<String, Tap>()

    /** How many views arrived (a tap is answered by a later one). */
    var views by mutableIntStateOf(0)
        private set

    /** Since when the link has been up; null while it is down. Set by the screen. */
    var upSince by mutableStateOf<Long?>(null)

    /** Presses [key] (a button's identity), [what] being the words a note uses ("Stop", "Start dibs"). */
    fun press(key: String, what: String, timeOnly: Boolean = false, progress: String? = null, nowMs: Long = Dibs.now()) {
        taps[key] = Tap(nowMs, views, what, timeOnly, progress)
    }

    fun tap(key: String): Tap? = taps[key]

    /** Some tap is pressed (the clocks run only then). */
    fun any(): Boolean = taps.isNotEmpty()

    /** Answers [Dibs.sweep] took back whose note may still be up: [Dibs.seen] drops the note if dibs drops the question after all. */
    val rolledBack = HashSet<String>()

    fun rolledBack(): List<String> = rolledBack.toList().also { rolledBack.retainAll { k -> taps.containsKey(k) } }

    /**
     * The user dismissed [key]'s note. A time-only tap whose answer is still shown stays (quiet), so [Dibs.sweep]
     * can still take the answer back; any other is forgotten.
     */
    fun dismiss(key: String) {
        val t = taps[key] ?: return
        if (t.timeOnly && key in Dibs.answered) taps[key] = Tap(t.at, t.views, t.what, true, t.progress, quiet = true) else taps.remove(key)
    }

    /** Forgets [key]: its note is dismissed, or the screen it belonged to is gone. */
    fun clear(key: String) {
        taps.remove(key)
        rolledBack.remove(key)
    }

    private var lastSource: Any? = null

    /** A view arrived; [source] (its JSON) counts once: the same view seen again by a recreated screen is no answer. */
    fun viewArrived(source: Any? = null) {
        if (source != null) {
            if (source === lastSource) return
            lastSource = source
        }
        views++
        // A tap a view answered can go; a time-only tap goes when [Dibs.seen] drops its answered key.
        taps.entries.removeAll { (_, t) -> !t.timeOnly && views > t.views && Dibs.now() - t.at >= MIN_BUSY_MS }
    }

    fun state(key: String, nowMs: Long): TapState = judge(taps[key], views, upSince, nowMs)

    /**
     * The taps that need a note, by key: the ones queued without a link, the ones dibs didn't answer,
     * and the busy ones that have [Tap.progress] to say.
     */
    fun notes(nowMs: Long): List<Pair<String, TapState>> =
        taps.entries.mapNotNull { (k, t) ->
            if (t.quiet) null else state(k, nowMs).takeIf { it == TapState.QUEUED || it == TapState.FAILED || (it == TapState.BUSY && t.progress != null) }?.let { k to it }
        }

    /** The words of a note. */
    fun noteText(what: String, state: TapState): String = when (state) {
        TapState.BUSY -> what
        TapState.QUEUED -> "“$what” is waiting: no link to the laptop. It goes when the link is back."
        else -> "dibs didn't answer “$what”. Try again."
    }

    internal fun reset() {
        taps.clear()
        rolledBack.clear()
        lastSource = null
        views = 0
        upSince = null
    }

    /**
     * The state of [tap] with [views] seen so far, the link up since [upSince] (null: down) at [nowMs].
     * A tap shows busy for [MIN_BUSY_MS] whatever happens; then a later view answers it (unless only
     * time ends it); with no link it is queued; with the link up for [FAIL_MS] since the tap, failed.
     */
    fun judge(tap: Tap?, views: Int, upSince: Long?, nowMs: Long, minBusyMs: Long = MIN_BUSY_MS, failMs: Long = FAIL_MS): TapState {
        if (tap == null) return TapState.IDLE
        if (nowMs - tap.at < minBusyMs) return TapState.BUSY
        if (!tap.timeOnly && views > tap.views) return TapState.IDLE
        if (upSince == null) return if (nowMs - tap.at >= QUEUED_MS) TapState.QUEUED else TapState.BUSY
        return if (nowMs - maxOf(tap.at, upSince) >= failMs) TapState.FAILED else TapState.BUSY
    }
}
