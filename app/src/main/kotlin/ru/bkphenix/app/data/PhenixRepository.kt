package ru.bkphenix.app.data

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.bkphenix.app.data.mapping.CellMapping
import ru.bkphenix.app.data.mapping.LocationMapping
import ru.bkphenix.app.data.mapping.NavigationMessageMapping
import ru.bkphenix.app.data.model.BleSnapshot
import ru.bkphenix.app.data.model.CellSnapshot
import ru.bkphenix.app.data.model.GnssCapabilitiesSnapshot
import ru.bkphenix.app.data.model.MeasurementSummary
import ru.bkphenix.app.data.model.SensorKind
import ru.bkphenix.app.data.model.SensorSnapshot
import ru.bkphenix.app.data.model.SourceStatus
import ru.bkphenix.app.data.model.WifiSnapshot
import ru.bkphenix.app.data.source.BleDataSource
import ru.bkphenix.app.data.source.CellDataSource
import ru.bkphenix.app.data.source.GnssDataSource
import ru.bkphenix.app.data.source.LocationDataSource
import ru.bkphenix.app.data.source.NetworkTimeDataSource
import ru.bkphenix.app.data.source.SensorDataSource
import ru.bkphenix.app.data.source.WifiDataSource
import ru.bkphenix.core.PhenixCore
import ru.bkphenix.core.PhenixSolution
import ru.bkphenix.core.anchor.Anchor
import ru.bkphenix.core.anchor.AnchorMode
import ru.bkphenix.core.geo.GeoPoint
import ru.bkphenix.core.integrity.NavigationWord
import ru.bkphenix.core.model.SatelliteStatus
import ru.bkphenix.core.model.TimeObservation
import ru.bkphenix.core.model.TimeSource
import ru.bkphenix.core.radio.CellLocator
import ru.bkphenix.core.radio.CellTowerDatabase
import ru.bkphenix.core.radio.EmptyCellTowerDatabase

/** Статистика проверки чётности GPS LNAV. */
data class NavigationStats(val words: Long = 0, val parityFailures: Long = 0)

/** Статусы всех источников для экрана «Диагностика». */
data class SourceStatuses(val items: Map<String, SourceStatus> = emptyMap())

