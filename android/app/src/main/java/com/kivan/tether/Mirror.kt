package com.kivan.tether

/**
 * The plain arithmetic and text rules behind the notification and media mirrors, kept free of
 * Android types so the JVM unit tests can cover them.
 */
object Mirror {
    /**
     * A notification's body. A MessagingStyle stack (Google Messages groups SMS this way) puts each
     * message in EXTRA_MESSAGES and only a summary in EXTRA_TEXT, so the messages win; they come
     * oldest first, which keeps the newest last.
     */
    fun notifText(messages: List<CharSequence?>?, bigText: CharSequence?, text: CharSequence?): String {
        val joined = messages?.mapNotNull { it?.toString()?.takeIf(String::isNotBlank) }?.joinToString("\n")
        if (!joined.isNullOrEmpty()) return joined
        return (bigText?.takeIf { it.isNotBlank() } ?: text)?.toString().orEmpty()
    }

    /** Session volume as 0–100; null when it can't be read (no range). */
    fun volumePercent(current: Int, max: Int): Int? =
        if (max <= 0) null else (current.coerceIn(0, max) * 100 / max)

    /** The laptop's 0–100 back to a volume step, rounded so 100 is always the top step. */
    fun volumeIndex(percent: Int, max: Int): Int =
        ((percent.coerceIn(0, 100) * max + 50) / 100).coerceIn(0, max)

    /**
     * Position now, from the session's last report. The laptop extrapolates from what it is sent,
     * so this must be the position at send time, not at the session's last update.
     */
    fun positionNow(position: Long, updatedAt: Long, now: Long, speed: Float, playing: Boolean, duration: Long): Long {
        if (position < 0) return 0
        val p = if (playing && updatedAt > 0) position + ((now - updatedAt) * speed).toLong() else position
        return if (duration > 0) p.coerceIn(0, duration) else p.coerceAtLeast(0)
    }
}
