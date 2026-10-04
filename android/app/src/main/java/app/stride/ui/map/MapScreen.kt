package app.stride.ui.map

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.stride.store.BBox
import app.stride.store.PhotoSummary
import app.stride.store.SettingsState
import com.google.android.gms.location.LocationServices
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.expressions.Expression.all
import org.maplibre.android.style.expressions.Expression.eq
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.expressions.Expression.has
import org.maplibre.android.style.expressions.Expression.literal
import org.maplibre.android.style.expressions.Expression.not
import org.maplibre.android.style.expressions.Expression.step
import org.maplibre.android.style.expressions.Expression.stop
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.iconRotate
import org.maplibre.android.style.layers.PropertyFactory.iconRotationAlignment
import org.maplibre.android.style.layers.PropertyFactory.textAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.textColor
import org.maplibre.android.style.layers.PropertyFactory.textField
import org.maplibre.android.style.layers.PropertyFactory.textFont
import org.maplibre.android.style.layers.PropertyFactory.textIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.textSize
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

private const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
private const val SOURCE = "photos"
private const val LAYER_CLUSTER = "photos-cluster"
private const val LAYER_COUNT = "photos-count"
private const val LAYER_WEDGE = "photos-wedge"
private const val LAYER_POINT = "photos-point"
private const val WEDGE_IMAGE = "stride-wedge"

@Composable
fun MapScreen(settings: SettingsState, vm: MapViewModel = viewModel()) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val photos by vm.photos.collectAsStateWithLifecycle()
    var style by remember { mutableStateOf<Style?>(null) }

    val mapView = remember {
        MapView(context).apply {
            onCreate(null)
            getMapAsync { m ->
                m.uiSettings.isCompassEnabled = true
                m.uiSettings.isRotateGesturesEnabled = true
                val restored = vm.camera
                if (restored != null) m.cameraPosition = restored
                else initialCamera(context, m)
                m.setStyle(Style.Builder().fromUri(STYLE_URL)) { s ->
                    setUpLayers(context, s)
                    style = s
                }
                m.addOnCameraIdleListener {
                    vm.camera = m.cameraPosition
                    val b = m.projection.visibleRegion.latLngBounds
                    vm.onViewport(BBox(b.latitudeSouth, b.longitudeWest, b.latitudeNorth, b.longitudeEast))
                }
                m.addOnMapClickListener { latLng -> onMapClick(m, latLng, vm) }
            }
        }
    }

    // forward the screen's lifecycle to the MapView
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    LaunchedEffect(style, photos) {
        val s = style ?: return@LaunchedEffect
        s.getSourceAs<GeoJsonSource>(SOURCE)?.setGeoJson(toFeatures(photos))
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
        Box(Modifier.align(Alignment.TopCenter).statusBarsPadding().fillMaxWidth()) {
            if (vm.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            vm.error?.let {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.align(Alignment.Center).padding(8.dp),
                ) { Text(it, Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall) }
            }
        }
    }

    if (vm.selectedId != null) PhotoSheet(vm, settings)
}

@SuppressLint("MissingPermission")
private fun initialCamera(context: Context, map: MapLibreMap) {
    map.cameraPosition = CameraPosition.Builder().target(LatLng(48.0, 11.0)).zoom(3.0).build()
    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
    if (!granted) return
    LocationServices.getFusedLocationProviderClient(context).lastLocation.addOnSuccessListener { loc ->
        if (loc != null) map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(loc.latitude, loc.longitude), 15.0))
    }
}

private fun setUpLayers(context: Context, style: Style) {
    style.addImage(WEDGE_IMAGE, wedgeBitmap(context))
    style.addSource(
        GeoJsonSource(
            SOURCE,
            FeatureCollection.fromFeatures(emptyList()),
            GeoJsonOptions().withCluster(true).withClusterMaxZoom(15).withClusterRadius(48),
        ),
    )
    val amber = "#FFB300"
    val dark = "#0E3B43"
    style.addLayer(
        CircleLayer(LAYER_CLUSTER, SOURCE)
            .withFilter(has("point_count"))
            .withProperties(
                circleColor(amber),
                circleRadius(step(get("point_count"), literal(14), stop(10, 18), stop(100, 24), stop(1000, 30))),
                circleStrokeColor("#FFFFFF"),
                circleStrokeWidth(2f),
            ),
    )
    style.addLayer(
        SymbolLayer(LAYER_COUNT, SOURCE)
            .withFilter(has("point_count"))
            .withProperties(
                textField(Expression.toString(get("point_count_abbreviated"))),
                textFont(arrayOf("Noto Sans Bold")),
                textSize(12f),
                textColor(dark),
                textAllowOverlap(true),
                textIgnorePlacement(true),
            ),
    )
    // heading wedge, only where the heading was actually measured
    style.addLayer(
        SymbolLayer(LAYER_WEDGE, SOURCE)
            .withFilter(all(not(has("point_count")), eq(get("has_heading"), literal(true))))
            .withProperties(
                iconImage(WEDGE_IMAGE),
                iconRotate(get("heading")),
                iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                iconAllowOverlap(true),
                iconIgnorePlacement(true),
            ),
    )
    style.addLayer(
        CircleLayer(LAYER_POINT, SOURCE)
            .withFilter(not(has("point_count")))
            .withProperties(
                circleColor(dark),
                circleRadius(6f),
                circleStrokeColor("#FFFFFF"),
                circleStrokeWidth(2f),
            ),
    )
}

private fun toFeatures(photos: List<PhotoSummary>): FeatureCollection =
    FeatureCollection.fromFeatures(
        photos.map { p ->
            Feature.fromGeometry(Point.fromLngLat(p.lon, p.lat)).apply {
                addStringProperty("id", p.id)
                addBooleanProperty("has_heading", p.hasHeading && p.heading != null)
                addNumberProperty("heading", p.heading ?: 0.0)
            }
        },
    )

private fun onMapClick(map: MapLibreMap, latLng: LatLng, vm: MapViewModel): Boolean {
    val point = map.projection.toScreenLocation(latLng)
    val area = RectF(point.x - 24, point.y - 24, point.x + 24, point.y + 24)
    val photo = map.queryRenderedFeatures(area, LAYER_POINT, LAYER_WEDGE).firstOrNull()
    if (photo != null) {
        photo.getStringProperty("id")?.let(vm::select)
        return true
    }
    val cluster = map.queryRenderedFeatures(area, LAYER_CLUSTER).firstOrNull()
    if (cluster != null) {
        val p = cluster.geometry() as? Point ?: return true
        map.animateCamera(
            CameraUpdateFactory.newLatLngZoom(LatLng(p.latitude(), p.longitude()), map.cameraPosition.zoom + 2),
        )
        return true
    }
    return false
}

/** A 60° viewing wedge pointing up (north at heading 0), apex at the bitmap centre. */
private fun wedgeBitmap(context: Context): Bitmap {
    val density = context.resources.displayMetrics.density
    val size = (64 * density).toInt()
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val oval = RectF(0f, 0f, size.toFloat(), size.toFloat())
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xB3FFB300.toInt(); style = Paint.Style.FILL }
    val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE65100.toInt(); style = Paint.Style.STROKE; strokeWidth = 1.5f * density
    }
    // Canvas angles: 0° = east, clockwise; "up" is −90°
    c.drawArc(oval.apply { inset(edge.strokeWidth, edge.strokeWidth) }, -120f, 60f, true, fill)
    c.drawArc(oval, -120f, 60f, true, edge)
    return bmp
}
