package com.kivan.tether

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Progress notifications for file transfers, from [Core.progress]. A transfer gets one only once
 * it has run for [SHOW_AFTER_MS], so small files never flash one; updates come at most once per
 * [TICK_MS].
 */
object Transfers {
    private const val SHOW_AFTER_MS = 2_000L
    private const val TICK_MS = 1_000L

    fun watch(context: Context) {
        val started = mutableMapOf<String, Long>()
        val shown = mutableSetOf<String>()
        Core.scope.launch {
            // A StateFlow is conflated already: while this waits out a tick, only the newest map is kept.
            Core.progress.collect { progress ->
                val now = SystemClock.elapsedRealtime()
                for (id in shown - progress.keys) Notifier.cancelTransfer(context, id)
                shown.retainAll(progress.keys)
                started.keys.retainAll(progress.keys)
                for ((id, p) in progress) {
                    if (now - started.getOrPut(id) { now } < SHOW_AFTER_MS) continue
                    val m = Core.messages.value.firstOrNull { it.id == id }
                    Notifier.transfer(context, id, m?.fileName ?: "file", m?.fromMe, p.first, p.second)
                    shown += id
                }
                delay(TICK_MS)
            }
        }
    }
}
