package com.kivan.tether

import android.app.Notification
import android.content.Context
import android.os.SystemClock
import com.kivan.tether.core.MsgState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * One progress notification for the file transfers in flight, from [Core.progress], also the
 * notification of [TransferService]. Files that overlap form a batch ("Sending 3 files"); the batch
 * ends when nothing is in flight. It shows only once a transfer has run for [SHOW_AFTER_MS], so
 * small files never flash one; updates come at most once per [TICK_MS].
 */
object Transfers {
    private const val SHOW_AFTER_MS = 2_000L
    private const val TICK_MS = 1_000L

    /** One file of the batch; a finished one keeps its place, counted as complete. */
    class Item(val id: String, val name: String, val sending: Boolean?, var done: Long, var total: Long, var finished: Boolean = false)

    private val batch = linkedMapOf<String, Item>()
    private val started = mutableMapOf<String, Long>()
    @Volatile private var shown = false
    @Volatile private var last: Notification? = null

    /** A batch is on screen (and [TransferService] should be running). */
    val active: Boolean get() = shown

    /** The batch's notification as last posted; what [TransferService] starts with. */
    fun notification(context: Context): Notification =
        last ?: Notifier.transferNotification(context, emptyList(), emptySet())

    fun watch(context: Context) {
        Core.scope.launch {
            // A StateFlow is conflated already: while this waits out a tick, only the newest map is kept.
            Core.progress.collect { progress ->
                update(context, progress)
                delay(TICK_MS)
            }
        }
    }

    /** Files I sent to an app channel (dibs), by id: they aren't in the chat's messages, so their names are kept here. */
    private val channelFiles = ConcurrentHashMap<String, String>()

    fun sentToChannel(id: String, name: String) {
        // A file may wait offline for days before it moves; a bound is all the pruning it needs.
        if (channelFiles.size > 200) channelFiles.clear()
        channelFiles[id] = name
    }

    private fun update(context: Context, progress: Map<String, Pair<Long, Long>>) {
        val now = SystemClock.elapsedRealtime()
        if (progress.isEmpty()) {
            if (shown) {
                shown = false
                last = null
                TransferService.stop(context)
                Notifier.cancelTransfer(context)
            }
            batch.clear()
            started.clear()
            return
        }
        val messages = Core.messages.value
        for ((id, p) in progress) {
            started.getOrPut(id) { now }
            val f = batch.getOrPut(id) {
                val m = messages.firstOrNull { it.id == id }
                val channel = channelFiles[id]
                Item(id, m?.fileName ?: channel ?: "file", m?.fromMe ?: if (channel != null) true else null, 0, 0)
            }
            f.done = p.first
            f.total = p.second
        }
        // Gone from the map: finished, unless it was cancelled, which leaves the batch.
        batch.values.removeAll { it.id !in progress && messages.firstOrNull { m -> m.id == it.id }?.state == MsgState.CANCELLED }
        for (f in batch.values) if (f.id !in progress) f.finished = true
        if (!shown && progress.keys.none { now - (started[it] ?: now) >= SHOW_AFTER_MS }) return
        val n = Notifier.transfer(context, batch.values.toList(), progress.keys) ?: return
        last = n
        if (!shown) {
            shown = true
            TransferService.tryStart(context)
        }
    }
}
