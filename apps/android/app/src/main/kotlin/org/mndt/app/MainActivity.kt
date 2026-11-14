package org.mndt.app

import android.app.Application
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.dsl.module
import org.mndt.app.alert.HazardNotifier
import org.mndt.app.location.LocationRepository
import org.mndt.app.ui.MosimoApp
import org.mndt.app.ui.map.mapModule
import org.mndt.app.ui.post.connectivity
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
        val koin = startKoin {
            androidContext(this@MeshApplication)
            modules(module {
                single { MeshCoordinator.get(get()) }
                single { LocationRepository(get()) }
                single { HazardNotifier(get(), get(), get()) }
            }, timelineModule, mapModule, settingsModule)
        }.koin
        // Started with the process, not the UI: MeshService keeps the process alive in the background.
        koin.get<HazardNotifier>().start()
    }
}

class MainActivity : ComponentActivity() {
    /** Post to open from a hazard notification; cleared once the app has navigated to it. */
    private val openPost = mutableStateOf<String?>(null)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        // A recreated activity keeps its back stack; only a fresh launch follows the notification.
        if (savedInstanceState == null) openPost.value = intent.getStringExtra(EXTRA_POST)
        setContent { MosimoTheme { MosimoApp(openPost.value) { openPost.value = null } } }
        // Debug-only lab hook (as iOS `-mosimo.test.post`): `am start -n … --es mosimo.test.post "text"` publishes one located post.
        if (BuildConfig.DEBUG && savedInstanceState == null) intent.getStringExtra("mosimo.test.post")?.let { text ->
            MeshCoordinator.get(this).publish("［安全］$text", listOf(Extensions.origin(Origin(35_681_236, 139_767_125)), connectivity(this)))
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
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_POST)?.let { openPost.value = it }
    }
    override fun onStart() { super.onStart(); MeshCoordinator.get(this).setOnlineForeground(true) }
    override fun onStop() { MeshCoordinator.get(this).setOnlineForeground(false); super.onStop() }
    companion object {
        /** Event ID of the post whose detail to open (hazard notifications). */
        const val EXTRA_POST = "org.mndt.app.POST"
    }
}
