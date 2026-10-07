package com.kivan.tether.dibs.ideas

import android.app.PendingIntent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.kivan.tether.dibs.IdeaActivity

/** The Quick Settings tile "Idea" (Capture's): a trampoline to Voice or Text, never the microphone itself. */
class IdeaTileService : TileService() {
    override fun onStartListening() {
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        startActivityAndCollapse(PendingIntent.getActivity(this, 0, IdeaActivity.choose(this), PendingIntent.FLAG_IMMUTABLE))
    }
}
