package ru.bkphenix.app.ui.map

import ru.bkphenix.app.ui.theme.LocalIsOled
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import ru.bkphenix.app.R
import ru.bkphenix.app.data.map.MapFileState
import ru.bkphenix.app.data.roads.RoadGraphState
import ru.bkphenix.app.data.terrain.TerrainState
import ru.bkphenix.core.terrain.VerticalCheck
import kotlin.math.abs
import ru.bkphenix.app.data.roads.RoadGraphStore
import ru.bkphenix.core.roads.MapMatcher
import ru.bkphenix.core.roads.MatchResult
import ru.bkphenix.app.ui.components.fmt
import ru.bkphenix.core.geo.ErrorCircle
import ru.bkphenix.core.geo.GeoPoint
import ru.bkphenix.core.track.MotionMode
import java.io.File

@Composable
fun MapScreen(viewModel: MapViewModel) {
    val mapFile by viewModel.mapFile.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::importMap)
    }
    when (val f = mapFile) {
        is MapFileState.Ready -> MapContent(viewModel, f.file, onReimport = { picker.launch(arrayOf("*/*")) })
        else -> Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.map_missing_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.map_missing_text), style = MaterialTheme.typography.bodyMedium)
            when (f) {
                is MapFileState.Importing -> Text(stringResource(R.string.map_importing, f.copiedBytes / (1 shl 20)))
                is MapFileState.Failed -> Text(f.message, color = MaterialTheme.colorScheme.error)
                else -> Unit
            }
            Button(onClick = { picker.launch(arrayOf("*/*")) }, enabled = f !is MapFileState.Importing) {
                Text(stringResource(R.string.map_import))
            }
        }
    }
}

