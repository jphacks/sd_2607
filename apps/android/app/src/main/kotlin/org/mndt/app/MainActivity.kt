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
import org.mndt.mesh.MeshCoordinator

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
    }
    override fun onStart() { super.onStart(); MeshCoordinator.get(this).setOnlineForeground(true) }
    override fun onStop() { MeshCoordinator.get(this).setOnlineForeground(false); super.onStop() }
}
