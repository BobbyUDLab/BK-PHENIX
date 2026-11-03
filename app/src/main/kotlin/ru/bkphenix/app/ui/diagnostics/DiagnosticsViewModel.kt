package ru.bkphenix.app.ui.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
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

    fun toggleLog() = if (repository.logState.value.recording) repository.stopLog() else repository.startLog()

    fun deleteLogs() = repository.deleteLogs()

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