@Composable
private fun MapContent(viewModel: MapViewModel, file: File, onReimport: () -> Unit) {
    val context = LocalContext.current
    val solution by viewModel.solution.collectAsStateWithLifecycle()
    val held by viewModel.held.collectAsStateWithLifecycle()
    val track by viewModel.track.collectAsStateWithLifecycle()
    val motionMode by viewModel.motionMode.collectAsStateWithLifecycle()
    val viewMode by viewModel.viewMode.collectAsStateWithLifecycle()
    val recording by viewModel.recording.collectAsStateWithLifecycle()
    val road by viewModel.roadMatch.collectAsStateWithLifecycle()
    val roadGraph by viewModel.roadGraph.collectAsStateWithLifecycle()
    val terrain by viewModel.terrain.collectAsStateWithLifecycle()
    val vertical by viewModel.vertical.collectAsStateWithLifecycle()
    val terrainPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::importTerrain)
    }
    val roadPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::importRoads)
    }
    val northUp by viewModel.northUp.collectAsStateWithLifecycle()
    var follow by remember { mutableStateOf(true) }
    var placingAnchor by remember { mutableStateOf(false) }
    var bearing by remember { mutableStateOf(0.0) }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }

    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context).apply {
            getMapAsync { m ->
                m.uiSettings.isAttributionEnabled = true
                m.uiSettings.isLogoEnabled = false
                // Свой компас: кнопка «Север вверх / по курсу» (встроенный скрывается при севере вверху).
                m.uiSettings.isCompassEnabled = false
                m.addOnCameraMoveListener { bearing = m.cameraPosition.bearing }
                m.cameraPosition = CameraPosition.Builder().target(LatLng(MOSCOW.latitude, MOSCOW.longitude)).zoom(10.0).build()
                m.addOnCameraMoveStartedListener { reason ->
                    if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) follow = false
                }
                m.addOnMapLongClickListener { latLng ->
                    viewModel.setAnchor(GeoPoint(latLng.latitude, latLng.longitude))
                    Toast.makeText(context, R.string.map_anchor_set, Toast.LENGTH_SHORT).show()
                    true
                }
                map = m
            }
        }
    }
    MapViewLifecycle(mapView)

    // Стиль карты следует за темой: при смене темы стиль и слои приложения ставятся заново.
    val oled = LocalIsOled.current
    LaunchedEffect(map, oled) {
        val m = map ?: return@LaunchedEffect
        style = null
        val asset = if (oled) "map/style-ru-dark.json" else "map/style-ru.json"
        val json = context.assets.open(asset).bufferedReader().use { it.readText() }
            .replace("{PMTILES}", "file://${file.absolutePath}")
        m.setStyle(Style.Builder().fromJson(json)) { s ->
            MapLayers.install(s, oled)
            style = s
        }
    }

    LaunchedEffect(style, solution, held, track, road) {
        style?.let { MapLayers.update(it, solution, held, track, road.takeIf { motionMode != MotionMode.NONE }) }
    }
    // «Север вверх»: поворот пальцами выключен, чтобы карта случайно не разворачивалась.
    LaunchedEffect(map, northUp) {
        val m = map ?: return@LaunchedEffect
        m.uiSettings.isRotateGesturesEnabled = !northUp
        if (northUp && m.cameraPosition.bearing != 0.0) {
            m.easeCamera(CameraUpdateFactory.bearingTo(0.0), EASE_MILLIS)
        }
    }
    // Камера: в режиме «Местоположение» следует за позицией; в «Авто» — по курсу, если не «Север вверх».
    LaunchedEffect(map, held, follow, viewMode, motionMode, northUp, placingAnchor) {
        val m = map ?: return@LaunchedEffect
        val h = held ?: return@LaunchedEffect
        if (viewMode != MapViewMode.LOCATION || !follow || placingAnchor) return@LaunchedEffect
        val camera = CameraPosition.Builder().target(LatLng(h.point.latitude, h.point.longitude))
        when (motionMode) {
            MotionMode.CAR -> camera.zoom(maxOf(m.cameraPosition.zoom, 15.0))
                .bearing(if (northUp) 0.0 else h.bearingDeg ?: m.cameraPosition.bearing)
            MotionMode.WALK -> camera.zoom(maxOf(m.cameraPosition.zoom, 16.5)).bearing(if (northUp) 0.0 else m.cameraPosition.bearing)
            MotionMode.NONE -> camera.zoom(m.cameraPosition.zoom).bearing(if (northUp) 0.0 else m.cameraPosition.bearing)
        }
        m.easeCamera(CameraUpdateFactory.newCameraPosition(camera.build()), EASE_MILLIS)
    }
    LaunchedEffect(map, viewMode) {
        val m = map ?: return@LaunchedEffect
        if (viewMode == MapViewMode.TRACK && track.size >= 2) {
            val bounds = LatLngBounds.Builder().includes(track.map { LatLng(it.point.latitude, it.point.longitude) }).build()
            m.easeCamera(CameraUpdateFactory.newLatLngBounds(bounds, 80), EASE_MILLIS)
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
        if (placingAnchor) {
            // Перекрестие в центре карты: якорь ставится туда, куда вы сдвинули карту.
            Text(
                "+",
                Modifier.align(Alignment.Center),
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Light,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Surface(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(8.dp),
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 3.dp,
            shadowElevation = 3.dp,
        ) {
            Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MapViewMode.entries.forEach { mode ->
                        FilterChip(selected = viewMode == mode, onClick = { viewModel.setViewMode(mode) }, label = { Text(mode.displayName) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MotionMode.entries.forEach { mode ->
                        FilterChip(selected = motionMode == mode, onClick = { viewModel.setMotionMode(mode) }, label = { Text(mode.displayName) })
                    }
                }
                val h = held
                Text(
                    when {
                        h == null -> stringResource(R.string.map_no_position)
                        else -> stringResource(R.string.map_status, ErrorCircle.radius(h.accuracyM).fmt(0), (h.speedMps * 3.6).fmt(0)) +
                            (solution?.gnss?.raim?.horizontalProtectionLevelM?.let { " · " + stringResource(R.string.map_hpl, it.fmt(0)) } ?: "") +
                            (if (h.deadReckoning) " · " + stringResource(R.string.map_dead_reckoning) else if (h.extrapolated) " · " + stringResource(R.string.map_held) else "")
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                heightLine(terrain, vertical)?.let { (text, alarm) ->
                    Text(text, style = MaterialTheme.typography.bodySmall, color = if (alarm) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                }
                roadLine(motionMode, roadGraph, road)?.let { (text, alarm) ->
                    Text(text, style = MaterialTheme.typography.bodySmall, color = if (alarm) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                }
            }
        }
        Column(
            Modifier.align(Alignment.BottomEnd).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.End,
        ) {
            // Компас: стрелка показывает север; нажатие — «Север вверх» ⇄ «По курсу».
            SmallFloatingActionButton(onClick = { viewModel.setNorthUp(!northUp) }) {
                Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("▲", Modifier.size(20.dp).rotate(-bearing.toFloat()), color = MaterialTheme.colorScheme.error)
                    Text(
                        stringResource(if (northUp) R.string.map_north_up else R.string.map_course_up),
                        Modifier.padding(start = 6.dp),
                    )
                }
            }
            if (placingAnchor) {
                SmallFloatingActionButton(onClick = {
                    map?.cameraPosition?.target?.let { target ->
                        viewModel.setAnchor(GeoPoint(target.latitude, target.longitude))
                        Toast.makeText(context, R.string.map_anchor_set, Toast.LENGTH_SHORT).show()
                    }
                    placingAnchor = false
                    follow = true
                }) { Text(stringResource(R.string.map_anchor_confirm), Modifier.padding(8.dp)) }
                OutlinedButton(onClick = { placingAnchor = false; follow = true }) { Text(stringResource(R.string.map_anchor_cancel)) }
            } else {
                if (!follow && viewMode == MapViewMode.LOCATION) {
                    SmallFloatingActionButton(onClick = { follow = true }) { Text(stringResource(R.string.map_center), Modifier.padding(8.dp)) }
                }
                SmallFloatingActionButton(onClick = {
                    // Начинаем с текущей позиции (или там, где сейчас карта); дальше вы двигаете карту под перекрестие.
                    follow = false
                    placingAnchor = true
                    viewModel.setViewMode(MapViewMode.LOCATION)
                    held?.let { h -> map?.easeCamera(CameraUpdateFactory.newLatLng(LatLng(h.point.latitude, h.point.longitude)), EASE_MILLIS) }
                    Toast.makeText(context, R.string.map_anchor_hint, Toast.LENGTH_LONG).show()
                }) { Text(stringResource(if (solution?.anchor?.anchor != null) R.string.map_anchor_move else R.string.map_anchor_place), Modifier.padding(8.dp)) }
                if (solution?.anchor?.anchor != null) {
                    SmallFloatingActionButton(onClick = viewModel::clearAnchor) { Text(stringResource(R.string.map_anchor_clear), Modifier.padding(8.dp)) }
                }
            }
            SmallFloatingActionButton(onClick = viewModel::toggleTrack) {
                Text(
                    if (recording.recording) stringResource(R.string.map_track_stop, recording.points) else stringResource(R.string.action_track_start),
                    Modifier.padding(8.dp),
                )
            }
        }
        // Замена файла карты (новый регион или обновление) — редкое действие, кнопка в углу.
        Column(Modifier.align(Alignment.BottomStart).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallFloatingActionButton(onClick = { terrainPicker.launch(arrayOf("*/*")) }) {
                Text(stringResource(if (terrain is TerrainState.Ready) R.string.map_terrain_reimport else R.string.map_terrain_import), Modifier.padding(8.dp))
            }
            SmallFloatingActionButton(onClick = { roadPicker.launch(arrayOf("*/*")) }) {
                Text(stringResource(if (roadGraph is RoadGraphState.Ready) R.string.map_roads_reimport else R.string.map_roads_import), Modifier.padding(8.dp))
            }
            SmallFloatingActionButton(onClick = onReimport) {
                Text(stringResource(R.string.map_reimport), Modifier.padding(8.dp))
            }
        }
    }
}

/** Строка высоты: ГНСС над геоидом, барометр, рельеф и расхождение; true — расхождение выше порога. */
@Composable
private fun heightLine(terrain: TerrainState, v: VerticalCheck?): Pair<String, Boolean>? = when (terrain) {
    is TerrainState.Missing -> stringResource(R.string.map_terrain_missing) to false
    is TerrainState.Importing -> stringResource(R.string.map_importing, terrain.copiedBytes / (1 shl 20)) to false
    is TerrainState.Loading -> stringResource(R.string.map_terrain_loading) to false
    is TerrainState.Failed -> terrain.message to true
    is TerrainState.Ready -> v?.let {
        val parts = listOfNotNull(
            it.gnssOrthometricM?.let { h -> stringResource(R.string.map_height_gnss, h.fmt(0)) },
            it.barometric?.let { b -> stringResource(R.string.map_height_baro, b.heightM.fmt(0), b.source.displayName) },
            it.terrainM?.let { h -> stringResource(R.string.map_height_terrain, h.fmt(0)) },
            it.residualM?.let { r -> stringResource(R.string.map_height_residual, r.fmt(0), it.thresholdM!!.fmt(0)) },
        )
        if (parts.isEmpty()) null
        else (stringResource(R.string.map_height_prefix) + " " + parts.joinToString(" · ")) to (it.residualM != null && abs(it.residualM!!) > it.thresholdM!!)
    }
}

/** Строка привязки: расстояние от исходной точки до дороги; true — вне дорог (сигнал целостности). */
@Composable
private fun roadLine(mode: MotionMode, graph: RoadGraphState, road: MatchResult?): Pair<String, Boolean>? {
    if (mode == MotionMode.NONE) return null
    return when (graph) {
        is RoadGraphState.Missing -> stringResource(R.string.map_roads_missing) to false
        is RoadGraphState.Importing -> stringResource(R.string.map_importing, graph.copiedBytes / (1 shl 20)) to false
        is RoadGraphState.Loading -> stringResource(R.string.map_roads_loading) to false
        is RoadGraphState.Failed -> graph.message to true
        is RoadGraphState.Ready -> when {
            road == null -> null
            road.distanceToRoadM == null -> stringResource(R.string.map_roads_far, MapMatcher.SEARCH_M.fmt(0)) to true
            road.offRoad -> stringResource(R.string.map_roads_off, road.distanceToRoadM!!.fmt(0)) to true
            else -> stringResource(R.string.map_roads_on, road.distanceToRoadM!!.fmt(0)) to false
        }?.let { (t, a) -> "$t · ${RoadGraphStore.SOURCE}" to a }
    }
}

@Composable
private fun MapViewLifecycle(mapView: MapView) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, mapView) {
        mapView.onCreate(null)
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
}

private val MOSCOW = GeoPoint(55.7558, 37.6173)
private const val EASE_MILLIS = 800
