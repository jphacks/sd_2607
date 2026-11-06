package dev.jisaku.hanashi

import android.content.Intent
import android.os.Bundle
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.jisaku.hanashi.ui.HanashiRoot
import dev.jisaku.hanashi.ui.HanashiTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    /** Set by a notification tap; the UI opens that chat and clears it. */
    private val openPeer = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Light theme only, so dark system-bar icons even when the device is in dark mode.
        enableEdgeToEdge(SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT), SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT))
        openPeer.value = intent.getStringExtra(Messenger.EXTRA_PEER)
        val messenger = (application as HanashiApp).messenger
        setContent { HanashiTheme { HanashiRoot(messenger, openPeer) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openPeer.value = intent.getStringExtra(Messenger.EXTRA_PEER)
    }
}
