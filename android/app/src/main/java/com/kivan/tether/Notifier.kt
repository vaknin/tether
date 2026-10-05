package com.kivan.tether

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.ui.graphics.toArgb
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationCompat.MessagingStyle
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import com.kivan.tether.core.ChatMessage
import com.kivan.tether.core.MsgKind
import com.kivan.tether.core.Status
import com.kivan.tether.ui.theme.Palette

/**
 * Every high-priority FCM wake must end in a visible notification, or Android throttles FCM.
 * Chat, pings and received files share one conversation notification with the laptop. Nothing is
 * shown just because the phone is syncing: only news, transfer progress and problems.
 */
object Notifier {
    private const val MESSAGES = "messages"
    private const val TRANSFERS = "transfers"
    private const val RING = "ring"
    private const val PROBLEMS = "problems"
    /** The transfer batch, also [TransferService]'s foreground notification. */
    const val TRANSFER_ID = 1
    private const val RING_ID = 2
    private const val STUCK_ID = 3
    private const val REFUSED_ID = 4
    private const val FAILED_TAG = "failed"
    private const val CHAT_TAG = "chat"
    private const val APP_TAG = "app"
    /** Each Tether channel's notification channel is `app.<name>`, in this group. */
    private const val APP_PREFIX = "app."
    private const val APP_GROUP = "apps"
    private const val MAX_LINES = 10
    const val REPLY_KEY = "reply"

