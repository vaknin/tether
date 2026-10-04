package com.kivan.tether

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.RemoteInput
import kotlinx.coroutines.launch

/**
 * The chat notification's Reply, Mark as read and Copy, the transfer notification's Cancel, the
 * stuck notice's Retry now and a channel notification's buttons.
 */
class ChatActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        if (intent.action == RETRY) {
            Notifier.cancelStuck(app)
            SyncWorker.start(app, "retry")
            return
        }
        if (intent.action == COPY) {
            // Android 13+ confirms the copy itself. The notification stays, so a reply can follow.
            intent.getStringExtra(TEXT)?.let {
                app.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("message", it))
            }
            return
        }
        if (intent.action == APP_ACTION) {
            val ch = intent.getStringExtra(CHANNEL) ?: return
            val id = intent.getStringExtra(ACTION_ID) ?: return
            val pending = goAsync()
            Core.scope.launch {
                try {
                    Channels.act(ch, org.json.JSONObject().put("action", id))
                    // Only the tapped notification; buttons from before tags clear the channel's.
                    val tag = intent.getStringExtra(NOTE_TAG)
                    if (tag != null) Notifier.cancelApp(app, tag, intent.getIntExtra(NOTE_ID, 0)) else Notifier.clearApp(app, ch)
                    SyncWorker.start(app, "action")
                } catch (e: Exception) {
                    Log.w("Tether", "app action $id on $ch failed", e)
                } finally {
                    pending.finish()
                }
            }
            return
        }
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
                    // The batch notification goes away by itself once nothing is in flight.
                    CANCEL -> intent.getStringArrayExtra(IDS)?.forEach { id -> Core.withNode { it.cancel(id) } }
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
            SyncWorker.start(app, "reply")
        } finally {
            Core.release(HOLD)
        }
    }

    companion object {
        const val REPLY = "com.kivan.tether.REPLY"
        const val MARK_READ = "com.kivan.tether.MARK_READ"
        const val CANCEL = "com.kivan.tether.CANCEL"
        const val COPY = "com.kivan.tether.COPY"
        const val RETRY = "com.kivan.tether.RETRY"
        const val APP_ACTION = "com.kivan.tether.APP_ACTION"
        const val CHANNEL = "channel"
        const val ACTION_ID = "action_id"
        const val NOTE_TAG = "note_tag"
        const val NOTE_ID = "note_id"
        const val IDS = "ids"
        const val TEXT = "text"
        private const val HOLD = "notification"

        fun intent(context: Context, action: String): Intent =
            Intent(context, ChatActionReceiver::class.java).setAction(action)
    }
}
