package ru.bkphenix.app.ui.map

import ru.bkphenix.app.ui.theme.LocalIsOled
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import kotlinx.coroutines.delay
import ru.bkphenix.app.ui.theme.LocalStatusColors
import ru.bkphenix.core.PhenixSolution
import ru.bkphenix.core.integrity.PointTrust
import ru.bkphenix.core.integrity.PointTrustLevel
import ru.bkphenix.core.track.HeldPosition
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
import ru.bkphenix.app.ui.components.AnchorPrompt
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
    val heading by viewModel.heading.collectAsStateWithLifecycle()
    val logState by viewModel.logState.collectAsStateWithLifecycle()
    var follow by remember { mutableStateOf(true) }
    var placingAnchor by remember { mutableStateOf(false) }
    var layersOpen by remember { mutableStateOf(false) }
    var bearing by remember { mutableStateOf(0.0) }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }

    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context).apply {
            getMapAsync { m ->
                m.uiSettings.isAttributionEnabled = true
                m.uiSettings.isLogoEnabled = false
                // Свой компас (кнопка справа сверху), встроенный выключен.
                m.uiSettings.isCompassEnabled = false
                // Атрибуция — внизу слева над панелью скорости не нужна: прижимаем к верху под переключателями.
                m.uiSettings.attributionGravity = android.view.Gravity.TOP or android.view.Gravity.START
                m.uiSettings.setAttributionMargins(24, 150, 0, 0)
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

    val showSources by viewModel.showSources.collectAsStateWithLifecycle()
    LaunchedEffect(style, solution, held, track, road, showSources) {
        style?.let { MapLayers.update(it, solution, held, track, road.takeIf { motionMode != MotionMode.NONE }, showSources) }
    }
    // «Север вверх»: поворот пальцами выключен, чтобы карта случайно не разворачивалась; «По курсу» — включён.
    LaunchedEffect(map, northUp) {
        val m = map ?: return@LaunchedEffect
        m.uiSettings.isRotateGesturesEnabled = !northUp
        if (northUp && m.cameraPosition.bearing != 0.0) {
            m.easeCamera(CameraUpdateFactory.bearingTo(0.0), EASE_MILLIS)
        }
    }
    // Камера следует за позицией одним циклом: точка и поворот вместе, чтобы анимации не перебивали друг друга.
    // «По курсу»: в машине на скорости — курс движения, иначе — компас (куда смотрит верх телефона).
    // До 0.8 поворот брался только из курса ГНСС в режиме «Авто», пешком и без режима карта всегда стояла севером вверх.
    val latestHeld by rememberUpdatedState(held)
    val latestHeading by rememberUpdatedState(heading)
    LaunchedEffect(map, follow, viewMode, motionMode, northUp, placingAnchor) {
        val m = map ?: return@LaunchedEffect
        if (viewMode != MapViewMode.LOCATION || !follow || placingAnchor) return@LaunchedEffect
        var first = true
        while (true) {
            latestHeld?.let { h ->
                val cam = m.cameraPosition
                val zoom = when (motionMode) {
                    MotionMode.CAR -> maxOf(cam.zoom, 15.0)
                    MotionMode.WALK -> maxOf(cam.zoom, 16.5)
                    MotionMode.NONE -> cam.zoom
                }
                val targetBearing = if (northUp) 0.0 else courseBearing(h, latestHeading, motionMode) ?: cam.bearing
                val moved = cam.target?.let { GeoPoint(it.latitude, it.longitude).distanceTo(h.point) } ?: Double.MAX_VALUE
                if (first || moved > 0.5 || angleDiff(cam.bearing, targetBearing) > 2.0 || zoom != cam.zoom) {
                    val update = CameraPosition.Builder().target(LatLng(h.point.latitude, h.point.longitude)).zoom(zoom).bearing(targetBearing).build()
                    m.easeCamera(CameraUpdateFactory.newCameraPosition(update), if (first) EASE_MILLIS else FOLLOW_STEP_MILLIS)
                }
                first = false
            }
            delay(FOLLOW_STEP_MILLIS.toLong())
        }
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

        // Сверху слева — компактные переключатели: что показывать и как привязывать.
        Row(
            Modifier.align(Alignment.TopStart).padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Pill {
                ModeIcon(viewMode == MapViewMode.LOCATION, R.drawable.ic_my_location, MapViewMode.LOCATION.displayName) { viewModel.setViewMode(MapViewMode.LOCATION) }
                ModeIcon(viewMode == MapViewMode.TRACK, R.drawable.ic_timeline, MapViewMode.TRACK.displayName) { viewModel.setViewMode(MapViewMode.TRACK) }
            }
            Pill {
                MotionMode.entries.forEach { mode ->
                    ModeIcon(motionMode == mode, mode.icon(), mode.displayName) { viewModel.setMotionMode(mode) }
                }
            }
        }

        // Сверху справа — компас и слои (рельеф, дороги, карта).
        Column(
            Modifier.align(Alignment.TopEnd).padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.End,
        ) {
            CompassButton(bearing, northUp) { viewModel.setNorthUp(!northUp) }
            Box {
                RoundButton(R.drawable.ic_layers, stringResource(R.string.map_layers)) { layersOpen = true }
                DropdownMenu(expanded = layersOpen, onDismissRequest = { layersOpen = false }) {
                    LayerItem(R.drawable.ic_terrain, stringResource(R.string.map_layer_terrain), terrainLabel(terrain)) {
                        layersOpen = false; terrainPicker.launch(arrayOf("*/*"))
                    }
                    LayerItem(R.drawable.ic_road, stringResource(R.string.map_layer_roads), roadsLabel(roadGraph)) {
                        layersOpen = false; roadPicker.launch(arrayOf("*/*"))
                    }
                    LayerItem(R.drawable.ic_map, stringResource(R.string.map_layer_map), stringResource(R.string.map_layer_ready)) {
                        layersOpen = false; onReimport()
                    }
                    LayerItem(
                        R.drawable.ic_layers, stringResource(R.string.map_layer_sources),
                        stringResource(if (showSources) R.string.map_layer_on else R.string.map_layer_off),
                    ) { layersOpen = false; viewModel.toggleSources() }
                }
            }
        }

        // Предложение якоря на остановке — по центру снизу, над панелью скорости.
        AnchorPrompt(
            solution?.anchor, viewModel::confirmAnchorSuggestion, viewModel::dismissAnchorSuggestion,
            Modifier.align(Alignment.TopCenter).padding(top = 64.dp, start = 16.dp, end = 16.dp),
        )
        // Снизу слева — скорость, точность и сигналы целостности.
        SpeedPanel(
            Modifier.align(Alignment.BottomStart).padding(8.dp),
            held, solution, heightLine(terrain, vertical), roadLine(motionMode, roadGraph, road), vertical, road.takeIf { motionMode != MotionMode.NONE },
        )

        // Снизу справа — действия.
        Column(
            Modifier.align(Alignment.BottomEnd).padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.End,
        ) {
            if (placingAnchor) {
                ExtendedFloatingActionButton(
                    onClick = {
                        map?.cameraPosition?.target?.let { target ->
                            viewModel.setAnchor(GeoPoint(target.latitude, target.longitude))
                            Toast.makeText(context, R.string.map_anchor_set, Toast.LENGTH_SHORT).show()
                        }
                        placingAnchor = false
                        follow = true
                    },
                    icon = { Icon(painterResource(R.drawable.ic_anchor), null) },
                    text = { Text(stringResource(R.string.map_anchor_confirm)) },
                )
                OutlinedButton(onClick = { placingAnchor = false; follow = true }) { Text(stringResource(R.string.map_anchor_cancel)) }
            } else {
                if (!follow && viewMode == MapViewMode.LOCATION) {
                    RoundButton(R.drawable.ic_my_location, stringResource(R.string.map_center)) { follow = true }
                }
                if (solution?.anchor?.anchor != null) {
                    RoundButton(null, stringResource(R.string.map_anchor_clear), text = "✕") { viewModel.clearAnchor() }
                }
                RoundButton(R.drawable.ic_anchor, stringResource(if (solution?.anchor?.anchor != null) R.string.map_anchor_move else R.string.map_anchor_place)) {
                    // Начинаем с текущей позиции (или там, где сейчас карта); дальше вы двигаете карту под перекрестие.
                    follow = false
                    placingAnchor = true
                    viewModel.setViewMode(MapViewMode.LOCATION)
                    held?.let { h -> map?.easeCamera(CameraUpdateFactory.newLatLng(LatLng(h.point.latitude, h.point.longitude)), EASE_MILLIS) }
                    Toast.makeText(context, R.string.map_anchor_hint, Toast.LENGTH_LONG).show()
                }
                RecordButton(recording.recording, recording.points, logState.bytes, viewModel::toggleTrack)
            }
        }
    }
}

/** Курс для режима «По курсу»: в машине на скорости — курс движения, иначе компас; null — нечем повернуть. */
private fun courseBearing(h: HeldPosition, compass: Double?, mode: MotionMode): Double? {
    val moving = h.bearingDeg?.takeIf { h.speedMps >= COURSE_MIN_SPEED_MPS }
    return if (mode == MotionMode.CAR) moving ?: compass else compass ?: moving
}

private fun angleDiff(a: Double, b: Double): Double {
    val d = ((a - b) % 360 + 540) % 360 - 180
    return abs(d)
}

private fun MotionMode.icon(): Int = when (this) {
    MotionMode.NONE -> R.drawable.ic_block
    MotionMode.WALK -> R.drawable.ic_walk
    MotionMode.CAR -> R.drawable.ic_car
}

@Composable
private fun Pill(content: @Composable RowScope.() -> Unit) {
    Surface(shape = CircleShape, tonalElevation = 3.dp, shadowElevation = 3.dp, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)) {
        Row(Modifier.padding(3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), content = content)
    }
}

