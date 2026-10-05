package com.kivan.tether

import android.app.Application
import com.kivan.tether.dibs.Dibs

class TetherApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Native.load(this)
        Notifier.channels(this)
        Push.init(this)
        Core.init(this)
        Channels.init(this)
        Shortcuts.publish(this)
        // dibs's own screens (the :dibs module) reach the link through this.
        Dibs.host = DibsBridge(this)
    }
}
