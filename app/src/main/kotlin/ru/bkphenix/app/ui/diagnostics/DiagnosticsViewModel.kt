package ru.bkphenix.app.ui.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.bkphenix.app.data.PhenixRepository
import ru.bkphenix.app.data.log.RawLogState
import ru.bkphenix.app.data.model.BleSnapshot
import ru.bkphenix.app.data.model.CellSnapshot
import ru.bkphenix.app.data.model.GnssCapabilitiesSnapshot
import ru.bkphenix.app.data.model.NetworkTimeSample
import ru.bkphenix.app.data.model.SensorKind
import ru.bkphenix.app.data.model.SensorSnapshot
import ru.bkphenix.app.data.model.SourceStatus
import ru.bkphenix.app.data.model.WifiSnapshot
import ru.bkphenix.app.data.source.RinexEphemerisInfo
import java.io.InputStream
import java.io.OutputStream

data class DiagnosticsUiState(
    val statuses: Map<String, SourceStatus> = emptyMap(),
    val capabilities: GnssCapabilitiesSnapshot? = null,
    val cells: List<CellSnapshot> = emptyList(),
    val wifi: List<WifiSnapshot> = emptyList(),
    val ble: List<BleSnapshot> = emptyList(),
    val sensors: Map<SensorKind, SensorSnapshot> = emptyMap(),
    val networkTime: NetworkTimeSample? = null,
)

class DiagnosticsViewModel(private val repository: PhenixRepository) : ViewModel() {
    val logState: StateFlow<RawLogState> = repository.logState
    val rinexInfo: StateFlow<RinexEphemerisInfo?> = repository.rinexInfo
    val cellDatabase: StateFlow<ru.bkphenix.app.data.cells.CellDatabaseInfo?> = repository.cellDatabaseInfo

    fun toggleLog() = if (repository.logState.value.recording) repository.stopLog() else repository.startLog()

    fun deleteLogs() = repository.deleteLogs()

    /** Сколько сот своя обучаемая база уже знает надёжно. */
    val learnedCells: StateFlow<Int> = repository.learnedCellCount
    private val _learnedMessage = MutableStateFlow<String?>(null)
    val learnedMessage: StateFlow<String?> = _learnedMessage

    fun exportLearned(open: () -> OutputStream?) {
        viewModelScope.launch {
            _learnedMessage.value = runCatching {
                (open() ?: error("файл не открылся")).use { repository.exportLearnedCells(it) }
                "Своя база сохранена"
            }.getOrElse { "Не сохранено: ${it.message}" }
        }
    }

    fun importLearned(open: () -> InputStream?) {
        viewModelScope.launch {
            _learnedMessage.value = runCatching {
                val n = (open() ?: error("файл не открылся")).use { repository.importLearnedCells(it) }
                "Добавлено записей: $n"
            }.getOrElse { "Не импортировано: ${it.message}" }
        }
    }

    private val statusFlow = repository.statusFlows.let { flows ->
        combine(flows.values) { values -> flows.keys.zip(values).toMap() }
    }

    private val radio = combine(repository.cellList, repository.wifiList, repository.bleList) { c, w, b -> Triple(c, w, b) }

    val state: StateFlow<DiagnosticsUiState> = combine(
        statusFlow,
        repository.capabilities,
        radio,
        repository.sensorReadings,
        repository.networkTime,
    ) { statuses, caps, (cells, wifi, ble), sensors, offset ->
        DiagnosticsUiState(
            statuses = statuses,
            capabilities = caps,
            cells = cells.sortedByDescending { it.isServing },
            wifi = wifi.sortedByDescending { it.rssiDbm },
            ble = ble,
            sensors = sensors,
            networkTime = offset,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DiagnosticsUiState())
}
