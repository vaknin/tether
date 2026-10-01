package com.kivan.tether

import android.content.Context

/** The one JNI call into the Rust library; everything else goes through uniffi (JNA). */
object Native {
    /** Loads the library through the JVM and hands iroh the application context. Call once, early. */
    fun load(context: Context) {
        System.loadLibrary("tether_ffi")
        init(context.applicationContext)
    }

    private external fun init(context: Context)
}
