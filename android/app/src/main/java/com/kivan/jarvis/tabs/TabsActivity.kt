package com.kivan.jarvis.tabs

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.kivan.jarvis.CallActivity
import com.kivan.jarvis.CallService
import com.kivan.jarvis.Voice
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * The wall of tabs. The link to the server lives only while this is on screen; the mic starts here,
 * in sight, because Android lets it go on behind another app or a locked screen only if it did.
 */
class TabsActivity : ComponentActivity() {
    private lateinit var deck: Deck
    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { Voice.listen(this) }

    /** Closing the prompt resumes this screen: a no stays a no until he comes back to it. */
    private var askedMic = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        deck = (lastNonConfigurationInstance as? Deck) ?: trialDeck(intent) ?: RealDeck.get(this)
        setContent { TabsTheme { Stage(deck) } }
        if (deck is RealDeck) {
            // Back from the wall goes to a running conversation he had open, not out of it.
            val back = onBackPressedDispatcher.addCallback(this, false) { startActivity(CallActivity.intent(this@TabsActivity, false)) }
            Voice.ui.onEach { back.isEnabled = it.live && Voice.spoke }.launchIn(lifecycleScope)
        }
        opening(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        opening(intent)
    }

    private fun opening(intent: Intent) {
        intent.getStringExtra(TAB)?.let { (deck as? RealDeck)?.landed(it) }
        intent.removeExtra(TAB)
    }

    override fun onStart() {
        super.onStart()
        (deck as? RealDeck)?.up()
    }

    override fun onResume() {
        super.onResume()
        if (deck !is RealDeck) return
        Voice.tabsUp = true
        if (CallService.micGranted(this)) {
            Voice.listen(this)
        } else if (!Voice.saved.value && !askedMic) {
            askedMic = true
            askMic.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    override fun onStop() {
        super.onStop()
        askedMic = false
        Voice.tabsUp = false
        if (!isChangingConfigurations) (deck as? RealDeck)?.down()
    }

    @Deprecated("A Deck outlives a rotation")
    override fun onRetainCustomNonConfigurationInstance(): Any = deck

    companion object {
        private const val TAB = "tab"

        fun opening(context: Context, tab: String) =
            Intent(context, TabsActivity::class.java).putExtra(TAB, tab)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
