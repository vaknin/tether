package com.kivan.tether

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.RemoteInput
import kotlinx.coroutines.launch

/** The chat notification's Reply and Mark as read, and a transfer notification's Cancel. */
class ChatActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val reply = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(Notifier.REPLY_KEY)
            ?.toString()?.takeIf { it.isNotBlank() }
        val pending = goAsync()
        Core.scope.launch {
            try {
                when (intent.action) {
                    REPLY -> reply?.let { send(app, it) }
                    MARK_READ -> {
                        Notifier.clearChat(app)
                        try {
                            Core.acquire(HOLD).markRead()
                        } finally {
                            Core.release(HOLD)
                        }
                    }
                    CANCEL -> intent.getStringExtra(ID)?.let { id ->
                        Core.withNode { it.cancel(id) }
                        Notifier.cancelTransfer(app, id)
                    }
                }
            } catch (e: Exception) {
                Log.w("Tether", "notification action ${intent.action} failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    /** Queues the reply like a chat message and starts a sync to deliver it (as a share does). */
    private suspend fun send(app: Context, text: String) {
        try {
            val node = Core.acquire(HOLD)
            node.sendText(text)
            node.markRead()
            Notifier.replied(app, text)
            // A notification action may start a foreground service; if not, dial while still held.
            if (!SyncService.tryStart(app, "reply")) Core.connect()
        } finally {
            Core.release(HOLD)
        }
    }

    companion object {
        const val REPLY = "com.kivan.tether.REPLY"
        const val MARK_READ = "com.kivan.tether.MARK_READ"
        const val CANCEL = "com.kivan.tether.CANCEL"
        const val ID = "id"
        private const val HOLD = "notification"

        fun intent(context: Context, action: String): Intent =
            Intent(context, ChatActionReceiver::class.java).setAction(action)
    }
}
