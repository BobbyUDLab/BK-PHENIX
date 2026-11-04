package ru.bkphenix.app.ui.map

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.bkphenix.app.data.PhenixRepository
import ru.bkphenix.app.data.map.MapFileState
import ru.bkphenix.app.data.map.MapFileStore
import ru.bkphenix.app.data.roads.RoadGraphState
import ru.bkphenix.app.data.roads.RoadGraphStore
import ru.bkphenix.app.data.terrain.TerrainState
import ru.bkphenix.app.data.terrain.TerrainStore
import ru.bkphenix.core.terrain.VerticalCheck
import ru.bkphenix.core.roads.MatchResult
import ru.bkphenix.app.data.track.TrackRecorder
import ru.bkphenix.app.data.track.TrackRecordingState
import ru.bkphenix.app.data.track.toTrackPoint
import ru.bkphenix.app.ui.coordinate.AnchorInput
import ru.bkphenix.core.PhenixSolution
import ru.bkphenix.core.geo.GeoPoint
import ru.bkphenix.core.track.HeldPosition
import ru.bkphenix.core.track.MotionMode
import ru.bkphenix.core.track.MotionModel
import ru.bkphenix.core.inertial.PositionFusion
import ru.bkphenix.core.track.TrackPoint

/** Что показывает карта: следит за позицией или показывает трек целиком. */
enum class MapViewMode(val displayName: String) { LOCATION("Местоположение"), TRACK("Трек") }

class MapViewModel(
    private val repository: PhenixRepository,
    private val recorder: TrackRecorder,
    private val mapFiles: MapFileStore,
    private val roadGraphs: RoadGraphStore,
    private val terrainFiles: TerrainStore,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    val mapFile: StateFlow<MapFileState> = mapFiles.state
    val solution: StateFlow<PhenixSolution?> = repository.solution
    val recording: StateFlow<TrackRecordingState> = recorder.state
    val roadGraph: StateFlow<RoadGraphState> = roadGraphs.state
    val roadMatch: StateFlow<MatchResult?> = repository.roadMatch
    val terrain: StateFlow<TerrainState> = terrainFiles.state
    val vertical: StateFlow<VerticalCheck?> = repository.verticalCheck

    private val motion = MotionModel(MotionMode.CAR).also { repository.motionMode = it.mode }
    private val _motionMode = MutableStateFlow(motion.mode)
    val motionMode: StateFlow<MotionMode> = _motionMode.asStateFlow()
    private val _viewMode = MutableStateFlow(MapViewMode.LOCATION)
    val viewMode: StateFlow<MapViewMode> = _viewMode.asStateFlow()

    /** Позиция для карты: свежая или удержанная по скорости. Пересчитывается раз в секунду. */
    private val _held = MutableStateFlow<HeldPosition?>(null)
    val held: StateFlow<HeldPosition?> = _held.asStateFlow()

    /** Трек: текущий записываемый, иначе последний сохранённый. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val track: StateFlow<List<TrackPoint>> = recorder.state
        .flatMapLatest { s -> s.trackId?.let(recorder::points) ?: recorder.latestPoints() }
        .map { list -> list.map { it.toTrackPoint() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            var lastTime = 0L
            repository.solution.collect { s ->
                val p = s?.point ?: return@collect
                val t = clock()
                if (t != lastTime) motion.update(TrackPoint(t, p, s.accuracyM))
                lastTime = t
                _held.value = current()
            }
        }
        viewModelScope.launch {
            while (true) {
                delay(1_000)
                _held.value = current()
            }
        }
    }

    private fun current() = PositionFusion.choose(motion.position(clock()), repository.deadReckoningEstimate())

    fun setMotionMode(mode: MotionMode) {
        motion.mode = mode
        repository.motionMode = mode
        _motionMode.value = mode
    }

    fun setViewMode(mode: MapViewMode) {
        _viewMode.value = mode
    }

    fun setAnchor(point: GeoPoint) = repository.setAnchor(point, AnchorInput.DEFAULT_ACCURACY_M)

    fun clearAnchor() = repository.clearAnchor()

    fun toggleTrack() = if (recorder.state.value.recording) recorder.stop() else recorder.start()

    fun importTerrain(uri: Uri) {
        viewModelScope.launch { terrainFiles.import(uri) }
    }

    fun importRoads(uri: Uri) {
        viewModelScope.launch { roadGraphs.import(uri) }
    }

    fun importMap(uri: Uri) {
        viewModelScope.launch { mapFiles.import(uri) }
    }
}
