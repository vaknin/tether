package com.kivan.tether

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import com.kivan.tether.core.ChatMessage
import com.kivan.tether.core.Event
import com.kivan.tether.core.EventListener
import com.kivan.tether.core.MsgKind
import com.kivan.tether.core.MsgState
import com.kivan.tether.core.Status
import com.kivan.tether.core.TetherNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

private const val TAG = "Tether"
private const val HISTORY = 500u

/**
 * Owns the Rust node. The phone holds no connection while idle (CLAUDE.md "Battery"): the node
 * runs only while something holds it (the app on screen, media playing, a wake or share sync, an
 * outbox retry) or a link is still open. Once nothing holds it and the link has closed (the core closes it after
 * 60 s without traffic), the endpoint is shut down.
 */
object Core {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var app: Context
    private val lock = Mutex()
    @Volatile private var node: TetherNode? = null
    private val holds = mutableSetOf<String>()
    private val _held = MutableStateFlow<Set<String>>(emptySet())
    /** The current holds; SyncService stays up while "media" is among them. */
    val held: StateFlow<Set<String>> = _held.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()
    private val _status = MutableStateFlow<Status?>(null)
    val status: StateFlow<Status?> = _status.asStateFlow()
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()
    /** File transfers in flight: message id → (done, total). */
    private val _progress = MutableStateFlow<Map<String, Pair<Long, Long>>>(emptyMap())
    val progress: StateFlow<Map<String, Pair<Long, Long>>> = _progress.asStateFlow()

    /** The chat is on screen: incoming messages are read, not notified. */
    @Volatile var chatVisible = false

    fun init(context: Context) {
        app = context.applicationContext
        // While the process lives, a new network is a chance to reach the laptop (check-in).
        app.getSystemService(ConnectivityManager::class.java)
            .registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    scope.launch { if (lock.withLock { holds.isNotEmpty() }) connect() }
                }
            })
    }

    val outgoingDir: File get() = File(app.filesDir, "outgoing")

    /** Starts the node if needed and keeps it running until [release] with the same [reason]. */
    suspend fun acquire(reason: String): TetherNode = lock.withLock {
        holds += reason
        _held.value = holds.toSet()
        val n = node ?: startLocked()
        n.setStay(stayLocked())
        n
    }

    fun release(reason: String) {
        scope.launch {
            lock.withLock {
                if (!holds.remove(reason)) return@withLock
                _held.value = holds.toSet()
                node?.setStay(stayLocked())
            }
            // A hold is often handed on (a share to SyncService); don't restart the endpoint in between.
            delay(STOP_GRACE_MS)
            lock.withLock {
                val n = node ?: return@withLock
                if (holds.isEmpty() && !n.isConnected()) stopLocked()
            }
        }
    }

    // The link stays open past the idle timeout only while it is cheap or needed (CLAUDE.md "Battery").
    private fun stayLocked() = UI in holds || MEDIA in holds

    /** Dials the laptop unless connected; false when it couldn't be reached. */
    suspend fun connect(): Boolean {
        val n = lock.withLock { node } ?: return false
        return try {
            n.connect()
            true
        } catch (e: Exception) {
            Log.i(TAG, "connect failed: ${e.message}")
            false
        }
    }

    /**
     * Sends a live frame (media, notifications, stop ring) if a node is running. Those never block,
     * and skipping the lock keeps them in the caller's order; a node closed meanwhile just drops it.
     */
    fun sendLive(send: (TetherNode) -> Boolean): Boolean =
        node?.let { runCatching { send(it) }.getOrDefault(false) } ?: false

    suspend fun <T> withNode(block: suspend (TetherNode) -> T): T? = lock.withLock { node }?.let { block(it) }

    private suspend fun startLocked(): TetherNode {
        val n = com.kivan.tether.core.start(
            File(app.filesDir, "state").path,
            File(app.filesDir, "incoming").path,
            "Pixel 8",
        )
        n.setListener(object : EventListener {
            override fun onEvent(event: Event) = handle(n, event)
        })
        node = n
        refresh(n)
        Log.i(TAG, "node started")
        return n
    }

    private suspend fun stopLocked() {
        val n = node ?: return
        node = null
        val queued = runCatching { n.status().queued }.getOrDefault(0uL)
        n.shutdown()
        n.close()
        _connected.value = false
        Log.i(TAG, "node stopped, $queued queued")
        if (queued > 0uL) OutboxWorker.schedule(app)
    }

    private fun refresh(n: TetherNode) {
        runCatching {
            _status.value = n.status()
            _messages.value = n.recent(HISTORY)
        }.onFailure { Log.w(TAG, "refresh failed", it) }
    }

    /** Called on a Rust runtime thread. */
    private fun handle(n: TetherNode, event: Event) {
        when (event) {
            is Event.Connected -> {
                _connected.value = true
                Push.sendToken(n)
                // Live state is dropped while offline, so each new link starts with a fresh snapshot.
                PhoneListener.linkUp()
            }
            is Event.Disconnected -> {
                _connected.value = false
                scope.launch { lock.withLock { if (node === n && holds.isEmpty()) stopLocked() } }
            }
            is Event.Message -> onMessage(event.msg)
            is Event.Progress -> {
                val p = _progress.value.toMutableMap()
                if (event.done >= event.total) p.remove(event.id)
                else p[event.id] = event.done.toLong() to event.total.toLong()
                _progress.value = p
                return
            }
            is Event.Paired, is Event.Unpaired -> {}
            is Event.MediaCmd -> {
                PhoneListener.command(event.cmd)
                return
            }
            is Event.StopRing -> {
                Ringer.stop(app, fromLaptop = true)
                return
            }
        }
        refresh(n)
    }

    private fun onMessage(m: ChatMessage) {
        if (m.fromMe) {
            // The copy made for sending is no longer needed once the laptop has it.
            if (m.state == MsgState.DELIVERED || m.state == MsgState.EXPIRED) {
                m.path?.let { File(it) }?.takeIf { it.startsWith(outgoingDir) }?.let {
                    it.delete()
                    it.parentFile?.delete()
                }
            }
            return
        }
        if (m.state != MsgState.RECEIVED) return
        when (m.kind) {
            MsgKind.FILE -> scope.launch { Downloads.publish(app, m) }
            MsgKind.TEXT, MsgKind.PING ->
                if (chatVisible) scope.launch { withNode { it.markRead() } } else Notifier.message(app, m)
            // A ring is meant to be heard, chat open or not.
            MsgKind.RING -> {
                Ringer.start(app)
                if (chatVisible) scope.launch { withNode { it.markRead() } }
            }
        }
    }

    const val UI = "ui"
    const val MEDIA = "media"
    private const val STOP_GRACE_MS = 2_000L
}
