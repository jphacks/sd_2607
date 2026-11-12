package org.mndt.app

import android.app.Application
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.dsl.module
import org.mndt.app.location.LocationRepository
import org.mndt.app.ui.MosimoApp
import org.mndt.app.ui.map.mapModule
import org.mndt.app.ui.settings.settingsModule
import org.mndt.app.ui.theme.MosimoTheme
import org.mndt.app.ui.timeline.timelineModule
import androidx.lifecycle.lifecycleScope
import dev.jisaku.mosimo.BuildConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.mndt.mesh.Extensions
import org.mndt.mesh.MeshCoordinator
import org.mndt.mesh.Origin

class MeshApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        MeshCoordinator.get(this)
        startKoin {
            androidContext(this@MeshApplication)
            modules(module {
                single { MeshCoordinator.get(get()) }
                single { LocationRepository(get()) }
            }, timelineModule, mapModule, settingsModule)
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        setContent { MosimoTheme { MosimoApp() } }
        // Debug-only lab hook (as iOS `-mosimo.test.post`): `am start -n … --es mosimo.test.post "text"` publishes one located post.
        if (BuildConfig.DEBUG && savedInstanceState == null) intent.getStringExtra("mosimo.test.post")?.let { text ->
            MeshCoordinator.get(this).publish("［安全］$text", listOf(Extensions.origin(Origin(35_681_236, 139_767_125))))
        }
        // `--es mosimo.test.report <id prefix>` / `--es mosimo.test.delete <id prefix>` act on a held event once loaded.
        if (BuildConfig.DEBUG && savedInstanceState == null) {
            val report = intent.getStringExtra("mosimo.test.report")
            val delete = intent.getStringExtra("mosimo.test.delete")
            if (report != null || delete != null) lifecycleScope.launch {
                val mesh = MeshCoordinator.get(this@MainActivity)
                val events = mesh.state.first { it.ready }.let { delay(2000); mesh.state.value.events }
                report?.let { p -> events.firstOrNull { it.id.startsWith(p) }?.let { mesh.report(it.id, "spam", "試験", false) } }
                delete?.let { p -> events.firstOrNull { it.id.startsWith(p) }?.let { mesh.delete(it.id) } }
            }
        }
    }
    override fun onStart() { super.onStart(); MeshCoordinator.get(this).setOnlineForeground(true) }
    override fun onStop() { MeshCoordinator.get(this).setOnlineForeground(false); super.onStop() }
}
