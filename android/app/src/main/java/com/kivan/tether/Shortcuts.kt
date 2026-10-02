package com.kivan.tether

import android.content.Context
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat

/**
 * The "Laptop" Direct Share tile (category matches res/xml/shortcuts.xml). It is also the
 * conversation shortcut of the chat notification, so that lands in the Conversations section.
 * Each app channel has a shortcut too (`ch.<name>`): in the launcher's long-press menu, pinnable,
 * and a text-only Direct Share tile for channels whose manifest has `share`.
 */
object Shortcuts {
    private const val SHARE_CATEGORY = "com.kivan.tether.SHARE"
    private const val SHARE_TEXT_CATEGORY = "com.kivan.tether.SHARE_TEXT"
    private const val CHANNEL_PREFIX = "ch."

    const val ID = "laptop"

    fun publish(context: Context) {
        val laptop = Notifier.laptop(context)
        // The long label is also the chat notification's title, so it is just the name.
        val shortcut = ShortcutInfoCompat.Builder(context, ID)
            .setShortLabel("Laptop")
            .setLongLabel("Laptop")
            .setIcon(laptop.icon)
            .setIntent(MainActivity.open(context, Channels.CHAT))
            .setCategories(setOf(SHARE_CATEGORY))
            .setLongLived(true)
            .setPerson(laptop)
            .build()
        ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
    }

    /** The share sheet ranks direct-share tiles by reported use; without it ours sits below others. */
    fun used(context: Context) = ShortcutManagerCompat.reportShortcutUsed(context, ID)

    fun channelId(name: String) = CHANNEL_PREFIX + name

    /** The channel a shortcut id opens, if it is a channel's. */
    fun channelOf(id: String?): String? = id?.takeIf { it.startsWith(CHANNEL_PREFIX) }?.removePrefix(CHANNEL_PREFIX)

    private fun channel(context: Context, c: ChannelInfo): ShortcutInfoCompat =
        ShortcutInfoCompat.Builder(context, channelId(c.name))
            .setShortLabel(c.title)
            .setLongLabel(c.title)
            .setIcon(IconCompat.createWithBitmap(Notifier.glyph(c, 192)))
            .setIntent(MainActivity.open(context, c.name))
            .setCategories(if (c.share) setOf(SHARE_TEXT_CATEGORY) else emptySet())
            .setLongLived(true)
            .build()

    /** Publishes one shortcut per channel and retires those of channels that are gone. */
    fun channels(context: Context, list: List<ChannelInfo>) {
        runCatching {
            val names = list.map { channelId(it.name) }.toSet()
            val stale = ShortcutManagerCompat.getShortcuts(
                context,
                ShortcutManagerCompat.FLAG_MATCH_DYNAMIC or ShortcutManagerCompat.FLAG_MATCH_PINNED,
            ).map { it.id }.filter { it.startsWith(CHANNEL_PREFIX) && it !in names }.distinct()
            if (stale.isNotEmpty()) {
                ShortcutManagerCompat.removeLongLivedShortcuts(context, stale)
                // A pinned one stays on the home screen, greyed out.
                ShortcutManagerCompat.disableShortcuts(context, stale, "This channel is gone")
            }
            for (c in list) ShortcutManagerCompat.pushDynamicShortcut(context, channel(context, c))
        }
    }

    fun usedChannel(context: Context, name: String) =
        ShortcutManagerCompat.reportShortcutUsed(context, channelId(name))

    /** Asks the launcher to pin the channel on the home screen; false when it can't. */
    fun pin(context: Context, c: ChannelInfo): Boolean =
        ShortcutManagerCompat.isRequestPinShortcutSupported(context) &&
            ShortcutManagerCompat.requestPinShortcut(context, channel(context, c), null)
}
