package org.mndt.app.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

/** Foreground one-shot fixes via the platform LocationManager (no Play services). */
class LocationRepository(private val context: Context) {
    private val manager = context.getSystemService(LocationManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableLocation = MutableStateFlow<Location?>(null)
    val location: StateFlow<Location?> = mutableLocation.asStateFlow()
    /** Reverse-geocoded label such as 「仙台市青葉区 川内」; null offline or when Geocoder is unavailable. */
    private val mutableAddress = MutableStateFlow<String?>(null)
    val address: StateFlow<String?> = mutableAddress.asStateFlow()
    private val mutableBusy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = mutableBusy.asStateFlow()

    fun granted() = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        .any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    fun precise() = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun refresh() {
        if (!granted() || mutableBusy.value) return
        val provider = listOf(LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { Build.VERSION.SDK_INT >= 31 || it != LocationManager.FUSED_PROVIDER }
            .firstOrNull { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) } ?: return
        // Show the last known fix at once; the fresh fix replaces it.
        runCatching { manager.getLastKnownLocation(provider) }.getOrNull()?.let { if (mutableLocation.value == null) update(it) }
        mutableBusy.value = true
        LocationManagerCompat.getCurrentLocation(manager, provider, null as android.os.CancellationSignal?, ContextCompat.getMainExecutor(context)) { fix: Location? ->
            mutableBusy.value = false
            fix?.let(::update)
        }
    }

    private fun update(fix: Location) {
        mutableLocation.value = fix
        if (!Geocoder.isPresent()) return
        scope.launch {
            @Suppress("DEPRECATION") // The async listener API is 33+; this runs off the main thread.
            val found = runCatching { Geocoder(context, Locale.JAPAN).getFromLocation(fix.latitude, fix.longitude, 1)?.firstOrNull() }.getOrNull()
            mutableAddress.value = found?.let { listOfNotNull(it.locality ?: it.subAdminArea, it.subLocality ?: it.thoroughfare).joinToString(" ").ifBlank { null } }
        }
    }
}
