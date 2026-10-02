package com.kivan.tether

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import com.kivan.tether.core.MediaCmd
import com.kivan.tether.core.PhoneNotif

/**
 * Notification access, used twice: mirroring the active notifications to the laptop, and (through
 * the same grant) reading media sessions for [MediaMirror]. Notifications go out only over a link
 * that is already up; they never start the node. A new link gets a full snapshot, which is what
 * `tether notifications --fresh` waits for after its FCM wake.
 */
class PhoneListener : NotificationListenerService() {
    private var media: MediaMirror? = null
    private val labels = HashMap<String, String>()
    private val sendNotifs = Runnable { publishNotifs() }

    override fun onListenerConnected() {
        instance = this
        media = MediaMirror(this, ComponentName(this, PhoneListener::class.java), ::label).also { it.start() }
        schedule()
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
        main.removeCallbacks(sendNotifs)
        media?.stop()
        media = null
    }

    override fun onDestroy() {
        onListenerDisconnected()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = schedule()

    override fun onNotificationRemoved(sbn: StatusBarNotification) = schedule()

    // A burst (a chat app posting a message and its summary) becomes one send.
    private fun schedule() {
        if (!Core.connected.value) return
        main.removeCallbacks(sendNotifs)
        main.postDelayed(sendNotifs, DEBOUNCE_MS)
    }

    private fun publishNotifs() {
        main.removeCallbacks(sendNotifs)
        if (!Core.connected.value) return
        val active = runCatching { activeNotifications }.getOrNull() ?: return
        val list = active.filter(::mirrored).sortedBy { it.postTime }.map(::toNotif)
        Core.sendLive { it.sendNotifs(list) }
    }

    private fun mirrored(sbn: StatusBarNotification): Boolean {
        val n = sbn.notification
        if (sbn.packageName == packageName) return false
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return false
        if (sbn.isOngoing || !sbn.isClearable) return false
        // Media is mirrored as MPRIS, not as a notification.
        if (n.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) return false
        if (n.extras.getString(Notification.EXTRA_TEMPLATE)?.contains("MediaStyle") == true) return false
        return true
    }

    private fun toNotif(sbn: StatusBarNotification): PhoneNotif {
        val x = sbn.notification.extras
        val messages = x.getParcelableArray(Notification.EXTRA_MESSAGES, Parcelable::class.java)
            ?.let { Notification.MessagingStyle.Message.getMessagesFromBundleArray(it) }
            ?.map { it.text }
        return PhoneNotif(
            key = sbn.key,
            app = label(sbn.packageName),
            title = x.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
            text = Mirror.notifText(messages, x.getCharSequence(Notification.EXTRA_BIG_TEXT), x.getCharSequence(Notification.EXTRA_TEXT)),
            postedMs = sbn.postTime,
        )
    }

    /** Needs the app to be visible to us: the manifest's <queries> covers launchable apps. */
    private fun label(pkg: String): String = labels.getOrPut(pkg) {
        runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }
            .getOrDefault(pkg)
    }

    companion object {
        private const val DEBOUNCE_MS = 500L
        private val main = Handler(Looper.getMainLooper())
        /** Only touched on the main thread. */
        private var instance: PhoneListener? = null

        /** Called on the node's thread when a link comes up: send both snapshots. */
        fun linkUp() {
            main.post { instance?.let { it.publishNotifs(); it.media?.linkUp() } }
        }

        fun command(cmd: MediaCmd) {
            main.post { instance?.media?.command(cmd) }
        }

        fun enabled(context: Context): Boolean =
            context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)
    }
}
