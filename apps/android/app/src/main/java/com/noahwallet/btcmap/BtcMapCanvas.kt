package com.noahwallet.btcmap

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.RectF
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.log.Logger
import org.maplibre.android.maps.*
import org.maplibre.android.style.expressions.Expression.*
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import kotlin.math.roundToInt

@Composable
fun BtcMapCanvas(model: BtcMapModel, places: List<BtcMapPlace>, dark: Boolean, sheetTop: Float?,
    styleRevision: Int, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val canvas = remember(context) { MapCanvas(context, model) }
    val features by produceState(FeatureCollection.fromFeatures(emptyArray<Feature>()), places) {
        value = withContext(Dispatchers.Default) {
            FeatureCollection.fromFeatures(places.map { place ->
                Feature.fromGeometry(Point.fromLngLat(place.lon, place.lat)).apply { addNumberProperty("placeID", place.id) }
            })
        }
    }
    DisposableEffect(canvas, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> canvas.start()
                Lifecycle.Event.ON_RESUME -> canvas.resume()
                Lifecycle.Event.ON_PAUSE -> canvas.pause()
                Lifecycle.Event.ON_STOP -> canvas.stop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        val memory = object : ComponentCallbacks2 {
            override fun onConfigurationChanged(newConfig: Configuration) = Unit
            @Deprecated("""Deprecated in Java""")
            override fun onLowMemory() { canvas.view.onLowMemory() }
            // Below API 34 the system still sends running-low levels, where the map should free its tiles early.
            @Suppress("""DEPRECATION""")
            override fun onTrimMemory(level: Int) { if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) canvas.view.onLowMemory() }
        }
        context.registerComponentCallbacks(memory)
        onDispose { lifecycle.removeObserver(observer); context.unregisterComponentCallbacks(memory); canvas.destroy() }
    }
    AndroidView(factory = { canvas.view }, modifier = modifier, update = {
        canvas.update(features, dark, sheetTop, styleRevision)
    })
}

private class MapCanvas(context: Context, private val model: BtcMapModel) {
    val view: MapView
    private var map: MapLibreMap? = null
    private var features = FeatureCollection.fromFeatures(emptyArray<Feature>())
    private var dark: Boolean? = null
    private var styleRevision = -1
    private var cameraRevision = -1
    private var sheetTop: Float? = null
    private var bottomPadding = -1
    private var fallback = false
    private var selectedID: Long? = null
    private var location: List<Double>? = null
    private var moved = false
    private var started = false
    private var resumed = false
    private var destroyed = false
    private val styleTimeout = Runnable { useOfflineStyle() }

