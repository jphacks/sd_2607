package org.mndt.app.ui.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.style.sources.GeoJsonSource
import org.mndt.app.ui.theme.Mosimo
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

fun Color.hex() = "#%06X".format(Locale.ROOT, toArgb() and 0xFFFFFF)

private val EMPTY = featureCollection(emptyList())

// GSI pale tiles turned dark in the shader: brightness min/max swapped inverts, saturation -1 drops the tint.
// Tune raster-contrast / brightness here if roads read too faint on device.
private val STYLE = """
{"version":8,
 "sources":{
  "gsi":{"type":"raster","tiles":["https://cyberjapandata.gsi.go.jp/xyz/pale/{z}/{x}/{y}.png"],"tileSize":128,"minzoom":5,"maxzoom":18,"attribution":"地理院タイル"},
  "regions":{"type":"geojson","data":$EMPTY},
  "me":{"type":"geojson","data":$EMPTY}
 },
 "layers":[
  {"id":"bg","type":"background","paint":{"background-color":"${Mosimo.bgBase.hex()}"}},
  {"id":"gsi","type":"raster","source":"gsi","paint":{"raster-brightness-min":0.55,"raster-brightness-max":0.04,"raster-saturation":-1,"raster-contrast":0.1}},
  {"id":"regions-fill","type":"fill","source":"regions","paint":{"fill-color":["get","color"],"fill-opacity":0.16}},
  {"id":"regions-line","type":"line","source":"regions","paint":{"line-color":["get","color"],"line-width":1.5,"line-opacity":0.8,"line-dasharray":[2,2]}},
  {"id":"me-accuracy","type":"fill","source":"me","filter":["==",["geometry-type"],"Polygon"],"paint":{"fill-color":"${Mosimo.info.hex()}","fill-opacity":0.15}},
  {"id":"me","type":"circle","source":"me","filter":["==",["geometry-type"],"Point"],"paint":{"circle-radius":7,"circle-color":"${Mosimo.info.hex()}","circle-stroke-width":3,"circle-stroke-color":"${Mosimo.textPrimary.hex()}"}}
 ]}
"""

/** Camera access for overlays and buttons; [tick] bumps on every camera move so projected markers re-place. */
class MapHandle {
    var map by mutableStateOf<MapLibreMap?>(null)
    var style by mutableStateOf<Style?>(null)
    val tick = mutableIntStateOf(0)
    var height = 0

    /** [bottomFraction] of the view is treated as covered (by a sheet), so the point lands in the visible part. */
    fun moveTo(lat: Double, lng: Double, zoom: Double? = null, bottomFraction: Double = 0.0) {
        val m = map ?: return
        m.animateCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder().target(LatLng(lat, lng))
            .zoom(zoom ?: max(m.cameraPosition.zoom, 14.0)).padding(0.0, 0.0, 0.0, height * bottomFraction).build()))
    }
    fun center(): Pair<Double, Double>? = map?.cameraPosition?.target?.let { it.latitude to it.longitude }
}

/** Places the content so the point at [anchorY] from its top (bottom when null) sits on lat/lng. */
fun Modifier.at(handle: MapHandle, lat: Double, lng: Double, anchorY: Dp? = null) = layout { measurable, constraints ->
    val p = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
    layout(0, 0) {
        handle.tick.intValue
        val pt = handle.map?.projection?.toScreenLocation(LatLng(lat, lng)) ?: return@layout
        p.place(pt.x.roundToInt() - p.width / 2, pt.y.roundToInt() - (anchorY?.roundToPx() ?: p.height))
    }
}

@Composable
fun MapLibreView(handle: MapHandle, regions: String, me: String, onClick: (Double, Double) -> Unit, modifier: Modifier = Modifier, camera: CameraPosition? = null) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val click by rememberUpdatedState(onClick)
    val view = remember {
        MapLibre.getInstance(context)
        // ponytail: ambient cache only (viewed tiles); add OfflineManager regions if whole-area prefetch is needed.
        OfflineManager.getInstance(context).setMaximumAmbientCacheSize(200L * 1024 * 1024, object : OfflineManager.FileSourceCallback {
            override fun onSuccess() {}
            override fun onError(message: String) {}
        })
        MapView(context).apply { onCreate(null) }
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> view.onStart()
                Lifecycle.Event.ON_RESUME -> view.onResume()
                Lifecycle.Event.ON_PAUSE -> view.onPause()
                Lifecycle.Event.ON_STOP -> view.onStop()
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) view.onPause()
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) view.onStop()
            view.onDestroy()
            handle.style = null
            handle.map = null
        }
    }
    LaunchedEffect(view) {
        view.getMapAsync { m ->
            m.uiSettings.apply { isLogoEnabled = false; isAttributionEnabled = false; isRotateGesturesEnabled = false; isTiltGesturesEnabled = false }
            m.cameraPosition = camera ?: CameraPosition.Builder().target(LatLng(36.5, 138.0)).zoom(5.0).build()
            m.addOnCameraMoveListener { handle.tick.intValue++ }
            m.addOnCameraIdleListener { handle.tick.intValue++ }
            m.addOnMapClickListener { click(it.latitude, it.longitude); true }
            m.setStyle(Style.Builder().fromJson(STYLE)) { handle.style = it; handle.map = m; handle.tick.intValue++ }
        }
    }
    val style = handle.style
    LaunchedEffect(style, regions) { style?.getSourceAs<GeoJsonSource>("regions")?.setGeoJson(regions) }
    LaunchedEffect(style, me) { style?.getSourceAs<GeoJsonSource>("me")?.setGeoJson(me) }
    AndroidView({ view }, modifier.onSizeChanged { handle.height = it.height })
}