    fun channels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        // Received files used to have their own channel; they are lines in the chat now. Syncing
        // no longer shows anything.
        nm.deleteNotificationChannel("files")
        nm.deleteNotificationChannel("sync")
        nm.createNotificationChannels(
            listOf(
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
                NotificationChannel(PROBLEMS, "Problems", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Messages stuck on the phone, a file that couldn't be sent, or the laptop no longer paired"
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
            .setSmallIcon(R.drawable.ic_notification)
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
        val copy = m.text?.takeIf { m.kind == MsgKind.TEXT }
        postChat(context, MessagingStyle.Message(text, m.tsMs, laptop(context)), open = null, silent = false, copy = copy)
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
     * thread survives the process being killed between messages. [copy] (a text line) adds Copy.
     */
    private fun postChat(
        context: Context,
        line: MessagingStyle.Message,
        open: PendingIntent?,
        silent: Boolean,
        copy: String? = null,
    ) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (!nm.areNotificationsEnabled()) return
        val style = MessagingStyle(Person.Builder().setName("You").build())
        nm.activeNotifications.firstOrNull { it.tag == CHAT_TAG }
            ?.let { MessagingStyle.extractMessagingStyleFromNotification(it.notification) }
            ?.messages?.takeLast(MAX_LINES - 1)?.forEach { style.addMessage(it) }
        style.addMessage(line)

        val b = NotificationCompat.Builder(context, MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setStyle(style)
            .setShortcutId(Shortcuts.ID)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(openChannel(context, Channels.CHAT))
            .setAutoCancel(true)
            .setOnlyAlertOnce(silent)
            .addAction(replyAction(context))
            .addAction(markReadAction(context))
        copy?.let { b.addAction(copyAction(context, it)) }
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

    /** Copies the newest text; older lines are a long-press away in the app. */
    private fun copyAction(context: Context, text: String): NotificationCompat.Action {
        val pi = PendingIntent.getBroadcast(
            context, 3, ChatActionReceiver.intent(context, ChatActionReceiver.COPY).putExtra(ChatActionReceiver.TEXT, text),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Action.Builder(0, "Copy", pi)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_NONE)
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
     * The transfer batch ([Transfers]) as a progress notification, which Android 16+ promotes to a
     * Live Update (status-bar chip) while the user allows it. Null when notifications are off.
     */
    fun transfer(context: Context, items: List<Transfers.Item>, inFlight: Set<String>): Notification? {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (!nm.areNotificationsEnabled()) return null
        return transferNotification(context, items, inFlight).also { nm.notify(TRANSFER_ID, it) }
    }

    fun transferNotification(context: Context, items: List<Transfers.Item>, inFlight: Set<String>): Notification {
        val total = items.sumOf { it.total }
        val done = items.sumOf { if (it.finished) it.total else it.done }
        val permille = if (total > 0) (done * 1000 / total).toInt().coerceIn(0, 1000) else 0
        val pct = "${permille / 10}%"
        val title = when {
            items.isEmpty() -> "Transfers"
            items.size == 1 -> when (items[0].sending) {
                true -> "Sending ${items[0].name}"
                false -> "Receiving ${items[0].name}"
                null -> items[0].name
            }
            items.all { it.sending == true } -> "Sending ${items.size} files"
            items.all { it.sending == false } -> "Receiving ${items.size} files"
            else -> "Transferring ${items.size} files"
        }
        val sizes = "${Formatter.formatShortFileSize(context, done)} of ${Formatter.formatShortFileSize(context, total)}"
        val count = if (items.size > 1) "${items.count { it.finished }} of ${items.size} · " else ""
        val b = NotificationCompat.Builder(context, TRANSFERS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText("$count$pct · $sizes")
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
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openChannel(context, Channels.CHAT))
        // Only the sender can cancel a file (the core's cancel matches my own files).
        val mine = items.filter { it.sending == true && it.id in inFlight }.map { it.id }
        if (mine.isNotEmpty()) {
            val cancel = PendingIntent.getBroadcast(
                context, 4,
                ChatActionReceiver.intent(context, ChatActionReceiver.CANCEL).putExtra(ChatActionReceiver.IDS, mine.toTypedArray()),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            b.addAction(NotificationCompat.Action.Builder(0, "Cancel", cancel).build())
        }
        return b.build()
    }

    fun cancelTransfer(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(TRANSFER_ID)
    }

    /**
     * Items of mine queued for [STUCK_AFTER_MS] without reaching the laptop: one notice, with
     * Retry now. Cleared once the outbox is empty.
     */
    fun stuck(context: Context, status: Status) {
        val oldest = status.oldestQueuedMs ?: return cancelStuck(context)
        when (stuckNotice(status.queued, oldest, System.currentTimeMillis())) {
            false -> return cancelStuck(context)
            null -> return
            true -> {}
        }
        val nm = context.getSystemService(NotificationManager::class.java)
        if (!nm.areNotificationsEnabled()) return
        val n = status.queued.toInt()
        val retry = PendingIntent.getBroadcast(
            context, 5, ChatActionReceiver.intent(context, ChatActionReceiver.RETRY), PendingIntent.FLAG_IMMUTABLE,
        )
        val since = DateUtils.formatDateTime(context, oldest, DateUtils.FORMAT_SHOW_TIME)
        val b = NotificationCompat.Builder(context, PROBLEMS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (n == 1) "1 message not delivered to Laptop" else "$n messages not delivered to Laptop")
            .setContentText("Waiting since $since. Tether keeps trying.")
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setOnlyAlertOnce(true)
            .setContentIntent(openChannel(context, Channels.CHAT))
            .addAction(NotificationCompat.Action.Builder(0, "Retry now", retry).build())
        nm.notify(STUCK_ID, b.build())
    }

    fun cancelStuck(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(STUCK_ID)
    }

    /** The laptop refused the link: it doesn't take this phone as its pair any more. */
    fun refused(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (!nm.areNotificationsEnabled()) return
        val b = NotificationCompat.Builder(context, PROBLEMS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Laptop doesn't recognise this phone")
            .setContentText("Unpair here, then run tether pair on the laptop and scan the code.")
            .setStyle(NotificationCompat.BigTextStyle())
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(context))
        nm.notify(REFUSED_ID, b.build())
    }

    fun cancelRefused(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(REFUSED_ID)
    }

    /** A file of mine the core gave up on (gone, or changed while sending); it is cancelled. */
    fun sendFailed(context: Context, id: String, name: String, reason: String) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (!nm.areNotificationsEnabled()) return
        val b = NotificationCompat.Builder(context, PROBLEMS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Couldn't send $name")
            .setContentText(reason.replaceFirstChar { it.uppercase() } + ".")
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setContentIntent(openChannel(context, Channels.CHAT))
            .setAutoCancel(true)
        nm.notify(FAILED_TAG, id.hashCode(), b.build())
    }

    /** One notification channel per Tether channel that may notify; the rest are deleted. */
    fun appChannels(context: Context, list: List<ChannelInfo>) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannelGroup(NotificationChannelGroup(APP_GROUP, "Channels"))
        val wanted = list.filter { it.notify }
        nm.createNotificationChannels(
            wanted.map { c ->
                NotificationChannel(APP_PREFIX + c.name, c.title, NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Updates from ${c.title} on the laptop"
                    group = APP_GROUP
                }
            },
        )
        val ids = wanted.map { APP_PREFIX + it.name }.toSet()
        nm.notificationChannels.filter { it.id.startsWith(APP_PREFIX) && it.id !in ids }
            .forEach { nm.deleteNotificationChannel(it.id) }
    }

    /**
     * A channel's news (a view's `notify`, a thread post). Without [tag], one notification per
     * channel, replaced; with one (a post's `tag`), one per tag, so each keeps its own buttons. A
     * replaced notification doesn't alert again.
     */
    fun app(
        context: Context,
        c: ChannelInfo,
        title: String,
        text: String,
        actions: List<Pair<String, String>> = emptyList(),
        tag: String? = null,
    ) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (!nm.areNotificationsEnabled()) return
        val noteTag = if (tag != null) "$APP_TAG:${c.name}:$tag" else APP_TAG
        val noteId = if (tag != null) 0 else c.name.hashCode()
        // Request codes differ per notification and button, so one's buttons never replace another's.
        val code = (if (tag != null) noteTag else "$APP_TAG:${c.name}").hashCode()
        val b = NotificationCompat.Builder(context, APP_PREFIX + c.name)
            .setSmallIcon(R.drawable.ic_notification)
            .setLargeIcon(glyph(c, 192))
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setColor(c.accent ?: Palette.Accent.toArgb())
            .setShortcutId(Shortcuts.channelId(c.name))
            .setContentIntent(openChannel(context, c.name))
            .setAutoCancel(true)
            // A tagged post updated in place doesn't alert again; a new untagged post (it replaces
            // the channel's one) does, as before.
            .setOnlyAlertOnce(tag != null)
        // Buttons run in the background, so they work from the lock screen without unlocking. A tap
        // clears only its own notification.
        for ((i, a) in actions.take(3).withIndex()) {
            val pi = PendingIntent.getBroadcast(
                context, code * 31 + i,
                ChatActionReceiver.intent(context, ChatActionReceiver.APP_ACTION)
                    .putExtra(ChatActionReceiver.CHANNEL, c.name).putExtra(ChatActionReceiver.ACTION_ID, a.first)
                    .putExtra(ChatActionReceiver.NOTE_TAG, noteTag).putExtra(ChatActionReceiver.NOTE_ID, noteId),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            b.addAction(NotificationCompat.Action.Builder(0, a.second, pi).setShowsUserInterface(false).build())
        }
        nm.notify(noteTag, noteId, b.build())
    }

    /** All of a channel's notifications: the untagged one and each tagged post's. */
    fun clearApp(context: Context, name: String) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.cancel(APP_TAG, name.hashCode())
        cancelTagged(nm, name) { true }
    }

    /**
     * A view's `open_tags`: the channel's tagged notifications not in [open] were answered. One
     * posted in the last [FRESH_MS] stays: the view may have been made before its post (the app
     * publishes both, and they can cross), and the next view lists it.
     */
    fun keepApp(context: Context, name: String, open: Set<String>) {
        val fresh = System.currentTimeMillis() - FRESH_MS
        cancelTagged(context.getSystemService(NotificationManager::class.java), name, fresh) { it !in open }
    }

    private const val FRESH_MS = 15_000L

    /** The channel's one untagged notification (an older app's post, or a view's `notify`). */
    fun cancelUntagged(context: Context, name: String) {
        context.getSystemService(NotificationManager::class.java).cancel(APP_TAG, name.hashCode())
    }

    /** One notification, as a button on it names it ([app]). */
    fun cancelApp(context: Context, tag: String, id: Int) {
        context.getSystemService(NotificationManager::class.java).cancel(tag, id)
    }

    // Cancels the channel's tagged notifications (`app:<channel>:<tag>`) whose tag [drop] picks.
    private fun cancelTagged(nm: NotificationManager, name: String, before: Long = Long.MAX_VALUE, drop: (String) -> Boolean) {
        val prefix = "$APP_TAG:$name:"
        for (sbn in nm.activeNotifications) {
            val t = sbn.tag ?: continue
            if (t.startsWith(prefix) && sbn.postTime < before && drop(t.removePrefix(prefix))) nm.cancel(t, sbn.id)
        }
    }

    /** Opens the app on a channel ([Channels.CHAT]: the chat). */
    fun openChannel(context: Context, name: String): PendingIntent =
        PendingIntent.getActivity(
            context, name.hashCode(), MainActivity.open(context, name), PendingIntent.FLAG_IMMUTABLE,
        )

    /** A channel's tile (the shortcut and notification icon), as the list draws it. */
    fun glyph(c: ChannelInfo, size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        ChannelLook.draw(Canvas(bmp), c, 0f, 0f, size.toFloat())
        return bmp
    }

    private fun openApp(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )

    @Volatile private var avatarCache: Bitmap? = null

    /** The laptop icon, white on the app's tile, like the chat's avatar. */
    private fun avatar(context: Context): Bitmap = avatarCache ?: run {
        val size = 192
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.Tile.toArgb() })
        val glyph = ContextCompat.getDrawable(context, R.drawable.lucide_laptop)!!.mutate()
        glyph.setTint(Palette.Text.toArgb())
        val inset = size / 5
        glyph.setBounds(inset, inset, size - inset, size - inset)
        glyph.draw(canvas)
        bmp.also { avatarCache = it }
    }
}

/** Queued this long without reaching the laptop counts as stuck. */
internal const val STUCK_AFTER_MS = 30 * 60_000L

/** The stuck notice: true to post it, false to clear it, null to leave it as it is. */
internal fun stuckNotice(queued: ULong, oldestQueuedMs: Long?, nowMs: Long): Boolean? = when {
    queued == 0uL || oldestQueuedMs == null -> false
    nowMs - oldestQueuedMs < STUCK_AFTER_MS -> null
    else -> true
}
