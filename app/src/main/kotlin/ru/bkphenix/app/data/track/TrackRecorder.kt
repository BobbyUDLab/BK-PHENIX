package ru.bkphenix.app.data.track

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.bkphenix.core.PhenixSolution
import ru.bkphenix.core.geo.GeoPoint
import ru.bkphenix.core.track.GpxWriter
import ru.bkphenix.core.track.TrackPoint
import ru.bkphenix.core.track.TrackThinner
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class TrackRecordingState(val trackId: Long? = null, val points: Int = 0) {
    val recording: Boolean get() = trackId != null
}

/**
 * Пишет итоговые решения приложения в Room, пока идёт запись. Живёт в масштабе процесса, поэтому
 * трек не прерывается при смене экрана; незакрытые треки после убийства процесса закрываются при старте.
 */
class TrackRecorder(
    private val dao: TrackDao,
    private val solutions: Flow<PhenixSolution?>,
    private val gpxDir: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(TrackRecordingState())
    val state: StateFlow<TrackRecordingState> = _state.asStateFlow()
    val tracks: Flow<List<TrackSummary>> = dao.summaries()
    private var job: Job? = null

    init {
        scope.launch { dao.closeDangling() }
    }

    fun start() {
        if (job != null) return
        val startedAt = clock()
        job = scope.launch {
            val id = dao.insert(TrackEntity(name = "Трек ${nameFormat.format(Date(startedAt))}", startedAt = startedAt))
            _state.value = TrackRecordingState(id, 0)
            val thinner = TrackThinner()
            solutions.filterNotNull().collect { s ->
                val point = s.point ?: return@collect
                val tp = TrackPoint(clock(), point, s.accuracyM, s.integrity.state.name)
                if (thinner.accept(tp)) {
                    dao.insertPoint(tp.toEntity(id))
                    _state.value = TrackRecordingState(id, _state.value.points + 1)
                }
            }
        }
    }

    fun stop() {
        val id = _state.value.trackId
        job?.cancel()
        job = null
        _state.value = TrackRecordingState()
        if (id != null) scope.launch { dao.finish(id, clock()) }
    }

    fun delete(id: Long) {
        scope.launch { dao.delete(id) }
    }

    /** Точки последнего сохранённого трека или null, если треков нет. */
    fun latestPoints(): Flow<List<TrackPointEntity>> = dao.latestPointsFlow()

    /** Точки трека для карты. */
    fun points(id: Long): Flow<List<TrackPointEntity>> = dao.pointsFlow(id)

    /** Пишет GPX в каталог экспорта и возвращает файл, или null, если трек пуст. */
    suspend fun exportGpx(id: Long): File? = withContext(Dispatchers.IO) {
        val track = dao.track(id) ?: return@withContext null
        val points = dao.points(id).map { it.toTrackPoint() }
        if (points.isEmpty()) return@withContext null
        gpxDir.mkdirs()
        gpxDir.listFiles()?.forEach { it.delete() } // экспорт временный: держим только последний файл
        File(gpxDir, "bk-phenix-${fileFormat.format(Date(track.startedAt))}.gpx").apply {
            writeText(GpxWriter.write(track.name, points))
        }
    }

    private companion object {
        val nameFormat = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.ROOT)
        val fileFormat = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT)
    }
}

internal fun TrackPoint.toEntity(trackId: Long) = TrackPointEntity(
    trackId = trackId,
    time = timeMillis,
    latitude = point.latitude,
    longitude = point.longitude,
    altitude = point.altitude,
    accuracyM = accuracyM,
    integrity = integrity,
)

internal fun TrackPointEntity.toTrackPoint() = TrackPoint(time, GeoPoint(latitude, longitude, altitude), accuracyM, integrity)
