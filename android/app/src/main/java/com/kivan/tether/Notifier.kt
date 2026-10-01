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
    const val SYNC_ID = 1

    fun channels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(SYNC, "Syncing", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "Shown briefly while the phone syncs with the laptop"
                },
                NotificationChannel(MESSAGES, "Messages", NotificationManager.IMPORTANCE_HIGH),
                NotificationChannel(FILES, "Files", NotificationManager.IMPORTANCE_DEFAULT),
            ),
        )
    }

    fun sync(context: Context): Notification =
        Notification.Builder(context, SYNC)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("Syncing with Laptop")
            .setContentIntent(openApp(context))
            .build()

    fun message(context: Context, m: ChatMessage) {
        val (title, text) = when (m.kind) {
            MsgKind.PING -> "Laptop" to (m.text ?: "Ping")
            MsgKind.RING -> "Laptop is ringing your phone" to "Open Tether"
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
