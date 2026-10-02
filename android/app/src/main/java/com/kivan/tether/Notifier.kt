package com.kivan.tether

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationCompat.MessagingStyle
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import com.kivan.tether.core.ChatMessage
import com.kivan.tether.core.MsgKind

/**
 * Every high-priority FCM wake must end in a visible notification, or Android throttles FCM.
 * Chat, pings and received files share one conversation notification with the laptop.
 */
object Notifier {
    const val SYNC = "sync"
    private const val MESSAGES = "messages"
    private const val TRANSFERS = "transfers"
    private const val RING = "ring"
    const val SYNC_ID = 1
    private const val RING_ID = 2
    private const val CHAT_TAG = "chat"
    private const val TRANSFER_TAG = "transfer"
    private const val MAX_LINES = 10
    const val REPLY_KEY = "reply"

    fun channels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        // Received files used to have their own channel; they are lines in the chat now.
        nm.deleteNotificationChannel("files")
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(SYNC, "Syncing", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "Shown while the phone syncs with the laptop, or stays linked while media plays"
                },
                NotificationChannel(MESSAGES, "Messages", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Chat, pings and files from the laptop"
                },
                NotificationChannel(TRANSFERS, "Transfers", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Progress of large files going to or coming from the laptop"
                },
                // Ringer plays the sound itself, at alarm volume; the channel stays silent.
                NotificationChannel(RING, "Ring", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "The laptop is looking for the phone"
                    setSound(null, null)
                },
            ),
        )
    }

    /** The other side of the conversation; also the Person on the "Laptop" shortcut. */
    fun laptop(context: Context): Person = Person.Builder()
        .setName("Laptop")
        .setKey("laptop")
        .setIcon(IconCompat.createWithBitmap(avatar(context)))
        .setImportant(true)
        .build()

    /** [linked]: held open for media rather than a one-off sync. */
    fun sync(context: Context, linked: Boolean): Notification =
        Notification.Builder(context, SYNC)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(if (linked) "Connected to Laptop" else "Syncing with Laptop")
            .setContentIntent(openApp(context))
            .build()

    fun ring(context: Context) {
        val full = PendingIntent.getActivity(
            context, 0,
            Intent(context, RingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getBroadcast(
            context, 0, Intent(context, RingStopReceiver::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(context, RING)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("Laptop is ringing your phone")
            .setCategory(Notification.CATEGORY_ALARM)
            .setOngoing(true)
            .setContentIntent(full)
            .setFullScreenIntent(full, true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.areNotificationsEnabled()) nm.notify(RING_ID, n)
    }

    fun cancelRing(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(RING_ID)
    }

    fun message(context: Context, m: ChatMessage) {
        val text = when (m.kind) {
            MsgKind.PING -> "🔔 ${m.text ?: "Ping"}"
            else -> m.text ?: ""
        }
        postChat(context, MessagingStyle.Message(text, m.tsMs, laptop(context)), open = null, silent = false)
    }

    /** A received file, saved at [uri]: a picture inline, anything else a 📎 line, plus Open. */
    fun file(context: Context, m: ChatMessage, uri: Uri) {
        val name = m.fileName ?: "file"
        val mime = Downloads.mimeOf(name)
        val line = if (mime.startsWith("image/")) {
            MessagingStyle.Message(name, m.tsMs, laptop(context)).setData(mime, uri)
        } else {
            MessagingStyle.Message("📎 $name", m.tsMs, laptop(context))
        }
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        val open = PendingIntent.getActivity(context, m.id.hashCode(), view, PendingIntent.FLAG_IMMUTABLE)
        postChat(context, line, open, silent = false)
    }

    /** Shows a reply sent from the notification; Android keeps a spinner until it is re-posted. */
    fun replied(context: Context, text: String) {
        postChat(context, MessagingStyle.Message(text, System.currentTimeMillis(), null as Person?), open = null, silent = true)
    }

    fun clearChat(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(CHAT_TAG, 0)
    }

    /**
     * Appends [line] to the conversation. The posted notification is the only state, so the
     * thread survives the process being killed between messages.
     */
    private fun postChat(context: Context, line: MessagingStyle.Message, open: PendingIntent?, silent: Boolean) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (!nm.areNotificationsEnabled()) return
        val style = MessagingStyle(Person.Builder().setName("You").build())
        nm.activeNotifications.firstOrNull { it.tag == CHAT_TAG }
            ?.let { MessagingStyle.extractMessagingStyleFromNotification(it.notification) }
            ?.messages?.takeLast(MAX_LINES - 1)?.forEach { style.addMessage(it) }
        style.addMessage(line)

        val b = NotificationCompat.Builder(context, MESSAGES)
            .setSmallIcon(R.drawable.ic_notify)
            .setStyle(style)
            .setShortcutId(Shortcuts.ID)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .setOnlyAlertOnce(silent)
            .addAction(replyAction(context))
            .addAction(markReadAction(context))
        open?.let {
            b.addAction(
                NotificationCompat.Action.Builder(0, "Open", it)
                    .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_NONE)
                    .setShowsUserInterface(true)
                    .build(),
            )
        }
        nm.notify(CHAT_TAG, 0, b.build())
    }

    private fun replyAction(context: Context): NotificationCompat.Action {
        // Mutable: the system fills in the typed text.
        val pi = PendingIntent.getBroadcast(
            context, 1, ChatActionReceiver.intent(context, ChatActionReceiver.REPLY),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Action.Builder(0, "Reply", pi)
            .addRemoteInput(RemoteInput.Builder(REPLY_KEY).setLabel("Message").build())
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setAllowGeneratedReplies(true)
            .setShowsUserInterface(false)
            .build()
    }

    private fun markReadAction(context: Context): NotificationCompat.Action {
        val pi = PendingIntent.getBroadcast(
            context, 2, ChatActionReceiver.intent(context, ChatActionReceiver.MARK_READ),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Action.Builder(0, "Mark as read", pi)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .setShowsUserInterface(false)
            .build()
    }

    /**
     * A file transfer in flight: a progress notification that Android 16+ promotes to a Live
     * Update (status-bar chip) while the user allows it.
     */
    fun transfer(context: Context, id: String, name: String, sending: Boolean?, done: Long, total: Long) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (!nm.areNotificationsEnabled()) return
        val permille = if (total > 0) (done * 1000 / total).toInt().coerceIn(0, 1000) else 0
        val pct = "${permille / 10}%"
        val title = when (sending) {
            true -> "Sending $name"
            false -> "Receiving $name"
            null -> name
        }
        val b = NotificationCompat.Builder(context, TRANSFERS)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText("$pct · ${Formatter.formatShortFileSize(context, done)} of ${Formatter.formatShortFileSize(context, total)}")
            .setStyle(
                NotificationCompat.ProgressStyle()
                    .addProgressSegment(NotificationCompat.ProgressStyle.Segment(1000))
                    .setProgress(permille),
            )
            .setShortCriticalText(pct)
            .setRequestPromotedOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openApp(context))
        // Only the sender can cancel a file (the core's cancel matches my own files).
        if (sending == true) {
            val cancel = PendingIntent.getBroadcast(
                context, id.hashCode(), ChatActionReceiver.intent(context, ChatActionReceiver.CANCEL).putExtra(ChatActionReceiver.ID, id),
                PendingIntent.FLAG_IMMUTABLE,
            )
            b.addAction(NotificationCompat.Action.Builder(0, "Cancel", cancel).build())
        }
        nm.notify(TRANSFER_TAG, id.hashCode(), b.build())
    }

    fun cancelTransfer(context: Context, id: String) {
        context.getSystemService(NotificationManager::class.java).cancel(TRANSFER_TAG, id.hashCode())
    }

    private fun openApp(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )

    @Volatile private var avatarCache: Bitmap? = null

    /** The laptop glyph on a gruvbox circle. */
    private fun avatar(context: Context): Bitmap = avatarCache ?: run {
        val size = 192
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF83A598.toInt() })
        val glyph = ContextCompat.getDrawable(context, R.drawable.ic_laptop)!!.mutate()
        glyph.setTint(0xFF1D2021.toInt())
        val inset = size / 5
        glyph.setBounds(inset, inset, size - inset, size - inset)
        glyph.draw(canvas)
        bmp.also { avatarCache = it }
    }
}
