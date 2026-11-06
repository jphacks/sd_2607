package dev.jisaku.hanashi

import android.app.Application
import org.mndt.mesh.MeshCoordinator

class HanashiApp : Application() {
    val messenger by lazy { Messenger(this) }
    override fun onCreate() {
        super.onCreate()
        // This demo always talks Mosimo: a pure mesh node, never a relay gateway.
        MeshCoordinator.get(this).setOnlineEnabled(false)
        messenger
    }
}
