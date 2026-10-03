package com.kivan.tether

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.kivan.tether.core.TetherNode
import kotlinx.coroutines.launch

/**
 * FCM is used only to wake the phone: the laptop sends a content-free data message and the phone
 * dials it. Firebase is initialised by hand from the BuildConfig values (local.properties), so the
 * app builds and works without a Firebase project, just without wakes.
 */
object Push {
    val configured get() = BuildConfig.FCM_APP_ID.isNotEmpty()

    fun init(context: Context) {
        if (!configured || FirebaseApp.getApps(context).isNotEmpty()) return
        FirebaseApp.initializeApp(
            context,
            FirebaseOptions.Builder()
                .setApiKey(BuildConfig.FCM_API_KEY)
                .setApplicationId(BuildConfig.FCM_APP_ID)
                .setProjectId(BuildConfig.FCM_PROJECT_ID)
                .setGcmSenderId(BuildConfig.FCM_SENDER_ID)
                .build(),
        )
    }

    /** Sends the registration token on every connect; it is tiny and the laptop may have lost it. */
    fun sendToken(node: TetherNode) {
        if (!configured) return
        FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
            runCatching { node.sendPushToken(token) }
        }.addOnFailureListener { Log.w("Tether", "no FCM token", it) }
    }
}

class WakeMessagingService : com.google.firebase.messaging.FirebaseMessagingService() {
    override fun onMessageReceived(message: com.google.firebase.messaging.RemoteMessage) {
        if (message.data["t"] == "wake") SyncWorker.start(this, "wake")
    }

    override fun onNewToken(token: String) {
        // Delivered on the next connect (Push.sendToken); a live link gets it now.
        Core.scope.launch { Core.withNode { runCatching { it.sendPushToken(token) } } }
    }
}