/**
 * Единая точка сбора данных: подписывается на все источники, складывает наблюдения
 * и раз в секунду решает эпоху в [PhenixCore]. Запускается foreground-сервисом.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PhenixRepository(
    private val location: LocationDataSource,
    private val gnss: GnssDataSource,
    private val cells: CellDataSource,
    private val wifi: WifiDataSource,
    private val ble: BleDataSource,
    private val sensors: SensorDataSource,
    private val networkTime: NetworkTimeDataSource,
    cellTowers: CellTowerDatabase = EmptyCellTowerDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Ядро не потокобезопасно: все обращения — через один поток. */
    private val coreDispatcher = Dispatchers.Default.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + coreDispatcher)
    private val core = PhenixCore()
    private val cellLocator = CellLocator(cellTowers)
    private val aggregator = ObservationAggregator()
    private val pendingWords = ArrayList<NavigationWord>()
    private var gnssTimeOffsetMs: Long? = null
    private var networkTimeOffsetMs: Long? = null
    private var job: Job? = null

    private val _solution = MutableStateFlow<PhenixSolution?>(null)
    val solution: StateFlow<PhenixSolution?> = _solution.asStateFlow()
    private val _satellites = MutableStateFlow<List<SatelliteStatus>>(emptyList())
    val satellites: StateFlow<List<SatelliteStatus>> = _satellites.asStateFlow()
    private val _measurements = MutableStateFlow<MeasurementSummary?>(null)
    val measurements: StateFlow<MeasurementSummary?> = _measurements.asStateFlow()
    private val _navigation = MutableStateFlow(NavigationStats())
    val navigation: StateFlow<NavigationStats> = _navigation.asStateFlow()
    private val _capabilities = MutableStateFlow<GnssCapabilitiesSnapshot?>(null)
    val capabilities: StateFlow<GnssCapabilitiesSnapshot?> = _capabilities.asStateFlow()
    private val _cells = MutableStateFlow<List<CellSnapshot>>(emptyList())
    val cellList: StateFlow<List<CellSnapshot>> = _cells.asStateFlow()
    private val _wifi = MutableStateFlow<List<WifiSnapshot>>(emptyList())
    val wifiList: StateFlow<List<WifiSnapshot>> = _wifi.asStateFlow()
    private val _ble = MutableStateFlow<List<BleSnapshot>>(emptyList())
    val bleList: StateFlow<List<BleSnapshot>> = _ble.asStateFlow()
    private val _sensors = MutableStateFlow<Map<SensorKind, SensorSnapshot>>(emptyMap())
    val sensorReadings: StateFlow<Map<SensorKind, SensorSnapshot>> = _sensors.asStateFlow()
    private val _networkOffset = MutableStateFlow<Long?>(null)
    val networkTimeOffsetMillis: StateFlow<Long?> = _networkOffset.asStateFlow()
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /** Статусы источников по имени для диагностики. */
    val statusFlows: Map<String, StateFlow<SourceStatus>> = linkedMapOf(
        "Местоположение" to location.status,
        "Спутники" to gnss.status,
        "Сырые измерения" to gnss.measurementsStatus,
        "Навигационные сообщения" to gnss.navigationStatus,
        "Сотовые вышки" to cells.status,
        "Wi-Fi" to wifi.status,
        "Wi-Fi RTT" to wifi.rttStatus,
        "Bluetooth/BLE" to ble.status,
        "Датчики" to sensors.status,
        "Сетевое время" to networkTime.status,
    )

    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            _capabilities.value = gnss.capabilities()
            collectSafely(location.locations()) { raw ->
                LocationMapping.toObservation(raw)?.let(aggregator::put)
                if (raw.provider == "gps") gnssTimeOffsetMs = raw.timeMillis - clock()
            }
            collectSafely(gnss.satellites()) { _satellites.value = it }
            collectSafely(gnss.measurements()) { _measurements.value = it }
            collectSafely(gnss.navigationWords()) { words ->
                pendingWords += words
                val failures = NavigationMessageMapping.parityFailureCount(words)
                _navigation.update { NavigationStats(it.words + words.size, it.parityFailures + failures) }
            }
            collectSafely(cells.cells()) { list ->
                _cells.value = list
                cellLocator.locate(list.map(CellMapping::toMeasurement), clock())?.let(aggregator::put)
            }
            collectSafely(wifi.scans()) { _wifi.value = it }
            collectSafely(ble.beacons()) { _ble.value = it }
            collectSafely(sensors.readings()) { s -> _sensors.update { it + (s.kind to s) } }
            collectSafely(networkTime.offsets()) {
                networkTimeOffsetMs = it
                _networkOffset.value = it
            }
            while (true) {
                solveEpoch()
                delay(EPOCH_MILLIS)
            }
        }
        _running.value = true
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        _running.value = false
    }

    fun setAnchor(point: GeoPoint, accuracyM: Double, mode: AnchorMode = AnchorMode.MANUAL) {
        scope.launch {
            core.anchorManager.setAnchor(Anchor(point, accuracyM, clock(), mode))
            solveEpoch()
        }
    }

    fun clearAnchor() {
        scope.launch {
            core.anchorManager.clear()
            solveEpoch()
        }
    }

    private fun solveEpoch() {
        val now = clock()
        val time = buildList {
            gnssTimeOffsetMs?.let { add(TimeObservation(TimeSource.GNSS, now + it, GNSS_TIME_UNCERTAINTY_MS)) }
            networkTimeOffsetMs?.let { add(TimeObservation(TimeSource.NITZ, now + it, NETWORK_TIME_UNCERTAINTY_MS)) }
        }
        val words = pendingWords.toList().also { pendingWords.clear() }
        _solution.value = core.solve(aggregator.fresh(now), now, words, time)
    }

    private fun <T> CoroutineScope.collectSafely(flow: Flow<T>, action: suspend (T) -> Unit) {
        launch {
            flow.catch { Log.w(TAG, "Источник остановлен с ошибкой", it) }.collect { action(it) }
        }
    }

    private companion object {
        const val TAG = "BkPhenix"
        const val EPOCH_MILLIS = 1_000L
        const val GNSS_TIME_UNCERTAINTY_MS = 1_000L
        const val NETWORK_TIME_UNCERTAINTY_MS = 1_000L
    }
}
