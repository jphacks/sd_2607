package dev.jisaku.hanashi

import android.app.Application
import org.mndt.mesh.MeshCoordinator

class HanashiApp : Application() {
    val messenger by lazy { Messenger(this) }
    override fun onCreate() {
        super.onCreate()
        MeshCoordinator.get(this).apply {
            // Talk never shows posts; building them would keep the mesh busy enough to miss same-phone HELLOs once online.
            postViews = false
            // This demo always talks Mosimo; it becomes a relay gateway only when the user turns on 「インターネットへ搬出」.
            setOnlineEnabled(messenger.chats.value.gateway)
        }
    }
}