@Composable
private fun ModeIcon(selected: Boolean, icon: Int, description: String, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        Modifier.size(36.dp).clip(CircleShape).background(bg).clickable(onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(painterResource(icon), contentDescription = description, tint = fg, modifier = Modifier.size(20.dp)) }
}

@Composable
private fun RoundButton(icon: Int?, description: String, text: String? = null, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        tonalElevation = 3.dp,
        shadowElevation = 3.dp,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
        modifier = Modifier.size(44.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (icon != null) Icon(painterResource(icon), contentDescription = description, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(22.dp))
            else Text(text ?: "", style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** Компас: красная стрелка — север. Нажатие — «Север вверх» ⇄ «По курсу» (подпись под стрелкой). */
@Composable
private fun CompassButton(bearing: Double, northUp: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        tonalElevation = 3.dp,
        shadowElevation = 3.dp,
        color = if (northUp) MaterialTheme.colorScheme.surface.copy(alpha = 0.95f) else MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.size(52.dp),
    ) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(
                painterResource(R.drawable.ic_navigation),
                contentDescription = stringResource(if (northUp) R.string.map_north_up else R.string.map_course_up),
                tint = LocalStatusColors.current.bad,
                modifier = Modifier.size(22.dp).rotate(-bearing.toFloat()),
            )
            Text(if (northUp) "С" else stringResource(R.string.map_course_short), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun LayerItem(icon: Int, title: String, status: String, onClick: () -> Unit) {
    DropdownMenuItem(
        leadingIcon = { Icon(painterResource(icon), contentDescription = null) },
        text = {
            Column {
                Text(title)
                Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        onClick = onClick,
    )
}

@Composable
private fun terrainLabel(t: TerrainState): String = when (t) {
    is TerrainState.Ready -> stringResource(R.string.map_layer_ready)
    is TerrainState.Importing -> stringResource(R.string.map_importing, t.copiedBytes / (1 shl 20))
    is TerrainState.Loading -> stringResource(R.string.map_terrain_loading)
    is TerrainState.Failed -> t.message
    is TerrainState.Missing -> stringResource(R.string.map_layer_missing)
}

@Composable
private fun roadsLabel(g: RoadGraphState): String = when (g) {
    is RoadGraphState.Ready -> stringResource(R.string.map_layer_ready)
    is RoadGraphState.Importing -> stringResource(R.string.map_importing, g.copiedBytes / (1 shl 20))
    is RoadGraphState.Loading -> stringResource(R.string.map_roads_loading)
    is RoadGraphState.Failed -> g.message
    is RoadGraphState.Missing -> stringResource(R.string.map_layer_missing)
}

/** Запись трека и журнала: красная кнопка, во время записи — квадрат «стоп», число точек и объём журнала. */
@Composable
private fun RecordButton(recording: Boolean, points: Int, logBytes: Long, onClick: () -> Unit) {
    val red = LocalStatusColors.current.bad
    if (recording) {
        ExtendedFloatingActionButton(
            onClick = onClick,
            containerColor = red,
            contentColor = Color.White,
            icon = { Icon(painterResource(R.drawable.ic_stop), contentDescription = stringResource(R.string.action_track_stop)) },
            text = { Text(stringResource(R.string.map_recording, points, (logBytes / (1 shl 20)).toInt())) },
        )
    } else {
        FloatingActionButton(onClick = onClick, containerColor = MaterialTheme.colorScheme.surface, contentColor = red) {
            Icon(painterResource(R.drawable.ic_record), contentDescription = stringResource(R.string.action_track_start), modifier = Modifier.size(26.dp))
        }
    }
}

/**
 * Панель внизу слева: крупная скорость, точность (цвет — доверие к точке), HPL и чипы высоты и дороги.
 * Нажатие раскрывает подробные строки высоты и привязки.
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun SpeedPanel(
    modifier: Modifier,
    held: HeldPosition?,
    solution: PhenixSolution?,
    heightText: Pair<String, Boolean>?,
    roadText: Pair<String, Boolean>?,
    vertical: VerticalCheck?,
    road: MatchResult?,
) {
    var expanded by remember { mutableStateOf(false) }
    val status = LocalStatusColors.current
    val trust = PointTrust.of(solution)
    val trustColor = when (trust.level) {
        PointTrustLevel.RELIABLE -> status.ok
        PointTrustLevel.LIMITED, PointTrustLevel.UNRELIABLE -> status.warn
        PointTrustLevel.ALARM -> status.bad
        PointTrustLevel.NONE -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        onClick = { expanded = !expanded },
        modifier = modifier.widthIn(max = 240.dp),
        shape = MaterialTheme.shapes.large,
        tonalElevation = 3.dp,
        shadowElevation = 3.dp,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (held == null) {
                Text(stringResource(R.string.map_no_position), style = MaterialTheme.typography.titleMedium, color = status.bad)
                Text(trust.reason, style = MaterialTheme.typography.bodySmall)
            } else {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text((held.speedMps * 3.6).fmt(0), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)
                    Text(" км/ч", Modifier.padding(bottom = 6.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.padding(bottom = 4.dp)) {
                        Text("±${ErrorCircle.radius(held.accuracyM).fmt(0)} м", style = MaterialTheme.typography.titleMedium, color = trustColor, fontWeight = FontWeight.SemiBold)
                        Text(trust.level.displayName, style = MaterialTheme.typography.labelSmall, color = trustColor)
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    solution?.gnss?.raim?.horizontalProtectionLevelM?.let { Chip("HPL ${it.fmt(0)} м", null) }
                    if (held.deadReckoning) Chip(stringResource(R.string.map_chip_dr), status.warn)
                    else if (held.extrapolated) Chip(stringResource(R.string.map_chip_held), status.warn)
                    vertical?.residualM?.let { r ->
                        val bad = vertical?.thresholdM?.let { abs(r) > it } == true
                        Chip("Δh ${if (r >= 0) "+" else ""}${r.fmt(0)} м", if (bad) status.bad else status.ok)
                    }
                    road?.let { m ->
                        Chip(
                            m.distanceToRoadM?.let { "⟂ ${it.fmt(0)} м" } ?: "⟂ —",
                            if (m.offRoad) status.bad else status.ok,
                        )
                    }
                }
            }
            if (expanded) {
                heightText?.let { (t, alarm) -> Text(t, style = MaterialTheme.typography.bodySmall, color = if (alarm) status.bad else MaterialTheme.colorScheme.onSurface) }
                roadText?.let { (t, alarm) -> Text(t, style = MaterialTheme.typography.bodySmall, color = if (alarm) status.bad else MaterialTheme.colorScheme.onSurface) }
                if (held != null) Text(trust.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Chip(text: String, color: Color?) {
    val c = color ?: MaterialTheme.colorScheme.onSurfaceVariant
    Surface(shape = CircleShape, color = c.copy(alpha = 0.14f), border = BorderStroke(1.dp, c.copy(alpha = 0.6f))) {
        Text(text, Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelMedium, color = c)
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
/** Шаг слежения камеры: точка и поворот обновляются вместе, плавно. */
private const val FOLLOW_STEP_MILLIS = 400
/** Ниже этой скорости курс движения шумит — поворачиваем по компасу. */
private const val COURSE_MIN_SPEED_MPS = 2.0
