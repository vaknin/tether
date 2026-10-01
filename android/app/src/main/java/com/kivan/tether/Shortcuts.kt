package com.kivan.tether

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat

/** The "Laptop" Direct Share tile (category matches res/xml/shortcuts.xml). */
object Shortcuts {
    private const val SHARE_CATEGORY = "com.kivan.tether.SHARE"

    private const val ID = "laptop"

    fun publish(context: Context) {
        val shortcut = ShortcutInfoCompat.Builder(context, ID)
            .setShortLabel("Laptop")
            .setLongLabel("Send to Laptop")
            .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
            .setIntent(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN))
            .setCategories(setOf(SHARE_CATEGORY))
            .setLongLived(true)
            .build()
        ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
    }

    /** The share sheet ranks direct-share tiles by reported use; without it ours sits below others. */
    fun used(context: Context) = ShortcutManagerCompat.reportShortcutUsed(context, ID)
}