    init {
        MapLibre.getInstance(context)
        Logger.setVerbosity(Logger.NONE)
        view = MapView(context).apply { onCreate(null) }
        view.addOnDidFailLoadingMapListener { useOfflineStyle() }
        view.getMapAsync { ready ->
            if (!destroyed) {
                map = ready
                ready.setMinZoomPreference(1.0); ready.setMaxZoomPreference(19.0)
                ready.uiSettings.isRotateGesturesEnabled = false
                ready.uiSettings.isTiltGesturesEnabled = false
                ready.moveCamera(CameraUpdateFactory.newLatLngZoom(model.viewport.latLng(), model.viewport.zoom))
                ready.addOnCameraMoveStartedListener { reason ->
                    moved = reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE
                }
                ready.addOnCameraIdleListener {
                    val bounds = ready.projection.visibleRegion.latLngBounds
                    model.visibleBounds = BtcMapBounds.fromCamera(bounds.longitudeWest, bounds.latitudeSouth,
                        bounds.longitudeEast, bounds.latitudeNorth)
                    if (moved) {
                        val camera = ready.cameraPosition
                        camera.target?.let { model.record(BtcMapViewport(listOf(it.longitude, it.latitude), camera.zoom)) }
                        moved = false
                    }
                }
                ready.addOnMapClickListener { coordinate ->
                    val point = ready.projection.toScreenLocation(coordinate)
                    val radius = 22 * context.resources.displayMetrics.density
                    val area = RectF(point.x - radius, point.y - radius, point.x + radius, point.y + radius)
                    val hits = ready.queryRenderedFeatures(area, "places", "clusters")
                    val hit = hits.firstOrNull()
                    when {
                        hit?.hasProperty("placeID") == true -> {
                            val id = hit.getNumberProperty("placeID").toLong()
                            model.places.firstOrNull { it.id == id }?.let(model::select)
                        }
                        hit?.hasProperty("cluster_id") == true -> {
                            val center = hit.geometry() as? Point
                            val source = ready.style?.getSourceAs<GeoJsonSource>("merchants")
                            if (center != null && source != null) model.move(BtcMapViewport(listOf(center.longitude(), center.latitude()),
                                source.getClusterExpansionZoom(hit).toDouble().coerceIn(1.0, 19.0)))
                        }
                    }
                    hit != null
                }
                loadStyle()
            }
        }
        view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> padding() }
    }

    /** Applies the screen's state; the model's camera, selection and location are read here, inside the view's observed update. */
    fun update(newFeatures: FeatureCollection, newDark: Boolean, newSheetTop: Float?, retry: Int) {
        val revision = model.cameraRevision
        val selected = model.selectedID
        val userLocation = model.userLocation
        val changed = features !== newFeatures
        features = newFeatures
        val styleChanged = dark != newDark || styleRevision != retry
        dark = newDark; styleRevision = retry
        val paddingChanged = sheetTop != newSheetTop
        sheetTop = newSheetTop
        selectedID = selected; location = userLocation
        if (styleChanged) loadStyle()
        else {
            if (changed) map?.style?.getSourceAs<GeoJsonSource>("merchants")?.setGeoJson(features)
            updateSelection()
        }
        if (paddingChanged) padding()
        if (cameraRevision != revision) {
            cameraRevision = revision
            map?.moveCamera(CameraUpdateFactory.newLatLngZoom(model.viewport.latLng(), model.viewport.zoom))
        }
    }

    private fun loadStyle() {
        val ready = map ?: return
        view.removeCallbacks(styleTimeout)
        fallback = false
        model.basemapUnavailable = false
        // MapLibre may retry indefinitely while offline without reporting a failed load.
        view.postDelayed(styleTimeout, 15_000)
        ready.setStyle("https://tiles.openfreemap.org/styles/${if (dark == true) "dark" else "liberty"}", ::configure)
    }
    private fun useOfflineStyle() {
        if (fallback || destroyed) return
        view.removeCallbacks(styleTimeout)
        fallback = true; model.basemapUnavailable = true
        map?.setStyle(Style.Builder().fromJson(offlineStyle()), ::configure)
    }
    private fun offlineStyle(): String {
        val background = if (dark == true) "#161618" else "#f2f2f7"
        val layers = """[{"id":"background","type":"background","paint":{"background-color":"$background"}}]"""
        return """{"version":8,"glyphs":"https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf","sources":{},"layers":$layers}"""
    }

    private fun configure(style: Style) {
        if (destroyed) return
        view.removeCallbacks(styleTimeout)
        style.addSource(GeoJsonSource("merchants", features, GeoJsonOptions().withCluster(true).withClusterRadius(46).withClusterMaxZoom(14)))
        style.addLayer(CircleLayer("clusters", "merchants").withFilter(has("point_count"))
            .withProperties(circleColor("#F7931A"), circleRadius(20f), circleStrokeWidth(2f), circleStrokeColor(Color.WHITE)))
        style.addLayer(SymbolLayer("cluster-count", "merchants").withFilter(has("point_count"))
            .withProperties(textField(toString(get("point_count_abbreviated"))), textSize(13f), textColor(Color.BLACK), textAllowOverlap(true),
                textFont(arrayOf("Noto Sans Regular"))))
        style.addLayer(CircleLayer("places", "merchants").withFilter(not(has("point_count")))
            .withProperties(circleColor("#F7931A"), circleRadius(7f), circleStrokeWidth(2f), circleStrokeColor(Color.WHITE)))
        style.addSource(GeoJsonSource("selection"))
        style.addLayer(CircleLayer("selected-place", "selection").withProperties(circleColor("#F7931A"), circleRadius(11f),
            circleStrokeWidth(3f), circleStrokeColor(Color.BLACK)))
        style.addSource(GeoJsonSource("location"))
        style.addLayer(CircleLayer("user-location", "location").withProperties(circleColor("#007AFF"), circleRadius(8f),
            circleStrokeColor(Color.WHITE), circleStrokeWidth(3f)))
        updateSelection(); padding()
    }

    private fun updateSelection() {
        fun point(center: List<Double>?) = FeatureCollection.fromFeatures(center?.let {
            arrayOf(Feature.fromGeometry(Point.fromLngLat(it[0], it[1])))
        } ?: emptyArray())
        map?.style?.getSourceAs<GeoJsonSource>("selection")?.setGeoJson(point(model.places.firstOrNull { it.id == selectedID }?.center))
        map?.style?.getSourceAs<GeoJsonSource>("location")?.setGeoJson(point(location))
    }
    private fun padding() {
        val ready = map ?: return
        val origin = IntArray(2)
        view.getLocationOnScreen(origin)
        val overlap = sheetTop?.let { (origin[1] + view.height - it).roundToInt().coerceIn(0, (view.height - 1).coerceAtLeast(0)) } ?: 0
        if (bottomPadding == overlap) return
        bottomPadding = overlap
        // Padding and the selection must use the same layout, including sheet chrome/insets.
        ready.moveCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder(ready.cameraPosition)
            .target(model.viewport.latLng()).padding(0.0, 0.0, 0.0, overlap.toDouble()).build()))
    }
    fun start() { if (!started) { view.onStart(); started = true } }
    fun resume() { if (!resumed) { view.onResume(); resumed = true } }
    fun pause() { if (resumed) { view.onPause(); resumed = false } }
    fun stop() { if (started) { view.onStop(); started = false } }
    fun destroy() { destroyed = true; view.removeCallbacks(styleTimeout); pause(); stop(); view.onDestroy() }
}

private fun BtcMapViewport.latLng() = LatLng(center[1], center[0])
