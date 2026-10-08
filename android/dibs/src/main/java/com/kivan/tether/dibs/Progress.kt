package com.kivan.tether.dibs

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

// A running task's progress on its board card (dibs's `progress.rs`, the desktop app's progress.ts):
// dibs sends its stage and times (when it started, when it's likely done, the earliest and latest),
// and the card counts down on the phone's clock, so the view only changes when the estimate moves.

/**
 * [stage]: plan, build, review or ship; [words] "building, step 2 of 5"; [eta], [lo], [hi] null when
 * nothing like it finished yet (the card shows only the words); [guess]: few to go by, or it ran
 * longer than nearly all of them.
 */
data class Progress(
    val stage: String,
    val words: String,
    val from: Long,
    val eta: Long? = null,
    val lo: Long? = null,
    val hi: Long? = null,
    val guess: Boolean = false,
)

/** Minutes, rounded the way a rough estimate is said: 7, 15, 40, 1 h 20 min. */
private fun rough(secs: Long): Long {
    val m = max(1L, (secs / 60.0).roundToLong())
    return when {
        m < 10 -> m
        m < 60 -> (m / 5.0).roundToLong() * 5
        else -> (m / 10.0).roundToLong() * 10
    }
}

/** "done in ~15 min", "done in ~10–30 min" when unsure, "any minute now" once its time came. */
fun leftWords(p: Progress, now: Long): String {
    val eta = p.eta ?: return ""
    val guess = if (p.guess) " (rough guess)" else ""
    if (eta - now < 60) return "any minute now$guess"
    val lo = max(60L, (p.lo ?: eta) - now)
    val hi = max(lo, (p.hi ?: eta) - now)
    val a = rough(lo)
    val b = rough(hi)
    if (hi <= lo * 1.5 || a == b) return "done in ~${duration(rough(eta - now))}$guess"
    return "done in ~${if (b < 60) "$a–$b min" else "${duration(a)} – ${duration(b)}"}$guess"
}

/** The card's line: its stage and the time left. */
fun progressLine(p: Progress, now: Long): String = listOf(p.words, leftWords(p, now)).filter { it.isNotEmpty() }.joinToString(" · ")

/** How full the bar is: [sure] up to the latest finish, [maybe] up to the earliest (the light part). */
data class BarFill(val sure: Float, val maybe: Float)

fun barFill(p: Progress, now: Long): BarFill? {
    val eta = p.eta ?: return null
    val ran = max(0L, now - p.from)
    fun fill(end: Long?): Float {
        val left = max(0L, (end ?: eta) - now)
        return if (ran + left > 0) min(0.98f, ran.toFloat() / (ran + left)) else 0f
    }
    val sure = fill(p.hi)
    return BarFill(sure, max(sure, fill(p.lo)))
}
