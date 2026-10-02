package com.kivan.tether

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.kivan.tether.core.ChatMessage
import com.kivan.tether.core.MsgKind

/** Every high-priority FCM wake must end in a visible notification, or Android throttles FCM. */
object Notifier {
    const val SYNC = "sync"
    private const val MESSAGES = "messages"
    private const val FILES = "files"
    private const val RING = "ring"
    const val SYNC_ID = 1
    private const val RING_ID = 2

    fun channels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(SYNC, "Syncing", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "Shown while the phone syncs with the laptop, or stays linked while media plays"
                },
                NotificationChannel(MESSAGES, "Messages", NotificationManager.IMPORTANCE_HIGH),
                NotificationChannel(FILES, "Files", NotificationManager.IMPORTANCE_DEFAULT),
                // Ringer plays the sound itself, at alarm volume; the channel stays silent.
                NotificationChannel(RING, "Ring", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "The laptop is looking for the phone"
                    setSound(null, null)
                },
            ),
        )
    }

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
        val (title, text) = when (m.kind) {
            MsgKind.PING -> "Laptop" to (m.text ?: "Ping")
            else -> "Laptop" to (m.text ?: "")
        }
        val n = Notification.Builder(context, MESSAGES)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .build()
        notify(context, m.id, n)
    }

    fun file(context: Context, m: ChatMessage, uri: Uri) {
        val name = m.fileName ?: "file"
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, Downloads.mimeOf(name))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        val n = Notification.Builder(context, FILES)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("Laptop sent a file")
            .setContentText(name)
            .setContentIntent(
                PendingIntent.getActivity(context, m.id.hashCode(), view, PendingIntent.FLAG_IMMUTABLE),
            )
            .setAutoCancel(true)
            .build()
        notify(context, m.id, n)
    }

    private fun notify(context: Context, id: String, n: Notification) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.areNotificationsEnabled()) nm.notify(id, 0, n)
    }

    private fun openApp(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
}
