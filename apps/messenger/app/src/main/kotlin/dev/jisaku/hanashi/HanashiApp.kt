package dev.jisaku.hanashi

import android.app.Application
import org.mndt.mesh.MeshCoordinator

class HanashiApp : Application() {
    val messenger by lazy { Messenger(this) }
    override fun onCreate() {
        super.onCreate()
        // This demo always talks Mosimo; it becomes a relay gateway only when the user turns on 「インターネットへ搬出」.
        MeshCoordinator.get(this).setOnlineEnabled(messenger.chats.value.gateway)
    }
}
